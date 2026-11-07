package com.jjy.domain.entity;

import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 通用聚合根 POJO
 * <p>
 * 24 个聚合根（Customer / Order / Item ...）都用这一个类表示。
 * 字段值存在 {@link #attrs}，类型信息存在 {@link #schema}，关联子表存在 {@link #entities}。
 * <p>
 * 设计原因：避免 24 个聚合根写 24 个类（数百个字段），保持领域层极简。
 * 业务层 handler 通过 {@code attrs.get("customerName")} 访问字段；通过 {@code attrs.put("status", "ACTIVE")} 修改。
 * MyBatis PO 在基础设施层做属性映射。
 *
 * @author jjy-base
 */
@Data
@NoArgsConstructor
public class Aggregate implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 聚合根 ID（如 AGG-CUSTOMER-001） */
    private String aggregateId;

    /** 业务主键（UUID 字符串） */
    private String id;

    /** 业务编号（CUS+日期+流水） */
    private String businessNo;

    /** 当前生命周期状态 */
    private String status;

    /** 所有属性：field name → value */
    private Map<String, Object> attrs = new HashMap<>();

    /** 子实体列表：entity name → List<Entity> */
    private Map<String, List<Entity>> entities = new HashMap<>();

    /**
     * 兼容构造：聚合类型 + 属性 Map
     * <p>
     * V1-V2 大量 handler 用 {@code new Aggregate("Order", attrs)} 写法，
     * 代替先 new 再 setAttr 一遍。
     */
    public Aggregate(String aggregateType, Map<String, Object> attrs) {
        if (attrs != null) {
            this.attrs.putAll(attrs);
        }
        if (aggregateType != null) {
            this.attrs.put("_modelType", aggregateType);
        }
    }

    /**
     * 兼容方法：按子表名存子实体列表（Order.items / Invoice.billingItems 等）
     */
    public Aggregate setEntities(String name, List<Entity> entities) {
        this.entities.put(name, entities);
        return this;
    }

    /**
     * 兼容方法：按子表名取子实体列表
     */
    @SuppressWarnings("unchecked")
    public List<Entity> getEntities(String name) {
        return this.entities.get(name);
    }

    /**
     * 获取属性值
     */
    public Object get(String field) {
        return attrs.get(field);
    }

    /**
     * 设置属性值
     */
    public Aggregate set(String field, Object value) {
        attrs.put(field, value);
        return this;
    }

    /**
     * 兼容方法：别名 setAttr（BehaviorExecutor / UserRoleHandler 等老代码用）
     */
    public Aggregate setAttr(String field, Object value) {
        return set(field, value);
    }

    /**
     * 兼容方法：别名 getAttr（InvoiceHandler / OrderHandler 等用）
     */
    public Object getAttr(String field) {
        return attrs.get(field);
    }

    /**
     * 兼容方法：返回 attrs Map（BehaviorExecutor 用）
     */
    public Map<String, Object> getAttrs() {
        return attrs;
    }

    /**
     * 获取 String 字段（带类型转换）
     */
    public String getString(String field) {
        Object v = attrs.get(field);
        return v == null ? null : v.toString();
    }

    /**
     * 获取 Integer 字段
     */
    public Integer getInt(String field) {
        Object v = attrs.get(field);
        if (v == null) return null;
        if (v instanceof Number) return ((Number) v).intValue();
        return Integer.parseInt(v.toString());
    }

    /**
     * 获取 Long 字段
     */
    public Long getLong(String field) {
        Object v = attrs.get(field);
        if (v == null) return null;
        if (v instanceof Number) return ((Number) v).longValue();
        return Long.parseLong(v.toString());
    }

    /**
     * 获取 BigDecimal 字段（用于金额）
     */
    public java.math.BigDecimal getDecimal(String field) {
        Object v = attrs.get(field);
        if (v == null) return null;
        if (v instanceof java.math.BigDecimal) return (java.math.BigDecimal) v;
        return new java.math.BigDecimal(v.toString());
    }
}
