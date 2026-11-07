package com.jjy.infrastructure.integration;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jjy.domain.registry.ModelRegistry;
import com.jjy.infrastructure.event.outbox.OutboxService;
import com.jjy.infrastructure.persistence.mapper.integration.WebhookEventMapper;
import com.jjy.infrastructure.persistence.po.integration.WebhookEventPO;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Webhook 事件服务
 * <p>P6 M1 集成模型：14 个 EVENT_INBOUND 端点的统一处理</p>
 *
 * <h3>流程</h3>
 * <ol>
 *   <li>{@link #receive}：同步写 t_webhook_event (status=0)，返回 2000</li>
 *   <li>{@link #processAsync}：异步（@Async）按 M1 mappedInternalEvent 写 outbox</li>
 *   <li>{@link #scheduleRetry}：失败时计算下次重试时间（指数退避）</li>
 * </ol>
 *
 * @author jjy-base
 */
@Slf4j
@Service
public class WebhookEventService {

    @Autowired
    private WebhookEventMapper mapper;

    @Autowired
    private OutboxService outboxService;

    @Autowired
    private ObjectMapper objectMapper;

    /**
     * 本体注册表：提供 MI 接口声明（外部接口 → 内部事件 / 聚合类型 / 聚合 ID 字段）。
     */
    @Autowired
    private ModelRegistry registry;

    /** 按 eventUid 查（仅服务内部用，避免反射） */
    public WebhookEventPO findByEventUid(String eventUid) {
        return mapper.findByEventUid(eventUid);
    }

    /**
     * 同步落库（idempotent by eventUid）
     *
     * @return 已存在返回 false（重复事件，不重复处理）；首次插入返回 true
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW, rollbackFor = Exception.class)
    public boolean receive(String eventUid, String interfaceId, String systemCode,
                           String payloadJson, String signature, String remoteIp) {
        if (eventUid == null || eventUid.isEmpty()) {
            eventUid = UUID.randomUUID().toString().replace("-", "");
        }
        WebhookEventPO exist = mapper.findByEventUid(eventUid);
        if (exist != null) {
            log.info("[Webhook] 重复事件跳过: eventUid={}, interfaceId={}", eventUid, interfaceId);
            return false;
        }
        WebhookEventPO po = new WebhookEventPO();
        po.setEventUid(eventUid);
        po.setInterfaceId(interfaceId);
        po.setSystemCode(systemCode);
        po.setPayload(payloadJson);
        po.setStatus(0);  // 待处理
        po.setRetryCount(0);
        po.setResponseCode(200);
        po.setSignature(signature);
        po.setRemoteIp(remoteIp);
        po.setReceivedAt(LocalDateTime.now());
        po.setCreatedAt(LocalDateTime.now());
        po.setUpdatedAt(LocalDateTime.now());
        po.setFlag(0);
        mapper.insert(po);
        return true;
    }

    /**
     * 异步处理：把 webhook payload 写到 outbox，由 outbox dispatcher 触发订阅者
     */
    @Async("eventTaskExecutor")
    public void processAsync(Long id) {
        WebhookEventPO po = mapper.selectById(id);
        if (po == null) {
            log.warn("[Webhook] 事件不存在: id={}", id);
            return;
        }
        if (po.getStatus() != null && po.getStatus() != 0) {
            log.info("[Webhook] 事件已处理: id={}, status={}", id, po.getStatus());
            return;
        }
        try {
            String mapped = mapToInternalEvent(po.getInterfaceId());
            if (mapped == null) {
                markFailed(po.getId(), "未配置 mappedInternalEvent: " + po.getInterfaceId());
                return;
            }
            // payload → Map，写 outbox
            Map<String, Object> payloadMap;
            try {
                payloadMap = objectMapper.readValue(po.getPayload(), new TypeReference<Map<String, Object>>() {});
            } catch (Exception parseEx) {
                markFailed(po.getId(), "payload 解析失败: " + parseEx.getMessage());
                return;
            }
            if (!payloadMap.containsKey("aggregateId")) {
                // 从 payload 抽个 ID 当 aggregateId
                String aggId = extractAggregateId(po.getInterfaceId(), payloadMap);
                if (aggId != null) payloadMap.put("aggregateId", aggId);
            }
            outboxService.write(mapAggregateType(po.getInterfaceId()),
                    (String) payloadMap.get("aggregateId"), mapped, payloadMap);

            // 标记成功
            WebhookEventPO upd = new WebhookEventPO();
            upd.setId(po.getId());
            upd.setStatus(1);
            upd.setMappedEvent(mapped);
            upd.setProcessedAt(LocalDateTime.now());
            upd.setUpdatedAt(LocalDateTime.now());
            mapper.updateById(upd);
            log.info("[Webhook] 处理成功: id={}, interfaceId={}, mappedEvent={}", po.getId(), po.getInterfaceId(), mapped);
        } catch (Exception e) {
            log.error("[Webhook] 处理失败: id={}, interfaceId={}, err={}", po.getId(), po.getInterfaceId(), e.getMessage(), e);
            scheduleRetry(po.getId(), e.getMessage());
        }
    }

    /**
     * 标记失败 + 安排重试
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW, rollbackFor = Exception.class)
    public void scheduleRetry(Long id, String errorMsg) {
        WebhookEventPO po = mapper.selectById(id);
        if (po == null) return;
        int nextCount = (po.getRetryCount() == null ? 0 : po.getRetryCount()) + 1;
        WebhookEventPO upd = new WebhookEventPO();
        upd.setId(id);
        upd.setStatus(nextCount >= 3 ? 3 : 2);  // 3=死信
        upd.setRetryCount(nextCount);
        upd.setErrorMsg(errorMsg != null && errorMsg.length() > 1900 ? errorMsg.substring(0, 1900) : errorMsg);
        // 指数退避: 1min, 4min, 16min
        long delaySec = (long) Math.pow(4, nextCount - 1) * 60;
        upd.setNextRetryAt(LocalDateTime.now().plusSeconds(delaySec));
        upd.setUpdatedAt(LocalDateTime.now());
        mapper.updateById(upd);
        if (nextCount >= 3) {
            log.error("[Webhook] 进入死信: id={}, retryCount={}, err={}", id, nextCount, errorMsg);
        } else {
            log.warn("[Webhook] 安排重试: id={}, retryCount={}, nextRetryAt+{}s", id, nextCount, delaySec);
        }
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW, rollbackFor = Exception.class)
    public void markFailed(Long id, String errorMsg) {
        WebhookEventPO upd = new WebhookEventPO();
        upd.setId(id);
        upd.setStatus(2);
        upd.setErrorMsg(errorMsg);
        upd.setUpdatedAt(LocalDateTime.now());
        mapper.updateById(upd);
    }

    /**
     * 从 MI 模型取接口声明。
     *
     * <p>外部接口 → 内部事件 / 聚合类型 / 聚合 ID 字段的映射全部由模型声明，
     * 代码里不维护 interfaceId 的硬编码 switch——否则 MI 模型改了代码不会跟着改，
     * 这正是「模型一套、代码一套」要消灭的情况。</p>
     */
    @SuppressWarnings("unchecked")
    private Map<String, Object> interfaceDecl(String interfaceId) {
        if (interfaceId == null) {
            return null;
        }
        Object decl = registry.getInterfaces().get(interfaceId);
        return decl instanceof Map ? (Map<String, Object>) decl : null;
    }

    /**
     * 把 interfaceId 映射到内部领域事件 ID（取自 MI 模型的 mappedInternalEvent）。
     */
    private String mapToInternalEvent(String interfaceId) {
        Map<String, Object> decl = interfaceDecl(interfaceId);
        if (decl == null) {
            return null;
        }
        Object evt = decl.get("mappedInternalEvent");
        return evt == null ? null : evt.toString();
    }

    /**
     * 把 interfaceId 映射到聚合类型（取自 MI 模型的 aggregateType）。
     */
    private String mapAggregateType(String interfaceId) {
        Map<String, Object> decl = interfaceDecl(interfaceId);
        if (decl != null) {
            Object t = decl.get("aggregateType");
            if (t != null && !t.toString().trim().isEmpty()) {
                return t.toString();
            }
        }
        return "Unknown";
    }

    /**
     * 从 payload 抽取聚合 ID（取自 MI 模型的 aggregateIdField，缺省用 id）。
     */
    private String extractAggregateId(String interfaceId, Map<String, Object> payload) {
        if (interfaceId == null || payload == null) {
            return null;
        }
        String[] candidates;
        Map<String, Object> decl = interfaceDecl(interfaceId);
        if (decl != null) {
            Object f = decl.get("aggregateIdField");
            if (f instanceof List && !((List<?>) f).isEmpty()) {
                List<?> l = (List<?>) f;
                candidates = new String[l.size()];
                for (int i = 0; i < l.size(); i++) {
                    candidates[i] = String.valueOf(l.get(i));
                }
            } else {
                candidates = new String[]{"id"};
            }
        } else {
            candidates = new String[]{"id"};
        }
        for (String k : candidates) {
            Object v = payload.get(k);
            if (v != null && !v.toString().isEmpty()) {
                return v.toString();
            }
        }
        return null;
    }
}
