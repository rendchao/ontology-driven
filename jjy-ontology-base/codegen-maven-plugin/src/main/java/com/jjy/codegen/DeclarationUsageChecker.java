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
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 「声明—消费点」对账：模型声明的每个语义字段，在 Java 代码里是否真的被读取。
 *
 * <p><b>补的是现有 7 项检查的盲区。</b>那 7 项对账的是「模型 ↔ 代码结构」
 * （字段有没有生成 PO、行为有没有注册 Handler……），管不了另一类问题：
 * <b>模型声明了，但运行期根本不消费它</b>。</p>
 *
 * <p>这类缺口最隐蔽：模型写得越完整、代码跑得越正常，越没人发现。
 * 实测过的真实案例：</p>
 * <ul>
 *   <li>M2 声明了 {@code syncTriggers}（跨聚合联动），Java 侧<b>整个领域层都没有这个字段</b>
 *       → 联动只能绕道事件，模型声明形同虚设</li>
 *   <li>ME 声明了 {@code subscribers[].handlerRef}，但 {@code DomainEvent} POJO
 *       <b>没有 subscribers 属性</b> → 加载时被 Jackson 静默丢弃</li>
 *   <li>M7 声明了 {@code resultColumns}（报表口径），查询代码<b>完全不读它</b>
 *       → 手写 SQL，M7 沦为摆设</li>
 * </ul>
 *
 * <p>本检查器把这类「L1 有、L2 缺」的缺口变成机器可发现。</p>
 *
 * <h3>检查规则</h3>
 * <p>对每个「需要被消费」的模型字段：</p>
 * <ol>
 *   <li>若模型中<em>有</em>该字段的声明（值非空、且非空数组），
 *       则 Java 源码中必须能搜到对应的读取点（字段名 / getter / 反射 key）；</li>
 *   <li>若模型中<em>无</em>该字段声明（或全为空），则不要求代码消费
 *       ——避免对「尚未启用的可选能力」误报。</li>
 * </ol>
 *
 * <p>这样既能发现真缺口，又不会因为「模型声明了但项目还没用这个能力」而误报，
 * 因为空声明一律放过。</p>
 *
 * <p>用法：</p>
 * <pre>
 *   java com.jjy.codegen.DeclarationUsageChecker &lt;models-dir&gt; &lt;java-source-root&gt;
 * </pre>
 */
public final class DeclarationUsageChecker {

    private DeclarationUsageChecker() {
    }

    /**
     * 一条「声明了但没人消费」的缺口。
     */
    static class Gap {
        final String model;
        final String field;
        final String where;
        final String note;

        Gap(String model, String field, String where, String note) {
            this.model = model;
            this.field = field;
            this.where = where;
            this.note = note;
        }

        @Override
        public String toString() {
            return String.format("  %-14s %-22s 声明于 %-38s %s",
                    model, field, where, note);
        }
    }

    /**
     * 一条「模型字段 → 代码里应搜到的关键词」的规则。
     *
     * <p>关键词用「或」关系：只要代码里出现任意一个，就算有消费点。</p>
     *
     * <p><b>关键词的选取原则</b>：优先用<b>调用形式</b>（{@code getXxx()}）而不是裸字段名。
     * 裸字段名会撞上两类假阳性——</p>
     * <ol>
     *   <li>领域层 POJO 的字段声明（{@code private List<String> postconditions;}）——
     *       那只是「模型能被反序列化」，不是「语义被消费」；</li>
     *   <li>同名的运行期结构（如 {@code EventSubscriberRegistry.getSubscribers()}
     *       是订阅表，与 ME 声明的 subscribers 无关）。</li>
     * </ol>
     * <p>用 {@code getXxx()} 后，领域 POJO 的字段声明不再命中，只剩真正的读取点。</p>
     *
     * <p><b>关键词的两种形态</b>：</p>
     * <ul>
     *   <li>{@code getXxx()} —— 强类型 POJO 的读取调用；</li>
     *   <li>{@code "xxx"} —— 以字符串字面量形式读取 Map 里的模型字段
     *       （模型层大量用 {@code Map<String,Object>} 承载未专门建 POJO 的声明，
     *       读它们必须用字符串 key）。</li>
     * </ul>
     * <p>两种形态都<b>不会</b>命中领域 POJO 的字段声明行
     * （{@code private List<String> postconditions;} 既没有 {@code get} 也没有引号），
     * 因此不会把「模型能被反序列化」误判成「语义被消费」。</p>
     */
    static class Rule {
        final String model;
        /** 模型文件名匹配模式（如 {@code m2-}） */
        final String filePattern;
        final String field;
        final String[] keywords;
        final String note;
        /** true = 由模型自动推导；false = 手工补充（需特殊关键词） */
        final boolean auto;

        Rule(String model, String filePattern, String field, String note, String... keywords) {
            this.model = model;
            this.filePattern = filePattern;
            this.field = field;
            this.keywords = keywords;
            this.note = note;
            this.auto = false;
        }

        Rule(String model, String filePattern, String field, String note, boolean auto, String... keywords) {
            this.model = model;
            this.filePattern = filePattern;
            this.field = field;
            this.keywords = keywords;
            this.note = note;
            this.auto = auto;
        }
    }

    /**
     * 需检查消费点的模型字段。
     *
     * <p>选这几个的理由：它们都是<em>语义声明</em>（不是配置项），
     * 一旦模型写了就意味着「业务方期望它生效」。</p>
     */
    static final List<Rule> RULES = Arrays.asList(
            new Rule("M2 行为", "m2-", "syncTriggers",
                    "跨聚合联动；缺失则联动只能绕道事件",
                    "getSyncTriggers()", "\"syncTriggers\"", "SyncTrigger("),
            new Rule("ME 事件", "me-", "subscribers",
                    "事件订阅方；缺失则事件产出无人消费",
                    // 只认「解析 ME 声明的 handlerRef」这一动作。
                    // 不认 getSubscribers()——EventSubscriberRegistry 有同名方法，
                    // 但那是运行期订阅表（由 M2.subscribedEvents 构建），与 ME 声明无关。
                    // 同名不同物，是最典型的假阳性来源。
                    "handlerRef", "getHandlerRef()"),
            new Rule("M7 报表", "m7-", "resultColumns",
                    "报表结果列；缺失则查询口径在代码里手写",
                    "getResultColumns()", "\"resultColumns\""),
            new Rule("M7 报表", "m7-", "sourceObjects",
                    "报表数据源；缺失则报表与 M1 无关",
                    "getSourceObjects()", "\"sourceObjects\""),
            new Rule("M5 主体", "m5-", "dataScope",
                    "数据范围；缺失则按权限码约定猜",
                    "getDataScope()", "\"dataScope\""),
            new Rule("M5 主体", "m5-", "abacCondition",
                    "ABAC 条件；缺失则属性级授权不生效",
                    "getAbacCondition()", "\"abacCondition\""),
            new Rule("M1 对象", "m1-", "refRules",
                    "参照完整性规则；缺失则可写入悬空引用",
                    "getRefRules()", "\"refRules\""),
            new Rule("M1 对象", "m1-", "lifecycle",
                    "生命周期状态机；缺失则状态约束无处落地",
                    "getLifecycle()", "getTransitions()", "\"lifecycle\"", "\"transitions\""),
            new Rule("M2 行为", "m2-", "preconditions",
                    "前置条件；缺失则只有规则兜底",
                    "getPreconditions()", "\"preconditions\""),
            new Rule("M2 行为", "m2-", "postconditions",
                    "后置状态变更；缺失则状态迁移无处声明",
                    "getPostconditions()", "\"postconditions\"")
    );

    /**
     * 「只读透传层」的文件名。
     *
     * <p><b>为什么必须排除</b>：AI 工具（{@code BehaviorTool} 等）会把 M2 的
     * preconditions / postconditions 原样透传给 LLM 做描述。这在字面上确实出现了
     * 字段名，但它<b>不产生任何业务语义</b>——模型改了，行为不变。
     * 若把它算作「消费点」，守门员就会给假绿灯：模型声明被改坏，代码照常跑，
     * 永远没人发现。这比漏检更危险，因为它让人<b>以为</b>防线在。</p>
     *
     * <p>判据：<b>读出来影响行为的</b>才算消费点；<b>读出来给人看的</b>不算。</p>
     */
    static final Set<String> PASSTHROUGH_FILES = new LinkedHashSet<>(Arrays.asList(
            "BehaviorTool.java",      // AI 描述工具：把 M2 元数据透传给 LLM
            "SystemPromptBuilder.java",
            "QueryTool.java",
            "NavigationTool.java"
    ));

    public static void main(String[] args) throws Exception {
        if (args.length < 2) {
            System.err.println("Usage: DeclarationUsageChecker <models-dir> <java-source-root>");
            System.exit(2);
        }
        Path modelsDir = Paths.get(args[0]);
        // 多个源码根用 ';' 分隔（domain / application / infrastructure / interfaces 都要扫）
        List<Path> javaRoots = new ArrayList<>();
        for (String r : args[1].split(";")) {
            String t = r.trim();
            if (!t.isEmpty()) {
                javaRoots.add(Paths.get(t));
            }
        }
        if (javaRoots.isEmpty()) {
            System.err.println("源码目录不能为空");
            System.exit(2);
        }

        // 1) 收集全部 Java 源码文本（去注释），作为「消费点」语料
        String corpus = readAllJava(javaRoots);
        if (corpus.isEmpty()) {
            System.out.println("=== DeclarationUsage 检查（声明 → 消费点）===");
            System.out.println("未找到 Java 源文件，跳过（源码目录: " + javaRoots + "）");
            System.exit(0);
        }

        // 2) 逐条规则判断：模型里有声明 → 代码里必须有消费点
        List<Gap> gaps = new ArrayList<>();
        List<String> checked = new ArrayList<>();
        for (Rule rule : RULES) {
            Declaration d = findDeclaration(modelsDir, rule);
            if (d == null || !d.declared) {
                // 模型未声明（或全为空）→ 不要求代码消费，不算缺口
                continue;
            }
            checked.add(rule.model + "." + rule.field + " @ " + d.where);
            boolean consumed = false;
            for (String kw : rule.keywords) {
                if (corpus.contains(kw)) {
                    consumed = true;
                    break;
                }
            }
            if (!consumed) {
                gaps.add(new Gap(rule.model, rule.field, d.where, rule.note));
            }
        }

        System.out.println("=== DeclarationUsage 检查（声明 → 消费点）===");
        System.out.println("Java 源码语料      : " + corpus.length() + " 字符");
        System.out.println("需检查的模型字段    : " + checked.size());
        for (String c : checked) {
            System.out.println("  · " + c);
        }
        System.out.println("  - 声明但无消费点   : " + gaps.size());

        if (!gaps.isEmpty()) {
            System.out.println("\n--- [gap] 模型声明了，但 Java 代码中找不到消费点 ---");
            gaps.forEach(g -> System.out.println(g.toString()));
            System.out.println("\n  这类缺口的症状：模型写得完整、代码跑得正常，但该语义<b>从未生效</b>。");
            System.out.println("  属于「L1 已声明 / L2 未实现 / L3 未接通」——最隐蔽的一类漂移。");
            System.out.println("\n  修复二选一：");
            System.out.println("    a) 在代码里真正消费该字段（补齐实现）；");
            System.out.println("    b) 若该能力本项目暂不需要，从模型里删掉该声明（模型不应声明用不到的东西）。");
        }
        System.exit(gaps.isEmpty() ? 0 : 1);
    }

    /**
     * 一处声明的定位结果。
     */
    static class Declaration {
        final boolean declared;
        final String where;

        Declaration(boolean declared, String where) {
            this.declared = declared;
            this.where = where;
        }
    }

    /**
     * 在模型目录里找该字段的声明。
     *
     * <p>判定「有声明」：字段存在于 YAML 中，且值不是 null、不是空数组、不是空字符串。
     * 这样「模型声明了但项目还没实现」才算缺口；「模型压根没写这个字段」不算。</p>
     */
    static Declaration findDeclaration(Path modelsDir, Rule rule) throws IOException {
        for (Path yaml : listYaml(modelsDir)) {
            Object root = load(yaml);
            if (!(root instanceof Map)) {
                continue;
            }
            String rel = modelsDir.relativize(yaml).toString().replace('\\', '/');
            // 规则 → 可能的模型文件（按文件名模式匹配，如 m2- 匹配 m2-behavior-model.yaml）
            if (!rel.contains(rule.filePattern)) {
                continue;
            }
            // 全量统计：出现次数 + 有实质内容的次数
            Counter c = new Counter();
            countOccurrences(root, rule.field, c);
            if (c.total > 0) {
                return new Declaration(c.nonEmpty > 0, rel + "#" + rule.field);
            }
        }
        return null;
    }

    /**
     * 统计某字段在 YAML 树中的出现次数与「有实质内容」的次数。
     */
    static class Counter {
        int total;
        int nonEmpty;
    }

    /**
     * 递归统计字段出现情况。
     *
     * <p><b>必须全量统计而非只看第一处</b>：同一字段在模型里会出现多次
     * （每个 behavior 各一份 {@code syncTriggers}），其中大部分是空数组。
     * 若只看首个，遇到「首个为空」就立即返回，后面非空的那份根本没机会被看到——
     * 实测这会让 M2.syncTriggers 这个真实缺口漏检。</p>
     */
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
    /**
     * 值是否有实质内容：非 null、非空串、非空数组。
     */
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
        try (InputStream in = Files.newInputStream(yaml)) {
            return new Yaml().load(in);
        }
    }

    /**
     * 读全部 Java 源码并拼成一个大字符串（去注释）。
     *
     * <p><b>排除检查器自身</b>：本文件的注释里就写着 {@code syncTriggers} 等关键词，
     * 若不排除会自匹配成「有消费点」，形成假阴性。</p>
     */
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
                        .filter(p -> !p.getFileName().toString().equals("DeclarationUsageChecker.java"))
                        // 排除「只读透传层」：这些文件只是把模型字段读出来给人/给 LLM 看，
                        // 不产生业务语义。算作消费点会给假绿灯。
                        .filter(p -> !PASSTHROUGH_FILES.contains(p.getFileName().toString()))
                        .forEach(files::add);
                for (Path p : files) {
                    String src = new String(Files.readAllBytes(p), StandardCharsets.UTF_8);
                    sb.append(stripComments(src)).append('\n');
                }
            }
        }
        return sb.toString();
    }

    /** 去块注释与行注释，避免注释里的字段名造成假阳性 */
    static String stripComments(String src) {
        return src.replaceAll("(?s)/\\*.*?\\*/", "")
                .replaceAll("(?m)//.*$", "");
    }
}
