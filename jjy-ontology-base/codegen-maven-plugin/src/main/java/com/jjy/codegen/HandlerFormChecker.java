package com.jjy.codegen;

import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 行为形态三方对账：M2 声明 ↔ 生成物 ↔ 手写实现。
 *
 * <p>路径 C（模板生成 Handler）引入后，「行为由谁实现」变成了三方关系，
 * 必须持续对账，否则会出现三类漂移：</p>
 * <table border="1">
 *   <caption>三类漂移</caption>
 *   <tr><th>漂移</th><th>症状</th></tr>
 *   <tr>
 *     <td>声明 TEMPLATE 但没有生成物</td>
 *     <td>行为调起来抛「没有 @BehaviorHandler 实现」——运行期才发现</td>
 *   </tr>
 *   <tr>
 *     <td>声明 TEMPLATE 但同时有手写实现</td>
 *     <td>同一 behaviorId 两个实现，谁生效取决于 Spring 扫描顺序（不确定的静默行为）</td>
 *   </tr>
 *   <tr>
 *     <td>生成物被改过，与模型不一致</td>
 *     <td>模型说 A、代码做 B，且构建期只告警不阻断</td>
 *   </tr>
 * </table>
 *
 * <p>用法：</p>
 * <pre>
 *   java com.jjy.codegen.HandlerFormChecker &lt;m2.yaml&gt; &lt;java-source-root&gt;
 * </pre>
 */
public final class HandlerFormChecker {

    private HandlerFormChecker() {
    }

    /** 匹配 @BehaviorHandler("Xxx") */
    private static final Pattern HANDLER_ANN =
            Pattern.compile("@BehaviorHandler\\s*\\(\\s*\"([^\"]+)\"\\s*\\)");

    /** 匹配生成物里的指纹标记 */
    private static final String FINGERPRINT_MARK = "generated-signature:";

    /** 生成物目录名 */
    private static final String GENERATED_DIR = "generated";

    /** 一处漂移 */
    static class Drift {
        final String kind;
        final String behaviorId;
        final String detail;

        Drift(String kind, String behaviorId, String detail) {
            this.kind = kind;
            this.behaviorId = behaviorId;
            this.detail = detail;
        }

        @Override
        public String toString() {
            return String.format("  %-26s %-24s %s", kind, behaviorId, detail);
        }
    }

    public static void main(String[] args) throws Exception {
        if (args.length < 2) {
            System.err.println("Usage: HandlerFormChecker <m2.yaml> <java-source-root>");
            System.exit(2);
        }
        Path m2 = Paths.get(args[0]);
        Path javaRoot = Paths.get(args[1]);

        // ── 读 M2：behaviorId → behaviorForm ──
        Map<String, String> forms = new TreeMap<>();
        Object root = load(m2);
        Object behaviorsObj = ((Map<?, ?>) root).get("behaviors");
        if (behaviorsObj instanceof List) {
            for (Object o : (List<?>) behaviorsObj) {
                if (!(o instanceof Map)) {
                    continue;
                }
                Map<?, ?> b = (Map<?, ?>) o;
                Object id = b.get("id");
                if (id == null) {
                    continue;
                }
                Object form = b.get("behaviorForm");
                forms.put(id.toString(),
                        form == null || form.toString().trim().isEmpty()
                                ? "HANDWRITTEN" : form.toString().trim());
            }
        }

        // ── 扫 Java 源码：behaviorId → (文件列表, 是否生成物) ──
        Map<String, List<Path>> impls = new TreeMap<>();
        Map<String, Boolean> fromGenerated = new TreeMap<>();
        Map<Path, String> fingerprints = new TreeMap<>();
        if (Files.isDirectory(javaRoot)) {
            try (java.util.stream.Stream<Path> s = Files.walk(javaRoot)) {
                List<Path> files = new ArrayList<>();
                s.filter(Files::isRegularFile)
                        .filter(p -> p.toString().endsWith(".java"))
                        .forEach(files::add);
                for (Path p : files) {
                    String src = new String(Files.readAllBytes(p), StandardCharsets.UTF_8);
                    Matcher m = HANDLER_ANN.matcher(src);
                    boolean isGen = src.contains(FINGERPRINT_MARK);
                    if (isGen) {
                        int idx = src.indexOf(FINGERPRINT_MARK);
                        int end = src.indexOf('\n', idx);
                        fingerprints.put(p, src.substring(idx + FINGERPRINT_MARK.length(),
                                end < 0 ? src.length() : end).trim());
                    }
                    while (m.find()) {
                        String id = m.group(1);
                        List<Path> l = impls.get(id);
                        if (l == null) {
                            l = new ArrayList<>();
                            impls.put(id, l);
                        }
                        l.add(p);
                        fromGenerated.put(id, isGen);
                    }
                }
            }
        }

        List<Drift> drifts = new ArrayList<>();
        int templateCount = 0;

        for (Map.Entry<String, String> e : forms.entrySet()) {
            String id = e.getKey();
            String form = e.getValue();
            List<Path> paths = impls.get(id);

            if ("TEMPLATE".equalsIgnoreCase(form)) {
                templateCount++;
                if (paths == null || paths.isEmpty()) {
                    drifts.add(new Drift("TEMPLATE 无实现", id,
                            "M2 声明 behaviorForm=TEMPLATE，但代码里没有 @BehaviorHandler(\"" + id + "\")"
                                    + " → 运行期会抛「没有实现」"));
                    continue;
                }
                if (paths.size() > 1) {
                    drifts.add(new Drift("TEMPLATE 多实现", id,
                            "存在 " + paths.size() + " 个实现：" + rel(paths, javaRoot)
                                    + " → 同一行为只能有一个 Handler"));
                }
                for (Path p : paths) {
                    if (!p.toString().contains(GENERATED_DIR)) {
                        drifts.add(new Drift("TEMPLATE 混入手写", id,
                                "生成物之外还有手写实现：" + rel(javaRoot.relativize(p))
                                        + " → 与生成物冲突，谁生效不确定"));
                    }
                }
                continue;
            }

            // 非 TEMPLATE：不应存在生成物实现
            if (paths != null) {
                for (Path p : paths) {
                    if (p.toString().contains(GENERATED_DIR)) {
                        drifts.add(new Drift("非模板却生成", id,
                                "M2 声明 behaviorForm=" + form
                                        + "，但存在生成物：" + rel(javaRoot.relativize(p))
                                        + " → 生成物与声明矛盾"));
                    }
                }
            }
        }

        // ── 生成物指纹是否与「去掉指纹行后的内容」一致 ──
        // 一致 = 开发者没改过；不一致 = 改过（此时构建期会跳过覆盖，需要人工同步）
        int modified = 0;
        for (Map.Entry<Path, String> e : fingerprints.entrySet()) {
            String src = new String(Files.readAllBytes(e.getKey()), StandardCharsets.UTF_8);
            String actual = HandlerGenerator.fingerprint(src);
            if (!actual.equals(e.getValue())) {
                modified++;
                drifts.add(new Drift("生成物已被修改", e.getKey().getFileName().toString(),
                        "内容指纹与记录不符（记录 " + e.getValue() + " / 实际 " + actual
                                + "）→ 模型变更不会自动同步进来，请人工核对"));
            }
        }

        System.out.println("=== HandlerForm 检查（行为形态三方对账）===");
        System.out.println("M2 声明行为总数           : " + forms.size());
        System.out.println("其中 TEMPLATE 形态        : " + templateCount);
        System.out.println("代码中 @BehaviorHandler 数 : " + impls.size());
        System.out.println("生成物文件数              : " + fingerprints.size());
        System.out.println("其中已被开发者修改        : " + modified);
        System.out.println("检出漂移                  : " + drifts.size());

        if (!drifts.isEmpty()) {
            System.out.println("\n--- [drift] 行为形态三方不一致 ---");
            drifts.forEach(d -> System.out.println(d.toString()));
            System.out.println("\n  修复：");
            System.out.println("    · TEMPLATE 无实现   → 执行 mvn clean install 触发生成；");
            System.out.println("    · TEMPLATE 多实现   → 删除多余实现（同一行为只能有一个 Handler）；");
            System.out.println("    · 生成物已被修改   → 人工把模型变更同步进生成物，或删除生成物后重新生成。");
        }
        System.exit(drifts.isEmpty() ? 0 : 1);
    }

    static String rel(List<Path> paths, Path root) {
        List<String> out = new ArrayList<>();
        for (Path p : paths) {
            out.add(rel(root.relativize(p)));
        }
        return out.toString();
    }

    static String rel(Path p) {
        return p.toString().replace('\\', '/');
    }

    static Object load(Path yaml) throws IOException {
        if (!Files.isRegularFile(yaml)) {
            return null;
        }
        try (InputStream in = Files.newInputStream(yaml)) {
            return new Yaml().load(in);
        }
    }
}
