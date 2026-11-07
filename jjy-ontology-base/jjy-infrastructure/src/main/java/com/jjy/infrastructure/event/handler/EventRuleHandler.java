package com.jjy.infrastructure.event.handler;

import com.jjy.infrastructure.event.DomainEventMessage;

/**
 * EVENT_DRIVEN 规则订阅者接口
 * <p>
 * M3 中 ruleType=EVENT_DRIVEN 的规则实现此接口。
 * 实际生产用 Aviator 表达式引擎自动求值；这里用接口预留。
 *
 * @author jjy-base
 */
public interface EventRuleHandler {
    void onEvent(DomainEventMessage event);
}
