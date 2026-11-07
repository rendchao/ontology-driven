package com.jjy.codegen;

import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;

/**
 * 对比 M1 对象模型 vs MM 映射模型，验证「M1 → MM → DDL/PO」这条链是否闭合。
 *
 * <p>检查 3 类漂移：
 * <ol>
 *   <li><b>M1 有字段、MM 没映射</b> → 该字段在物理表里没有落点，DDL 会漏列</li>
 *   <li><b>MM 映射了 M1 没有的字段</b> → 悬空映射，指向已删除的字段</li>
 *   <li><b>同一物理列被多个 M1 字段占用但类型不一致</b> → 别名列的类型冲突</li>
 * </ol>
 *
 * <p>用法：
 * <pre>
 *   java com.jjy.codegen.MappingDiffReporter &lt;m1.yaml&gt; &lt;mm.yaml&gt;
 * </pre>
 */
public final class MappingDiffReporter {

    private MappingDiffReporter() {}

    public static void main(String[] args) throws Exception {
        if (args.length < 2) {
            System.err.println("Usage: MappingDiffReporter <m1.yaml> <mm.yaml>");
            System.exit(2);
        }
        Path m1 = Paths.get(args[0]);
        Path mm = Paths.get(args[1]);

        // M1: alias -> {fieldName -> type}
        Map<String, Map<String, String>> m1Fields = readM1(m1);
        // MM: entity -> {fieldName -> (columnName, columnType)}
        Map<String, Map<String, String[]>> mmFields = readMm(mm);

        List<String> missing = new ArrayList<>();   // M1 有 MM 无
        List<String> orphan = new ArrayList<>();    // MM 有 M1 无
        List<String> typeClash = new ArrayList<>(); // 别名列类型不一致

        // 表名 -> 该表内「列名 → 类型集合」，用于发现同表内的别名列类型冲突
        // （不同表之间允许出现同名列，各表列宽独立，不需要一致）
        Map<String, Map<String, Set<String>>> tableColTypes = new TreeMap<>();

        for (String alias : new TreeSet<>(m1Fields.keySet())) {
            Map<String, String> m1f = m1Fields.get(alias);
            Map<String, String[]> mmf = mmFields.get(alias);
            if (mmf == null) {
                missing.add(alias + " (整个实体未在 MM 中映射)");
                continue;
            }
            for (String field : new TreeSet<>(m1f.keySet())) {
                if (!mmf.containsKey(field)) {
                    missing.add(alias + "." + field);
                }
            }
            for (String field : new TreeSet<>(mmf.keySet())) {
                if (!m1f.containsKey(field)) {
                    orphan.add(alias + "." + field);
                }
            }
            // 别名列类型一致性（按表分组）
            Map<String, Set<String>> colTypes = tableColTypes.computeIfAbsent(alias, k -> new TreeMap<>());
            for (Map.Entry<String, String[]> e : mmf.entrySet()) {
                String col = e.getValue()[0];
                String ty = e.getValue()[1];
                if (ty == null) continue;
                colTypes.computeIfAbsent(col, k -> new TreeSet<>()).add(ty);
            }
        }
        for (Map.Entry<String, Map<String, Set<String>>> te : tableColTypes.entrySet()) {
            for (Map.Entry<String, Set<String>> e : te.getValue().entrySet()) {
                if (e.getValue().size() > 1) {
                    typeClash.add(te.getKey() + "." + e.getKey() + " -> " + e.getValue());
                }
            }
        }

        System.out.println("=== MappingDiff 报告（M1 对象模型 ↔ MM 映射模型）===");
        System.out.println("M1 实体/子实体总数        : " + m1Fields.size());
        System.out.println("M1 字段总数               : " + m1Fields.values().stream().mapToInt(Map::size).sum());
        System.out.println("MM 实体总数               : " + mmFields.size());
        System.out.println("MM 字段总数               : " + mmFields.values().stream().mapToInt(Map::size).sum());
        System.out.println("  - M1 有 / MM 无 (missing)   : " + missing.size());
        System.out.println("  - MM 有 / M1 无 (orphan)    : " + orphan.size());
        System.out.println("  - 别名列类型冲突 (type clash): " + typeClash.size());

        if (!missing.isEmpty()) {
            System.out.println("\n--- [missing] M1 有该字段但 MM 未映射（DDL 会漏列）---");
            missing.forEach(s -> System.out.println("  " + s));
        }
        if (!orphan.isEmpty()) {
            System.out.println("\n--- [orphan] MM 映射了 M1 已不存在的字段 ---");
            orphan.forEach(s -> System.out.println("  " + s));
        }
        if (!typeClash.isEmpty()) {
            System.out.println("\n--- [type clash] 同一物理列被声明了不同类型 ---");
            typeClash.forEach(s -> System.out.println("  " + s));
        }
        boolean drift = !missing.isEmpty() || !orphan.isEmpty() || !typeClash.isEmpty();
        System.exit(drift ? 1 : 0);
    }

    /** M1: alias -> {fieldName -> type} */
    static Map<String, Map<String, String>> readM1(Path yaml) throws IOException {
        Map<String, Map<String, String>> out = new LinkedHashMap<>();
        try (InputStream in = Files.newInputStream(yaml)) {
            Object root = new Yaml().load(in);
            if (!(root instanceof Map)) return out;
            collect((Map<?, ?>) root, out);
        }
        return out;
    }

    @SuppressWarnings("unchecked")
    private static void collect(Map<?, ?> node, Map<String, Map<String, String>> out) {
        Object alias = node.get("alias");
        Object attrs = node.get("attributes");
        if (alias != null && attrs instanceof List) {
            Map<String, String> f = new LinkedHashMap<>();
            for (Object a : (List<Object>) attrs) {
                if (!(a instanceof Map)) continue;
                Map<?, ?> am = (Map<?, ?>) a;
                Object n = am.get("name");
                if (n == null) continue;
                Object t = am.get("type");
                f.put(n.toString(), t == null ? "String" : t.toString());
            }
            out.put(alias.toString(), f);
        }
        for (Object v : node.values()) {
            if (v instanceof Map) collect((Map<?, ?>) v, out);
            else if (v instanceof List) {
                for (Object x : (List<Object>) v) {
                    if (x instanceof Map) collect((Map<?, ?>) x, out);
                }
            }
        }
    }

    /** MM: mappedEntity -> {fieldName -> [columnName, columnType]} */
    static Map<String, Map<String, String[]>> readMm(Path yaml) throws IOException {
        Map<String, Map<String, String[]>> out = new LinkedHashMap<>();
        try (InputStream in = Files.newInputStream(yaml)) {
            Object root = new Yaml().load(in);
            if (!(root instanceof Map)) return out;
            Object mappings = ((Map<?, ?>) root).get("mappings");
            if (!(mappings instanceof List)) return out;
            for (Object mo : (List<Object>) mappings) {
                if (!(mo instanceof Map)) continue;
                Object tables = ((Map<?, ?>) mo).get("tables");
                if (!(tables instanceof List)) continue;
                for (Object to : (List<Object>) tables) {
                    if (!(to instanceof Map)) continue;
                    Map<?, ?> tm = (Map<?, ?>) to;
                    Object ent = tm.get("mappedEntity");
                    if (ent == null) continue;
                    Map<String, String[]> f = out.computeIfAbsent(ent.toString(), k -> new LinkedHashMap<>());
                    Object cols = tm.get("columnMappings");
                    if (!(cols instanceof List)) continue;
                    for (Object co : (List<Object>) cols) {
                        if (!(co instanceof Map)) continue;
                        Map<?, ?> cm = (Map<?, ?>) co;
                        Object fp = cm.get("fieldPath");
                        Object cn = cm.get("columnName");
                        if (fp == null || cn == null) continue;
                        String field = fp.toString().contains(".")
                                ? fp.toString().substring(fp.toString().indexOf('.') + 1) : fp.toString();
                        Object ct = cm.get("columnType");
                        f.put(field, new String[]{cn.toString(), ct == null ? null : ct.toString()});
                    }
                }
            }
        }
        return out;
    }
}
