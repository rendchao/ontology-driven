package com.jjy.application.scenario;

import com.jjy.application.behavior.BehaviorContext;
import com.jjy.application.behavior.BehaviorDispatcher;
import com.jjy.application.behavior.BehaviorResult;
import com.jjy.infrastructure.exception.BizException;
import com.jjy.infrastructure.scenario.ScenarioInstancePO;
import com.jjy.infrastructure.scenario.ScenarioInstanceService;
import com.jjy.infrastructure.scenario.ScenarioStepType;
import com.jjy.application.scenario.step.StepHandler;
import com.jjy.application.scenario.step.StepResult;
import com.jjy.infrastructure.security.LoginUser;
import com.jjy.infrastructure.security.SecurityUtils;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * BEHAVIOR_CALL 步骤处理：调 {@link BehaviorDispatcher} 执行 M2 行为。
 *
 * <p>context 中已有的入参（aggregateId / businessKey 等）会作为 input 传给 BehaviorDispatcher；
 * 行为产生的事件 payload 自动通过 outbox 走事件链，EventBridge 顺带唤醒其他场景。</p>
 *
 * <p>如果 step 声明了 branches（例如 "通知失败重试"），则根据 BehaviorResult.data.notification.status
 * 命中分支，命中 default 走 BRANCH（targetStep 仍为当前 step 标识重试入口）。</p>
 *
 * @author jjy-base
 */
@Slf4j
@Component
public class BehaviorCallStepHandler implements StepHandler {

    @Autowired
    private BehaviorDispatcher behaviorDispatcher;

    @Autowired
    private ScenarioInstanceService scenarioInstanceService;

    @Override
    public ScenarioStepType supportedType() {
        return ScenarioStepType.BEHAVIOR_CALL;
    }

    @Override
    @SuppressWarnings("unchecked")
    public StepResult handle(ScenarioInstancePO instance, Map<String, Object> scenario, Map<String, Object> step) {
        String behaviorId = (String) step.get("behaviorRef");
        if (behaviorId == null) {
            return StepResult.fail("BEHAVIOR_CALL step 缺少 behaviorRef: " + step.get("stepId"));
        }
        Map<String, Object> ctx = scenarioInstanceService.readContext(instance);
        BehaviorContext bc = BehaviorContext.of(behaviorId);
        // 把 scenario context 里所有键值作为 input 传入（aggregateId 优先从 ctx 取）
        Object aggId = ctx.get("aggregateId");
        if (aggId != null) bc.setAggregateId(aggId.toString());
        Map<String, Object> input = new HashMap<>();
        for (Map.Entry<String, Object> e : ctx.entrySet()) {
            if ("waitingEventRef".equals(e.getKey())
                    || "waitingDeadline".equals(e.getKey())
                    || "_internal".equals(e.getKey())) continue;
            input.put(e.getKey(), e.getValue());
        }
        bc.setInput(input);
        // 写入 behaviorId 让 handler 取得到
        input.put("behaviorId", behaviorId);

        LoginUser actor = SecurityUtils.currentUser();
        if (actor == null) {
            // 场景调度在 async 线程中，SecurityUtils 没有当前用户；用 SYSTEM 兜底
            actor = new LoginUser("SYSTEM", "系统调度", "SYSTEM", "PLATFORM", null, null, null, null);
            log.debug("[Scenario] BEHAVIOR_CALL 走 SYSTEM 兜底 actor: scenario={}, step={}, behavior={}",
                    instance.getScenarioId(), step.get("stepId"), behaviorId);
        }
        bc.setActor(actor);

        BehaviorResult result;
        try {
            result = behaviorDispatcher.execute(bc);
        } catch (BizException e) {
            log.warn("[Scenario] BEHAVIOR_CALL 失败: scenario={}, step={}, behavior={}, msg={}",
                    instance.getScenarioId(), step.get("stepId"), behaviorId, e.getMessage());
            return StepResult.fail("BEHAVIOR_CALL 失败: " + e.getMessage());
        } catch (Exception e) {
            log.error("[Scenario] BEHAVIOR_CALL 异常: scenario={}, step={}, behavior={}",
                    instance.getScenarioId(), step.get("stepId"), behaviorId, e);
            return StepResult.fail("BEHAVIOR_CALL 异常: " + e.getMessage());
        }

        Map<String, Object> delta = new HashMap<>();
        if (result.getAggregateId() != null) delta.put("aggregateId", result.getAggregateId());
        if (result.getStatus() != null) delta.put("lastBehaviorStatus", result.getStatus());
        if (result.getData() != null) delta.put("lastBehaviorData", result.getData());
        if (result.getProducedEventIds() != null && !result.getProducedEventIds().isEmpty()) {
            delta.put("lastProducedEventIds", result.getProducedEventIds());
        }
        // 把 BehaviorResult.data 中可能命中的 status 字段展平到 context（用于 branch 匹配）
        if (result.getData() != null) {
            Object status = result.getData().get("status");
            if (status != null) delta.put("behaviorStatus", status.toString());
            Object notifStatus = result.getData().get("notification.status");
            if (notifStatus != null) delta.put("notificationStatus", notifStatus.toString());
        }

        // branch 解析
        Object branchesObj = step.get("branches");
        if (branchesObj instanceof List) {
            List<Map<String, Object>> branches = (List<Map<String, Object>>) branchesObj;
            String target = matchBranch(branches, ctx, delta);
            if (target != null) {
                log.info("[Scenario] BEHAVIOR_CALL branch 命中: scenario={}, step={}, target={}",
                        instance.getScenarioId(), step.get("stepId"), target);
                return StepResult.branch(target);
            }
        }
        return StepResult.advance(delta);
    }

    @SuppressWarnings("unchecked")
    private String matchBranch(List<Map<String, Object>> branches, Map<String, Object> ctx, Map<String, Object> delta) {
        // 合并 ctx + delta 用于条件求值
        Map<String, Object> env = new HashMap<>();
        env.putAll(ctx);
        env.putAll(delta);

        // 默认分支
        for (Map<String, Object> b : branches) {
            if (Boolean.TRUE.equals(b.get("isDefault"))) {
                // default 仍需条件为真才生效；如果条件缺失，落到末尾兜底
                Object cond = b.get("conditionExpression");
                if (cond == null || String.valueOf(cond).trim().isEmpty()) {
                    return (String) b.get("targetStep");
                }
            }
        }
        // 显式条件分支
        for (Map<String, Object> b : branches) {
            if (Boolean.TRUE.equals(b.get("isDefault"))) continue;
            String expr = (String) b.get("conditionExpression");
            if (expr == null || expr.trim().isEmpty()) continue;
            // 简化版条件求值：仅支持 ==/!=/IS EMPTY/IS NOT EMPTY，避免引入完整 Aviator 依赖
            try {
                Boolean ok = evaluateSimple(expr, env);
                if (Boolean.TRUE.equals(ok)) {
                    return (String) b.get("targetStep");
                }
            } catch (Exception e) {
                log.warn("[Scenario] branch condition 求值失败: expr={}, err={}", expr, e.getMessage());
            }
        }
        // 没有 default 也没有命中：返回 null 让 executor 走顺序下一步
        return null;
    }

    /**
     * 轻量级条件求值（仅支持 m4-scenario-model.yaml 中已出现的 pattern）：
     * <ul>
     *   <li>notification.status == 'FAILED'</li>
     *   <li>signResult == '客户拒收' / != '客户拒收'</li>
     *   <li>differences IS NOT EMPTY / IS EMPTY</li>
     * </ul>
     */
    private Boolean evaluateSimple(String expr, Map<String, Object> env) {
        String s = expr.trim();
        // IS NOT EMPTY / IS EMPTY
        if (s.toUpperCase().contains("IS NOT EMPTY")) {
            String field = s.substring(0, s.toUpperCase().indexOf("IS NOT EMPTY")).trim();
            Object v = lookup(field, env);
            return isNotEmpty(v);
        }
        if (s.toUpperCase().contains("IS EMPTY")) {
            String field = s.substring(0, s.toUpperCase().indexOf("IS EMPTY")).trim();
            Object v = lookup(field, env);
            return !isNotEmpty(v);
        }
        // != 优先
        if (s.contains("!=")) {
            String[] parts = s.split("!=", 2);
            Object lhs = lookup(parts[0].trim(), env);
            Object rhs = parseLiteral(parts[1].trim());
            return !eq(lhs, rhs);
        }
        if (s.contains("==")) {
            String[] parts = s.split("==", 2);
            Object lhs = lookup(parts[0].trim(), env);
            Object rhs = parseLiteral(parts[1].trim());
            return eq(lhs, rhs);
        }
        return false;
    }

    private Object lookup(String key, Map<String, Object> env) {
        // 支持 "notification.status" 嵌套
        if (key.contains(".")) {
            Object cur = env;
            for (String p : key.split("\\.")) {
                if (cur instanceof Map) {
                    cur = ((Map<String, Object>) cur).get(p);
                } else return null;
            }
            return cur;
        }
        return env.get(key);
    }

    private Object parseLiteral(String s) {
        s = s.trim();
        if ((s.startsWith("'") && s.endsWith("'")) || (s.startsWith("\"") && s.endsWith("\""))) {
            return s.substring(1, s.length() - 1);
        }
        return s;
    }

    private boolean eq(Object a, Object b) {
        if (a == null) return b == null || "".equals(b);
        if (b == null) return a == null;
        return String.valueOf(a).equals(String.valueOf(b));
    }

    private boolean isNotEmpty(Object v) {
        if (v == null) return false;
        if (v instanceof String) return !((String) v).isEmpty();
        if (v instanceof java.util.Collection) return !((java.util.Collection<?>) v).isEmpty();
        if (v instanceof Map) return !((Map<?, ?>) v).isEmpty();
        return true;
    }
}
