package com.jjy.application.scenario;

import com.jjy.application.rule.RuleEvaluator;
import com.jjy.domain.registry.ModelRegistry;
import com.jjy.domain.rule.Rule;
import com.jjy.infrastructure.scenario.ScenarioInstancePO;
import com.jjy.infrastructure.scenario.ScenarioInstanceService;
import com.jjy.infrastructure.scenario.ScenarioStepType;
import com.jjy.application.scenario.step.StepHandler;
import com.jjy.application.scenario.step.StepResult;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * RULE_CHECK 步骤处理：调 {@link RuleEvaluator} 对 context 求值，命中 branch.targetStep。
 *
 * <p>rule 不存在或 expression 为空 → 走 default 分支；若没有 default → ADVANCE 顺序下一步。</p>
 *
 * <p>复杂条件（==/!=/IS EMPTY 等）仍走 {@link com.jjy.application.scenario.BehaviorCallStepHandler}
 * 里的简化求值器；M3 表达式 + Aviator 的强诉求由 RuleEvaluator 处理。</p>
 *
 * @author jjy-base
 */
@Slf4j
@Component
public class RuleCheckStepHandler implements StepHandler {

    @Autowired
    private ModelRegistry modelRegistry;

    @Autowired
    private RuleEvaluator ruleEvaluator;

    @Autowired
    private ScenarioInstanceService scenarioInstanceService;

    @Override
    public ScenarioStepType supportedType() {
        return ScenarioStepType.RULE_CHECK;
    }

    @Override
    @SuppressWarnings("unchecked")
    public StepResult handle(ScenarioInstancePO instance, Map<String, Object> scenario, Map<String, Object> step) {
        String ruleId = (String) step.get("ruleRef");
        if (ruleId == null) {
            return StepResult.fail("RULE_CHECK step 缺少 ruleRef: " + step.get("stepId"));
        }
        Rule rule = modelRegistry.getRule(ruleId);
        Map<String, Object> ctx = scenarioInstanceService.readContext(instance);
        boolean ok = true;
        if (rule == null) {
            log.warn("[Scenario] RULE_CHECK 引用不存在的 rule: {}", ruleId);
        } else if (rule.getExpression() != null && !rule.getExpression().isEmpty()) {
            // 从 M3.inputParams 把 sourceField 解析出来
            Map<String, Object> params = new HashMap<>();
            if (rule.getInputParams() != null) {
                for (Map<String, Object> ip : rule.getInputParams()) {
                    String name = (String) ip.get("name");
                    String src = (String) ip.get("sourceField");
                    if (name != null) {
                        params.put(name, ctx.get(name));
                    } else if (src != null && src.contains(".")) {
                        String key = src.substring(src.lastIndexOf('.') + 1);
                        params.put(key, ctx.get(key));
                    }
                }
            }
            // 把整个 ctx 摊平作为 fallback（很多 rule 直接读 differences/signResult/...）
            params.putAll(ctx);
            ok = ruleEvaluator.evaluate(rule, params);
        }
        log.info("[Scenario] RULE_CHECK: scenario={}, step={}, rule={}, result={}",
                instance.getScenarioId(), step.get("stepId"), ruleId, ok);

        // branches 解析
        Object branchesObj = step.get("branches");
        if (branchesObj instanceof List) {
            List<Map<String, Object>> branches = (List<Map<String, Object>>) branchesObj;
            String target = matchBranch(branches, ok, ctx);
            if (target != null) {
                return StepResult.branch(target);
            }
        }
        return StepResult.advance();
    }

    private String matchBranch(List<Map<String, Object>> branches, boolean ruleOk, Map<String, Object> ctx) {
        // 优先匹配 default；若没有 default，按 rule 解析结果走第一个非 default
        for (Map<String, Object> b : branches) {
            if (Boolean.TRUE.equals(b.get("isDefault"))) {
                return (String) b.get("targetStep");
            }
        }
        // 没有 default：M3 规则结果直接作为布尔选择首个非 default 分支
        if (ruleOk) {
            for (Map<String, Object> b : branches) {
                return (String) b.get("targetStep");
            }
        }
        return null;
    }
}
