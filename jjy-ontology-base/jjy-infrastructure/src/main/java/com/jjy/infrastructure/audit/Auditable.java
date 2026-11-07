package com.jjy.infrastructure.audit;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 审计注解：标注在 Service 方法或 Controller 方法上，自动写入 t_audit_log
 *
 * @author jjy-base
 */
@Target({ElementType.METHOD})
@Retention(RetentionPolicy.RUNTIME)
public @interface Auditable {

    /** 操作类型，如 APPROVE / REJECT / SUBMIT / SAVE_DRAFT */
    String action();

    /** 目标类型，如 CUSTOMER / INBOUND_ORDER / STATEMENT */
    String targetType();

    /** SpEL：用于从参数中提取 targetId（默认取第一个参数的 id 字段） */
    String targetIdSpel() default "";

    /** 是否捕获请求体作为 afterSnapshot（默认 false，敏感字段不捕获） */
    boolean captureAfter() default false;
}
