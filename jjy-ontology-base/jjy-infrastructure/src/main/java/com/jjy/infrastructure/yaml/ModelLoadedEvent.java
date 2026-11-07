package com.jjy.infrastructure.yaml;

/**
 * 「模型已装载完成」事件。
 *
 * <p><b>为什么需要它：</b>依赖模型注册表的启动期组件（如事件订阅者注册）
 * 必须等 {@code ModelLoader} 把 YAML 装进 {@code ModelRegistry} 之后才能工作。
 * 若两者都监听 {@code ApplicationReadyEvent}，执行顺序由 Spring 决定、不确定——
 * 就会出现「注册时注册表还是空的 → 订阅表为空 → 事件产出正常但无人消费」的静默缺陷。</p>
 *
 * <p>本事件由 {@code ModelLoader} 在加载并校验通过后发布，保证顺序确定。</p>
 *
 * @author jjy-base
 */
public class ModelLoadedEvent {

    /** 聚合根数量 */
    private final int aggregateCount;

    /** 行为数量 */
    private final int behaviorCount;

    /** 事件数量 */
    private final int eventCount;

    public ModelLoadedEvent(int aggregateCount, int behaviorCount, int eventCount) {
        this.aggregateCount = aggregateCount;
        this.behaviorCount = behaviorCount;
        this.eventCount = eventCount;
    }

    public int getAggregateCount() {
        return aggregateCount;
    }

    public int getBehaviorCount() {
        return behaviorCount;
    }

    public int getEventCount() {
        return eventCount;
    }

    @Override
    public String toString() {
        return "ModelLoadedEvent{aggregates=" + aggregateCount
                + ", behaviors=" + behaviorCount
                + ", events=" + eventCount + "}";
    }
}
