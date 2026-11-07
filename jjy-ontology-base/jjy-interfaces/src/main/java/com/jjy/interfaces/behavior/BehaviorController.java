package com.jjy.interfaces.behavior;

import com.jjy.application.behavior.BehaviorContext;
import com.jjy.application.behavior.BehaviorDispatcher;
import com.jjy.application.behavior.BehaviorResult;
import com.jjy.infrastructure.web.R;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;
import java.util.Map;

/**
 * 行为执行端点（模型驱动的核心入口）。
 *
 * <p>前端<b>不直接调用业务 Service</b>，而是调用 M2 行为 ID。执行链路完全由模型驱动：</p>
 * <pre>
 * POST /behavior/{behaviorId}
 *   → BehaviorDispatcher.execute(ctx)
 *      → ① 权限校验（读 M2.requiredPermissions）
 *      → ② 规则求值（读 M2.appliedRules，违反即抛中文提示）
 *      → ③ 反射调用 @BehaviorHandler
 *      → ④ 同事务写 outbox（读 M2.producedEvents）
 * </pre>
 *
 * <p>这意味着：<b>新增一个业务功能，后端只需加一个 @BehaviorHandler 方法 + 模型里加一条 YAML，
 * 前端即可调用，不需要改动本控制器或任何框架代码。</b></p>
 *
 * @author jjy-base
 */
@Slf4j
@RestController
@RequestMapping("/behavior")
public class BehaviorController {

    @Autowired
    private BehaviorDispatcher dispatcher;

    /**
     * 执行一个 M2 行为。
     *
     * @param behaviorId M2 行为 ID，如 Product_SaveAsDraft
     * @param body       输入参数；约定放入 dto 键下传递业务对象
     */
    @PostMapping("/{behaviorId}")
    public R<Map<String, Object>> execute(@PathVariable String behaviorId,
                                          @RequestBody(required = false) Map<String, Object> body) {
        BehaviorContext ctx = BehaviorContext.of(behaviorId);
        if (body != null) {
            ctx.setInput(new HashMap<>(body));
        }
        BehaviorResult result = dispatcher.execute(ctx);
        return R.ok(toPayload(result), result.getMessage());
    }

    private Map<String, Object> toPayload(BehaviorResult result) {
        Map<String, Object> data = new HashMap<>();
        data.put("aggregateId", result.getAggregateId());
        data.put("status", result.getStatus());
        data.put("producedEventIds", result.getProducedEventIds());
        if (result.getData() != null) {
            data.putAll(result.getData());
        }
        return data;
    }
}
