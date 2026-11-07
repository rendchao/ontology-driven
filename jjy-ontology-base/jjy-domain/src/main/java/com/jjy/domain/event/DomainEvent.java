package com.jjy.domain.event;

import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.Map;

/**
 * ME 事件定义（POJO，从 me-event-model.yaml 反序列化）
 *
 * @author jjy-base
 */
@Data
@NoArgsConstructor
public class DomainEvent {

    /**
     * 事件 ID（EVT-CUS-002）。
     *
     * <p>注意：模型里 ID 字段存在两种写法（{@code eventId} 与 {@code id}），
     * 统一由 {@code ModelLoader} 做兜底解析（domain 层不引入 Jackson 依赖）。</p>
     */
    private String eventId;

    /** 事件名称 */
    private String name;

    /** INTERNAL_BEHAVIOR / EXTERNAL */
    private String origin;

    /** origin=INTERNAL_BEHAVIOR 时的事件源（单生产者） */
    private String sourceBehavior;

    /** origin=EXTERNAL 时的 MI 通道 */
    private String miChannelRef;

    /** 所属聚合根 ID */
    private String sourceAggregate;

    /** STATE_CHANGE / OCCURRENCE */
    private String eventType;

    /** 载荷字段列表 */
    private List<PayloadField> payload;

    /** AT_LEAST_ONCE / AT_MOST_ONCE */
    private String reliability;

    /** NORMAL / AUDIT_ONLY */
    private String retention;

    /** 版本 */
    private String version;

    /** 是否多生产者（true=多个 M2 行为可产生本事件） */
    private Boolean multiProducer;

    /**
     * 事件订阅方声明（来自 ME 模型的 {@code events[].subscribers[]}）。
     *
     * <p><b>此前 POJO 没有这个字段</b>，而 Jackson 配了
     * {@code FAIL_ON_UNKNOWN_PROPERTIES=false} —— ME 模型里的
     * {@code subscribers[]} 被<b>静默丢弃</b>：模型写了订阅方，运行期完全不知道。
     * 这是典型的「模型说了不算」，且没有任何报错。</p>
     *
     * <p>本字段让 ME 声明的订阅方真正进入领域模型，
     * 供 {@code ModelRegistry.validateReferences()} 与 M2 的
     * {@code subscribedEvents} 做<b>双向闭合</b>校验：</p>
     * <ul>
     *   <li>ME 声明了订阅方 → 该行为必须存在，且其 {@code subscribedEvents} 必须含本事件；</li>
     *   <li>M2 声明了订阅本事件 → ME 必须也声明了它，否则事件产出后无人知晓。</li>
     * </ul>
     * <p>每个元素形如 {@code {handlerRef: Product_OnShelf, description: ...}}。</p>
     */
    private List<Map<String, String>> subscribers;

    @Data
    @NoArgsConstructor
    public static class PayloadField {
        private String name;
        private String dataType;
        private String sourceField;
        private String description;
    }
}
