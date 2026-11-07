package com.jjy.infrastructure.event.outbox;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * outbox 提交后监听器
 * <p>
 * 监听 OutboxService.write 时由 BehaviorExecutor 发布的 OutboxFlushedEvent，
 * 在业务事务 AFTER_COMMIT 阶段把内存中的事件包装为本地 OutboxFlushedEvent 发布。
 * <p>
 * 注：实际的事件持久化在 OutboxService.write 同事务完成；本监听器只负责
 * 触发同步的派发（在同进程内快速派发），最终一致性由 @Scheduled OutboxDispatcher 保证。
 *
 * @author jjy-base
 */
@Slf4j
@Component
public class OutboxFlushedListener {

    @Autowired
    private ApplicationEventPublisher publisher;

    @Autowired
    private ObjectMapper objectMapper;

    /**
     * 业务事务提交后：把已持久化的 outbox 行加载 → 立即派发到本地订阅者
     * <p>
     * 注意：这里只"快速派发同进程"，不依赖它做最终一致性（@Scheduled 兜底）。
     */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onFlushed(OutboxFlushedEvent event) {
        if (event == null || event.getEntries() == null || event.getEntries().isEmpty()) {
            return;
        }
        for (OutboxFlushedEvent.OutboxEntry e : event.getEntries()) {
            try {
                // 解析 payload 字符串为 Map（如果已经是 Map 直接用）
                Map<String, Object> payload = e.getPayload();
                if (e.getPayload() == null && e.getEventType() != null) {
                    payload = new java.util.HashMap<>();
                }
                // 触发本地 DomainEvent
                com.jjy.infrastructure.event.DomainEventMessage msg =
                        new com.jjy.infrastructure.event.DomainEventMessage(
                                e.getEventUid(), e.getEventType(), e.getAggregateType(), e.getAggregateId(), payload);
                publisher.publishEvent(msg);
            } catch (Exception ex) {
                log.error("[OutboxFlushed] 派发失败: eventUid={} type={}", e.getEventUid(), e.getEventType(), ex);
            }
        }
    }
}
