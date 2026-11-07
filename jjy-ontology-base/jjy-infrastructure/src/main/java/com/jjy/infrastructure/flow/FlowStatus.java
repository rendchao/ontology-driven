package com.jjy.infrastructure.flow;

/**
 * M6 流程实例状态机
 *
 * <ul>
 *   <li>{@link #RUNNING}：正在推进中</li>
 *   <li>{@link #WAITING}：等外部事件/审批（仅当实现 EVENT_WAIT 后实际使用，P5 不触发）</li>
 *   <li>{@link #COMPLETED}：走到 END 节点</li>
 *   <li>{@link #TERMINATED}：主动结束（如 onTimeout）</li>
 *   <li>{@link #FAILED}：异常</li>
 * </ul>
 */
public enum FlowStatus {
    RUNNING,
    WAITING,
    COMPLETED,
    TERMINATED,
    FAILED
}
