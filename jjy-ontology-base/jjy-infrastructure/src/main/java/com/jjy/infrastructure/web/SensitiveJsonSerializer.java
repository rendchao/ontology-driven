package com.jjy.infrastructure.web;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.BeanProperty;
import com.fasterxml.jackson.databind.JsonSerializer;
import com.fasterxml.jackson.databind.SerializerProvider;
import com.fasterxml.jackson.databind.ser.ContextualSerializer;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;

/**
 * 敏感字段脱敏序列化器
 *
 * <p>根据 {@link Sensitive.Type} 类型对字符串值进行脱敏：</p>
 * <ul>
 *   <li>PHONE：138****8000（前 3 后 4）</li>
 *   <li>EMAIL：a***@example.com</li>
 *   <li>ID_CARD：110102********1234（前 6 后 4）</li>
 *   <li>BANK_CARD：6222 **** **** 1234（前 4 后 4）</li>
 *   <li>NAME：张*（保留首字）</li>
 * </ul>
 *
 * @author jjy-base
 */
@Slf4j
public class SensitiveJsonSerializer extends JsonSerializer<String> implements ContextualSerializer {

    private Sensitive.Type type;

    public SensitiveJsonSerializer() {
        this.type = Sensitive.Type.PHONE;
    }

    public SensitiveJsonSerializer(Sensitive.Type type) {
        this.type = type;
    }

    @Override
    public JsonSerializer<?> createContextual(SerializerProvider prov, BeanProperty property) {
        if (property == null) return this;
        Sensitive ann = property.getAnnotation(Sensitive.class);
        if (ann == null) ann = property.getContextAnnotation(Sensitive.class);
        if (ann != null && ann.value() != null) {
            return new SensitiveJsonSerializer(ann.value());
        }
        return this;
    }

    @Override
    public void serialize(String value, JsonGenerator gen, SerializerProvider serializers) throws IOException {
        if (value == null) {
            gen.writeNull();
            return;
        }
        gen.writeString(mask(value, type));
    }

    public static String mask(String value, Sensitive.Type type) {
        if (value == null || value.isEmpty()) return value;
        try {
            switch (type) {
                case PHONE:
                    if (value.length() < 7) return value;
                    return value.substring(0, 3) + "****" + value.substring(value.length() - 4);
                case EMAIL:
                    int at = value.indexOf('@');
                    if (at <= 1) return value;
                    return value.charAt(0) + "***" + value.substring(at);
                case ID_CARD:
                    if (value.length() < 10) return value;
                    return value.substring(0, 6) + "********" + value.substring(value.length() - 4);
                case BANK_CARD:
                    if (value.length() < 8) return value;
                    return value.substring(0, 4) + " **** **** " + value.substring(value.length() - 4);
                case NAME:
                    if (value.length() <= 1) return value;
                    return value.charAt(0) + "*";
                default:
                    return value;
            }
        } catch (Exception e) {
            log.warn("[Sensitive] 脱敏失败: type={}, value.len={}", type, value.length());
            return value;
        }
    }
}
