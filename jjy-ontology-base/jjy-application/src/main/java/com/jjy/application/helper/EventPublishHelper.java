package com.jjy.application.helper;

import com.jjy.infrastructure.event.outbox.OutboxService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 业务事件发布 helper（带去重保护）
 * <p>
 * 关键点：M2.yaml 的 producedEvents 已经被 BehaviorDispatcher.execute() 步骤 4
 * 自动写 outbox 一次。如果 handler 里再 publish 一次就会写 2 条。
 * <p>
 * 本 helper 用 in-memory map 做 5 秒内去重（防 handler 重复 publish），
 * 业务上等价于"幂等事件发布"。
 * <p>
 * 用法：
 * <pre>
 *     &#64;Autowired private EventPublishHelper eventPublisher;
 *     // ...
 *     eventPublisher.publish("Customer", customerId, "EVT-CUS-001", payload);
 * </pre>
 *
 * @author jjy-base
 */
@Slf4j
@Component
public class EventPublishHelper {

    /** 5 秒内同 aggregateId+eventType 已发过 → 跳过 */
    private static final ConcurrentHashMap<String, Long> RECENT = new ConcurrentHashMap<>();
    private static final long DEDUP_WINDOW_MS = 5_000L;

    @Autowired
    private OutboxService outboxService;

    /**
     * 业务事件发布（同事务 outbox + 5s 内去重）
     *
     * @param aggregateType 聚合根 alias（Customer / Order / ...）
     * @param aggregateId   业务主键
     * @param eventType     事件 ID（EVT-CUS-001 / EVT-INB-004 / ...）
     * @param kvPairs       事件载荷 key-value 字符串对
     */
    public void publish(String aggregateType, String aggregateId, String eventType, String... kvPairs) {
        String key = aggregateId + ":" + eventType;
        long now = System.currentTimeMillis();
        Long lastTime = RECENT.get(key);
        if (lastTime != null && (now - lastTime) < DEDUP_WINDOW_MS) {
            log.debug("[EventPublish] 去重跳过 ({} ms 内已发过): {}", now - lastTime, key);
            return;
        }
        RECENT.put(key, now);

        try {
            Map<String, Object> payload = new HashMap<>();
            payload.put("aggregateId", aggregateId);
            payload.put("aggregateType", aggregateType);
            payload.put("eventType", eventType);
            for (int i = 0; i + 1 < kvPairs.length; i += 2) {
                payload.put(kvPairs[i], kvPairs[i + 1]);
            }
            outboxService.write(aggregateType, aggregateId, eventType, payload);
            log.debug("[EventPublish] outbox: {} aggregateId={}", eventType, aggregateId);
        } catch (Exception e) {
            log.error("[EventPublish] outbox 写入失败: type={} aggregateId={} err={}",
                eventType, aggregateId, e.getMessage());
        }
    }
}
