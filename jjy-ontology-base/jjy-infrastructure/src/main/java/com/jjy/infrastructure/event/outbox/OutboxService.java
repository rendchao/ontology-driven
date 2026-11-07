package com.jjy.infrastructure.event.outbox;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;
import java.util.UUID;

/**
 * outbox 写入服务
 * <p>
 * 业务方法 @Transactional 内调用本服务，事件与业务写入同一事务（保证原子性）。
 *
 * @author jjy-base
 */
@Slf4j
@Service
public class OutboxService {

    @Autowired
    private OutboxMapper outboxMapper;

    @Autowired
    private ObjectMapper objectMapper;

    /**
     * 同事务写入 outbox
     *
     * @param aggregateType 聚合根 alias（Customer / Order ...）
     * @param aggregateId   业务主键
     * @param eventType     事件 ID（EVT-CUS-002）
     * @param payload       事件载荷（按 ME.payload.sourceField 取值）
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void write(String aggregateType, String aggregateId, String eventType, Map<String, Object> payload) {
        enqueue(aggregateType, aggregateId, eventType, payload);
    }

    /**
     * 兼容方法：与 write 语义相同，BehaviorExecutor 用 enqueue 命名。
     *
     * @return eventUid（BehaviorExecutor 用作 List&lt;String&gt; 返回）
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public String enqueue(String aggregateType, String aggregateId, String eventType, Map<String, Object> payload) {
        Outbox o = new Outbox();
        o.setEventUid(UUID.randomUUID().toString().replace("-", ""));
        o.setAggregateType(aggregateType);
        o.setAggregateId(aggregateId);
        o.setEventType(eventType);
        try {
            o.setPayload(objectMapper.writeValueAsString(payload));
        } catch (JsonProcessingException e) {
            log.error("[Outbox] payload 序列化失败", e);
            throw new RuntimeException("payload 序列化失败", e);
        }
        o.setStatus(0);
        o.setRetryCount(0);
        outboxMapper.insert(o);
        log.debug("[Outbox] 入箱: {} {}", eventType, o.getEventUid());
        return o.getEventUid();
    }
}
