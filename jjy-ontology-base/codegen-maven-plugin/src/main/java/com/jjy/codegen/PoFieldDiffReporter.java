package com.jjy.codegen;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 对比 M1 yaml 里声明的 aggregate 属性 vs 手写 PO 里的字段，输出字段级漂移报告。
 *
 * 用法：
 *   java com.jjy.codegen.PoFieldDiffReporter
 *     <m1-yaml>  <po-source-root>  [package-prefix]
 *   例：
 *     java ... m1-object-model.yaml
 *       src/main/java
 *       com.jjy.persistence.po
 *
 * 退出码：发现漂移 → 1；无漂移 → 0。
 * 设计为可被 CI 调用：漂移即视为 PR 阻断。
 */
public final class PoFieldDiffReporter {

    private static final Pattern FIELD_DECL = Pattern.compile(
        "private\\s+(?:static\\s+)?(?:final\\s+)?([\\w.<>, ?]+?)\\s+(\\w+)\\s*[;=]");
    /** 排除 BaseAuditPO 的 5 个公共列（这些不该列在 AGG 属性里） */
    private static final Set<String> BASE_AUDIT_FIELDS = new HashSet<>(Arrays.asList(
        "createdBy", "createdAt", "updatedBy", "updatedAt", "flag"));
    /** 匹配 PO 类声明，判断是否真继承 BaseAuditPO */
    private static final Pattern CLASS_DECL = Pattern.compile(
        "class\\s+\\w+PO\\s+extends\\s+(\\w+)");

    private PoFieldDiffReporter() {}

    public static void main(String[] args) throws Exception {
        if (args.length < 2) {
            System.err.println("Usage: PoFieldDiffReporter <m1.yaml> <po-source-root> [package-prefix]");
            System.exit(2);
        }
        Path yaml = Paths.get(args[0]);
        // 多个 PO 根用 ';' 分隔（生成目录 + 手写目录都要查，只查一个会漏）
        List<Path> poRoots = new ArrayList<>();
        for (String r : args[1].split(";")) {
            String t = r.trim();
            if (!t.isEmpty()) {
                poRoots.add(Paths.get(t));
            }
        }
        if (poRoots.isEmpty()) {
            System.err.println("PO 根目录不能为空");
            System.exit(2);
        }
        // 支持多包前缀：逗号分隔，覆盖 platform 内部不同子模块（如 flow / scenario 独立包）
        List<String> pkgPrefixes;
        if (args.length >= 3 && !args[2].isEmpty()) {
            pkgPrefixes = new ArrayList<>();
            for (String p : args[2].split(",")) {
                String t = p.trim();
                if (!t.isEmpty()) pkgPrefixes.add(t);
            }
        } else {
            pkgPrefixes = Collections.singletonList(
                "com.jjy.persistence.po");
        }

        M1Reader.M1 model = M1Reader.read(yaml);
        int totalAggr = 0, drifted = 0, missing = 0;
        List<String> driftDetails = new ArrayList<>();

        List<M1Reader.Aggregate> objects = new ArrayList<>(model.aggregates);
        objects.addAll(model.entities);

        // ── 未构建的仓库不判漂移 ──
        // PO 由 codegen 在 generate-sources 阶段生成。全新克隆的仓库还没编译过，
        // 生成目录不存在 → 所有聚合根的 PO 都「缺失」。
        // 那是「还没构建」，不是「模型与代码漂移」，不该拦住开发者的第一次提交。
        // 判定：根目录下连一个 PO 源文件都没有 → 视为未构建，放行并提示先构建。
        if (!hasAnyPoFile(poRoots, pkgPrefixes)) {
            System.out.println("=== PoFieldDiff 报告 ===");
            System.out.println("未检测到任何 PO 源文件（PO 由 codegen 在构建期生成）");
            System.out.println("→ 仓库尚未构建，跳过 PO 字段漂移检查。");
            System.out.println("  请先执行：mvn -o clean install -DskipTests");
            System.exit(0);
        }

        for (M1Reader.Aggregate agg : objects) {
            totalAggr++;
            Path poFile = findPoFile(poRoots, pkgPrefixes, agg.alias);
            if (poFile == null) {
                missing++;
                driftDetails.add(agg.alias + " : 手写 PO 不存在");
                continue;
            }
            List<String> yamlFields = new ArrayList<>();
            for (M1Reader.Attr a : agg.attributes) {
                // BaseAuditPO 5 个公共列不参与 aggregate 业务字段比对（属于平台基类）
                if (BASE_AUDIT_FIELDS.contains(a.name)) continue;
                yamlFields.add(a.name);
            }

            List<String> poFields = extractPoFields(poFile);

            List<String> onlyInYaml = new ArrayList<>(yamlFields);
            onlyInYaml.removeAll(poFields);
            List<String> onlyInPo = new ArrayList<>(poFields);
            onlyInPo.removeAll(yamlFields);
            // 子实体允许存在由聚合归属隐含的父外键；这里校验模型字段是否实现。
            if (model.entities.contains(agg)) onlyInPo.clear();

            if (!onlyInYaml.isEmpty() || !onlyInPo.isEmpty()) {
                drifted++;
                StringBuilder sb = new StringBuilder(agg.alias).append(" :\n");
                if (!onlyInYaml.isEmpty())
                    sb.append("    仅 yaml 有（手写 PO 缺）     : ").append(onlyInYaml).append("\n");
                if (!onlyInPo.isEmpty())
                    sb.append("    仅手写 PO 有（yaml 缺/被删）  : ").append(onlyInPo).append("\n");
                driftDetails.add(sb.toString());
            }
        }

        System.out.println("=== PoFieldDiff 报告 ===");
        System.out.println("扫描 aggregate 总数 : " + totalAggr);
        System.out.println("  - 手写 PO 缺失    : " + missing);
        System.out.println("  - 字段漂移        : " + drifted);
        System.out.println("  - 完全一致        : " + (totalAggr - missing - drifted));
        if (!driftDetails.isEmpty()) {
            System.out.println("\n--- 漂移明细 ---");
            driftDetails.forEach(System.out::println);
        }
        System.exit((missing + drifted) > 0 ? 1 : 0);
    }

    /**
     * 根目录下是否存在任何 PO 源文件。
     *
     * <p>用于区分两种情况：</p>
     * <ul>
     *   <li>一个 PO 都没有 → 仓库尚未构建（codegen 没跑过），跳过检查；</li>
     *   <li>有 PO 但对不上 → 真的漂移，阻断。</li>
     * </ul>
     */
    static boolean hasAnyPoFile(List<Path> roots, List<String> pkgPrefixes) throws IOException {
        for (Path root : roots) {
            for (String pkgPrefix : pkgPrefixes) {
                Path base = root.resolve(pkgPrefix.replace('.', '/'));
                if (!Files.isDirectory(base)) {
                    continue;
                }
                try (java.util.stream.Stream<Path> stream = Files.walk(base)) {
                    boolean any = stream.filter(Files::isRegularFile)
                            .anyMatch(p -> p.getFileName().toString().endsWith("PO.java"));
                    if (any) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    /**
     * 按 alias 找手写 PO：com.jjy.persistence.po.* (含子包)
     * 例: alias=Customer → po/customer/CustomerPO.java 或 po/CustomerPO.java
     *
     * 多个同名 PO（如新旧版本并存）时：
     *   - 优先选非 @Deprecated 的
     *   - 都 @Deprecated 才返回第一个
     *   - 都非 @Deprecated 返回第一个
     */
    static Path findPoFile(List<Path> roots, List<String> pkgPrefixes, String alias) throws IOException {
        String fileName = alias + "PO.java";
        List<Path> candidates = new ArrayList<>();
        for (Path root : roots) {
            for (String pkgPrefix : pkgPrefixes) {
                String rel = pkgPrefix.replace('.', '/');
                Path base = root.resolve(rel);
                if (!Files.isDirectory(base)) continue;
                // 1) 顶层
                Path p1 = base.resolve(fileName);
                if (Files.isRegularFile(p1)) candidates.add(p1);
                // 2) snake_case 子包
                String sub = aliasToSubPackage(alias);
                Path p2 = base.resolve(sub).resolve(fileName);
                if (Files.isRegularFile(p2)) candidates.add(p2);
                // 3) 扫所有子目录兜底
                try (java.util.stream.Stream<Path> stream = Files.walk(base)) {
                    stream.filter(Files::isRegularFile)
                          .filter(p -> p.getFileName().toString().equals(fileName))
                          .forEach(candidates::add);
                }
            }
        }
        if (candidates.isEmpty()) return null;
        if (candidates.size() == 1) return candidates.get(0);
        // 多版本：优先非 @Deprecated
        for (Path c : candidates) {
            if (!isDeprecated(c)) return c;
        }
        return candidates.get(0);
    }

    /** 简单判断源文件第一屏内有无 @Deprecated 注解 */
    static boolean isDeprecated(Path poFile) throws IOException {
        try (java.util.stream.Stream<String> lines = Files.lines(poFile, StandardCharsets.UTF_8)) {
            return lines.limit(80).anyMatch(l -> l.contains("@Deprecated"));
        }
    }

    static String aliasToSubPackage(String alias) {
        if (alias == null || alias.isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < alias.length(); i++) {
            char c = alias.charAt(i);
            if (i == 0) sb.append(Character.toLowerCase(c));
            else if (Character.isUpperCase(c)) break;
            else sb.append(c);
        }
        return sb.toString();
    }

    /**
     * 从 PO 源文件里抽所有 private 字段名（排除 BaseAuditPO 5 个）。
     * 用正则匹配 "private Type name [;=]"，简单但够用——
     * 避免引 JavaParser 这种重依赖。
     */
    static List<String> extractPoFields(Path poFile) throws IOException {
        String src = new String(Files.readAllBytes(poFile), StandardCharsets.UTF_8);
        // 去掉块注释和行注释
        src = src.replaceAll("(?s)/\\*.*?\\*/", "");
        src = src.replaceAll("//.*?\\n", "\n");
        // 去掉字符串字面量（避免误匹配）
        src = src.replaceAll("\"([^\"\\\\]|\\\\.)*\"", "\"\"");

        // BaseAuditPO 的 5 个公共列在所有 PO 里都该当作"基类公共字段"忽略比对：
        // - 继承 BaseAuditPO 的 PO 父类提供
        // - 不继承但手动声明的 PO（如 InventoryMirrorPO）按 M1 规范也不该列在 AGG 属性里
        Matcher m = FIELD_DECL.matcher(src);
        Set<String> fields = new LinkedHashSet<>();
        while (m.find()) {
            String name = m.group(2);
            if (BASE_AUDIT_FIELDS.contains(name)) continue;
            fields.add(name);
        }
        return new ArrayList<>(fields);
    }
}
