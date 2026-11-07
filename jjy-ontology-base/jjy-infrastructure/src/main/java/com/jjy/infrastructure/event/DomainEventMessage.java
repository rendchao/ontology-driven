package com.jjy.infrastructure.event;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Map;

/**
 * 本地 Spring 事件消息（DomainEventMessage）
 * <p>
 * 由 OutboxFlushedListener 发布，由 50 个本地订阅者（@EventListener）消费。
 * 与 OutboxService.write 的入箱解耦，避免单事件同时进 Spring 事件和 outbox 表。
 *
 * @author jjy-base
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class DomainEventMessage {

    private String eventUid;
    private String eventType;
    private String aggregateType;
    private String aggregateId;
    private Map<String, Object> payload;
}
