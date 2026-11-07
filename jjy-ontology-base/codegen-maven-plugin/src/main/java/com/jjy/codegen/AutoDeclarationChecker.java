package com.jjy.codegen;

import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * 自动声明检查：<b>规则表由模型自动推导，不再手工维护</b>。
 *
 * <p><b>解决的问题</b>：{@link DeclarationUsageChecker} 的规则表是手工维护的 10 条。
 * 新增一个模型语义字段时，若忘了同步加规则，该字段就退化成
 * 「声明了但没人消费，也不报」——<b>守门员自己会有盲区</b>。
 * 而守门员的盲区是所有盲区里最危险的一个：它让人<b>以为</b>防线在。</p>
 *
 * <p><b>本检查器怎么消除它</b>：扫描全部模型文件，提取每个「语义字段」，
 * 自动派生检查项与关键词。新增模型字段 → 检查项自动跟上，
 * 不依赖任何人记得手工维护规则表。</p>
 *
 * <p><b>如何区分「语义字段」与「技术字段」</b>：</p>
 * <table border="1">
 *   <caption>判定标准</caption>
 *   <tr><th>类别</th><th>例子</th><th>是否检查</th></tr>
 *   <tr><td>语义声明</td><td>{@code postconditions} {@code dataScope} {@code resultColumns}</td>
 *       <td>检查——业务方期望它生效</td></tr>
 *   <tr><td>标识/结构</td><td>{@code id} {@code name} {@code version} {@code type} {@code description}</td>
 *       <td>不检查——不承载业务语义</td></tr>
 *   <tr><td>技术字段</td><td>{@code flag} {@code created_at} {@code updated_by}</td>
 *       <td>不检查——基础设施字段</td></tr>
 * </table>
 *
 * <p>用法：</p>
 * <pre>
 *   java com.jjy.codegen.AutoDeclarationChecker &lt;models-dir&gt; &lt;java-source-roots&gt;
 * </pre>
 */
public final class AutoDeclarationChecker {

    private AutoDeclarationChecker() {
    }

    /** 不承载业务语义的字段（标识 / 结构 / 描述） */
    static final Set<String> NON_SEMANTIC = new LinkedHashSet<>(Arrays.asList(
            "id", "ids", "name", "names", "code", "codes", "version", "description", "desc",
            "label", "labels", "title", "type", "types", "remark", "remarks", "comment",
            "comments", "model_type", "domain", "alias", "aliases", "required", "unique",
            "pk", "localId", "cardinality", "targetAggregate", "targetRef", "targetType",
            "reusedBy", "inputParams", "outputType", "ruleType", "engine", "via",
            "dictionaryRef", "value", "operator", "sortOrder", "enabled",
            "objectType", "behaviorForm", "triggerType", "behaviorType", "sourceBehavior",
            "sourceAggregate", "multiProducer", "reliability", "retention", "origin",
            "miChannelRef", "mappedInternalEvent", "interfaceId", "screenId", "menuId",
            "fieldPath", "columnName", "columnType", "tableName", "mappedEntity",
            "defaultColumns", "primary", "objectRef", "behaviorRef", "dataBinding",
            "screenRef", "elementId", "field", "setValue", "actionId", "stepId",
            "flowId", "scenarioId", "eventId", "aggregateType", "direction", "path",
            "method", "contract", "expression", "violationMessage"
    ));

    /** 技术字段（基础设施，不检查） */
    static final Set<String> TECHNICAL = new LinkedHashSet<>(Arrays.asList(
            "flag", "created_by", "created_at", "updated_by", "updated_at",
            "deleted", "tenant_id", "tenantId"
    ));

    /** 明确不检查的字段（已由其它检查器覆盖，或本身是派生索引） */
    static final Set<String> EXEMPT = new LinkedHashSet<>(Arrays.asList(
            // 派生索引：由 M3.reusedBy ↔ M2.appliedRules 双向校验覆盖
            "reusedBy"
    ));

    /** 一个自动推导的检查项 */
    static class AutoRule {
        final String model;
        final String filePattern;
        final String field;
        final String[] keywords;

        AutoRule(String model, String filePattern, String field, String... keywords) {
            this.model = model;
            this.filePattern = filePattern;
            this.field = field;
            this.keywords = keywords;
        }
    }

    public static void main(String[] args) throws Exception {
        if (args.length < 2) {
            System.err.println("Usage: AutoDeclarationChecker <models-dir> <java-source-roots>");
            System.exit(2);
        }
        Path modelsDir = Paths.get(args[0]);
        List<Path> javaRoots = new ArrayList<>();
        for (String r : args[1].split(";")) {
            String t = r.trim();
            if (!t.isEmpty()) {
                javaRoots.add(Paths.get(t));
            }
        }
        // 第 3 个参数（可选）：codegen 插件源码根。
        //
        // 生成器本身就是模型的消费者——它把模型变成 PO / DDL / Handler。
        // 不扫它会把「已被生成器消费」的字段（如 M1.entities 子实体声明）
        // 误报成真缺口。
        if (args.length >= 3) {
            String t = args[2].trim();
            if (!t.isEmpty()) {
                javaRoots.add(Paths.get(t));
            }
        }

        String corpus = readAllJava(javaRoots);

        // ── 1. 扫描模型，推导规则 ──
        List<AutoRule> rules = deriveRules(modelsDir);
        System.out.println("=== AutoDeclaration 检查（规则表自动推导）===");
        System.out.println("模型目录              : " + modelsDir);
        System.out.println("扫描的源码根          : " + javaRoots.size() + " 个（含 codegen 生成器）");
        System.out.println("Java 源码语料         : " + corpus.length() + " 字符");
        System.out.println("自动推导的检查项      : " + rules.size());

        // ── 2. 逐项检查 ──
        List<String> gaps = new ArrayList<>();
        List<String> checked = new ArrayList<>();
        for (AutoRule r : rules) {
            Declaration d = findDeclaration(modelsDir, r.filePattern, r.field);
            if (d == null || !d.declared) {
                continue;   // 模型未声明 → 不要求消费
            }
            checked.add(r.model + "." + r.field + " @ " + d.where);
            boolean consumed = false;
            for (String kw : r.keywords) {
                if (corpus.contains(kw)) {
                    consumed = true;
                    break;
                }
            }
            if (!consumed) {
                gaps.add(String.format("  %-6s %-24s 声明于 %-38s 模型声明了但代码中无消费点",
                        r.model, r.field, d.where));
            }
        }

        System.out.println("实际需检查的字段      : " + checked.size());
        for (String c : checked) {
            System.out.println("  · " + c);
        }
        System.out.println("  - 声明但无消费点     : " + gaps.size());

        if (!gaps.isEmpty()) {
            System.out.println("\n--- [gap] 模型声明了，但 Java 代码中找不到消费点 ---");
            gaps.forEach(g -> System.out.println(g));
            System.out.println("\n  修复二选一：");
            System.out.println("    a) 在代码里真正消费该字段（补齐实现）；");
            System.out.println("    b) 若该能力本项目暂不需要，从模型里删掉该声明。");
        }
        System.exit(gaps.isEmpty() ? 0 : 1);
    }

    /**
     * 扫描模型目录，推导检查项。
     *
     * <p><b>推导规则</b>：</p>
     * <ol>
     *   <li>遍历每个 YAML，收集出现过的所有键名；</li>
     *   <li>排除非语义字段与技术字段；</li>
     *   <li>为每个剩余字段生成检查项，关键词按字段名自动派生。</li>
     * </ol>
     */
    static List<AutoRule> deriveRules(Path modelsDir) throws IOException {
        // 模型文件前缀 → 模型代号
        Map<String, String> modelOfPrefix = new java.util.LinkedHashMap<>();
        modelOfPrefix.put("m1-", "M1");
        modelOfPrefix.put("m2-", "M2");
        modelOfPrefix.put("m3-", "M3");
        modelOfPrefix.put("m4-", "M4");
        modelOfPrefix.put("m5-", "M5");
        modelOfPrefix.put("m6-", "M6");
        modelOfPrefix.put("m7-", "M7");
        modelOfPrefix.put("me-", "ME");
        modelOfPrefix.put("mi-", "MI");
        modelOfPrefix.put("mm-", "MM");
        modelOfPrefix.put("mu-", "MU");

        Map<String, AutoRule> byField = new java.util.LinkedHashMap<>();
        for (Path yaml : listYaml(modelsDir)) {
            String rel = modelsDir.relativize(yaml).toString().replace('\\', '/');
            String prefix = null;
            String model = null;
            for (Map.Entry<String, String> e : modelOfPrefix.entrySet()) {
                if (rel.contains(e.getKey())) {
                    prefix = e.getKey();
                    model = e.getValue();
                    break;
                }
            }
            if (prefix == null) {
                continue;
            }
            Set<String> fields = new TreeSet<>();
            collectFields(load(yaml), fields);
            for (String f : fields) {
                if (NON_SEMANTIC.contains(f) || TECHNICAL.contains(f) || EXEMPT.contains(f)) {
                    continue;
                }
                if (byField.containsKey(model + "." + f)) {
                    continue;
                }
                byField.put(model + "." + f, new AutoRule(model, prefix, f,
                        "get" + cap(f) + "()", "\"" + f + "\""));
            }
        }
        return new ArrayList<>(byField.values());
    }

    /** 递归收集 YAML 中出现过的所有键名 */
    @SuppressWarnings("unchecked")
    static void collectFields(Object node, Set<String> out) {
        if (node instanceof Map) {
            for (Map.Entry<?, ?> e : ((Map<?, ?>) node).entrySet()) {
                if (e.getKey() != null) {
                    out.add(e.getKey().toString());
                }
                collectFields(e.getValue(), out);
            }
        } else if (node instanceof List) {
            for (Object o : (List<?>) node) {
                collectFields(o, out);
            }
        }
    }

    /** 一处声明的定位结果 */
    static class Declaration {
        final boolean declared;
        final String where;

        Declaration(boolean declared, String where) {
            this.declared = declared;
            this.where = where;
        }
    }

    /** 在模型目录里找某字段的声明 */
    static Declaration findDeclaration(Path modelsDir, String filePattern, String field) throws IOException {
        for (Path yaml : listYaml(modelsDir)) {
            String rel = modelsDir.relativize(yaml).toString().replace('\\', '/');
            if (!rel.contains(filePattern)) {
                continue;
            }
            Counter c = new Counter();
            countOccurrences(load(yaml), field, c);
            if (c.total > 0) {
                return new Declaration(c.nonEmpty > 0, rel + "#" + field);
            }
        }
        return null;
    }

    static class Counter {
        int total;
        int nonEmpty;
    }

    static void countOccurrences(Object node, String field, Counter c) {
        if (node instanceof Map) {
            Map<?, ?> map = (Map<?, ?>) node;
            for (Map.Entry<?, ?> e : map.entrySet()) {
                if (field.equals(String.valueOf(e.getKey()))) {
                    c.total++;
                    if (hasValue(e.getValue())) {
                        c.nonEmpty++;
                    }
                }
            }
            for (Object v : map.values()) {
                countOccurrences(v, field, c);
            }
        } else if (node instanceof List) {
            for (Object o : (List<?>) node) {
                countOccurrences(o, field, c);
            }
        }
    }

    static boolean hasValue(Object v) {
        if (v == null) {
            return false;
        }
        if (v instanceof String) {
            return !((String) v).trim().isEmpty();
        }
        if (v instanceof List) {
            return !((List<?>) v).isEmpty();
        }
        if (v instanceof Map) {
            return !((Map<?, ?>) v).isEmpty();
        }
        return true;
    }

    static String cap(String s) {
        if (s == null || s.isEmpty()) {
            return s;
        }
        return Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }

    static List<Path> listYaml(Path dir) throws IOException {
        List<Path> out = new ArrayList<>();
        if (!Files.isDirectory(dir)) {
            return out;
        }
        try (java.util.stream.Stream<Path> s = Files.list(dir)) {
            s.filter(p -> p.toString().endsWith(".yaml") || p.toString().endsWith(".yml"))
                    .sorted()
                    .forEach(out::add);
        }
        return out;
    }

    static Object load(Path yaml) throws IOException {
        if (!Files.isRegularFile(yaml)) {
            return null;
        }
        try (InputStream in = Files.newInputStream(yaml)) {
            return new Yaml().load(in);
        }
    }

    /** 读全部 Java 源码（去注释），作为「消费点」语料 */
    static String readAllJava(List<Path> roots) throws IOException {
        StringBuilder sb = new StringBuilder();
        for (Path root : roots) {
            if (!Files.isDirectory(root)) {
                continue;
            }
            try (java.util.stream.Stream<Path> stream = Files.walk(root)) {
                List<Path> files = new ArrayList<>();
                stream.filter(Files::isRegularFile)
                        .filter(p -> p.toString().endsWith(".java"))
                        // 排除检查器自身（它只是声明了这些关键词，不是消费点）
                        .filter(p -> !p.getFileName().toString().equals("AutoDeclarationChecker.java"))
                        .filter(p -> !p.getFileName().toString().equals("DeclarationUsageChecker.java"))
                        .forEach(files::add);
                for (Path p : files) {
                    String src = new String(Files.readAllBytes(p), StandardCharsets.UTF_8);
                    sb.append(stripComments(src)).append('\n');
                }
            }
        }
        return sb.toString();
    }

    static String stripComments(String src) {
        return src.replaceAll("(?s)/\\*.*?\\*/", "")
                .replaceAll("(?m)//.*$", "");
    }
}
