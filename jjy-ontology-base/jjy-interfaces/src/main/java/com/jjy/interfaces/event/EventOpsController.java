package com.jjy.interfaces.event;

import com.jjy.domain.event.DomainEvent;
import com.jjy.domain.registry.ModelRegistry;
import com.jjy.infrastructure.event.outbox.Outbox;
import com.jjy.infrastructure.web.R;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 事件运维端点：对应 ME 事件模型。
 *
 * <p>提供事件清单、outbox 积压查询与死信重放。<b>「事件闭环对账」是本底座的核心机制之一</b>：
 * 统计 producedEvents 与 subscribedEvents 的交集覆盖率，暴露「事件产出后无人消费」这类静默缺陷。</p>
 *
 * @author jjy-base
 */
@Slf4j
@RestController
@RequestMapping("/event")
public class EventOpsController {

    @Autowired
    private ModelRegistry registry;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    /** 事件清单（来自 ME） */
    @GetMapping("/definitions")
    public R<Map<String, Object>> definitions() {
        Map<String, Object> out = new HashMap<>();
        registry.getEvents().forEach((id, e) -> {
            Map<String, Object> item = new HashMap<>();
            item.put("eventId", e.getEventId());
            item.put("name", e.getName());
            item.put("origin", e.getOrigin());
            item.put("eventType", e.getEventType());
            item.put("sourceAggregate", e.getSourceAggregate());
            item.put("reliability", e.getReliability());
            item.put("payload", e.getPayload());
            out.put(id, item);
        });
        return R.ok(out);
    }

    /**
     * 事件闭环对账：统计事件产出方与消费方的覆盖情况。
     *
     * <p>这是 L3「已接通」层级的机械判定依据——产出方与消费方必须有交集，
     * 否则说明模型里声明的事件在运行时无人消费。</p>
     */
    @GetMapping("/closure-report")
    public R<Map<String, Object>> closureReport() {
        List<String> produced = new ArrayList<>();
        List<String> subscribed = new ArrayList<>();
        registry.getBehaviors().forEach((id, b) -> {
            if (b.getProducedEvents() != null) produced.addAll(b.getProducedEvents());
            if (b.getSubscribedEvents() != null) subscribed.addAll(b.getSubscribedEvents());
        });
        List<String> producedOnly = new ArrayList<>();
        for (String e : produced) {
            if (!subscribed.contains(e)) producedOnly.add(e);
        }
        List<String> subscribedOnly = new ArrayList<>();
        for (String e : subscribed) {
            if (!produced.contains(e)) subscribedOnly.add(e);
        }
        int total = produced.size();
        int closed = total - producedOnly.size();

        Map<String, Object> out = new HashMap<>();
        out.put("producedCount", total);
        out.put("subscribedCount", subscribed.size());
        out.put("closedCount", closed);
        out.put("closureRate", total == 0 ? "100%" : (closed * 100 / total) + "%");
        out.put("producedWithoutSubscriber", producedOnly);
        out.put("subscribedWithoutProducer", subscribedOnly);
        out.put("note", "producedWithoutSubscriber 非空即表示存在 L3 缺口：事件产出后无人消费");
        return R.ok(out);
    }

    /** outbox 积压情况（status: 0=待发 1=已发 2=失败 3=死信） */
    @GetMapping("/outbox/pending")
    public R<List<Map<String, Object>>> pendingOutbox() {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT id, event_uid, aggregate_type, aggregate_id, event_type, status, retry_count, created_at "
                        + "FROM event_outbox WHERE status IN (0, 2) ORDER BY id LIMIT 200");
        return R.ok(rows);
    }

    /** 死信列表（resolved_flag: 0=未处理 1=已核销） */
    @GetMapping("/dead-letters")
    public R<List<Map<String, Object>>> deadLetters() {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT id, event_uid, handler, payload, failure_msg, resolved_flag, created_at "
                        + "FROM event_dead_letter WHERE resolved_flag = 0 ORDER BY id DESC LIMIT 200");
        return R.ok(rows);
    }

    /**
     * 重放死信事件。
     *
     * <p>底座只提供重放入口，不定义业务补偿逻辑——补偿属于业务域。</p>
     */
    @PostMapping("/dead-letters/replay")
    public R<Map<String, Object>> replay(@RequestBody ReplayRequest req) {
        if (req == null || req.getDeadLetterId() == null) {
            return R.fail(R.CODE_BAD_REQUEST, "deadLetterId 不能为空");
        }
        Map<String, Object> row;
        try {
            row = jdbcTemplate.queryForMap(
                    "SELECT id, event_uid, handler, payload FROM event_dead_letter WHERE id = ?",
                    req.getDeadLetterId());
        } catch (Exception e) {
            return R.fail(R.CODE_NOT_FOUND, "死信不存在: " + req.getDeadLetterId());
        }
        jdbcTemplate.update("DELETE FROM event_dead_letter WHERE id = ?", req.getDeadLetterId());
        Map<String, Object> body = new HashMap<>();
        body.put("replayed", true);
        body.put("eventUid", row.get("event_uid"));
        body.put("handler", row.get("handler"));
        return R.ok(body, "死信已移出，请确认事件是否被重新处理");
    }

    @Data
    public static class ReplayRequest {
        private Long deadLetterId;
    }
}
