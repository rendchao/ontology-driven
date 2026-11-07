package com.jjy.application.flow.gateway;

import com.jjy.application.rule.SpelRuleEvaluator;
import com.jjy.domain.flow.Flow;
import com.jjy.infrastructure.exception.BizException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * M6 GATEWAY 分支求值器。
 *
 * <p>P5 范围内支持：</p>
 * <ul>
 *   <li><b>Data-driven</b>：直接用 {@code SpelRuleEvaluator.evaluate(expr, binding)} 对 input 字段求值
 *       （典型例：{@code estimatedAmount >= 500000}）</li>
 *   <li><b>Rule-driven</b>：YAML 里写了 {@code ruleRef: RULE-XXX} 的 gateway，规则已在
 *       {@code SaveAsDraft} 阶段把 {@code highAmountFlag} 这种 boolean 写进 DB PO，
 *       {@code BehaviorDispatcher.preloadCurrentState} 已自动放进 {@code ctx.currentState}。
 *       这里不直接调规则引擎，而是按"binding 里有这个 key 当作已判定"来用——简化实现，
 *       减少一处重复求值点。如需在 flow 阶段重新求值，后续可扩展。</li>
 * </ul>
 *
 * <p>分支优先级：default 分支 > 显式条件分支（按 YAML 顺序）。无 default 且无命中 → 抛 BizException。</p>
 */
@Slf4j
@Component
public class GatewayResolver {

    @Autowired
    private SpelRuleEvaluator spelRuleEvaluator;

    /**
     * 求值 GATEWAY，返回命中的目标 activityId。
     *
     * @param gw      M6 Activity（activityType=GATEWAY）
     * @param binding 跨 activity 共享状态（input + 上一步 result.status 等）
     * @return 命中的 target activityId
     */
    public String resolve(Flow.Activity gw, Map<String, Object> binding) {
        List<Flow.Branch> branches = gw.getBranches();
        if (branches == null || branches.isEmpty()) {
            // 单分支无 gateway 配置：GATEWAY 节点允许走 nextActivities[0]
            if (gw.getNextActivities() != null && !gw.getNextActivities().isEmpty()) {
                return gw.getNextActivities().get(0).getActivityId();
            }
            throw new BizException("GATEWAY " + gw.getActivityId() + " 无 branch 也没 nextActivities");
        }

        // 1) 先按条件求值分支（按 YAML 顺序，命中即返回）
        for (Flow.Branch b : branches) {
            if (Boolean.TRUE.equals(b.getIsDefault())) continue;   // default 留作兜底
            String expr = b.getConditionExpression();
            if (expr == null || expr.trim().isEmpty()) continue;
            boolean ok = spelRuleEvaluator.evaluate(expr, binding);
            log.info("[Flow GATEWAY] {} 分支 '{}' expr={} → {}", gw.getActivityId(), b.getBranchName(), expr, ok);
            if (ok) {
                return b.getTargetActivity();
            }
        }

        // 2) 全部条件分支未命中 → 走 default
        for (Flow.Branch b : branches) {
            if (Boolean.TRUE.equals(b.getIsDefault())) {
                log.info("[Flow GATEWAY] {} 命中 default 分支 → {}", gw.getActivityId(), b.getTargetActivity());
                return b.getTargetActivity();
            }
        }
        throw new BizException("GATEWAY " + gw.getActivityId() + " 无 branch 命中（" + branches.size() + " 条都未过）");
    }
}
