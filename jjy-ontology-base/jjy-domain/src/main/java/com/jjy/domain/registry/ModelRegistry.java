package com.jjy.domain.registry;

import com.jjy.domain.behavior.Behavior;
import com.jjy.domain.event.DomainEvent;
import com.jjy.domain.flow.Flow;
import com.jjy.domain.rule.Rule;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.io.Serializable;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 运行时模型注册表（V1 方法论 §7 章节对齐）
 * <p>
 * 应用启动时由 ModelLoader 加载 11 个 yaml 填充；handler / dispatcher / AI 都从这里查。
 * <p>
 * 单例 bean（Spring @Component），线程安全（ConcurrentHashMap）。
 *
 * @author jjy-base
 */
@Slf4j
@Data
@NoArgsConstructor
public class ModelRegistry implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 聚合根（AGG-XXX-001） → 名称 */
    private final Map<String, String> aggregateNames = new ConcurrentHashMap<>();

    /**
     * 聚合根（AGG-XXX-001） → 原始声明。
     *
     * <p>保留 M1 里聚合根的完整定义（含 alias、编号规则 noRule、实体列表等），
     * 使「模型是唯一语义来源」在运行期真正可用——例如业务编号生成器需要从
     * {@code noRule} 读取表名/列名/前缀，而不必在代码里硬编码映射表。</p>
     */
    private final Map<String, Object> aggregateDefs = new ConcurrentHashMap<>();

    /**
     * 聚合根 ID（AGG-XXX-001） → 模型别名（Customer）。
     *
     * <p>M2 的 {@code ownerEntity} 与 M3 的 {@code sourceField} 都用别名引用聚合根，
     * 运行期需要把 ID 还原成别名去查状态。这个映射由 M1 模型声明，不在代码里硬编码。</p>
     */
    private final Map<String, String> aggregateAliases = new ConcurrentHashMap<>();

    /** 行为（Customer_SaveAsDraft） → Behavior 定义 */
    private final Map<String, Behavior> behaviors = new ConcurrentHashMap<>();

    /** 规则（RULE-CUS-001） → Rule 定义 */
    private final Map<String, Rule> rules = new ConcurrentHashMap<>();

    /** 事件（EVT-CUS-002） → DomainEvent 定义 */
    private final Map<String, DomainEvent> events = new ConcurrentHashMap<>();

    /** 流程（FLOW-CONTRACT-004） → Flow 定义 */
    private final Map<String, Flow> flows = new ConcurrentHashMap<>();

    /** 场景（SCN-XXX-001） */
    private final Map<String, Object> scenarios = new ConcurrentHashMap<>();

    /** 角色（ROLE-OPS） */
    private final Map<String, Object> roles = new ConcurrentHashMap<>();

    /** 权限（inbound:create） */
    private final Map<String, Object> permissions = new ConcurrentHashMap<>();

    /** 外部实体（EXT-WMS-001） */
    private final Map<String, Object> externalEntities = new ConcurrentHashMap<>();

    /** 数据字典项（dictionaryId+typeCode+code → item） */
    private final Map<String, Object> dataDictionaryItems = new ConcurrentHashMap<>();

    /** 查询报表（QR-XXX-001） */
    private final Map<String, Object> queryReports = new ConcurrentHashMap<>();

    /** 映射（MAP-AGG-XXX-001） */
    private final Map<String, Object> mappings = new ConcurrentHashMap<>();

    /**
     * MM 声明的默认列（{@code default_columns}）。
     *
     * <p>默认列是所有表统一的技术列（{@code created_by} / {@code created_at} /
     * {@code updated_by} / {@code updated_at} / {@code flag}），
     * 它们不出现在各表的 {@code columnMappings} 里，但 DDL 生成、逻辑删除、
     * 审计字段填充都要用。</p>
     *
     * <p>此前该声明<b>连装载都没有</b>——生成器把默认列写死在代码里，
     * MM 改了默认列不生效。本字段让 MM 成为默认列的唯一真源。</p>
     */
    private final List<String> defaultColumns = new java.util.concurrent.CopyOnWriteArrayList<>();

    /** 接口（INT-XXX-001） */
    private final Map<String, Object> interfaces = new ConcurrentHashMap<>();

    /**
     * 接口契约的响应字段清单（interfaceId → 字段名列表）。
     *
     * <p>MI 的 {@code responseFields} 声明了每个接口该返回哪些字段——
     * 这是一份契约，不只是文档。此前它<b>无人消费</b>：接口返回什么，
     * 全看 handler 碰巧返回了什么。</p>
     *
     * <p>本清单供契约校验与前端对接参考，让「接口该返回什么」有唯一答案。</p>
     */
    private final Map<String, List<String>> interfaceResponseFields = new ConcurrentHashMap<>();

    /** 屏幕 */
    private final Map<String, Object> screens = new ConcurrentHashMap<>();

    /** 菜单 */
    private List<Object> menus;

    // ============== 查询 API ==============

    public Behavior getBehavior(String id) {
        return behaviors.get(id);
    }

    public Rule getRule(String id) {
        return rules.get(id);
    }

    public DomainEvent getEvent(String id) {
        return events.get(id);
    }

    public Flow getFlow(String id) {
        return flows.get(id);
    }

    public boolean hasBehavior(String id) {
        return behaviors.containsKey(id);
    }

    public boolean hasEvent(String id) {
        return events.containsKey(id);
    }

    public boolean hasRule(String id) {
        return rules.containsKey(id);
    }

    /**
     * 加载完成时打印摘要
     */
    public void logSummary() {
        log.info("============================================================");
        log.info("[ModelRegistry] 模型注册完成：");
        log.info("  - 聚合根:        {}", aggregateNames.size());
        log.info("  - 行为:          {}", behaviors.size());
        log.info("  - 规则:          {}", rules.size());
        log.info("  - 事件:          {}", events.size());
        log.info("  - 流程:          {}", flows.size());
        log.info("  - 角色:          {}", roles.size());
        log.info("  - 权限:          {}", permissions.size());
        log.info("  - 外部实体:      {}", externalEntities.size());
        log.info("  - 数据字典项:    {}", dataDictionaryItems.size());
        log.info("  - 查询报表:      {}", queryReports.size());
        log.info("  - 屏幕:          {}", screens.size());
        log.info("============================================================");
    }

    /**
     * 校验所有跨模型引用（11 组自检的等价物）
     *
     * @return 校验结果（true=通过）
     */
    public boolean validateReferences() {
        boolean ok = true;
        // 1. M2.appliedRules 必须在 M3 中
        for (Behavior b : behaviors.values()) {
            if (b.getAppliedRules() != null) {
                for (String ruleId : b.getAppliedRules()) {
                    if (!rules.containsKey(ruleId)) {
                        log.error("[validate] Behavior {} 引用不存在的 Rule {}", b.getId(), ruleId);
                        ok = false;
                    }
                }
            }
            // 2. M2.producedEvents 必须在 ME 中
            if (b.getProducedEvents() != null) {
                for (String eventId : b.getProducedEvents()) {
                    if (!events.containsKey(eventId)) {
                        log.error("[validate] Behavior {} 引用不存在的 Event {}", b.getId(), eventId);
                        ok = false;
                    }
                }
            }
            // 3. M2.subscribedEvents 必须在 ME 中
            if (b.getSubscribedEvents() != null) {
                for (String eventId : b.getSubscribedEvents()) {
                    if (!events.containsKey(eventId)) {
                        log.error("[validate] Behavior {} 引用不存在的 Event {}", b.getId(), eventId);
                        ok = false;
                    }
                }
            }
        }
        // 4. M3 EVENT_DRIVEN 规则 subscribedEvent 必须在 ME 中
        for (Rule r : rules.values()) {
            if ("EVENT_DRIVEN".equals(r.getRuleType()) && r.getSubscribedEvent() != null) {
                if (!events.containsKey(r.getSubscribedEvent())) {
                    log.error("[validate] Rule {} 引用不存在的 Event {}", r.getId(), r.getSubscribedEvent());
                    ok = false;
                }
            }
        }
        // 5. M2.requiredPermissions 必须在 M5 权限中声明
        //    （方法论 §3.2.1：requiredPermissions 引用 M5 的 permissionId）
        //    漏检会导致：模型里写了一个不存在的权限码，运行期因通配权限而"看起来正常"，
        //    直到业务方按 M5 实际授予权限时才暴露为 403。
        for (Behavior b : behaviors.values()) {
            if (b.getRequiredPermissions() == null) {
                continue;
            }
            for (String permId : b.getRequiredPermissions()) {
                if (!permissions.containsKey(permId)) {
                    log.error("[validate] Behavior {} 引用不存在的 Permission {}", b.getId(), permId);
                    ok = false;
                }
            }
        }
        // 6. M5 角色的 permissions 必须在 M5 权限中声明
        for (Object roleObj : roles.values()) {
            if (!(roleObj instanceof Map)) {
                continue;
            }
            Object permsObj = ((Map<?, ?>) roleObj).get("permissions");
            if (!(permsObj instanceof List)) {
                continue;
            }
            String roleId = String.valueOf(((Map<?, ?>) roleObj).get("roleId"));
            for (Object p : (List<?>) permsObj) {
                String permId = String.valueOf(p);
                if (!permissions.containsKey(permId)) {
                    log.error("[validate] Role {} 授予了不存在的 Permission {}", roleId, permId);
                    ok = false;
                }
            }
        }
        // 7. M6 流程引用的行为/角色/聚合根必须存在
        //    这类漂移在运行期是「静音失败」——流程装载成功，直到真的跑到那个节点才崩。
        for (Flow flow : flows.values()) {
            String flowId = flow.getId();
            // trigger.behaviorRef
            Map<String, Object> trigger = flow.getTrigger();
            if (trigger != null) {
                String br = str(trigger.get("behaviorRef"));
                if (br != null && !behaviors.containsKey(br)) {
                    log.error("[validate] Flow {} trigger 引用不存在的 Behavior {}", flowId, br);
                    ok = false;
                }
            }
            // roleRefs[]
            if (flow.getRoleRefs() != null) {
                for (String roleId : flow.getRoleRefs()) {
                    if (roleId != null && !roles.containsKey(roleId)) {
                        log.error("[validate] Flow {} 引用不存在的 Role {}", flowId, roleId);
                        ok = false;
                    }
                }
            }
            // businessObjectRefs[]
            if (flow.getBusinessObjectRefs() != null) {
                for (String aggId : flow.getBusinessObjectRefs()) {
                    if (aggId != null && !aggregateNames.containsKey(aggId)) {
                        log.error("[validate] Flow {} 引用不存在的 Aggregate {}", flowId, aggId);
                        ok = false;
                    }
                }
            }
            // activities[].behaviorRef / roleRef
            if (flow.getActivities() != null) {
                for (Flow.Activity act : flow.getActivities()) {
                    String actId = act.getActivityId();
                    String br = act.getBehaviorRef();
                    if (br != null && !behaviors.containsKey(br)) {
                        log.error("[validate] Flow {}/{} 引用不存在的 Behavior {}", flowId, actId, br);
                        ok = false;
                    }
                    String rr = act.getRoleRef();
                    if (rr != null && !roles.containsKey(rr)) {
                        log.error("[validate] Flow {}/{} 引用不存在的 Role {}", flowId, actId, rr);
                        ok = false;
                    }
                }
            }
        }
        // 8. ME 事件引用的聚合根必须存在
        for (DomainEvent evt : events.values()) {
            String ar = evt.getSourceAggregate();
            if (ar != null && !aggregateNames.containsKey(ar)) {
                log.error("[validate] Event {} 引用不存在的 Aggregate {}", evt.getEventId(), ar);
                ok = false;
            }
        }
        // 9. MI 接口引用的行为/事件必须存在
        for (Object itfObj : interfaces.values()) {
            if (!(itfObj instanceof Map)) {
                continue;
            }
            Map<?, ?> itf = (Map<?, ?>) itfObj;
            String itfId = String.valueOf(itf.get("id"));
            String br = str(itf.get("behaviorRef"));
            if (br != null && !behaviors.containsKey(br)) {
                log.error("[validate] Interface {} 引用不存在的 Behavior {}", itfId, br);
                ok = false;
            }
            String ev = str(itf.get("mappedInternalEvent"));
            if (ev != null && !events.containsKey(ev)) {
                log.error("[validate] Interface {} 映射到不存在的 Event {}", itfId, ev);
                ok = false;
            }
        }
        // 10. ME 事件订阅方 ↔ M2 行为订阅，双向闭合
        //
        //     这是一处「双份声明同一事实」的地方：ME 从事件侧声明「谁订阅我」，
        //     M2 从行为侧声明「我订阅什么」。两边必须一致，否则模型内部自相矛盾：
        //       · ME 说有人订阅、M2 没说 → 事件产出后无人消费（事件孤儿）；
        //       · M2 说订阅了、ME 没说 → 订阅关系只在代码里存在，模型查不出来。
        for (DomainEvent evt : events.values()) {
            String evtId = evt.getEventId();
            if (evtId == null) {
                continue;
            }
            // 10a. ME 声明的每个订阅方 → 行为必须存在，且其 subscribedEvents 必须含本事件
            if (evt.getSubscribers() != null) {
                for (Map<String, String> sub : evt.getSubscribers()) {
                    if (sub == null) {
                        continue;
                    }
                    String handlerRef = sub.get("handlerRef");
                    if (handlerRef == null || handlerRef.trim().isEmpty()) {
                        log.error("[validate] Event {} 的 subscriber 缺少 handlerRef", evtId);
                        ok = false;
                        continue;
                    }
                    Behavior b = behaviors.get(handlerRef);
                    if (b == null) {
                        log.error("[validate] Event {} 的 subscriber.handlerRef 引用不存在的 Behavior {}",
                                evtId, handlerRef);
                        ok = false;
                        continue;
                    }
                    if (b.getSubscribedEvents() == null || !b.getSubscribedEvents().contains(evtId)) {
                        log.error("[validate] Event {} 声明订阅方 {}，但该行为的 subscribedEvents 未包含本事件"
                                + "（ME 与 M2 声明不一致）", evtId, handlerRef);
                        ok = false;
                    }
                }
            }
        }
        // 10b. M2 声明订阅事件的 EVENT 行为 → ME 必须也声明了它
        for (Behavior b : behaviors.values()) {
            if (b.getSubscribedEvents() == null || b.getSubscribedEvents().isEmpty()) {
                continue;
            }
            for (String evtId : b.getSubscribedEvents()) {
                DomainEvent evt = events.get(evtId);
                if (evt == null) {
                    continue;   // 已由第 3 项校验
                }
                boolean declared = false;
                if (evt.getSubscribers() != null) {
                    for (Map<String, String> sub : evt.getSubscribers()) {
                        if (sub != null && b.getId().equals(sub.get("handlerRef"))) {
                            declared = true;
                            break;
                        }
                    }
                }
                if (!declared) {
                    log.error("[validate] Behavior {} 声明订阅事件 {}，但 ME 事件的 subscribers 未声明它"
                            + "（M2 与 ME 声明不一致）", b.getId(), evtId);
                    ok = false;
                }
            }
        }
        log.info("[validate] 跨模型引用校验: {}", ok ? "PASS" : "FAIL");
        return ok;
    }

    /** 安全取值：空串视为 null */
    private static String str(Object v) {
        if (v == null) {
            return null;
        }
        String s = v.toString().trim();
        return s.isEmpty() ? null : s;
    }
}
