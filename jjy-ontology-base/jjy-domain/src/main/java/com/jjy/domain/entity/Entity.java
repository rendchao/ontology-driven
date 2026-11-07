package com.jjy.domain.entity;

import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.HashMap;
import java.util.Map;

/**
 * 通用子实体 POJO（聚合内子实体）
 *
 * @author jjy-base
 */
@Data
@NoArgsConstructor
public class Entity {

    /** 聚合内本地 ID（如 lineId / contactId） */
    private String localId;

    /** 属性 */
    private Map<String, Object> attrs = new HashMap<>();

    /**
     * 兼容构造：localId + 属性 Map
     */
    public Entity(String localId, Map<String, Object> attrs) {
        this.localId = localId;
        if (attrs != null) {
            this.attrs.putAll(attrs);
        }
    }

    public Object get(String field) {
        return attrs.get(field);
    }

    public Entity set(String field, Object value) {
        attrs.put(field, value);
        return this;
    }

    /**
     * 兼容方法：返回 attrs Map（handler 用于反射取字段）
     */
    public Map<String, Object> getAttrs() {
        return attrs;
    }
}
