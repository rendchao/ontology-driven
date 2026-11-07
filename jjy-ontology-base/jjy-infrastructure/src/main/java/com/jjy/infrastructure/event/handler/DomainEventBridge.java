package com.jjy.infrastructure.event.handler;

import com.jjy.infrastructure.event.DomainEventMessage;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

/**
 * 全局事件桥接器
 * <p>
 * Spring EventListener 收到 DomainEventMessage 后转发到 EventSubscriberRegistry.dispatch。
 * 异步执行避免阻塞主流程。
 *
 * @author jjy-base
 */
@Slf4j
@Component
public class DomainEventBridge {

    private final EventSubscriberRegistry registry;

    public DomainEventBridge(EventSubscriberRegistry registry) {
        this.registry = registry;
    }

    @Async("eventTaskExecutor")
    @EventListener
    public void onMessage(DomainEventMessage event) {
        try {
            registry.dispatch(event);
        } catch (Exception e) {
            log.error("[Dispatcher] 派发失败: eventUid={}", event.getEventUid(), e);
        }
    }
}
