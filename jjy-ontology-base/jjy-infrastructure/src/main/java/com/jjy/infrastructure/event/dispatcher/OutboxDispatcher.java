package com.jjy.infrastructure.event.dispatcher;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jjy.infrastructure.event.DomainEventMessage;
import com.jjy.infrastructure.event.deadletter.DeadLetterService;
import com.jjy.infrastructure.event.outbox.Outbox;
import com.jjy.infrastructure.event.outbox.OutboxMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;

/**
 * outbox 调度器
 * <p>
 * @Scheduled 每秒扫一次 PENDING 事件，发布为本地 Spring 事件。
 * 这是兜底机制——主流程是 OutboxFlushedListener 业务提交后立即派发。
 * <p>
 * 失败重试：连续失败超过 5 次入死信表（status=3）。
 *
 * @author jjy-base
 */
@Slf4j
@Component
public class OutboxDispatcher {

    @Autowired
    private OutboxMapper outboxMapper;

    @Autowired
    private ApplicationEventPublisher publisher;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private DeadLetterService deadLetterService;

    /** 单批处理上限 */
    private static final int BATCH_LIMIT = 100;
    private static final int MAX_RETRIES = 5;

    /**
     * 调度：每秒扫一次
     */
    @Scheduled(fixedDelay = 1000, initialDelay = 5000)
    @Transactional
    public void dispatch() {
        List<Outbox> batch;
        try {
            batch = outboxMapper.claimBatch(BATCH_LIMIT);
        } catch (Exception e) {
            log.error("[Dispatcher] 扫表失败", e);
            return;
        }

        if (batch.isEmpty()) return;
        log.debug("[Dispatcher] 认领 {} 条事件", batch.size());

        for (Outbox o : batch) {
            try {
                Map<String, Object> payload = parsePayload(o.getPayload());
                DomainEventMessage msg = new DomainEventMessage(
                        o.getEventUid(), o.getEventType(), o.getAggregateType(), o.getAggregateId(), payload);
                publisher.publishEvent(msg);
                outboxMapper.markSent(o.getId());
            } catch (Exception e) {
                log.error("[Dispatcher] 派发失败: eventUid={} type={}", o.getEventUid(), o.getEventType(), e);
                int retry = o.getRetryCount() + 1;
                int backoff = (int) Math.pow(2, Math.min(retry, 6)) * 5;  // 5/10/20/40/80/160/320s
                outboxMapper.markFailed(o.getId(), MAX_RETRIES, backoff);
                if (retry >= MAX_RETRIES) {
                    deadLetterService.toDeadLetter(o, e.getMessage());
                }
            }
        }
    }

    private Map<String, Object> parsePayload(String json) {
        if (json == null || json.isEmpty()) return new java.util.HashMap<>();
        try {
            return objectMapper.readValue(json, new TypeReference<Map<String, Object>>() {});
        } catch (Exception e) {
            log.warn("[Dispatcher] payload 解析失败: {}", e.getMessage());
            return new java.util.HashMap<>();
        }
    }
}
