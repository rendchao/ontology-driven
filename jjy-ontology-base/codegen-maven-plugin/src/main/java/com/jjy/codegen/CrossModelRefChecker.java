package com.jjy.codegen;

import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * 跨模型引用闭合检查：把 M4 / M6 / M7 / ME / MI 里对其他模型的引用，
 * 全部与「被引用方」的实际声明对账。
 *
 * <p><b>为什么需要它</b>：M1/M2/M3/M5/MU 有专门的守门员，但 M4/M6/M7/ME/MI
 * 此前既无守门员、也未被运行期 {@code validateReferences()} 覆盖。
 * 实测后果：把 M6 的 {@code behaviorRef} 改成不存在的行为、M6 的 {@code roleRefs}
 * 改成不存在的角色、ME 的 {@code aggregateRef} 改成不存在的聚合根——
 * 守门员报 PASS、运行期也报 PASS，三个坏引用全部漏过，
 * 直到流程真跑到那个节点才崩。这就是典型的<b>静音失败</b>。</p>
 *
 * <p>覆盖的引用关系：</p>
 * <table border="1">
 *   <caption>跨模型引用矩阵</caption>
 *   <tr><th>引用方</th><th>字段</th><th>被引用方</th></tr>
 *   <tr><td>M4 场景</td><td>steps[].ruleRef</td><td>M3 规则</td></tr>
 *   <tr><td>M6 流程</td><td>trigger.behaviorRef</td><td>M2 行为</td></tr>
 *   <tr><td>M6 流程</td><td>activities[].behaviorRef</td><td>M2 行为</td></tr>
 *   <tr><td>M6 流程</td><td>roleRefs[] / activities[].roleRef</td><td>M5 角色</td></tr>
 *   <tr><td>M6 流程</td><td>businessObjectRefs[]</td><td>M1 聚合根</td></tr>
 *   <tr><td>M7 报表</td><td>behaviorRef</td><td>M2 行为</td></tr>
 *   <tr><td>M7 报表</td><td>sourceObjects[].objectRef</td><td>M1 聚合根</td></tr>
 *   <tr><td>ME 事件</td><td>aggregateRef</td><td>M1 聚合根</td></tr>
 *   <tr><td>MI 接口</td><td>behaviorRef</td><td>M2 行为</td></tr>
 *   <tr><td>MI 接口</td><td>mappedInternalEvent</td><td>ME 事件</td></tr>
 *   <tr><td>MI 接口</td><td>aggregateType</td><td>M1 聚合根别名</td></tr>
 *   <tr><td>MI 接口</td><td>permissionCode</td><td>M5 权限</td></tr>
 * </table>
 *
 * <p>用法：</p>
 * <pre>
 *   java com.jjy.codegen.CrossModelRefChecker &lt;models-dir&gt;
 * </pre>
 */
public final class CrossModelRefChecker {

    private CrossModelRefChecker() {
    }

    /** 一条漂移记录 */
    static class Drift {
        final String from;
        final String field;
        final String ref;
        final String target;

        Drift(String from, String field, String ref, String target) {
            this.from = from;
            this.field = field;
            this.ref = ref;
            this.target = target;
        }

        @Override
        public String toString() {
            return String.format("  %-26s %-28s → %-28s (应存在于 %s)",
                    from, field + "=" + ref, "缺失", target);
        }
    }

    public static void main(String[] args) throws Exception {
        if (args.length < 1) {
            System.err.println("Usage: CrossModelRefChecker <models-dir>");
            System.exit(2);
        }
        Path dir = Paths.get(args[0]);

        // ── 加载被引用方 ──
        Set<String> aggregates = readIds(dir.resolve("m1-object-model.yaml"), "aggregates");
        Set<String> aggregateAliases = readAliases(dir.resolve("m1-object-model.yaml"));
        Set<String> behaviors = readIds(dir.resolve("m2-behavior-model.yaml"), "behaviors");
        Set<String> rules = readIds(dir.resolve("m3-rule-model.yaml"), "rules");
        Set<String> roles = readRoleIds(dir.resolve("m5-actor-model.yaml"));
        Set<String> permissions = readPermissionIds(dir.resolve("m5-actor-model.yaml"));
        Set<String> events = readIds(dir.resolve("me-event-model.yaml"), "events");

        List<Drift> drifts = new ArrayList<>();

        // ── M1 聚合根生命周期 → M2 行为 / 合法状态 ──
        //   transitions[].via  引用执行该迁移的 M2 行为
        //   transitions[].from/to  必须落在该聚合根的 lifecycle 集合内
        Path m1 = dir.resolve("m1-object-model.yaml");
        for (Map<String, Object> agg : listOf(load(m1), "aggregates")) {
            String aggId = str(agg.get("id"));
            String alias = str(agg.get("alias"));
            Set<String> states = new TreeSet<>();
            Object lc = agg.get("lifecycle");
            if (lc instanceof List) {
                for (Object s : (List<?>) lc) {
                    if (s != null) states.add(s.toString().trim());
                }
            }
            Object ts = agg.get("transitions");
            if (ts instanceof List) {
                for (Object tObj : (List<?>) ts) {
                    if (!(tObj instanceof Map)) continue;
                    Map<String, Object> t = (Map<String, Object>) tObj;
                    // via → M2 行为必须存在
                    String via = str(t.get("via"));
                    if (via != null && !behaviors.contains(via)) {
                        drifts.add(new Drift("M1 聚合根 " + aggId, "transitions[].via", via, "M2 行为"));
                    }
                    // from / to 必须在 lifecycle 集合内
                    for (String side : new String[]{"from", "to"}) {
                        String v = str(t.get(side));
                        if (v != null && !states.isEmpty() && !states.contains(v)) {
                            drifts.add(new Drift("M1 聚合根 " + aggId,
                                    "transitions[]." + side, v, "M1 lifecycle 合法状态"));
                        }
                    }
                }
            }
        }

        // ── M4 场景 → M3 规则 / M2 行为 ──
        Path m4 = dir.resolve("m4-scenario-model.yaml");
        Object m4Root = load(m4);
        for (Map<String, Object> scn : listOf(m4Root, "scenarios")) {
            String scnId = str(scn.get("id"));
            // trigger.behaviorRef
            Object trigger = scn.get("trigger");
            if (trigger instanceof Map) {
                String br = str(((Map<?, ?>) trigger).get("behaviorRef"));
                if (br != null && !behaviors.contains(br)) {
                    drifts.add(new Drift("M4 场景 " + scnId, "trigger.behaviorRef", br, "M2 行为"));
                }
            }
            // steps[]
            for (Map<String, Object> st : listOf(scn, "steps")) {
                String stepId = str(st.get("stepId"));
                String rr = str(st.get("ruleRef"));
                if (rr != null && !rules.contains(rr)) {
                    drifts.add(new Drift("M4 场景 " + scnId + "/" + stepId,
                            "steps[].ruleRef", rr, "M3 规则"));
                }
                String br = str(st.get("behaviorRef"));
                if (br != null && !behaviors.contains(br)) {
                    drifts.add(new Drift("M4 场景 " + scnId + "/" + stepId,
                            "steps[].behaviorRef", br, "M2 行为"));
                }
            }
        }

        // ── M6 流程 → M2 行为 / M5 角色 / M1 聚合根 ──
        Path m6 = dir.resolve("m6-flow-model.yaml");
        Object m6Root = load(m6);
        for (Map<String, Object> flow : listOf(m6Root, "flows")) {
            String flowId = str(flow.get("id"));
            // trigger.behaviorRef
            Object trigger = flow.get("trigger");
            if (trigger instanceof Map) {
                String br = str(((Map<?, ?>) trigger).get("behaviorRef"));
                if (br != null && !behaviors.contains(br)) {
                    drifts.add(new Drift("M6 流程 " + flowId, "trigger.behaviorRef", br, "M2 行为"));
                }
            }
            // businessObjectRefs[]（字符串数组）
            for (String ref : stringList(flow, "businessObjectRefs")) {
                if (!aggregates.contains(ref)) {
                    drifts.add(new Drift("M6 流程 " + flowId, "businessObjectRefs[]", ref, "M1 聚合根"));
                }
            }
            // roleRefs[]（字符串数组）
            for (String ref : stringList(flow, "roleRefs")) {
                if (!roles.contains(ref)) {
                    drifts.add(new Drift("M6 流程 " + flowId, "roleRefs[]", ref, "M5 角色"));
                }
            }
            // activities[]
            for (Map<String, Object> act : listOf(flow, "activities")) {
                String actId = str(act.get("activityId"));
                String br = str(act.get("behaviorRef"));
                if (br != null && !behaviors.contains(br)) {
                    drifts.add(new Drift("M6 流程 " + flowId + "/" + actId,
                            "activities[].behaviorRef", br, "M2 行为"));
                }
                String rr = str(act.get("roleRef"));
                if (rr != null && !roles.contains(rr)) {
                    drifts.add(new Drift("M6 流程 " + flowId + "/" + actId,
                            "activities[].roleRef", rr, "M5 角色"));
                }
            }
        }

        // ── M7 报表 → M2 行为 / M1 聚合根 ──
        Path m7 = dir.resolve("m7-report-model.yaml");
        for (Map<String, Object> qr : listOf(load(m7), "query_reports")) {
            String qrId = str(qr.get("id"));
            String br = str(qr.get("behaviorRef"));
            if (br != null && !behaviors.contains(br)) {
                drifts.add(new Drift("M7 报表 " + qrId, "behaviorRef", br, "M2 行为"));
            }
            for (Map<String, Object> so : listOf(qr, "sourceObjects")) {
                String ref = str(so.get("objectRef"));
                if (ref != null && !aggregates.contains(ref)) {
                    drifts.add(new Drift("M7 报表 " + qrId, "sourceObjects[].objectRef", ref, "M1 聚合根"));
                }
            }
        }

        // ── ME 事件 → M1 聚合根 / M2 行为（subscribers）──
        Path me = dir.resolve("me-event-model.yaml");
        for (Map<String, Object> evt : listOf(load(me), "events")) {
            String evtId = str(evt.get("id"));
            String ar = str(evt.get("aggregateRef"));
            if (ar != null && !aggregates.contains(ar)) {
                drifts.add(new Drift("ME 事件 " + evtId, "aggregateRef", ar, "M1 聚合根"));
            }
            // subscribers[].handlerRef → M2 行为必须存在
            //
            // 这条此前无人校验：ME 声明的订阅方指向一个不存在的行为时，
            // 模型装载照常成功、事件照常产出，只是永远没人消费——
            // 典型的静音失败，直到业务方发现「事件发出去了但下游没反应」。
            for (Map<String, Object> sub : listOf(evt, "subscribers")) {
                String hr = str(sub.get("handlerRef"));
                if (hr != null && !behaviors.contains(hr)) {
                    drifts.add(new Drift("ME 事件 " + evtId, "subscribers[].handlerRef", hr, "M2 行为"));
                }
            }
        }

        // ── MI 接口 → M2 行为 / ME 事件 / M1 别名 / M5 权限 ──
        Path mi = dir.resolve("mi-interface-model.yaml");
        for (Map<String, Object> itf : listOf(load(mi), "interfaces")) {
            String itfId = str(itf.get("interfaceId"));
            String br = str(itf.get("behaviorRef"));
            if (br != null && !behaviors.contains(br)) {
                drifts.add(new Drift("MI 接口 " + itfId, "behaviorRef", br, "M2 行为"));
            }
            String ev = str(itf.get("mappedInternalEvent"));
            if (ev != null && !events.contains(ev)) {
                drifts.add(new Drift("MI 接口 " + itfId, "mappedInternalEvent", ev, "ME 事件"));
            }
            String ag = str(itf.get("aggregateType"));
            if (ag != null && !aggregateAliases.contains(ag)) {
                drifts.add(new Drift("MI 接口 " + itfId, "aggregateType", ag, "M1 聚合根别名"));
            }
            String pc = str(itf.get("permissionCode"));
            if (pc != null && !permissions.contains(pc)) {
                drifts.add(new Drift("MI 接口 " + itfId, "permissionCode", pc, "M5 权限 permissionId"));
            }
        }

        // ── 输出 ──
        System.out.println("=== CrossModelRef 检查（M4/M6/M7/ME/MI 跨模型引用闭合）===");
        System.out.println("被引用方规模：");
        System.out.println("  M1 聚合根 / 别名   : " + aggregates.size() + " / " + aggregateAliases.size());
        System.out.println("  M2 行为            : " + behaviors.size());
        System.out.println("  M3 规则            : " + rules.size());
        System.out.println("  M5 角色 / 权限      : " + roles.size() + " / " + permissions.size());
        System.out.println("  ME 事件            : " + events.size());
        System.out.println("  检出漂移            : " + drifts.size());

        if (!drifts.isEmpty()) {
            System.out.println("\n--- [drift] 跨模型引用指向不存在的声明 ---");
            drifts.forEach(d -> System.out.println(d.toString()));
            System.out.println("\n  修复：把被引用的 ID 补进目标模型，或修正引用方里的拼写。");
            System.out.println("  注意：这类漂移在运行期是<b>静音失败</b>——模型装载照常成功，");
            System.out.println("        直到流程/事件真的走到那个节点才会抛异常。");
        }
        System.exit(drifts.isEmpty() ? 0 : 1);
    }

    // ══════════════════════════════════════════════════════════
    //  解析工具
    // ══════════════════════════════════════════════════════════

    static Object load(Path yaml) throws IOException {
        if (!Files.isRegularFile(yaml)) {
            return null;
        }
        try (InputStream in = Files.newInputStream(yaml)) {
            return new Yaml().load(in);
        }
    }

    /** 读取某顶层数组下所有对象的 id 字段 */
    static Set<String> readIds(Path yaml, String key) throws IOException {
        Set<String> out = new TreeSet<>();
        for (Map<String, Object> m : listOf(load(yaml), key)) {
            Object id = m.get("id");
            if (id == null) {
                id = m.get("scenarioId");
            }
            if (id == null) {
                id = m.get("flowId");
            }
            if (id == null) {
                id = m.get("eventId");
            }
            if (id != null) {
                out.add(id.toString());
            }
        }
        return out;
    }

    /** M1 aggregates[].alias */
    static Set<String> readAliases(Path yaml) throws IOException {
        Set<String> out = new TreeSet<>();
        for (Map<String, Object> m : listOf(load(yaml), "aggregates")) {
            Object alias = m.get("alias");
            if (alias != null) {
                out.add(alias.toString());
            }
        }
        return out;
    }

    /** M5 roles[].roleId */
    static Set<String> readRoleIds(Path yaml) throws IOException {
        Set<String> out = new TreeSet<>();
        for (Map<String, Object> m : listOf(load(yaml), "roles")) {
            Object id = m.get("roleId");
            if (id == null) {
                id = m.get("id");
            }
            if (id != null) {
                out.add(id.toString());
            }
        }
        return out;
    }

    /** M5 permissions[].permissionId */
    static Set<String> readPermissionIds(Path yaml) throws IOException {
        Set<String> out = new TreeSet<>();
        for (Map<String, Object> m : listOf(load(yaml), "permissions")) {
            Object id = m.get("permissionId");
            if (id == null) {
                id = m.get("id");
            }
            if (id != null) {
                out.add(id.toString());
            }
        }
        return out;
    }

    /** 取某顶层数组下的所有 Map 元素 */
    @SuppressWarnings("unchecked")
    static List<Map<String, Object>> listOf(Object root, String key) {
        List<Map<String, Object>> out = new ArrayList<>();
        if (!(root instanceof Map)) {
            return out;
        }
        Object arr = ((Map<?, ?>) root).get(key);
        if (!(arr instanceof List)) {
            return out;
        }
        for (Object o : (List<?>) arr) {
            if (o instanceof Map) {
                out.add((Map<String, Object>) o);
            }
        }
        return out;
    }

    /**
     * 取某字段下的字符串数组。
     *
     * <p><b>注意</b>：像 {@code roleRefs: [ROLE-A, ROLE-B]} 这种「字符串数组」
     * 不能用 {@link #listOf} 取——它只收 Map 元素，会把字符串全过滤掉，
     * 导致引用校验静默失效（曾漏检 M6 的 roleRefs 漂移）。</p>
     */
    static List<String> stringList(Map<String, Object> parent, String key) {
        List<String> out = new ArrayList<>();
        Object arr = parent.get(key);
        if (!(arr instanceof List)) {
            return out;
        }
        for (Object o : (List<?>) arr) {
            String s = str(o);
            if (s != null) {
                out.add(s);
            }
        }
        return out;
    }

    static String str(Object v) {
        if (v == null) {
            return null;
        }
        String s = v.toString().trim();
        return s.isEmpty() ? null : s;
    }
}
