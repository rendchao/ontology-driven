package com.jjy.application.flow;

import com.jjy.application.behavior.BehaviorContext;
import com.jjy.application.behavior.BehaviorDispatcher;
import com.jjy.application.behavior.BehaviorResult;
import com.jjy.application.flow.gateway.GatewayResolver;
import com.jjy.domain.flow.Flow;
import com.jjy.domain.registry.ModelRegistry;
import com.jjy.infrastructure.exception.BizException;
import com.jjy.infrastructure.exception.RuleViolationException;
import com.jjy.infrastructure.flow.FlowInstancePO;
import com.jjy.infrastructure.flow.FlowInstanceService;
import com.jjy.infrastructure.flow.FlowStatus;
import com.jjy.infrastructure.security.LoginUser;
import com.jjy.infrastructure.security.SecurityUtils;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * M6 FlowEngine：M6 流程定义的执行引擎（同步走完型，P5 范围）。
 *
 * <p>不支持的 activityType（抛 BizException）：
 * <ul>
 *   <li>SUB_FLOW_CALL — 留 P5.5</li>
 *   <li>EVENT_WAIT — 留 P5.5</li>
 *   <li>SCENARIO_CALL — 留 P5.5</li>
 *   <li>SYSTEM_TASK — M6 YAML 里实际没用</li>
 * </ul>
 *
 * <p>核心算法（同步走完）：</p>
 * <ol>
 *   <li>建实例（status=RUNNING，binding = input 副本）</li>
 *   <li>从 startActivity 顺序推进，每步把 result.status 写进 binding 给 GATEWAY 用</li>
 *   <li>遇到 END → COMPLETED；遇到异常 → FAILED</li>
 *   <li>走完 safetyCounter=50 还没到 END → FAILED（防无限循环）</li>
 * </ol>
 */
@Slf4j
@Service
public class FlowEngine {

    @Autowired
    private ModelRegistry modelRegistry;

    @Autowired
    private FlowInstanceService flowInstanceService;

    @Autowired
    private BehaviorDispatcher behaviorDispatcher;

    @Autowired
    private GatewayResolver gatewayResolver;

    /**
     * 物理映射解析器：从 MM 模型解析「模型别名 → 表名 → 主键列」。
     *
     * <p>流程预填需要按业务主键反查当前对象，表名与主键列由 MM 声明，
     * 不在代码里硬编码。</p>
     */
    @Autowired
    private com.jjy.infrastructure.yaml.PhysicalMappingResolver physicalMapping;

    /**
     * 同步走完一个 flow（从头跑到 END）。
     *
     * <p>用 REQUIRES_NEW：与 BehaviorDispatcher 对齐，避免子事务回滚污染外层。
     * P5 范围内的 flow 都很短（最长 7 节点），不会出现长事务问题。</p>
     */
    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Transactional(propagation = Propagation.REQUIRES_NEW, rollbackFor = Exception.class)
    public FlowInstancePO startSync(String flowId, Map<String, Object> input) {
        Flow flow = modelRegistry.getFlow(flowId);
        if (flow == null) {
            throw new BizException("flow 不存在: " + flowId);
        }

        // 0. 预填 binding: 从 input 拿 lossId/customerId/contractId 等，查 DB 把当前 PO 字段合并进来
        //    让首个 GATEWAY（rule-driven）能读到 highAmountFlag 等预填字段
        Map<String, Object> binding = new HashMap<>(input == null ? new HashMap<String, Object>() : input);
        preloadBindingFromDb(binding);

        // 1. 建实例
        FlowInstancePO po = flowInstanceService.create(flowId, binding);
        log.info("[Flow] 启动: id={}, flowId={}, bindingKeys={}", po.getId(), flowId, binding.keySet());

        // 2. 从 startActivity 顺序推进
        String currentId = flow.getStartActivity();
        int safety = 0;
        try {
            while (currentId != null && ++safety < 50) {
                Flow.Activity act = findActivity(flow, currentId);
                if (act == null) {
                    throw new BizException("flow " + flowId + " 找不到 activity: " + currentId);
                }
                currentId = executeOne(po, flow, act, binding);
            }
            if (safety >= 50) {
                throw new BizException("flow " + flowId + " 推进超过 50 步（疑似死循环）");
            }
            // 3. 走完：COMPLETED
            flowInstanceService.updateState(po.getId(), FlowStatus.RUNNING, FlowStatus.COMPLETED, null, binding, null);
            log.info("[Flow] 完成: id={}, flowId={}", po.getId(), flowId);
            return flowInstanceService.get(po.getId());
        } catch (BizException | RuleViolationException biz) {
            log.warn("[Flow] 业务异常终止: id={}, flowId={}, err={}", po.getId(), flowId, biz.getMessage());
            flowInstanceService.updateState(po.getId(), FlowStatus.RUNNING, FlowStatus.FAILED,
                    currentId, binding, biz.getMessage());
            return flowInstanceService.get(po.getId());
        } catch (Exception e) {
            log.error("[Flow] 系统异常终止: id={}, flowId={}", po.getId(), flowId, e);
            flowInstanceService.updateState(po.getId(), FlowStatus.RUNNING, FlowStatus.FAILED,
                    currentId, binding, e.getMessage());
            return flowInstanceService.get(po.getId());
        }
    }

    /**
     * 执行单个 activity，返回下一个 activityId（null = 走完到 END 或 throw）。
     *
     * <p>每个 activity 写一行 flow_activity_instance，便于审计与测试断言。</p>
     */
    private String executeOne(FlowInstancePO po, Flow flow, Flow.Activity act, Map<String, Object> binding) {
        // 落当前 activity 的状态（便于恢复时定位）
        flowInstanceService.updateState(po.getId(), FlowStatus.RUNNING, FlowStatus.RUNNING,
                act.getActivityId(), binding, null);

        Long aiId = flowInstanceService.startActivity(po.getId(), act.getActivityId(),
                act.getActivityType(), act.getBehaviorRef());
        try {
            String next;
            switch (act.getActivityType()) {
                case "START":
                    // 推进到 nextActivities[0]
                    next = firstNextActivity(act);
                    flowInstanceService.finishActivity(aiId, "START", null);
                    return next;
                case "END":
                    flowInstanceService.finishActivity(aiId, "END", null);
                    return null;  // 通知外层：流程结束
                case "USER_TASK":
                case "APPROVAL_TASK":
                case "BEHAVIOR_CALL": {
                    // 走 BehaviorDispatcher：调对应 M2 行为
                    String behaviorId = act.getBehaviorRef();
                    if (behaviorId == null) {
                        throw new BizException("activity " + act.getActivityId() + " 缺 behaviorRef");
                    }
                    BehaviorContext ctx = BehaviorContext.of(behaviorId);
                    ctx.setInput(binding);
                    LoginUser actor = SecurityUtils.currentUser();
                    if (actor != null) ctx.setActor(actor);
                    BehaviorResult result = behaviorDispatcher.execute(ctx);
                    // 把 result.status 写进 binding：GATEWAY 的 SpEL 表达式会读这个
                    if (result.getStatus() != null) {
                        if ("APPROVAL_TASK".equals(act.getActivityType())) {
                            binding.put("approval.outcome", result.getStatus());
                        } else {
                            binding.put("lastBehaviorStatus", result.getStatus());
                        }
                    }
                    // 合并 BehaviorDispatcher 预填的 currentState 进 binding
                    // 例：InventoryAdjustment.highAmountFlag、Customer.submittedBy 等
                    // GATEWAY 的 SpEL 表达式如 "highAmountFlag == true" 直接读 root（=binding）
                    if (ctx.getCurrentState() != null) {
                        for (Map.Entry<String, Object> aliasEntry : ctx.getCurrentState().entrySet()) {
                            Object stateObj = aliasEntry.getValue();
                            if (stateObj instanceof Map) {
                                Map<?, ?> stateMap = (Map<?, ?>) stateObj;
                                for (Map.Entry<?, ?> e : stateMap.entrySet()) {
                                    if (e.getKey() instanceof String && e.getValue() != null) {
                                        binding.put((String) e.getKey(), e.getValue());
                                    }
                                }
                            }
                        }
                    }
                    flowInstanceService.finishActivity(aiId, result.getStatus(), null);
                    return firstNextActivity(act);
                }
                case "GATEWAY": {
                    String target = gatewayResolver.resolve(act, binding);
                    // 把命中分支写进 binding 便于后续 GATEWAY/activity 复用
                    binding.put("_lastGatewayTarget", target);
                    flowInstanceService.finishActivity(aiId, target, null);
                    return target;
                }
                default:
                    throw new BizException("P5 不支持的 activityType: " + act.getActivityType()
                            + " (activityId=" + act.getActivityId() + ")");
            }
        } catch (BizException | RuleViolationException biz) {
            flowInstanceService.finishActivity(aiId, null, biz.getMessage());
            throw biz;
        } catch (Exception e) {
            flowInstanceService.finishActivity(aiId, null, e.getMessage());
            throw new BizException("activity " + act.getActivityId() + " 执行异常: " + e.getMessage());
        }
    }

    private static Flow.Activity findActivity(Flow flow, String activityId) {
        if (flow.getActivities() == null) return null;
        for (Flow.Activity a : flow.getActivities()) {
            if (activityId.equals(a.getActivityId())) return a;
        }
        return null;
    }

    /**
     * 提取 nextActivities 里的 activityId 列表。YAML 两种写法都兼容：
     * <pre>
     * nextActivities: [A03]                                # 字符串列表
     * nextActivities: [{activityId: A03}, {activityId: A04}]  # 对象列表
     * </pre>
     */
    private static String firstNextActivity(Flow.Activity act) {
        // 1. 显式 nextActivities 对象列表
        if (act.getNextActivities() != null && !act.getNextActivities().isEmpty()) {
            return act.getNextActivities().get(0).getActivityId();
        }
        // 2. 简写 nextActivityIds 字符串列表
        if (act.getNextActivityIds() != null && !act.getNextActivityIds().isEmpty()) {
            return act.getNextActivityIds().get(0);
        }
        throw new BizException("activity " + act.getActivityId() + " 缺 nextActivities");
    }

    /**
     * 从 input 里的聚合根 ID 查 DB，把字段合并进 binding。
     *
     * <p>让首个 GATEWAY（rule-driven）能读到 {@code highAmountFlag} 等「规则预填」字段，
     * 以及让后续 activity 的行为能从 input 拿到业务对象当前状态。</p>
     *
     * <p><b>表名与主键列从 MM 模型解析</b>（{@code PhysicalMappingResolver}），
     * 而不是硬编码某几张业务表——否则换业务域就得改底座代码。</p>
     */
    private void preloadBindingFromDb(Map<String, Object> binding) {
        for (Map.Entry<String, Object> e : binding.entrySet()) {
            String key = e.getKey();
            if (key == null || !key.endsWith("Id") || "id".equals(key)) {
                continue;
            }
            Object idValue = e.getValue();
            if (idValue == null || idValue.toString().trim().isEmpty()) {
                continue;
            }
            String alias = aliasFromIdKey(key);
            if (alias == null) {
                continue;
            }
            String table = physicalMapping.tableName(alias);
            String pk = physicalMapping.pkColumn(table);
            if (table == null || pk == null) {
                continue;
            }
            try {
                Map<String, Object> row = jdbcTemplate.queryForMap(
                        "SELECT * FROM " + table + " WHERE " + pk + " = ? AND flag = 1", idValue);
                mergeRowIntoBinding(binding, row);
                // M2 handler 通常从 input.id 读业务主键
                binding.putIfAbsent("id", idValue);
                log.info("[Flow preload] {}={}, merged keys={}", key, idValue, row.keySet());
            } catch (Exception ex) {
                log.debug("[Flow preload] {}={} 预填跳过: {}", key, idValue, ex.getMessage());
            }
        }
    }

    /**
     * 由 input 的 ID 字段名推断模型别名。
     *
     * <p>如 {@code productId} → {@code Product}、{@code customerId} → {@code Customer}。
     * 优先用 M1 声明的别名反查（把别名小写首字母 + "Id"），匹配不上再退回通用驼峰拆分。</p>
     */
    private String aliasFromIdKey(String idKey) {
        String base = idKey.substring(0, idKey.length() - 2);
        if (base.isEmpty()) {
            return null;
        }
        // 优先匹配 M1 中声明的别名（精确，避免大小写歧义）
        for (String alias : modelRegistry.getAggregateAliases().values()) {
            if (alias.equalsIgnoreCase(base)) {
                return alias;
            }
        }
        // 兜底：驼峰拆分
        return Character.toUpperCase(base.charAt(0)) + base.substring(1);
    }

    private void mergeRowIntoBinding(Map<String, Object> binding, Map<String, Object> row) {
        for (Map.Entry<String, Object> e : row.entrySet()) {
            // snake_case → camelCase，让 SpEL 用 camelCase 读（如 highAmountFlag）
            String camelKey = snakeToCamel(e.getKey());
            Object v = e.getValue();
            // JacksonTypeHandler 序列化 Map 时不支持 LocalDateTime，预先转 String 规避
            if (v instanceof java.time.LocalDateTime || v instanceof java.sql.Timestamp
                    || v instanceof java.sql.Date || v instanceof java.time.LocalDate) {
                v = v.toString();
            }
            // MySQL TINYINT(1) → Boolean（spEL: highAmountFlag == true 才能匹配 Boolean true）
            if (camelKey.endsWith("Flag") && v instanceof Number) {
                v = ((Number) v).intValue() != 0;
            }
            binding.put(camelKey, v);
        }
    }

    private static String snakeToCamel(String s) {
        if (s == null) return null;
        StringBuilder sb = new StringBuilder();
        boolean up = false;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '_') { up = true; continue; }
            sb.append(up ? Character.toUpperCase(c) : c);
            up = false;
        }
        return sb.toString();
    }
}
