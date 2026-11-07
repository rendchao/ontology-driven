package com.jjy.infrastructure.metrics;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;
import io.micrometer.core.instrument.Timer;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicLong;

/**
 * 业务自定义指标（Prometheus 通过 /actuator/prometheus 暴露）。
 *
 * 命名空间：jjy_*
 *
 * 指标列表：
 *   - jjy_outbox_pending          (Gauge)  event_outbox status=0
 *   - jjy_outbox_retry            (Gauge)  event_outbox status=2
 *   - jjy_outbox_dead             (Gauge)  event_outbox status=3
 *   - jjy_deadletter_unresolved   (Gauge)  event_dead_letter resolved_flag=0
 *   - jjy_scenarios_active        (Gauge)  scenario_instance RUNNING+WAITING
 *   - jjy_scenarios_completed     (Gauge)  scenario_instance COMPLETED
 *   - jjy_scenarios_failed        (Gauge)  scenario_instance FAILED+TERMINATED
 *   - jjy_behavior_invoke_total   (Counter) 行为调用次数（按 behavior_code / status 分桶）
 *   - jjy_behavior_invoke_seconds (Timer)  行为调用耗时
 *   - jjy_event_published_total   (Counter) 事件发布次数（按 event_id / status 分桶）
 *   - jjy_ai_chat_total           (Counter) AI 对话次数（按 provider / status 分桶）
 *
 * @author jjy-base
 */
@Slf4j
@Component
public class BusinessMetrics {

    private final MeterRegistry registry;
    private final AtomicLong outboxPending       = new AtomicLong(0);
    private final AtomicLong outboxRetry         = new AtomicLong(0);
    private final AtomicLong outboxDead          = new AtomicLong(0);
    private final AtomicLong deadLetterUnresolved = new AtomicLong(0);
    private final AtomicLong scenariosActive     = new AtomicLong(0);
    private final AtomicLong scenariosCompleted  = new AtomicLong(0);
    private final AtomicLong scenariosFailed     = new AtomicLong(0);

    @Autowired
    private JdbcTemplate jdbc;

    public BusinessMetrics(MeterRegistry registry) {
        this.registry = registry;
        // Gauge
        registry.gauge("jjy.outbox.pending",       outboxPending);
        registry.gauge("jjy.outbox.retry",         outboxRetry);
        registry.gauge("jjy.outbox.dead",          outboxDead);
        registry.gauge("jjy.deadletter.unresolved", deadLetterUnresolved);
        registry.gauge("jjy.scenarios.active",     scenariosActive);
        registry.gauge("jjy.scenarios.completed",  scenariosCompleted);
        registry.gauge("jjy.scenarios.failed",     scenariosFailed);
    }

    /**
     * 每 10s 刷新一次 gauge 数值。
     * 失败时 WARN，不抛异常（指标刷新不能影响主流程）。
     */
    @Scheduled(fixedRate = 10_000, initialDelay = 5_000)
    public void refresh() {
        try {
            outboxPending.set(count("SELECT COUNT(*) FROM event_outbox WHERE status = 0"));
            outboxRetry.set(count("SELECT COUNT(*) FROM event_outbox WHERE status = 2"));
            outboxDead.set(count("SELECT COUNT(*) FROM event_outbox WHERE status = 3"));
            deadLetterUnresolved.set(count("SELECT COUNT(*) FROM event_dead_letter WHERE resolved_flag = 0"));
            scenariosActive.set(count("SELECT COUNT(*) FROM scenario_instance WHERE status IN ('RUNNING','WAITING')"));
            scenariosCompleted.set(count("SELECT COUNT(*) FROM scenario_instance WHERE status = 'COMPLETED'"));
            scenariosFailed.set(count("SELECT COUNT(*) FROM scenario_instance WHERE status IN ('FAILED','TERMINATED')"));
        } catch (Exception e) {
            log.warn("BusinessMetrics.refresh 失败", e);
        }
    }

    private int count(String sql) {
        Integer n = jdbc.queryForObject(sql, Integer.class);
        return n == null ? 0 : n;
    }

    // ==================== Counter 供业务调用 ====================

    /**
     * 行为调用计数
     */
    public void recordBehaviorInvoke(String behaviorCode, String status) {
        Counter.builder("jjy.behavior.invoke")
                .description("行为调用次数")
                .tags(Tags.of("behavior", behaviorCode, "status", status))
                .register(registry)
                .increment();
    }

    /**
     * 行为调用耗时
     */
    public Timer.Sample startBehaviorTimer() {
        return Timer.start(registry);
    }

    public void stopBehaviorTimer(Timer.Sample sample, String behaviorCode, String status) {
        Timer timer = Timer.builder("jjy.behavior.invoke.seconds")
                .description("行为调用耗时")
                .tags(Tags.of("behavior", behaviorCode, "status", status))
                .register(registry);
        sample.stop(timer);
    }

    /**
     * 事件发布计数
     */
    public void recordEventPublished(String eventId, String status) {
        Counter.builder("jjy.event.published")
                .description("事件发布次数")
                .tags(Tags.of("event", eventId, "status", status))
                .register(registry)
                .increment();
    }

    /**
     * AI 对话计数
     */
    public void recordAiChat(String provider, String status) {
        Counter.builder("jjy.ai.chat")
                .description("AI 对话次数")
                .tags(Tags.of("provider", provider, "status", status))
                .register(registry)
                .increment();
    }
}
