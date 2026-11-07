package com.jjy.application.scenario.step;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.HashMap;
import java.util.Map;

/**
 * 单 step 执行结果。
 *
 * <p>由 {@link StepHandler} 返回，{@link com.jjy.application.scenario.ScenarioExecutor} 根据 status 决定下一步：
 * <ul>
 *   <li>ADVANCE：进入下一个 step（按 steps 顺序或 nextStepId 跳转）</li>
 *   <li>WAIT：停在当前 step，状态机切到 WAITING；EventBridge 唤醒后会重新走本 step</li>
 *   <li>BRANCH：跳转到 branchTargetStep（rule/condition 命中的 targetStep）</li>
 *   <li>TERMINATE/COMPENSATE/FAIL：终态，对应 ScenarioStatus.*</li>
 * </ul>
 *
 * @author jjy-base
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class StepResult {

    public enum Status {
        /** 推进到下一个 step */
        ADVANCE,
        /** 停在当前 step（WAITING 状态） */
        WAIT,
        /** 跳转到指定 step（rule 命中分支） */
        BRANCH,
        /** 主动终止（终态 = TERMINATED） */
        TERMINATE,
        /** 触发 onTimeoutBehavior 补偿（终态 = COMPENSATED） */
        COMPENSATE,
        /** 异常失败（终态 = FAILED） */
        FAIL
    }

    private Status status;
    /** BRANCH 时：命中的 targetStep */
    private String branchTargetStep;
    /** COMPENSATE 时：调用的 behaviorId（来自 step.onTimeoutBehavior） */
    private String compensateBehavior;
    /** FAIL 时：写入 error_msg 的内容 */
    private String errorMessage;
    /** 累加到 context 的增量（会与原 context 合并后落库） */
    private Map<String, Object> contextDelta = new HashMap<>();

    public static StepResult advance() {
        return new StepResult(Status.ADVANCE, null, null, null, new HashMap<>());
    }

    public static StepResult advance(Map<String, Object> delta) {
        return new StepResult(Status.ADVANCE, null, null, null, delta);
    }

    public static StepResult pause() {
        return new StepResult(Status.WAIT, null, null, null, new HashMap<>());
    }

    public static StepResult branch(String targetStep) {
        return new StepResult(Status.BRANCH, targetStep, null, null, new HashMap<>());
    }

    public static StepResult terminate(String msg) {
        return new StepResult(Status.TERMINATE, null, null, msg, new HashMap<>());
    }

    public static StepResult compensate(String behavior, String msg) {
        return new StepResult(Status.COMPENSATE, null, behavior, msg, new HashMap<>());
    }

    public static StepResult fail(String msg) {
        return new StepResult(Status.FAIL, null, null, msg, new HashMap<>());
    }
}
