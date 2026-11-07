package com.jjy.infrastructure.scenario;

/**
 * M4 场景实例状态机。
 *
 * <pre>
 *                  ┌─→ COMPLETED（自然走完所有 step）
 *                  │
 *  RUNNING ────────┼─→ WAITING ──→ RUNNING ──→ ...
 *   │              │     │           │
 *   │              │     │           ├─→ TIMEOUT 触发的 TERMINATE / COMPENSATE
 *   │              │     │           └─→ 业务异常 → FAILED
 *   │              │     │
 *   │              │     └─→ 超时扫描发现：TERMINATE / COMPENSATE
 *   │              │
 *   └─→ FAILED     │
 *                  └─→ EMBED 调用的子场景失败 → 外层 FAILED
 * </pre>
 *
 * @author jjy-base
 */
public enum ScenarioStatus {
    /** 正在执行 BEHAVIOR_CALL / RULE_CHECK / SUB_SCENARIO 等非等待步骤 */
    RUNNING,
    /** 停在 EVENT_WAIT 步骤，等待指定 event 到达 */
    WAITING,
    /** 全部步骤走完，scenario 成功结束 */
    COMPLETED,
    /** 主动终止（超时 onTimeout=TERMINATE / 业务主动调 terminate） */
    TERMINATED,
    /** 补偿完成（超时 onTimeout=COMPENSATE 走 onTimeoutBehavior 成功） */
    COMPENSATED,
    /** 异常失败（任意 step 抛 BizException） */
    FAILED;

    /** 是否终态（不可再 advance） */
    public boolean isTerminal() {
        return this == COMPLETED || this == TERMINATED || this == COMPENSATED || this == FAILED;
    }
}
