package com.jjy.infrastructure.scenario;

/**
 * M4 场景步骤类型。
 *
 * @author jjy-base
 */
public enum ScenarioStepType {
    /** 调 M2 行为（BehaviorDispatcher.execute） */
    BEHAVIOR_CALL,
    /** 等待 M1/M2 产生的指定 event 到达 */
    EVENT_WAIT,
    /** 用 M3 规则对当前 context 求值，命中 branch.targetStep */
    RULE_CHECK,
    /** 嵌套调用另一个 scenario（M4.SCN-VAS-001 这类 EMBEDDED_IN_FLOW） */
    SUB_SCENARIO
}
