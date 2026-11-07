package com.jjy.application.executor;

import com.jjy.domain.behavior.Behavior;
import com.jjy.domain.entity.Aggregate;
import com.jjy.domain.registry.ModelRegistry;
import com.jjy.domain.rule.Rule;
import com.jjy.infrastructure.event.DomainEventMessage;
import com.jjy.infrastructure.event.outbox.OutboxService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 行为执行器（Java 8 兼容版）
 * <p>
 * 核心职责：
 * 1. 接收 Aggregate（聚合根 POJO）+ behaviorId（行为 ID）
 * 2. 查 ModelRegistry 获取行为元数据（M2）
 * 3. 前置规则校验（Aviator 表达式）
 * 4. 执行业务逻辑（调用 handler 或直接修改状态）
 * 5. 状态变更写入 outbox（produceEvents → DomainEventMessage）
 * 6. 写回 Aggregate
 * <p>
 * 事务边界：一个行为 = 一个事务。
 *
 * @author jjy-base
 */
@Slf4j
@Component
public class BehaviorExecutor {

    private final ModelRegistry registry;
    private final OutboxService outboxService;
    private final RuleEvaluator ruleEvaluator;

    public BehaviorExecutor(ModelRegistry registry, OutboxService outboxService,
                             @Qualifier("executorRuleEvaluator") RuleEvaluator ruleEvaluator) {
        this.registry = registry;
        this.outboxService = outboxService;
        this.ruleEvaluator = ruleEvaluator;
    }

    /**
     * @param aggregate 聚合根（可以是 Map&lt;String,Object&gt; 通用版或具体 POJO）
     * @param behaviorId M2 行为 ID
     * @param context 运行时上下文（userId / tenantId 等，供规则引擎求值）
     * @param &lt;T&gt; 聚合根类型
     * @return 执行结果（行为出参 + 产生的事件 UID 列表）
     */
    @Transactional(rollbackFor = Exception.class)
    public <T extends Aggregate> BehaviorResult execute(T aggregate, String behaviorId, Map<String, Object> context) {
        Behavior behavior = registry.getBehavior(behaviorId);
        if (behavior == null) {
            throw new IllegalArgumentException("未找到行为: " + behaviorId);
        }

        // 1. 前置规则校验
        validateRules(behavior.getPreRules(), aggregate, context);

        // 2. 获取聚合根当前状态
        Map<String, Object> state = aggregate.getAttrs();

        // 3. 执行业务逻辑 → 产生 outputParams
        Map<String, Object> outputParams = executeBehaviorLogic(behavior, state, context);

        // 4. 后置规则校验
        validateRules(behavior.getPostRules(), aggregate, context);

        // 5. 状态变更合并回 aggregate
        if (outputParams != null) {
            for (Map.Entry<String, Object> entry : outputParams.entrySet()) {
                aggregate.setAttr(entry.getKey(), entry.getValue());
            }
        }

        // 6. 产生事件（outbox）
        List<String> eventUids = produceEvents(aggregate, behavior, outputParams);

        return new BehaviorResult(aggregate, outputParams, eventUids);
    }

    /**
     * 规则校验（Aviator 表达式求值）
     */
    private void validateRules(List<String> ruleIds, Aggregate aggregate, Map<String, Object> context) {
        if (ruleIds == null || ruleIds.isEmpty()) return;

        Map<String, Object> state = aggregate.getAttrs();
        for (String ruleId : ruleIds) {
            Rule rule = registry.getRule(ruleId);
            if (rule == null) {
                log.warn("[BehaviorExecutor] 规则不存在: {}", ruleId);
                continue;
            }
            boolean passed = ruleEvaluator.evaluate(rule, state, context);
            if (!passed) {
                throw new RuleViolationException(ruleId, rule.getName(),
                    ruleEvaluator.getLastError(ruleId));
            }
        }
    }

    /**
     * 业务逻辑执行（模板方法，子类覆盖）
     * <p>
     * 默认实现：行为定义了 actionScript 时走脚本解析。
     * 实际项目：直接注入 Handler Bean，这里是兜底。
     */
    @SuppressWarnings("unchecked")
    private Map<String, Object> executeBehaviorLogic(Behavior behavior, Map<String, Object> state,
                                                    Map<String, Object> context) {
        String script = behavior.getActionScript();
        if (script == null || script.isEmpty()) {
            // 无脚本的行为：纯状态变更（outputParams 由调用方提供）
            return null;
        }
        // Java 8：暂不支持脚本解析（无 Nashorn），走 Groovy 或直接调 Handler
        throw new UnsupportedOperationException(
            "Java 8 行为脚本执行请使用 Groovy 脚本引擎，behaviorId=" + behavior.getId());
    }

    /**
     * 产生领域事件 → 写入 outbox
     */
    private List<String> produceEvents(Aggregate aggregate, Behavior behavior,
                                        Map<String, Object> outputParams) {
        List<String> produced = behavior.getProducedEvents();
        if (produced == null || produced.isEmpty()) {
            return null;
        }

        String tenantId = String.valueOf(aggregate.getAttr("tenantId"));
        String operatorId = String.valueOf(aggregate.getAttr("updatedBy"));

        return produced.stream().map(eventId -> {
            Map<String, Object> payload = buildEventPayload(eventId, aggregate, outputParams, context(aggregate));
            return outboxService.enqueue(eventId, tenantId, operatorId, payload);
        }).collect(Collectors.toList());
    }

    private Map<String, Object> buildEventPayload(String eventId, Aggregate aggregate,
                                                    Map<String, Object> outputParams,
                                                    Map<String, Object> context) {
        com.jjy.domain.event.DomainEvent meta = registry.getEvent(eventId);
        Map<String, Object> payload = new java.util.HashMap<>();
        payload.put("eventId", eventId);
        payload.put("aggregateId", aggregate.getAttr("id"));
        payload.put("aggregateType", aggregate.getAttr("_modelType"));
        payload.put("payload", outputParams != null ? outputParams : new java.util.HashMap<>());
        if (meta != null) {
            payload.put("eventName", meta.getName());
        }
        return payload;
    }

    private Map<String, Object> context(Aggregate aggregate) {
        Map<String, Object> ctx = new java.util.HashMap<>();
        ctx.put("tenantId", aggregate.getAttr("tenantId"));
        ctx.put("userId", aggregate.getAttr("updatedBy"));
        return ctx;
    }

    /** 执行结果 */
    public static class BehaviorResult {
        public final Aggregate aggregate;
        public final Map<String, Object> outputParams;
        public final List<String> eventUids;

        public BehaviorResult(Aggregate aggregate, Map<String, Object> outputParams, List<String> eventUids) {
            this.aggregate = aggregate;
            this.outputParams = outputParams;
            this.eventUids = eventUids;
        }
    }
}
