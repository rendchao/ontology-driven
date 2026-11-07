package com.jjy.domain.behavior;

import java.util.Map;

/**
 * 事件型行为执行 SPI（依赖反转）
 * <p>
 * M2 中 {@code triggerType=EVENT} 的行为由事件驱动触发，但其实现体是
 * {@code @BehaviorHandler} 注解方法，与普通行为共用同一套 handler 类。
 * 事件订阅表（eventId → 行为列表）由 infrastructure 层维护，
 * 而行为执行（权限校验 → 规则求值 → 调 handler → 同事务写 outbox）在 application 层。
 * <p>
 * 用本接口在 domain 层做依赖反转，避免 infrastructure 反向依赖 application：
 * infrastructure 只声明"要执行这个行为"，application 提供实现。
 *
 * @author jjy-base
 */
public interface EventBehaviorInvoker {

    /**
     * 执行一个 M2 行为（供事件订阅派发使用）。
     *
     * @param behaviorId M2 行为 ID，如 {@code Order_ReceiveWmsReceipt}
     * @param input      行为入参（来自事件 payload，键为业务字段名）
     * @param payload    事件原始 payload
     * @param aggregateId 事件所属聚合根 ID（可为 null）
     */
    void invoke(String behaviorId, Map<String, Object> input, Map<String, Object> payload, String aggregateId);
}
