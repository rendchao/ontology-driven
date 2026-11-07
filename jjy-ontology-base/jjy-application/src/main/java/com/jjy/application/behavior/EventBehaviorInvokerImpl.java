package com.jjy.application.behavior;

import com.jjy.domain.behavior.EventBehaviorInvoker;
import com.jjy.infrastructure.security.LoginUser;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;

/**
 * 事件型行为执行实现（application 层）
 * <p>
 * 实现 {@link EventBehaviorInvoker} SPI，由 infrastructure 层的
 * {@code EventSubscriberRegistry} 在事件到达时回调。
 * <p>
 * 走 {@link BehaviorDispatcher#execute} 的完整链路：
 * <ol>
 *   <li>权限校验（EVENT 行为通常无 requiredPermissions，放行）</li>
 *   <li>规则求值（VALIDATION 类型用事件 payload 填充参数）</li>
 *   <li>反射调 {@code @BehaviorHandler} 方法</li>
 *   <li>同事务写 outbox（{@code producedEvents}），从而形成事件链的下一环</li>
 * </ol>
 *
 * <h3>系统执行者</h3>
 * <p>事件是异步派发的，此时通常没有登录态（SecurityContext 为空）。
 * {@code BehaviorDispatcher} 需要 actor，故这里构造一个系统账号，
 * 权限为 {@code *}，userType 为 {@code SYSTEM}，保证规则与 handler 能正常执行。
 *
 * @author jjy-base
 */
@Slf4j
@Service
public class EventBehaviorInvokerImpl implements EventBehaviorInvoker {

    @Autowired
    private BehaviorDispatcher dispatcher;

    @Override
    public void invoke(String behaviorId, Map<String, Object> input, Map<String, Object> payload, String aggregateId) {
        BehaviorContext ctx = BehaviorContext.of(behaviorId);
        if (input != null) {
            ctx.getInput().putAll(input);
        }
        if (payload != null) {
            ctx.setPayload(new HashMap<>(payload));
            // 规则求值需要能从 payload 读字段（如 "payload.xxx" / 直接字段名），
            // 因此把 payload 字段也平铺到 input，规则参数解析时可命中。
            for (Map.Entry<String, Object> e : payload.entrySet()) {
                ctx.getInput().putIfAbsent(e.getKey(), e.getValue());
            }
        }
        if (aggregateId != null) {
            ctx.setAggregateId(aggregateId);
        }
        ctx.setActor(systemActor());

        BehaviorResult result = dispatcher.execute(ctx);
        log.info("[EventBehavior] 行为 {} 执行完成 → aggregateId={}, 产出事件={}",
                behaviorId, result.getAggregateId(), result.getProducedEventIds());
    }

    /**
     * 系统执行者：事件异步派发时无登录态，用系统账号保证链路能跑通。
     */
    private LoginUser systemActor() {
        LoginUser u = new LoginUser();
        u.setUserId("SYSTEM");
        u.setUsername("system");
        u.setFullName("系统事件执行");
        u.setUserType("SYSTEM");
        u.setCustomerId(null);
        u.setCurrentTenantId(null);
        u.setRoleIds(java.util.Collections.singletonList("ROLE-SYSTEM"));
        u.setPermissions(new HashSet<>(java.util.Collections.singletonList("*")));
        return u;
    }
}
