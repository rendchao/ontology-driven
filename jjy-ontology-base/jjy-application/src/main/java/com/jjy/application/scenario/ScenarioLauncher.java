package com.jjy.application.scenario;

import com.jjy.infrastructure.scenario.ScenarioInstancePO;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;

/**
 * M4 场景启动器：M6 流程引擎 / 业务层调用入口。
 *
 * <p>对外暴露两个 API：
 * <ul>
 *   <li>{@link #startStandalone} — 异步启动 STANDALONE 场景（不等终态，返回 instanceId）</li>
 *   <li>{@link #startSync} — 同步阻塞启动 EMBEDDED 场景（等终态，常用于 M6 SCENARIO_CALL）</li>
 * </ul>
 *
 * <p>场景与 M2 行为的连接（无需调用方手动启动）：
 * <ol>
 *   <li>当一个 M2 行为执行完写 outbox 产生事件（m2-behavior-model.yaml 的 producedEvents）</li>
 *   <li>OutboxFlushedListener（AFTER_COMMIT）将事件发布为 DomainEventMessage</li>
 *   <li>{@link EventBridge} 收到 DomainEventMessage 后：
 *     <ul>
 *       <li>唤醒所有 waitingEventRef 匹配的 WAITING 场景</li>
 *       <li>启动所有 entryEvent 匹配的 STANDALONE 场景</li>
 *     </ul>
 *   </li>
 * </ol>
 *
 * <p>因此只要 m2.producedEvents 引用的事件对应了某个 m4-scenario-model 的 entryEvent，行为执行完
 * 会自动驱动场景启动，调用方不必显式调本 launcher。</p>
 *
 * @author jjy-base
 */
@Slf4j
@Component
public class ScenarioLauncher {

    @Autowired
    private ScenarioExecutor scenarioExecutor;

    /**
     * 异步启动 STANDALONE 场景（不等终态）。
     *
     * @return 新建实例 ID；场景不存在返回 null
     */
    public String startStandalone(String scenarioId, Map<String, Object> initialContext) {
        Map<String, Object> ctx = initialContext == null ? new HashMap<>() : new HashMap<>(initialContext);
        String id = scenarioExecutor.startStandalone(scenarioId, ctx);
        if (id != null) {
            log.info("[ScenarioLauncher] STANDALONE 已启动: scenario={} → instance={}", scenarioId, id);
        }
        return id;
    }

    /**
     * 同步阻塞启动 EMBEDDED 场景（等终态返回）。
     * 用于 M6 流程的 SCENARIO_CALL 活动。
     *
     * @return 终态实例；场景不存在返回 null
     */
    public ScenarioInstancePO startSync(String scenarioId, Map<String, Object> initialContext) {
        Map<String, Object> ctx = initialContext == null ? new HashMap<>() : new HashMap<>(initialContext);
        ScenarioInstancePO po = scenarioExecutor.startSync(scenarioId, ctx);
        if (po != null) {
            log.info("[ScenarioLauncher] EMBEDDED 同步启动完成: scenario={} → instance={}, status={}",
                    scenarioId, po.getId(), po.getStatus());
        }
        return po;
    }

    /**
     * 触发已 WAITING 的场景继续推进（由业务代码主动调，例如：人工补单后手动驱动下一阶段）。
     */
    public void resume(String instanceId, Map<String, Object> eventPayload) {
        scenarioExecutor.resume(instanceId, eventPayload);
    }
}
