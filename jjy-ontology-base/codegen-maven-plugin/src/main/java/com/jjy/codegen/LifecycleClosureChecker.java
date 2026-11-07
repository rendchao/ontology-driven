package com.jjy.codegen;

import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * M1 生命周期 ↔ M2 状态迁移 闭合检查。
 *
 * <p><b>补的盲区</b>：M1 声明 {@code lifecycle}（合法状态集合）与
 * {@code transitions}（状态迁移表），M2 声明 {@code postconditions}
 * （行为完成后状态变成什么）。三者描述的是同一件事——状态机，
 * 却分散在两个模型里、各自独立校验，于是可能出现：</p>
 * <ul>
 *   <li>M2 的 {@code setValue} 不在 M1 的 {@code lifecycle} 内
 *       → 行为会写入一个「非法状态」，且没有任何检查拦它；</li>
 *   <li>M2 的 {@code setValue} 与 M1 迁移表里的 {@code to} 不一致
 *       → 迁移表形同虚设（声明能到 B，行为却写到 C）。</li>
 * </ul>
 *
 * <p>两者在运行期由 {@code LifecycleGuard} 兜底（会拒绝非法状态），
 * 但「模型内部自相矛盾」属于构建期就该发现的问题——
 * 等到运行期才发现，意味着已经有人在错误的模型上写了不少代码。</p>
 *
 * <p>用法：</p>
 * <pre>
 *   java com.jjy.codegen.LifecycleClosureChecker &lt;models-dir&gt;
 * </pre>
 */
public final class LifecycleClosureChecker {

    private LifecycleClosureChecker() {
    }

    /** 一处不一致 */
    static class Drift {
        final String from;
        final String field;
        final String detail;

        Drift(String from, String field, String detail) {
            this.from = from;
            this.field = field;
            this.detail = detail;
        }

        @Override
        public String toString() {
            return String.format("  %-24s %-26s %s", from, field, detail);
        }
    }

    public static void main(String[] args) throws Exception {
        if (args.length < 1) {
            System.err.println("Usage: LifecycleClosureChecker <models-dir>");
            System.exit(2);
        }
        Path dir = Paths.get(args[0]);

        // M1：别名 → 合法状态集合；别名 → (from,to) 迁移对集合
        Map<String, Set<String>> lifecycles = new LinkedHashMap<>();
        Map<String, Set<String>> transitions = new LinkedHashMap<>();
        Path m1 = dir.resolve("m1-object-model.yaml");
        for (Map<String, Object> agg : listOf(load(m1), "aggregates")) {
            String alias = str(agg.get("alias"));
            if (alias == null) {
                continue;
            }
            Set<String> states = new TreeSet<>();
            Object lc = agg.get("lifecycle");
            if (lc instanceof List) {
                for (Object s : (List<?>) lc) {
                    if (s != null) {
                        states.add(s.toString().trim());
                    }
                }
            }
            lifecycles.put(alias, states);

            Set<String> pairs = new TreeSet<>();
            Object ts = agg.get("transitions");
            if (ts instanceof List) {
                for (Object tObj : (List<?>) ts) {
                    if (!(tObj instanceof Map)) {
                        continue;
                    }
                    Map<String, Object> t = (Map<String, Object>) tObj;
                    // from 允许为空串——表示「无前置状态」（新建对象）
                    String from = t.get("from") == null ? "" : t.get("from").toString().trim();
                    String to = str(t.get("to"));
                    String via = str(t.get("via"));
                    if (to != null) {
                        pairs.add(from + "→" + to + (via == null ? "" : "(" + via + ")"));
                    }
                }
            }
            transitions.put(alias, pairs);
        }

        List<Drift> drifts = new ArrayList<>();

        // M2：每个行为的 postconditions 目标状态必须在 M1 合法集合内，
        //     且与 M1 迁移表里该行为对应的 to 一致
        Path m2 = dir.resolve("m2-behavior-model.yaml");
        int checked = 0;
        for (Map<String, Object> b : listOf(load(m2), "behaviors")) {
            String bhvId = str(b.get("id"));
            String owner = str(b.get("ownerEntity"));
            if (bhvId == null || owner == null) {
                continue;
            }
            // ownerEntity 可能是别名（Product）或聚合根 ID（AGG-PRODUCT-001）
            String alias = resolveAlias(owner, lifecycles.keySet());
            Set<String> states = lifecycles.get(alias);
            if (states == null || states.isEmpty()) {
                continue;   // 该聚合根没声明 lifecycle → 无状态约束
            }
            Object pcs = b.get("postconditions");
            if (!(pcs instanceof List)) {
                continue;
            }
            for (Object pObj : (List<?>) pcs) {
                if (!(pObj instanceof Map)) {
                    continue;
                }
                Map<?, ?> pc = (Map<?, ?>) pObj;
                if (!"status".equals(str(pc.get("field")))) {
                    continue;
                }
                String setValue = str(pc.get("setValue"));
                if (setValue == null) {
                    continue;
                }
                checked++;
                if (!states.contains(setValue)) {
                    drifts.add(new Drift("M2 行为 " + bhvId,
                            "postconditions.status",
                            "目标状态 '" + setValue + "' 不在 M1 " + alias + " 的 lifecycle " + states + " 内"));
                    continue;
                }
                // 与 M1 迁移表交叉：若该行为出现在某条迁移的 via 里，
                // 迁移的 to 必须与 postconditions 的 setValue 一致
                Set<String> pairs = transitions.get(alias);
                if (pairs == null) {
                    continue;
                }
                boolean viaMatched = false;
                for (String pair : pairs) {
                    if (pair.contains("(" + bhvId + ")")) {
                        viaMatched = true;
                        String to = (pair.indexOf("→") < 0) ? "" : pair.substring(
                                    pair.indexOf("→") + 1,
                                    pair.indexOf("(") < 0 ? pair.length() : pair.indexOf("("));
                        if (!to.equals(setValue)) {
                            drifts.add(new Drift("M2 行为 " + bhvId,
                                    "postconditions.status",
                                    "声明目标状态 '" + setValue + "'，但 M1 迁移表说该行为迁移到 '" + to + "'"));
                        }
                    }
                }
                if (!viaMatched && !pairs.isEmpty()) {
                    drifts.add(new Drift("M2 行为 " + bhvId,
                            "postconditions.status",
                            "声明了状态迁移，但 M1 " + alias + " 的 transitions 里没有该行为（via=" + bhvId + "）"));
                }
            }
        }

        System.out.println("=== LifecycleClosure 检查（M1 生命周期 ↔ M2 状态迁移）===");
        System.out.println("M1 声明 lifecycle 的聚合根 : " + lifecycles.size());
        System.out.println("M1 声明 transitions 的聚合根: " + transitions.size());
        System.out.println("检查的行为状态声明           : " + checked);
        System.out.println("检出不一致                  : " + drifts.size());

        if (!drifts.isEmpty()) {
            System.out.println("\n--- [drift] 状态机声明不一致 ---");
            drifts.forEach(d -> System.out.println(d.toString()));
            System.out.println("\n  状态机分散在 M1 与 M2 两个模型里，各自校验就会漏掉「互相矛盾」的情况：");
            System.out.println("  · M2 的 setValue 不是 M1 的合法状态 → 行为会写入非法状态；");
            System.out.println("  · M2 的 setValue 与 M1 迁移表不一致 → 迁移表形同虚设。");
            System.out.println("  修复：以 M1 为准改 M2（状态取值域与迁移表是 M1 的职责），");
            System.out.println("        或在 M1 补上对应的 transitions 声明。");
        }
        System.exit(drifts.isEmpty() ? 0 : 1);
    }

    /** ownerEntity 可能是别名或聚合根 ID，统一解析成别名 */
    static String resolveAlias(String owner, Set<String> aliases) {
        if (aliases.contains(owner)) {
            return owner;
        }
        // AGG-PRODUCT-001 → Product
        if (owner.startsWith("AGG-")) {
            String mid = owner.substring(4);
            int idx = mid.lastIndexOf('-');
            if (idx > 0) {
                mid = mid.substring(0, idx);
            }
            String pascal = toPascal(mid);
            if (aliases.contains(pascal)) {
                return pascal;
            }
        }
        return owner;
    }

    static String toPascal(String s) {
        StringBuilder sb = new StringBuilder();
        boolean up = true;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '-' || c == '_') {
                up = true;
                continue;
            }
            sb.append(up ? Character.toUpperCase(c) : Character.toLowerCase(c));
            up = false;
        }
        return sb.toString();
    }

    static Object load(Path yaml) throws IOException {
        if (!Files.isRegularFile(yaml)) {
            return null;
        }
        try (InputStream in = Files.newInputStream(yaml)) {
            return new Yaml().load(in);
        }
    }

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

    static String str(Object v) {
        if (v == null) {
            return null;
        }
        String s = v.toString().trim();
        return s.isEmpty() ? null : s;
    }
}
