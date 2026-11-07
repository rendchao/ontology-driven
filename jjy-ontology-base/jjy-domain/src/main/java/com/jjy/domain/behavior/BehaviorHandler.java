package com.jjy.domain.behavior;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 行为处理器注解：标注在 Application/Handler 类的方法上，对应 M2 一个 Behavior
 *
 * <p>例：{@code @BehaviorHandler("Customer_Approve")}</p>
 *
 * <p>方法签名约定：<br>
 *   {@code Map<String, Object> handle(BehaviorContext ctx) throws Exception}<br>
 *   返回 Map 会被序列化为 outbox payload + HTTP 响应</p>
 *
 * @author jjy-base
 */
@Target({ElementType.METHOD})
@Retention(RetentionPolicy.RUNTIME)
public @interface BehaviorHandler {
    /** M2 行为 ID */
    String value();
}
