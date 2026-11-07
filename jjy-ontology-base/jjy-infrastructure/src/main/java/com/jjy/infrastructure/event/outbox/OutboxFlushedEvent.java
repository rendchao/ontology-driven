package com.jjy.infrastructure.event.outbox;

import lombok.Data;
import org.springframework.context.ApplicationEvent;

import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * Spring ApplicationEvent：业务事务提交后触发
 * <p>
 * 由 {@link com.jjy.infrastructure.event.outbox.OutboxFlushedListener}
 * 监听（@TransactionalEventListener AFTER_COMMIT）。
 *
 * @author jjy-base
 */
@Data
public class OutboxFlushedEvent extends ApplicationEvent {

    /** 业务事务刚提交时入箱的事件 */
    private List<OutboxEntry> entries;

    public OutboxFlushedEvent(List<OutboxEntry> entries) {
        super(OutboxFlushedEvent.class);
        this.entries = entries == null ? Collections.emptyList() : entries;
    }

    @Data
    public static class OutboxEntry {
        private String eventUid;
        private String eventType;
        private String aggregateType;
        private String aggregateId;
        private Map<String, Object> payload;

        public OutboxEntry() {}

        public OutboxEntry(String eventUid, String eventType, String aggregateType, String aggregateId, Map<String, Object> payload) {
            this.eventUid = eventUid;
            this.eventType = eventType;
            this.aggregateType = aggregateType;
            this.aggregateId = aggregateId;
            this.payload = payload;
        }
    }
}

