package com.jjy.infrastructure.event.handler;

import com.jjy.domain.behavior.Behavior;
import com.jjy.domain.behavior.EventBehaviorInvoker;
import com.jjy.domain.registry.ModelRegistry;
import com.jjy.domain.rule.Rule;
import com.jjy.infrastructure.event.DomainEventMessage;
import com.jjy.infrastructure.event.idempotent.IdempotentService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import com.jjy.infrastructure.yaml.ModelLoadedEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 事件订阅者自动注册
 * <p>
 * 启动时遍历 M2.behaviors（triggerType=EVENT）和 M3.rules（ruleType=EVENT_DRIVEN），
 * 为每个事件订阅者动态注册本地监听。
 * <p>
 * 实现策略：维护一个 {@code Map<String eventId, List<String>>}，事件到达时遍历派发。
 *
 * <h3>订阅者查找</h3>
 * <p>M2 的 EVENT 行为与 M3 的 EVENT_DRIVEN 规则都<b>不是独立 Bean</b>：
 * <ul>
 *   <li>EVENT 行为的实现体是 {@code @BehaviorHandler("Xxx_Yyy")} 注解方法，
 *       与普通行为共用同一套 handler 类，靠注解区分；</li>
 *   <li>EVENT_DRIVEN 规则命中后要调用 {@code Rule.onMatchBehavior} 指向的行为，
 *       同样落到 {@code @BehaviorHandler} 注解方法上。</li>
 * </ul>
 * <p>因此这里不再用 {@code ctx.getBean(behaviorId)} 查找——代码中不存在以行为 ID 命名的 Bean，
 * 该查找必然失败并被静默吞掉，导致事件链路从未真正执行。改为通过
 * {@link EventBehaviorInvoker} SPI 回调 application 层，由它走
 * {@code BehaviorDispatcher} 的完整链路（权限 → 规则 → handler → 同事务写 outbox）。
 *
 * @author jjy-base
 */
@Slf4j
@Component
public class EventSubscriberRegistry {

    @Autowired
    private ModelRegistry registry;

    @Autowired
    private IdempotentService idempotent;

    /**
     * 行为执行 SPI，由 application 层实现（依赖反转，避免 infrastructure 反向依赖 application）。
     */
    @Autowired
    private EventBehaviorInvoker behaviorInvoker;

    /** 事件 ID → 订阅该事件的（行为 ID / 规则 ID）列表 */
    private final Map<String, java.util.List<String>> subscribers = new HashMap<>();

    /**
     * 订阅者注册时机。
     *
     * <p><b>必须晚于模型加载</b>：{@code ModelLoader} 在 {@code ApplicationReadyEvent}
     * 才把 11 个 YAML 装进 {@link ModelRegistry}，而本类原先监听
     * {@code ContextRefreshedEvent}（更早），导致注册时注册表还是空的——
     * 表现为「事件正常产出、但永远没有消费方」，是极难排查的静默缺陷。</p>
     *
     * <p>改为监听 {@code ModelLoader} 装载并校验通过后发布的 {@link ModelLoadedEvent}，
     * 顺序完全确定（不再依赖 Spring 对同一事件的监听器执行顺序）。
     * 注册前仍会检查注册表是否已装载，未装载则明确告警而非静默注册 0 个订阅者。</p>
     */
    @EventListener(ModelLoadedEvent.class)
    public void onModelLoaded(ModelLoadedEvent event) {
        // 模型尚未装载 → 明确告警，避免「注册了 0 个订阅者」这种静默失败
        if (registry.getBehaviors().isEmpty() || registry.getEvents().isEmpty()) {
            log.warn("[EventSubscriberRegistry] 模型注册表尚未装载（behaviors={} events={}），"
                            + "跳过订阅者注册。请检查 ModelLoader 是否正常加载。",
                    registry.getBehaviors().size(), registry.getEvents().size());
            return;
        }
        // 1. 收集 ME 事件声明的订阅方（subscribers[].handlerRef）
        //
        //    以 ME 为订阅关系的权威来源：事件模型从「事件视角」声明谁订阅我，
        //    handlerRef 指向 M2 的行为 ID。
        //    此前只从 M2 的 subscribedEvents 建订阅，ME 声明的 handlerRef
        //    无人解析——ME 模型写了订阅方，运行期完全不知道（模型说了不算）。
        for (com.jjy.domain.event.DomainEvent evt : registry.getEvents().values()) {
            if (evt.getEventId() == null || evt.getSubscribers() == null) continue;
            for (Map<String, String> sub : evt.getSubscribers()) {
                if (sub == null) continue;
                String handlerRef = sub.get("handlerRef");
                if (handlerRef == null || handlerRef.trim().isEmpty()) {
                    log.warn("[EventSubscriberRegistry] 事件 {} 的 subscriber 缺少 handlerRef，已跳过",
                            evt.getEventId());
                    continue;
                }
                if (!registry.getBehaviors().containsKey(handlerRef)) {
                    // 不静默跳过：悬空引用必须暴露，否则事件产出后无人消费却毫无征兆
                    log.error("[EventSubscriberRegistry] 事件 {} 的 handlerRef={} 在 M2 中不存在，订阅未建立",
                            evt.getEventId(), handlerRef);
                    continue;
                }
                subscribers.computeIfAbsent(evt.getEventId(), k -> new java.util.ArrayList<>())
                        .add("BEHAVIOR:" + handlerRef);
            }
        }
        // 2. 收集 M3 EVENT_DRIVEN 规则的订阅
        for (Rule r : registry.getRules().values()) {
            if (!"EVENT_DRIVEN".equals(r.getRuleType())) continue;
            if (r.getSubscribedEvent() == null) continue;
            subscribers.computeIfAbsent(r.getSubscribedEvent(), k -> new java.util.ArrayList<>()).add("RULE:" + r.getId());
        }
        log.info("[EventSubscriberRegistry] 注册完成：{} 个事件有订阅者，订阅明细={}",
                subscribers.size(), subscribers);
    }

    /**
     * 派发：遍历订阅者，幂等执行
     */
    public void dispatch(DomainEventMessage msg) {
        List<String> subs = subscribers.get(msg.getEventType());
        if (subs == null || subs.isEmpty()) {
            log.debug("[EventSubscriber] 事件无订阅者: {}", msg.getEventType());
            return;
        }
        for (String sub : subs) {
            String[] parts = sub.split(":", 2);
            String kind = parts[0];
            String id = parts[1];

            // 幂等占位
            if (!idempotent.markIfAbsent(msg.getEventUid(), sub)) {
                continue;
            }

            try {
                if ("BEHAVIOR".equals(kind)) {
                    invokeBehavior(id, msg);
                } else if ("RULE".equals(kind)) {
                    invokeRule(id, msg);
                }
            } catch (Exception e) {
                log.error("[EventSubscriber] 处理失败: handler={} eventUid={}", sub, msg.getEventUid(), e);
                idempotent.markFailed(msg.getEventUid(), sub, e.getMessage());
            }
        }
    }

    /**
     * 调用 EVENT 类型行为。
     * <p>事件 payload 作为行为入参，{@code id} 取 payload 里的业务主键
     * （如 {@code Order_ReceiveWmsReceipt} 从 {@code ctx.getInput().get("id")} 取单据 ID）。</p>
     */
    private void invokeBehavior(String behaviorId, DomainEventMessage msg) {
        Map<String, Object> input = buildInput(msg);
        behaviorInvoker.invoke(behaviorId, input, msg.getPayload(), msg.getAggregateId());
        log.info("[EventSubscriber] 行为执行完成: behavior={} eventUid={}", behaviorId, msg.getEventUid());
    }

    /**
     * EVENT_DRIVEN 规则：命中后调用 {@code Rule.onMatchBehavior} 指向的行为。
     * <p>规则没有独立实现类，命中判定由 {@code EventBehaviorInvoker} 在 application 层
     * 用规则引擎完成（表达式求值），命中后再回调该行为。</p>
     */
    private void invokeRule(String ruleId, DomainEventMessage msg) {
        Rule r = registry.getRule(ruleId);
        if (r == null) {
            log.warn("[EventSubscriber] 找不到规则 {}", ruleId);
            return;
        }
        String onMatch = r.getOnMatchBehavior();
        if (onMatch == null || onMatch.isEmpty()) {
            log.debug("[EventSubscriber] 规则 {} 未配置 onMatchBehavior，跳过", ruleId);
            return;
        }
        log.info("[EventSubscriber] 规则 {} 命中 → 调用行为 {}", ruleId, onMatch);
        invokeBehavior(onMatch, msg);
    }

    /**
     * 构造 handler 的输入参数：payload 全部字段 + id + aggregateId 兜底。
     */
    private Map<String, Object> buildInput(DomainEventMessage msg) {
        Map<String, Object> input = new HashMap<>();
        if (msg.getPayload() != null) {
            input.putAll(msg.getPayload());
        }
        if (msg.getAggregateId() != null) {
            input.putIfAbsent("id", msg.getAggregateId());
            input.putIfAbsent("aggregateId", msg.getAggregateId());
        }
        return input;
    }

    public Map<String, List<String>> getSubscribers() {
        return subscribers;
    }
}
