package com.jjy.application.scenario;

import com.jjy.infrastructure.exception.BizException;
import com.jjy.infrastructure.scenario.ScenarioInstancePO;
import com.jjy.infrastructure.scenario.ScenarioStepType;
import com.jjy.application.scenario.step.StepHandler;
import com.jjy.application.scenario.step.StepResult;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;

/**
 * SUB_SCENARIO 步骤处理：嵌套调用另一个 STANDALONE 场景。
 *
 * <p>M4 编排两种类型：
 * <ul>
 *   <li>STANDALONE — 独立场景，有 entryEvent，由 EventBridge 触发 start（不在 SUB_SCENARIO 走）</li>
 *   <li>EMBEDDED_IN_FLOW — 嵌入流程，由 M6 流程的 SCENARIO_CALL 活动调 ScenarioExecutor.startSync()</li>
 * </ul>
 *
 * <p>本 handler 暂时保留实现位，作为扩展点。SCN-VAS-001 这种 EMBEDDED 走
 * {@link ScenarioExecutor#startSync(String, Map)}，由 M6 flow 引擎直接调 launcher，
 * 不需要 SUB_SCENARIO step 内部递归（避免循环依赖 + 难以调试）。</p>
 *
 * @author jjy-base
 */
@Slf4j
@Component
public class SubScenarioStepHandler implements StepHandler {

    @Autowired
    @org.springframework.context.annotation.Lazy
    private ScenarioExecutor scenarioExecutor;

    @Override
    public ScenarioStepType supportedType() {
        return ScenarioStepType.SUB_SCENARIO;
    }

    @Override
    public StepResult handle(ScenarioInstancePO instance, Map<String, Object> scenario, Map<String, Object> step) {
        String subId = (String) step.get("scenarioRef");
        if (subId == null) {
            return StepResult.fail("SUB_SCENARIO step 缺少 scenarioRef: " + step.get("stepId"));
        }
        try {
            Map<String, Object> childCtx = new HashMap<>();
            childCtx.put("parentScenarioId", instance.getScenarioId());
            childCtx.put("parentInstanceId", instance.getId());
            // 同步阻塞直到子场景到达终态
            ScenarioInstancePO child = scenarioExecutor.startSync(subId, childCtx);
            if (child == null) {
                return StepResult.fail("子场景启动失败: " + subId);
            }
            switch (child.getStatus()) {
                case "COMPLETED":
                case "COMPENSATED":
                    return StepResult.advance();
                case "TERMINATED":
                case "FAILED":
                default:
                    return StepResult.fail("子场景未成功结束: " + child.getStatus());
            }
        } catch (BizException e) {
            return StepResult.fail("子场景执行异常: " + e.getMessage());
        } catch (Exception e) {
            log.error("[Scenario] SUB_SCENARIO 异常: scenario={}, sub={}", instance.getScenarioId(), subId, e);
            return StepResult.fail("子场景执行异常: " + e.getMessage());
        }
    }
}
