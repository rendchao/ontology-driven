package com.jjy.application.scenario;

import com.jjy.infrastructure.scenario.DurationUtil;
import com.jjy.infrastructure.scenario.ScenarioInstancePO;
import com.jjy.infrastructure.scenario.ScenarioInstanceService;
import com.jjy.infrastructure.scenario.ScenarioStepType;
import com.jjy.application.scenario.step.StepHandler;
import com.jjy.application.scenario.step.StepResult;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * EVENT_WAIT 步骤处理：把当前实例切到 WAITING，并写入 waitingEventRef / waitingDeadline。
 *
 * <p>唤醒路径：
 * <ol>
 *   <li>由 {@link com.jjy.application.scenario.EventBridge} 监听 DomainEventMessage，
 *       匹配到 waitingEventRef 后调用 {@link com.jjy.infrastructure.scenario.ScenarioExecutor#resume}。</li>
 *   <li>由 {@link com.jjy.application.scenario.ScenarioTimeoutScanner} 定时扫超时，
 *       触发 onTimeout（TERMINATE / COMPENSATE）。</li>
 * </ol>
 *
 * <p>如果 timeout=0s 且无 onTimeout 行为，则立即 ADVANCE（不阻塞），用于"互斥等待"语义。</p>
 *
 * @author jjy-base
 */
@Slf4j
@Component
public class EventWaitStepHandler implements StepHandler {

    @Autowired
    private ScenarioInstanceService scenarioInstanceService;

    @Override
    public ScenarioStepType supportedType() {
        return ScenarioStepType.EVENT_WAIT;
    }

    @Override
    public StepResult handle(ScenarioInstancePO instance, Map<String, Object> scenario, Map<String, Object> step) {
        String eventRef = (String) step.get("eventRef");
        if (eventRef == null) {
            return StepResult.fail("EVENT_WAIT step 缺少 eventRef: " + step.get("stepId"));
        }
        String timeoutSpec = (String) step.get("timeout");
        Duration timeout = DurationUtil.parse(timeoutSpec);
        Object onTimeoutObj = step.get("onTimeout");
        String onTimeout = onTimeoutObj == null ? null : onTimeoutObj.toString();
        String onTimeoutBehavior = (String) step.get("onTimeoutBehavior");

        // 把等待信息写入 context（WAITING 状态，EventBridge 用 waitingEventRef 匹配）
        Map<String, Object> ctx = scenarioInstanceService.readContext(instance);
        Map<String, Object> delta = new HashMap<>();
        delta.put("waitingEventRef", eventRef);
        if (timeout.isZero() || timeout.isNegative()) {
            delta.put("waitingDeadline", null);
        } else {
            delta.put("waitingDeadline", LocalDateTime.now().plus(timeout).toString());
        }
        delta.put("onTimeout", onTimeout);
        delta.put("onTimeoutBehavior", onTimeoutBehavior);
        // 标记已记录 WAITING（context 落地由 executor 负责）
        ctx.putAll(delta);

        // timeout=0s 且无 onTimeout：直接 ADVANCE（互斥路径不等）
        if (timeout.isZero() && !"COMPENSATE".equalsIgnoreCase(onTimeout)) {
            log.info("[Scenario] EVENT_WAIT timeout=0s 直接跳过: scenario={}, step={}, event={}",
                    instance.getScenarioId(), step.get("stepId"), eventRef);
            delta.remove("waitingEventRef");
            delta.remove("waitingDeadline");
            return StepResult.advance(delta);
        }
        log.info("[Scenario] EVENT_WAIT 注册: scenario={}, step={}, event={}, timeout={}, onTimeout={}",
                instance.getScenarioId(), step.get("stepId"), eventRef, timeoutSpec, onTimeout);
        // 返回 WAIT，executor 会把 status 切到 WAITING 并落库 context
        StepResult sr = StepResult.pause();
        sr.setContextDelta(delta);
        return sr;
    }
}
