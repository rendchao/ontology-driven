package com.jjy.infrastructure.persistence.po;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * t_rule_fire_log 持久化对象 —— M3 规则触发日志（业务白盒化）
 *
 * <p>每次 {@code BehaviorDispatcher} 评估一条 M3 规则都写一行；含入参、表达式、结果，
 * 便于在管理端"按规则 / 按行为 / 按业务单"反查触发的痕迹。</p>
 *
 * @author jjy-base
 */
@Data
@TableName("t_rule_fire_log")
public class RuleFireLogPO extends BaseAuditPO {

    @TableId(type = IdType.AUTO)
    private Long id;

    private LocalDateTime firedAt;
    private String tenantId;
    private String behaviorId;
    private String behaviorName;
    private String aggregateType;
    private String aggregateId;
    private String ruleId;
    private String ruleName;
    private String ruleType;
    private String engine;
    private String expression;
    /** PASS / FAIL / SKIP */
    private String result;
    /** 0/1（SKIP 时为 null） */
    private Integer output;
    /** 入参 map JSON 字符串 */
    private String inputParams;
    private String violationMessage;
    private String actorUserId;
    private String traceId;
}
