package com.jjy.domain.rule;

import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.Map;

/**
 * M3 规则定义（POJO，从 m3-rule-model.yaml 反序列化）
 * <p>
 * 规则类型：
 * <ul>
 *   <li>VALIDATION：被行为调用的同步规则</li>
 *   <li>CALCULATION：计算类规则</li>
 *   <li>EVENT_DRIVEN：订阅事件，命中时调 onMatchBehavior</li>
 * </ul>
 *
 * @author jjy-base
 */
@Data
@NoArgsConstructor
public class Rule {

    /** 规则 ID */
    private String id;

    /** 规则名称 */
    private String name;

    /** VALIDATION / CALCULATION / EVENT_DRIVEN */
    private String ruleType;

    /** 规则描述 */
    private String description;

    /** Aviator / SpEL 表达式（如 {@code receivedTotal >= totalAmount}） */
    private String expression;

    /** 表达式引擎：AVIATOR（默认） / SPEL */
    private String engine;

    /** 违规消息 */
    private String violationMessage;

    /** 调用本规则的行为 ID 列表（反向追踪） */
    private List<String> reusedBy;

    /** EVENT_DRIVEN 规则订阅的事件 ID */
    private String subscribedEvent;

    /** EVENT_DRIVEN 规则匹配后调用的行为 */
    private String onMatchBehavior;

    /** 输入参数（Map） */
    private List<Map<String, Object>> inputParams;

    /** 输出类型（Boolean/Money/Decimal/String/Integer） */
    private String outputType;

    /** 版本 */
    private String version;
}
