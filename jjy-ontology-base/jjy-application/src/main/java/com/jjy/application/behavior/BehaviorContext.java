package com.jjy.application.behavior;

import com.jjy.infrastructure.security.LoginUser;
import lombok.Data;

import java.util.HashMap;
import java.util.Map;

/**
 * 行为执行上下文
 *
 * <p>由 BehaviorDispatcher 在执行前构造，传递给 handler 方法</p>
 *
 * @author jjy-base
 */
@Data
public class BehaviorContext {

    /** M2 行为 ID */
    private String behaviorId;

    /** 所属聚合根 ID（AGG-XXX-001） */
    private String aggregateId;

    /** 当前操作者（从 SecurityContext 拿） */
    private LoginUser actor;

    /** 输入参数：M2 行为的请求 DTO 反序列化后入此 */
    private Map<String, Object> input;

    /** 事件 payload 字段解析：M2.producedEvents 写 outbox 时用 */
    private Map<String, Object> payload;

    /** 当前聚合根状态（查询后放入） */
    private Map<String, Object> currentState;

    public BehaviorContext() {
        this.input = new HashMap<>();
        this.payload = new HashMap<>();
        this.currentState = new HashMap<>();
    }

    public static BehaviorContext of(String behaviorId) {
        BehaviorContext ctx = new BehaviorContext();
        ctx.setBehaviorId(behaviorId);
        return ctx;
    }

    public Object input(String key) {
        return input == null ? null : input.get(key);
    }

    public String inputString(String key) {
        Object v = input(key);
        return v == null ? null : v.toString();
    }

    /**
     * 将当前 PO 放入 currentState,key 为聚合根 alias (如 "Customer")
     * Rule 表达式 "Customer.submittedBy" 会从这里读
     */
    public void putState(String aggregateType, Object po) {
        if (this.currentState == null) this.currentState = new HashMap<>();
        this.currentState.put(aggregateType, po);
    }
}
