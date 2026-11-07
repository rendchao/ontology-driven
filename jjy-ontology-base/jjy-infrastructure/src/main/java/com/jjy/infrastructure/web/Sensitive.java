package com.jjy.infrastructure.web;

import com.fasterxml.jackson.annotation.JacksonAnnotationsInside;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 敏感字段脱敏注解：标注在 VO/PO 字段上，序列化时自动脱敏
 *
 * <p>支持的脱敏类型：PHONE / EMAIL / ID_CARD / BANK_CARD / NAME</p>
 *
 * <p>用法：
 * <pre>
 *   public class CustomerVO {
 *     {@code @Sensitive(type = Sensitive.Type.PHONE)}
 *     private String phone;
 *   }
 * </pre>
 *
 * @author jjy-base
 */
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.FIELD, ElementType.METHOD})
@JacksonAnnotationsInside
@JsonSerialize(using = SensitiveJsonSerializer.class)
public @interface Sensitive {
    /** 脱敏类型 */
    Type value() default Type.PHONE;

    enum Type {
        /** 手机号 138****8000 */
        PHONE,
        /** 邮箱 a***@example.com */
        EMAIL,
        /** 身份证 110102********1234 */
        ID_CARD,
        /** 银行卡 6222 **** **** 1234 */
        BANK_CARD,
        /** 姓名 张* */
        NAME
    }
}
