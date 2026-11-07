package com.jjy.domain.flow;

import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.Map;

/**
 * M6 流程定义（POJO，从 m6-flow-model.yaml 反序列化）
 * <p>
 * 流程类型：COLLABORATION（端到端协同流）/ APPROVAL（审批流）
 *
 * @author jjy-base
 */
@Data
@NoArgsConstructor
public class Flow {

    /** 流程 ID */
    private String id;

    /** 流程名称 */
    private String name;

    /** COLLABORATION / APPROVAL */
    private String flowType;

    /** 流程描述 */
    private String description;

    /** 涉及聚合根 ID 列表 */
    private List<String> businessObjectRefs;

    /** 参与角色 ID 列表 */
    private List<String> roleRefs;

    /** 触发器 */
    private Map<String, Object> trigger;

    /** 启动前提 */
    private List<String> preconditions;

    /** 完成后业务状态 */
    private List<String> postconditions;

    /** 开始活动 ID */
    private String startActivity;

    /** 结束活动 ID 列表 */
    private List<String> endActivities;

    /** 活动列表 */
    private List<Activity> activities;

    /** 版本 */
    private String version;

    @Data
    @NoArgsConstructor
    public static class Activity {
        private String activityId;
        private String name;
        private String activityType;   // START / END / USER_TASK / APPROVAL_TASK / SYSTEM_TASK / BEHAVIOR_CALL / SUB_FLOW_CALL / GATEWAY / EVENT_WAIT / SCENARIO_CALL
        private String roleRef;
        private String behaviorRef;
        private String subFlowRef;
        private String scenarioRef;
        private String ruleRef;
        private String conditionExpression;
        private List<String> approvalOutcomes;
        private String timeout;
        private String eventRef;
        private String onTimeout;
        private List<BehaviorCall> nextActivities;
        @SuppressWarnings("unused")
        private List<String> nextActivityIds; // YAML 简写：直接是 activityId 列表
        private List<Branch> branches;
    }

    @Data
    @NoArgsConstructor
    public static class BehaviorCall {
        private String activityId;
        public BehaviorCall(String activityId) { this.activityId = activityId; }
    }

    @Data
    @NoArgsConstructor
    public static class Branch {
        private String branchName;
        private String ruleRef;
        private String conditionExpression;
        private String approvalOutcome;
        private String targetActivity;
        private Boolean isDefault;
    }
}
