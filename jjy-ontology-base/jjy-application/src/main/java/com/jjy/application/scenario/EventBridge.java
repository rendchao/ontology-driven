package com.jjy.application.scenario;

import com.jjy.domain.registry.ModelRegistry;
import com.jjy.infrastructure.event.DomainEventMessage;
import com.jjy.infrastructure.scenario.ScenarioInstancePO;
import com.jjy.infrastructure.scenario.ScenarioInstanceService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 事件桥：把领域事件桥接到 M4 场景实例。
 *
 * <p>两类职责：
 * <ol>
 *   <li>WAITING 中的场景匹配到 eventRef 时被唤醒（调 ScenarioExecutor.resume）</li>
 *   <li>任何领域事件到达时，检查是否有 STANDALONE 场景以该 event 作为 entryEvent，有则启动</li>
 * </ol>
 *
 * <p>@Async 保证主线程（业务事务）不被场景调度阻塞；@EventListener 保证事件发布即可触发（AFTER_COMMIT）。</p>
 *
 * @author jjy-base
 */
@Slf4j
@Component
public class EventBridge {

    @Autowired
    private ScenarioInstanceService scenarioInstanceService;

    @Autowired
    private ScenarioExecutor scenarioExecutor;

    @Autowired
    private ModelRegistry modelRegistry;

    /**
     * 监听 DomainEventMessage，先恢复 WAITING 中的场景，再启动以本事件为 entryEvent 的 STANDALONE 场景。
     */
    @Async("scenarioTaskExecutor")
    @EventListener
    public void onDomainEvent(DomainEventMessage msg) {
        if (msg == null || msg.getEventType() == null) return;
        try {
            // 1) 唤醒 WAITING 场景
            List<ScenarioInstancePO> waiting = scenarioInstanceService.findWaitingByEventRef(msg.getEventType());
            if (!waiting.isEmpty()) {
                log.info("[EventBridge] event={} 匹配到 {} 个 WAITING 场景", msg.getEventType(), waiting.size());
                Map<String, Object> payload = new HashMap<>();
                if (msg.getPayload() != null) payload.putAll(msg.getPayload());
                if (msg.getAggregateType() != null) payload.put("aggregateType", msg.getAggregateType());
                if (msg.getAggregateId() != null) payload.put("aggregateId", msg.getAggregateId());
                for (ScenarioInstancePO po : waiting) {
                    try {
                        scenarioExecutor.resume(po.getId(), payload);
                    } catch (Exception e) {
                        log.error("[EventBridge] resume 失败: instanceId={}", po.getId(), e);
                    }
                }
            }
            // 2) 启动以本事件为 entryEvent 的 STANDALONE 场景
            for (Map.Entry<String, Object> e : modelRegistry.getScenarios().entrySet()) {
                @SuppressWarnings("unchecked")
                Map<String, Object> def = (Map<String, Object>) e.getValue();
                if (!"STANDALONE".equalsIgnoreCase((String) def.get("orchestration"))) continue;
                if (!msg.getEventType().equals(def.get("entryEvent"))) continue;
                Map<String, Object> ctx = new HashMap<>();
                if (msg.getPayload() != null) ctx.putAll(msg.getPayload());
                if (msg.getAggregateType() != null) ctx.put("aggregateType", msg.getAggregateType());
                if (msg.getAggregateId() != null) ctx.put("aggregateId", msg.getAggregateId());
                try {
                    String instanceId = scenarioExecutor.startStandalone((String) def.get("scenarioId"), ctx);
                    log.info("[EventBridge] 启动 STANDALONE 场景: scenario={} → instance={}",
                            def.get("scenarioId"), instanceId);
                } catch (Exception ex) {
                    log.error("[EventBridge] startStandalone 失败: scenario={}", def.get("scenarioId"), ex);
                }
            }
        } catch (Exception e) {
            log.error("[EventBridge] 处理 event={} 异常", msg.getEventType(), e);
        }
    }
}
