package com.jjy.application.lifecycle;

import com.jjy.domain.behavior.Behavior;
import com.jjy.domain.registry.ModelRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 生命周期守卫：校验状态变更是否落在 M1 声明的合法状态集合与迁移表内。
 *
 * <p><b>解决的问题</b>：M1 的 {@code lifecycle} 声明了聚合根的合法状态，
 * 但此前没有任何代码消费它——状态可以被写成任意字符串，模型声明形同虚设。
 * 本守卫让 M1 的 lifecycle 真正生效：状态变更前先问模型，
 * 目标状态不在声明集合内 → 直接拒绝。</p>
 *
 * <p><b>两级校验</b>（都由模型驱动，代码里没有状态常量）：</p>
 * <ol>
 *   <li><b>取值域校验</b>：目标状态必须在 M1 的 {@code lifecycle} 集合内；</li>
 *   <li><b>迁移校验</b>：若 M1 声明了 {@code transitions} 迁移表，
 *       则「原状态 → 目标状态」必须是表里的一行。
 *       没有这一层，DRAFT → DRAFT（重复提交）这种两端都合法的无效迁移会被放过。</li>
 * </ol>
 *
 * <p><b>目标状态从 M2 来</b>：调用方不传目标状态，而是传 M2 行为 ID——
 * 目标状态由 M2 的 {@code postconditions} 声明。这样「行为完成后状态变成什么」
 * 只有一个真源（M2），代码里不再出现硬编码的状态字符串。</p>
 *
 * <p><b>与 M3 规则的分工</b>：M3 规则判定「业务条件是否满足」
 * （如价格非负）；M1 lifecycle 判定「状态值与流转是否合法」。
 * 前者是业务语义，后者是取值域约束——两者不可互相替代。</p>
 *
 * @author jjy-base
 */
@Slf4j
@Component
public class LifecycleGuard {

    @Autowired
    private ModelRegistry registry;

    /**
     * 按 M2 行为声明校验状态迁移。
     *
     * <p>目标状态来自该行为 M2 声明的 {@code postconditions}
     * （形如 {@code - field: status, setValue: ON_SHELF}）。</p>
     *
     * @param alias      聚合根别名（如 Product）
     * @param behaviorId M2 行为 ID（如 Product_Submit）
     * @param oldStatus  原状态（可为 null，表示不限定来源状态）
     * @throws IllegalStateException 状态值非法或迁移不被模型允许
     */
    public void checkTransitionOf(String alias, String behaviorId, String oldStatus) {
        String newStatus = targetStatusOf(behaviorId);
        if (newStatus == null) {
            throw new IllegalStateException(
                    "行为 " + behaviorId + " 未在 M2 的 postconditions 中声明目标状态，无法执行状态迁移");
        }
        checkTransition(alias, oldStatus, newStatus);
    }

    /**
     * 校验状态变更合法（目标状态由调用方给出）。
     *
     * @param alias      聚合根别名（如 Product）
     * @param newStatus  目标状态
     * @param oldStatus  原状态（可为 null）
     * @throws IllegalStateException 状态不在 M1 声明的合法集合内，或迁移不被允许
     */
    public void checkTransition(String alias, String oldStatus, String newStatus) {
        Set<String> allowed = allowedStates(alias);
        if (allowed.isEmpty()) {
            // 模型未声明 lifecycle → 不做约束（该聚合根没有状态机）
            return;
        }
        if (newStatus == null || newStatus.trim().isEmpty()) {
            throw new IllegalStateException("状态不能为空（" + alias + " 合法状态: " + allowed + "）");
        }
        String ns = newStatus.trim();
        if (!allowed.contains(ns)) {
            throw new IllegalStateException(
                    "非法状态 '" + ns + "'：" + alias + " 的合法状态为 " + allowed
                            + "（来自 M1 lifecycle 声明）");
        }
        String os = (oldStatus == null || oldStatus.trim().isEmpty()) ? null : oldStatus.trim();
        if (os != null && !allowed.contains(os)) {
            // 存量数据里有非法状态：只告警不拦截，避免历史数据卡死业务
            log.warn("[Lifecycle] {} 现存状态 '{}' 不在 M1 声明的合法集合 {}，仅告警",
                    alias, os, allowed);
            return;
        }
        // 迁移校验：M1 声明了迁移表时，「从→到」必须在表内。
        // 新建对象（无原状态）只校验取值域——它不是「从某状态迁移而来」。
        if (os == null) {
            return;
        }
        Set<String> reachable = reachableStates(alias, os);
        // reachable == null  → M1 未声明 transitions → 不限制迁移
        // reachable 非 null   → 迁移表存在，必须命中其中一行。
        //   注意「该状态无出边」时 reachable 是空集，此时<b>任何</b>迁移都非法
        //   （终态不能再迁移）——不能因为集合为空就放行，
        //   否则「重复下架」这类无效迁移会被静默放过（实测漏过）。
        if (reachable != null && !reachable.contains(ns)) {
            throw new IllegalStateException(
                    "非法状态迁移 '" + os + "' → '" + ns + "'：" + alias + " 允许的迁移为 "
                            + transitionText(alias) + "（来自 M1 transitions 声明）");
        }
    }

    /**
     * 取 M2 行为声明的目标状态。
     *
     * <p>从 {@code postconditions} 里找 {@code field: status} 的那条，取其 {@code setValue}。
     * 这让「行为完成后状态变成什么」由 M2 唯一决定。</p>
     *
     * @param behaviorId M2 行为 ID
     * @return 目标状态；未声明时返回 null
     */
    @SuppressWarnings("unchecked")
    public String targetStatusOf(String behaviorId) {
        if (behaviorId == null) {
            return null;
        }
        Behavior b = registry.getBehavior(behaviorId);
        if (b == null || b.getPostconditions() == null) {
            return null;
        }
        for (Map<String, String> pc : b.getPostconditions()) {
            if (pc == null) {
                continue;
            }
            if ("status".equals(pc.get("field"))) {
                String v = pc.get("setValue");
                if (v != null && !v.trim().isEmpty()) {
                    return v.trim();
                }
            }
        }
        return null;
    }

    /**
     * 取聚合根在 M1 声明的合法状态集合。
     *
     * @param alias 聚合根别名
     * @return 合法状态集合；未声明时返回空集
     */
    @SuppressWarnings("unchecked")
    public Set<String> allowedStates(String alias) {
        Set<String> out = new LinkedHashSet<>();
        Object def = aggregateDef(alias);
        if (def == null) {
            return out;
        }
        Object lc = ((Map<String, Object>) def).get("lifecycle");
        if (lc instanceof List) {
            for (Object s : (List<Object>) lc) {
                if (s != null) {
                    out.add(s.toString().trim());
                }
            }
        }
        return out;
    }

    /**
     * 取从指定状态出发允许到达的状态集合。
     *
     * <p>M1 未声明 {@code transitions} 时返回 {@code null}（表示不限制迁移）。</p>
     *
     * @param alias    聚合根别名
     * @param fromState 原状态；null 表示不限定来源
     * @return 允许的目标状态集合；未声明迁移表时返回 null
     */
    @SuppressWarnings("unchecked")
    public Set<String> reachableStates(String alias, String fromState) {
        Object def = aggregateDef(alias);
        if (def == null) {
            return null;
        }
        Object ts = ((Map<String, Object>) def).get("transitions");
        if (!(ts instanceof List)) {
            return null;
        }
        Set<String> out = new LinkedHashSet<>();
        for (Object tObj : (List<Object>) ts) {
            if (!(tObj instanceof Map)) {
                continue;
            }
            Map<String, Object> t = (Map<String, Object>) tObj;
            String from = t.get("from") == null ? null : t.get("from").toString().trim();
            String to = t.get("to") == null ? null : t.get("to").toString().trim();
            if (to == null || to.isEmpty()) {
                continue;
            }
            // fromState 为 null 时，收集所有来源的目标状态（作为「任意来源」的可达集）
            if (fromState == null || fromState.trim().isEmpty() || fromState.trim().equals(from)) {
                out.add(to);
            }
        }
        return out;
    }

    /**
     * 可读的状态迁移描述（用于异常信息）。
     */
    @SuppressWarnings("unchecked")
    private String transitionText(String alias) {
        Object def = aggregateDef(alias);
        if (def == null) {
            return "（未声明）";
        }
        Object ts = ((Map<String, Object>) def).get("transitions");
        if (!(ts instanceof List)) {
            return "（未声明）";
        }
        List<String> parts = new ArrayList<>();
        for (Object tObj : (List<Object>) ts) {
            if (!(tObj instanceof Map)) {
                continue;
            }
            Map<String, Object> t = (Map<String, Object>) tObj;
            String from = t.get("from") == null ? "?" : t.get("from").toString();
            String to = t.get("to") == null ? "?" : t.get("to").toString();
            String via = t.get("via") == null ? null : t.get("via").toString();
            parts.add(from + "→" + to + (via == null ? "" : "(" + via + ")"));
        }
        return parts.isEmpty() ? "（未声明）" : parts.toString();
    }

    /**
     * 按别名取 M1 的聚合根声明。
     */
    @SuppressWarnings("unchecked")
    private Object aggregateDef(String alias) {
        if (alias == null) {
            return null;
        }
        for (Object defObj : registry.getAggregateDefs().values()) {
            if (!(defObj instanceof Map)) {
                continue;
            }
            Map<String, Object> def = (Map<String, Object>) defObj;
            if (alias.equals(def.get("alias"))) {
                return def;
            }
        }
        return null;
    }
}
