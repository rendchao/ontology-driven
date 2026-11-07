package com.jjy.domain.behavior;

import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.Map;

/**
 * M2 行为定义（POJO，从 m2-behavior-model.yaml 反序列化）
 * <p>
 * V1 行为全部走事件驱动：执行成功且事务提交后发出 {@link #producedEvents} 事件。
 * 跨聚合联动一律经事件，禁止跨聚合同步调用。
 *
 * @author jjy-base
 */
@Data
@NoArgsConstructor
public class Behavior {

    /** 行为 ID（如 Customer_SaveAsDraft） */
    private String id;

    /** 行为业务名称 */
    private String name;

    /** 所属聚合根 ID（如 AGG-CUSTOMER-001） */
    private String ownerEntity;

    /** COMMAND / QUERY */
    private String behaviorType;

    /** USER_ACTION / SYSTEM / EVENT */
    private String triggerType;

    /** 前置条件列表（人类可读） */
    private List<String> preconditions;

    /** 后置状态变更列表 */
    private List<Map<String, String>> postconditions;

    /** 调用的 M3 规则列表 */
    private List<String> appliedRules;

    /** 行为脚本（Groovy / 表达式） */
    private String actionScript;

    /** 所需权限列表（来自 M5） */
    private List<String> requiredPermissions;

    /** 执行成功发出的事件列表（INTERNAL 来源） */
    private List<String> producedEvents;

    /** triggerType=EVENT 时订阅的事件列表（OR 语义） */
    private List<String> subscribedEvents;

    /** 幂等键字段路径（triggerType=EVENT 时必填） */
    private String idempotencyKey;

    /** M7 queryReportRef（behaviorType=QUERY 时必填） */
    private String queryReportRef;

    /**
     * 行为形态：TEMPLATE / HANDWRITTEN / SCRIPT / DECLARATIVE。
     *
     * <p>声明「这个行为由谁实现」——是模板生成、手写、脚本，还是纯声明。
     * 没有它，生成器不知道该为谁生成，守门员也无法对账。</p>
     */
    private String behaviorForm;

    /**
     * 兼容方法：取前置规则列表（BehaviorExecutor 用）。
     * <p>
     * V1 yaml 用 {@code appliedRules}，V2 起区分 pre/post；
     * 这里全部映射到 appliedRules，足够 UAT 走通。
     */
    public List<String> getPreRules() {
        return appliedRules;
    }

    /**
     * 兼容方法：取后置规则列表。
     */
    public List<String> getPostRules() {
        return appliedRules;
    }
}
