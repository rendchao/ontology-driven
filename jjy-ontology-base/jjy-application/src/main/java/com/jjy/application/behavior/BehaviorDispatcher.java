package com.jjy.application.behavior;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jjy.domain.behavior.Behavior;
import com.jjy.domain.event.DomainEvent;
import com.jjy.domain.registry.ModelRegistry;
import com.jjy.application.rule.RuleEvaluator;
import com.jjy.domain.rule.Rule;
import com.jjy.infrastructure.event.outbox.OutboxService;
import com.jjy.infrastructure.exception.BizException;
import com.jjy.infrastructure.exception.RuleViolationException;
import com.jjy.infrastructure.persistence.service.RuleFireLogService;
import com.jjy.infrastructure.security.SecurityUtils;
import com.jjy.infrastructure.web.TraceIdFilter;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.lang.reflect.Method;
import java.util.*;

/**
 * 行为执行器（核心引擎）：M2 行为 → 同事务执行权限/规则/Handler/outbox
 *
 * <p>流程（参考 ontology-driven-dev §3.6）：
 * <ol>
 *   <li>权限校验（M2.requiredPermissions）</li>
 *   <li>规则求值（M2.appliedRules，仅 VALIDATION 类型；EVENT_DRIVEN 走事件订阅）</li>
 *   <li>反射调 handler</li>
 *   <li>同事务写 outbox（M2.producedEvents）</li>
 * </ol>
 *
 * @author jjy-base
 */
@Slf4j
@Service
public class BehaviorDispatcher {

    @Autowired
    private ModelRegistry registry;

    @Autowired
    private BehaviorHandlerRegistry handlerRegistry;

    @Autowired
    private org.springframework.jdbc.core.JdbcTemplate jdbcTemplate;

    @Autowired
    private com.jjy.application.rule.RuleEvaluator ruleEvaluator;

    @Autowired
    private com.jjy.application.rule.SpelRuleEvaluator spelRuleEvaluator;

    /**
     * ABAC 引擎：执行 M5 的 {@code abacCondition}。
     *
     * <p>放在规则校验之后、Handler 之前——RBAC 决定「能不能做这个动作」，
     * ABAC 决定「能不能碰这一条数据」，两者都要过。</p>
     */
    @Autowired
    private com.jjy.application.abac.AbacEngine abacEngine;

    @Autowired
    private OutboxService outboxService;

    @Autowired
    private RuleFireLogService ruleFireLogService;

    @Autowired
    private ObjectMapper objectMapper;

    /**
     * Spring Environment：用于 RULE-AUTH-002 等需要感知运行环境的规则。
     * 规则表达式里 sourceField: env 拿不到值时，回退到当前激活的 profile（如 "local" / "dev" / "prod"）。
     */
    @Autowired
    private org.springframework.core.env.Environment springEnvironment;

    /**
     * 物理映射解析器：从 MM 模型解析「模型别名 → 表名 → 主键列」。
     *
     * <p>替代原先硬编码的两张映射表——表结构信息在 MM 里已声明过，
     * 代码里再写一遍就是「模型一套、代码一套」。</p>
     */
    @Autowired
    private com.jjy.infrastructure.yaml.PhysicalMappingResolver physicalMapping;

    /**
     * 执行一个 M2 行为
     *
     * <p>注意：传播行为用 {@link Propagation#REQUIRES_NEW}，让 handler 抛 BizException 时的回滚
     * 只局限于本子事务，不污染外层（比如 M4 场景调度的 {@code runUntilWait.REQUIRES_NEW}），
     * 否则外层会被标 rollback-only，即便调用方在 catch 里把异常吃掉，外层 commit 仍会抛
     * {@code UnexpectedRollbackException}，导致 scenario_instance 整体回滚丢失。</p>
     *
     * @param ctx 行为上下文
     * @return 执行结果（含业务主键、状态、产生的事件列表）
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW, rollbackFor = Exception.class)
    public BehaviorResult execute(BehaviorContext ctx) {
        if (ctx.getBehaviorId() == null) {
            throw new BizException("behaviorId 不能为空");
        }
        Behavior behavior = registry.getBehavior(ctx.getBehaviorId());
        if (behavior == null) {
            throw new BizException("行为不存在: " + ctx.getBehaviorId());
        }
        if (ctx.getActor() == null) {
            ctx.setActor(SecurityUtils.currentUser());
        }
        log.info("[Behavior] 执行 {} ({}) | actor={}", behavior.getId(), behavior.getName(), ctx.getActor().getUserId());

        // 1. 权限校验
        checkPermissions(behavior);

        // 1.5 预填 currentState:从 input 拿 aggregateId,查 ownerEntity 对应的 PO,放入 ctx.currentState
        //     这样 Rule 校验时能拿到 Customer.submittedBy / OutboundOrder.customerId 等字段
        preloadCurrentState(behavior, ctx);

        // 2. 规则求值（VALIDATION 类型；CALCULATION 不在此求值，由 handler 主动调用）
        //
        //    校验的规则集 = appliedRules ∪ preconditions。
        //    preconditions 现在引用 M3 规则 ID（而非自由文本），
        //    因此它与 appliedRules 一样是可机器执行的约束——
        //    「前置条件」不再只是一句给人看的说明。
        List<String> ruleIds = mergedRuleIds(behavior);
        if (!ruleIds.isEmpty()) {
            // P10 业务白盒化：每条规则都落日志（PASS/FAIL/SKIP），与业务事务隔离
            String traceId = MDC.get(TraceIdFilter.MDC_KEY);
            String tenantId = ctx.getActor() != null ? ctx.getActor().getCurrentTenantId() : null;
            String actorUserId = ctx.getActor() != null ? ctx.getActor().getUserId() : null;
            for (String ruleId : ruleIds) {
                Rule r = registry.getRule(ruleId);
                if (r == null) continue;
                if (!"VALIDATION".equals(r.getRuleType())) {
                    // 非 VALIDATION 规则（CALCULATION/EVENT_DRIVEN）记录 SKIP
                    ruleFireLogService.recordSkip(
                            behavior.getId(), behavior.getName(), behavior.getOwnerEntity(),
                            ctx.getAggregateId(), ruleId, r.getName(), r.getRuleType(), r.getEngine(), r.getExpression(),
                            tenantId, actorUserId, traceId);
                    continue;
                }
                Map<String, Object> params = resolveRuleParams(r, ctx);
                // 根据规则 engine 字段路由：Aviator（默认）或 SpEL
                boolean ok;
                if ("SPEL".equalsIgnoreCase(r.getEngine())) {
                    ok = spelRuleEvaluator.evaluate(r, params);
                } else {
                    ok = ruleEvaluator.evaluate(r, params);
                }
                log.debug("[Behavior] rule={} engine={} result={} expr={}", ruleId, r.getEngine(), ok, r.getExpression());
                if (!ok) {
                    String msg = r.getViolationMessage() != null ? r.getViolationMessage() : ("规则校验不通过: " + r.getId());
                    log.warn("[Behavior] 规则违反: rule={}, params={}, msg={}", ruleId, params, msg);
                    // P10 业务白盒化：违规也落库（独立事务，即便业务回滚也保留）
                    ruleFireLogService.recordFail(
                            behavior.getId(), behavior.getName(), behavior.getOwnerEntity(),
                            ctx.getAggregateId(), ruleId, r.getName(), r.getRuleType(), r.getEngine(), r.getExpression(),
                            params, msg, tenantId, actorUserId, traceId);
                    throw new RuleViolationException(ruleId, msg);
                }
                // P10 业务白盒化：通过也落库
                ruleFireLogService.recordPass(
                        behavior.getId(), behavior.getName(), behavior.getOwnerEntity(),
                        ctx.getAggregateId(), ruleId, r.getName(), r.getRuleType(), r.getEngine(), r.getExpression(),
                        params, tenantId, actorUserId, traceId);
            }
        }

        // 2.5 ABAC 属性级授权：M5 的 abacCondition 声明了「这条数据谁能碰」
        //
        //     此前 abacCondition 只被校验（引用是否存在），从不执行——
        //     模型里声明了属性级约束，运行期没人求值，形同虚设。
        //     现在真正执行：RBAC 放行后，再逐条按属性判定。
        if (behavior.getOwnerEntity() != null) {
            Map<String, Object> row = null;
            if (ctx.getCurrentState() != null) {
                Object st = ctx.getCurrentState().get(ownerToAlias(behavior.getOwnerEntity()));
                if (st instanceof Map) {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> m = (Map<String, Object>) st;
                    row = m;
                }
            }
            abacEngine.check(behavior.getOwnerEntity(), behavior.getId(), row);
        }

        // 3. 反射调 handler
        BehaviorHandlerRegistry.HandlerEntry entry = handlerRegistry.get(behavior.getId());
        if (entry == null) {
            throw new BizException("行为 " + behavior.getId() + " 没有 @BehaviorHandler 实现");
        }
        Object raw;
        try {
            Method m = entry.getMethod();
            raw = m.invoke(entry.getBean(), ctx);
        } catch (Exception e) {
            Throwable cause = e.getCause() == null ? e : e.getCause();
            // BizException / RuleViolationException 是业务规则被违反（"只有待审核可审核"等），
            // 它们会被 GlobalExceptionHandler 转成 4xx 响应，调用方可见，不应记 ERROR 污染日志
            if (cause instanceof BizException) {
                log.info("[Behavior] 业务校验拒绝: behavior={}, msg={}", behavior.getId(), cause.getMessage());
                throw (BizException) cause;
            }
            if (cause instanceof RuleViolationException) {
                log.info("[Behavior] 规则违反: behavior={}, msg={}", behavior.getId(), cause.getMessage());
                throw (RuleViolationException) cause;
            }
            // 真正的非预期异常才记 ERROR
            log.error("[Behavior] Handler 异常: {}", behavior.getId(), cause);
            throw new BizException(com.jjy.infrastructure.web.R.CODE_BIZ_ERROR,
                    "业务执行失败: " + cause.getMessage(), cause);
        }
        BehaviorResult result = toResult(raw);
        if (result.getAggregateId() == null) {
            result.setAggregateId(ctx.getAggregateId());
        }

        // 4. 写 outbox
        if (behavior.getProducedEvents() != null) {
            for (String eventId : behavior.getProducedEvents()) {
                DomainEvent e = registry.getEvent(eventId);
                if (e == null) {
                    log.warn("[Behavior] producedEvents 引用不存在的 event: {}", eventId);
                    continue;
                }
                Map<String, Object> payload = buildEventPayload(e, ctx, result);
                outboxService.write(behavior.getOwnerEntity(), result.getAggregateId(), eventId, payload);
            }
            if (result.getProducedEventIds() == null) {
                result.setProducedEventIds(new ArrayList<>(behavior.getProducedEvents()));
            }
        }
        log.info("[Behavior] 完成 {} → aggregateId={}, events={}",
                behavior.getId(), result.getAggregateId(), result.getProducedEventIds());
        return result;
    }

    /**
     * 合并行为要校验的规则集合：{@code appliedRules ∪ preconditions}。
     *
     * <p><b>为什么要合并</b>：M2 里 {@code preconditions} 与 {@code appliedRules}
     * 都是「这个行为要满足哪些约束」的表达。preconditions 现在引用 M3 规则 ID，
     * 与 appliedRules 是同一个世界的两处列举——合并去重后统一求值，
     * 既不漏校验，也不重复求值。</p>
     *
     * <p>去重后保持顺序稳定（appliedRules 在前），使规则触发日志顺序可预期。</p>
     */
    private List<String> mergedRuleIds(Behavior behavior) {
        List<String> out = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        if (behavior.getAppliedRules() != null) {
            for (String id : behavior.getAppliedRules()) {
                if (id != null && seen.add(id)) {
                    out.add(id);
                }
            }
        }
        if (behavior.getPreconditions() != null) {
            for (String id : behavior.getPreconditions()) {
                if (id != null && seen.add(id)) {
                    out.add(id);
                }
            }
        }
        return out;
    }

    private void checkPermissions(Behavior behavior) {
        if (behavior.getRequiredPermissions() == null || behavior.getRequiredPermissions().isEmpty()) {
            return;
        }
        if (!SecurityUtils.hasAllPermissions(behavior.getRequiredPermissions())) {
            throw new AccessDeniedException("缺少权限: " + behavior.getRequiredPermissions());
        }
    }

    /**
     * 预填 currentState: 从 DB 读当前 PO 转 map,Rule 校验时能拿到 Customer.submittedBy 等字段
     */
    private void preloadCurrentState(Behavior behavior, BehaviorContext ctx) {
        String owner = behavior.getOwnerEntity();
        log.info("[preload] behavior={}, owner={}, aggId={}", behavior.getId(), owner, ctx.getAggregateId());
        if (owner == null) return;
        String aggId = ctx.getAggregateId();
        if (aggId == null) {
            // 从 input 里找聚合根 ID。
            // 候选字段名由 M1 模型推导（各聚合根的主键字段 + 通用 id），
            // 而不是硬编码一份业务 ID 字段名清单——否则换业务域就得改底座。
            for (String k : candidateIdKeys(owner)) {
                String v = ctx.inputString(k);
                if (v == null && ctx.getInput() != null && ctx.getInput().get("dto") instanceof Map) {
                    Object o = ((Map<?, ?>) ctx.getInput().get("dto")).get(k);
                    v = o == null ? null : o.toString();
                }
                if (v != null) { aggId = v; ctx.setAggregateId(v); break; }
            }
            log.debug("[preload] aggId resolved: {}, input is null: {}", aggId, ctx.getInput() == null);
        }
        // 即使 aggId 仍 null(新建时),也把 input/dto 填到 currentState 作为 fallback
        // 这样 Rule 表达式 Customer.contractId 等可以从 input 读到
        String alias = ownerToAlias(owner);
        Map<String, Object> stateForAlias = new HashMap<>();
        if (ctx.getInput() != null) {
            // 1) input 顶层字段(非 dto 包装)
            for (Map.Entry<String, Object> e : ctx.getInput().entrySet()) {
                if ("dto".equals(e.getKey())) continue;  // 跳过 dto,单独处理
                stateForAlias.put(snakeToCamel(e.getKey()), e.getValue());
            }
            // 2) input.dto 内的所有字段(camelCase 后)放进 alias
            Object dto = ctx.getInput().get("dto");
            // dto 为 null 是常态（很多 behavior 用 input 顶层而不是 dto 包装），不是错误，降为 debug
            log.debug("[preload] dto class: {}, dto is Map: {}", dto == null ? "null" : dto.getClass().getName(), dto instanceof Map);
            if (dto instanceof Map) {
                Map<?, ?> dtoMap = (Map<?, ?>) dto;
                log.debug("[preload] dtoMap size: {}", dtoMap.size());
                for (Map.Entry<?, ?> e : dtoMap.entrySet()) {
                    if (e.getKey() instanceof String) {
                        String key = (String) e.getKey();
                        stateForAlias.put(snakeToCamel(key), e.getValue());
                    }
                }
            }
        }
        log.debug("[preload] stateForAlias keys: {}, input keys: {}, dto isMap: {}, input null: {}",
            stateForAlias.keySet(),
            ctx.getInput() == null ? "INPUT_IS_NULL" : ctx.getInput().keySet(),
            ctx.getInput() == null ? "N/A" : ctx.getInput().get("dto") instanceof Map,
            ctx.getInput() == null);
        ctx.putState(alias, stateForAlias);
        // 继续走 DB 查 PO(覆盖 input 已填的字段)
        if (aggId == null) return;

        // owner 形如 "AGG-SERVICECONTRACT-001" → 表 t_service_contract
        String tableName = guessTableName(alias);
        // 找主键列:customer_id / contract_id / etc
        String idCol = guessIdColumn(tableName);
        if (idCol == null) return;
        try {
            String sql = "SELECT * FROM " + tableName + " WHERE " + idCol + " = ? AND flag = 0";
            log.info("[preload] executing SQL: {} with aggId={}", sql, aggId);
            Map<String, Object> row = jdbcTemplate.queryForMap(sql, aggId);
            log.info("[preload] row keys: {}", row.keySet());
            for (Map.Entry<String, Object> e : row.entrySet()) {
                stateForAlias.put(snakeToCamel(e.getKey()), e.getValue());
            }
            log.info("[preload] alias={}, aggId={}, stateForAlias.submittedBy={}, approvedBy={}",
                alias, aggId, stateForAlias.get("submittedBy"), stateForAlias.get("approvedBy"));
            ctx.putState(alias, stateForAlias);
            log.info("[preload] currentState keys after putState: {}", ctx.getCurrentState().keySet());
        } catch (Exception e) {
            log.warn("[preload] FAILED owner={} table={} idCol={} aggId={}: {}", owner, tableName, idCol, aggId, e.getMessage());
        }
    }

    private String snakeToCamel(String s) {
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

    /**
     * 聚合根 ID（AGG-XXX-001）→ 模型别名（Customer）。
     *
     * <p>优先从 M1 模型的 {@code id → alias} 声明解析；模型里没有该聚合根时，
     * 退化为按命名规范推导（去掉 AGG- 前缀与数字后缀后转 PascalCase）。</p>
     *
     * <p>原先这里维护着一张硬编码 switch 表——但 AGG-ID 与 alias 的对应关系
     * 本来就是 M1 模型的声明，代码里再写一遍必然漂移。</p>
     */
    private String ownerToAlias(String owner) {
        if (owner == null) {
            return null;
        }
        // 1) 从 M1 模型解析（唯一真源）
        Object alias = registry.getAggregateAliases().get(owner);
        if (alias != null && !alias.toString().trim().isEmpty()) {
            return alias.toString();
        }
        // 2) 兜底：按命名规范推导
        String s = owner.replace("AGG-", "").replaceAll("-\\d+$", "");
        StringBuilder sb = new StringBuilder();
        boolean up = true;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '-' || c == '_') {
                up = true;
                continue;
            }
            sb.append(up ? Character.toUpperCase(c) : Character.toLowerCase(c));
            up = false;
        }
        return sb.toString();
    }

    private String guessIdColumn(String table) {
        // 表 → 主键列：优先从 MM 映射模型解析，避免在代码里硬编码不规则映射
        return physicalMapping.pkColumn(table);
    }

    private String guessTableName(String alias) {
        // 模型别名 → 物理表名：优先从 MM 映射模型解析，避免在代码里硬编码 switch
        return physicalMapping.tableName(alias);
    }

    private Map<String, Object> resolveRuleParams(Rule r, BehaviorContext ctx) {
        Map<String, Object> p = new HashMap<>();
        if (r.getInputParams() == null) return p;
        for (Map<String, Object> ip : r.getInputParams()) {
            String name = (String) ip.get("name");
            String src = (String) ip.get("sourceField");
            if (name == null) continue;
            // 形如 "Customer.customerId" 或 "payload.fieldName"
            Object v = resolveField(src, ctx);
            // 兜底:从 input/dto 找同 name 字段
            if (v == null) {
                v = ctx.getInput() == null ? null : ctx.getInput().get(name);
                if (v == null && ctx.getInput() != null && ctx.getInput().get("dto") instanceof Map) {
                    v = ((Map<?, ?>) ctx.getInput().get("dto")).get(name);
                }
            }
            // P-UAT 兜底：env 字段从 Spring profile 取（让 RULE-AUTH-002 等感知运行环境）
            // 规则里写 sourceField: env 但 request 不一定带，缺省时回退到当前激活的 profile。
            if (v == null && "env".equals(name) && springEnvironment != null) {
                String[] profiles = springEnvironment.getActiveProfiles();
                v = (profiles != null && profiles.length > 0) ? profiles[0] : "default";
            }
            // defaultValue 兜底（用于可选入参如 userType）
            if (v == null && ip.containsKey("defaultValue")) {
                v = ip.get("defaultValue");
            }
            p.put(name, v);
        }
        return p;
    }

    private Object resolveField(String srcField, BehaviorContext ctx) {
        if (srcField == null) return null;
        if (srcField.contains(".")) {
            String[] parts = srcField.split("\\.", 2);
            String head = parts[0];
            String tail = parts[1];
            switch (head) {
                case "payload": return ctx.getPayload() == null ? null : ctx.getPayload().get(tail);
                case "input": return ctx.getInput() == null ? null : ctx.getInput().get(tail);
                case "actor": return getActorField(tail, ctx.getActor());
                default:
                    // 实体别名（Customer / Product / …）——由 M1 模型判定，不在代码里硬编码列表
                    if (!isModelAlias(head)) {
                        return null;
                    }
                    // srcField 是 "Customer.submittedBy" 完整路径，直接从 currentState 取
                    Object v = getNested(ctx.getCurrentState(), srcField);
                    if (v != null) {
                        return v;
                    }
                    // fallback：新建时对象还没落库，从 input / dto 里取同名字段
                    Object o = lookupInput(ctx, tail);
                    if (o != null) {
                        return o;
                    }
                    // 关联字段（*.xxxId）：先试 aggregateId，再试 input 里的同名字段
                    if (tail.endsWith("Id")) {
                        if (ctx.getAggregateId() != null) {
                            return ctx.getAggregateId();
                        }
                        return lookupInput(ctx, tail);
                    }
                    return null;
            }
        }
        return ctx.getInput() == null ? null : ctx.getInput().get(srcField);
    }

    /**
     * 判断一个标识符是否为模型中声明的聚合根别名。
     *
     * <p>依据 M1 模型的 {@code alias} 声明动态判定——原先这里硬编码了二十多个
     * 具体业务实体别名，换业务域就得改底座代码，属于典型的「模型一套、代码一套」。</p>
     */
    private boolean isModelAlias(String name) {
        if (name == null || name.isEmpty()) {
            return false;
        }
        for (String alias : registry.getAggregateAliases().values()) {
            if (alias.equals(name)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 推导「从 input 里找聚合根 ID」的候选字段名。
     *
     * <p>来源：M1 模型里各聚合根的主键字段名（小驼峰形式，如 {@code productId}），
     * 外加通用的 {@code id}。这样新增聚合根时无需改底座代码——
     * 模型里声明了 {@code productId}，前端传 {@code dto.productId} 即可被识别。</p>
     *
     * @param owner 行为的 ownerEntity（聚合根 ID 或别名）
     */
    private List<String> candidateIdKeys(String owner) {
        List<String> keys = new ArrayList<>();
        keys.add("id");
        String alias = ownerToAlias(owner);
        // 优先放该行为所属聚合根自己的主键字段
        String ownPk = primaryKeyFieldOf(alias);
        if (ownPk != null) {
            keys.add(ownPk);
        }
        // 再放其它聚合根的主键字段（跨实体规则可能引用别的主键）
        for (Map.Entry<String, String> e : registry.getAggregateAliases().entrySet()) {
            String a = e.getValue();
            if (a == null || a.equals(alias)) {
                continue;
            }
            String pk = primaryKeyFieldOf(a);
            if (pk != null && !keys.contains(pk)) {
                keys.add(pk);
            }
        }
        return keys;
    }

    /**
     * 取某聚合根的主键字段名（依据 M1 的 alias + 属性声明推导）。
     *
     * <p>规则与 codegen 一致：唯一满足 {@code required + unique + 以 Id 结尾} 的属性，
     * 或 M1 里显式标记的 {@code localId}。</p>
     */
    private String primaryKeyFieldOf(String alias) {
        if (alias == null) {
            return null;
        }
        for (Map.Entry<String, String> e : registry.getAggregateAliases().entrySet()) {
            if (!alias.equals(e.getValue())) {
                continue;
            }
            Object def = registry.getAggregateDefs().get(e.getKey());
            if (!(def instanceof Map)) {
                return null;
            }
            @SuppressWarnings("unchecked")
            Map<String, Object> agg = (Map<String, Object>) def;
            Object attrsObj = agg.get("attributes");
            if (!(attrsObj instanceof List)) {
                return null;
            }
            String candidate = null;
            for (Object aObj : (List<?>) attrsObj) {
                if (!(aObj instanceof Map)) {
                    continue;
                }
                @SuppressWarnings("unchecked")
                Map<String, Object> a = (Map<String, Object>) aObj;
                String name = (String) a.get("name");
                if (name == null) {
                    continue;
                }
                boolean req = Boolean.TRUE.equals(a.get("required"));
                boolean uni = Boolean.TRUE.equals(a.get("unique"));
                if (req && uni && name.endsWith("Id")) {
                    if (candidate != null) {
                        return null;   // 不唯一符合，无法判定
                    }
                    candidate = name;
                }
            }
            return candidate;
        }
        return null;
    }

    /**
     * 从行为的 input（及其 dto 子 map）里取值。
     */
    private Object lookupInput(BehaviorContext ctx, String key) {
        if (ctx.getInput() == null) {
            return null;
        }
        Object v = ctx.getInput().get(key);
        if (v == null && ctx.getInput().get("dto") instanceof Map) {
            v = ((Map<?, ?>) ctx.getInput().get("dto")).get(key);
        }
        return v;
    }

    private Object getActorField(String tail, com.jjy.infrastructure.security.LoginUser actor) {
        if (actor == null) return null;
        switch (tail) {
            case "userId": return actor.getUserId();
            case "username": return actor.getUsername();
            case "userType": return actor.getUserType();
            case "customerId": return actor.getCustomerId();
            default: return null;
        }
    }

    @SuppressWarnings("unchecked")
    private Object getNested(Map<String, Object> map, String path) {
        if (map == null) return null;
        String[] parts = path.split("\\.");
        Object cur = map;
        for (String p : parts) {
            if (cur instanceof Map) {
                cur = ((Map<String, Object>) cur).get(p);
            } else {
                return null;
            }
            if (cur == null) return null;
        }
        return cur;
    }

    private BehaviorResult toResult(Object raw) {
        if (raw == null) return BehaviorResult.ok();
        if (raw instanceof BehaviorResult) return (BehaviorResult) raw;
        if (raw instanceof Map) {
            Map<String, Object> m = (Map<String, Object>) raw;
            BehaviorResult r = BehaviorResult.ok();
            r.setAggregateId(asString(m.get("aggregateId")));
            r.setStatus(asString(m.get("status")));
            r.setData(m);
            return r;
        }
        // POJO: 用 ObjectMapper 转 Map
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> m = objectMapper.convertValue(raw, Map.class);
            BehaviorResult r = BehaviorResult.ok();
            r.setAggregateId(asString(m.get("aggregateId")));
            r.setStatus(asString(m.get("status")));
            r.setData(m);
            return r;
        } catch (Exception e) {
            return BehaviorResult.ok();
        }
    }

    private String asString(Object v) {
        return v == null ? null : v.toString();
    }

    private Map<String, Object> buildEventPayload(DomainEvent e, BehaviorContext ctx, BehaviorResult result) {
        Map<String, Object> p = new HashMap<>();
        if (e.getPayload() == null) return p;
        for (DomainEvent.PayloadField f : e.getPayload()) {
            String name = f.getName();
            String src = f.getSourceField();
            Object v = resolveField(src, ctx);
            if (v == null) {
                // 回退到 result.data
                v = result.getData() == null ? null : result.getData().get(name);
            }
            if (v == null) {
                v = result.getData() == null ? null : result.getData().get(src);
            }
            p.put(name, v);
        }
        // 至少保证 aggregateId 在 payload 里
        if (!p.containsKey("aggregateId") && result.getAggregateId() != null) {
            p.put("aggregateId", result.getAggregateId());
        }
        return p;
    }
}
