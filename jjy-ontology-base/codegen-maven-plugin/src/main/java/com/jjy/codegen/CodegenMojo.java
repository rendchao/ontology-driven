package com.jjy.codegen;

import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

/**
 * codegen 插件主类。
 *
 * <p>读取 models/m1-object-model.yaml + models/mm-mapping-model.yaml，
 * 为每个聚合根与子实体生成 PO Java 源与目标态 DDL：
 * <pre>
 *   outputDir/&lt;poRootPkg&gt;/&lt;sub&gt;/&lt;Name&gt;PO.java
 * </pre>
 * 生成物加入 Maven compile source root，随主源码一起编译。
 *
 * <p>包名由 {@code poRootPkg} 参数决定（默认 {@code com.jjy.persistence.po}），
 * 子包名、主键、是否继承基类来自 {@link PoMapping} 的工程约定表。
 * </p>
 *
 * <p>两种调用方式：
 * <ol>
 *   <li>Maven 插件：{@code mvn compile}（绑 generate-sources 阶段）</li>
 *   <li>独立 main：{@code java -cp ... com.jjy.codegen.Main <m1Yaml> <mmYaml> <outDir> <ddlDir>}</li>
 * </ol>
 */
@Mojo(name = "generate", defaultPhase = LifecyclePhase.GENERATE_SOURCES, threadSafe = true)
public class CodegenMojo extends AbstractMojo {

    /**
     * 独立 main 入口（避开 maven-plugin-plugin 依赖）。
     * 用法：java ... com.jjy.codegen.Main <m1Yaml> <mmYaml> <outputDir> <ddlDir>
     */
    public static void main(String[] args) throws Exception {
        if (args.length < 4) {
            System.err.println("Usage: Main <m1Yaml> <mmYaml> <outDir> <ddlDir>");
            System.exit(1);
        }
        M1Reader.M1 m1 = M1Reader.read(Paths.get(args[0]));
        MmReader.Mm mm = MmReader.read(Paths.get(args[1]));
        Path outDir = Paths.get(args[2]);
        Path ddlOutDir = Paths.get(args[3]);
        Files.createDirectories(outDir);
        Files.createDirectories(ddlOutDir);
        int javaCount = generate(m1, mm, outDir, ddlOutDir);
        System.out.println("[codegen] generated " + javaCount + " PO to " + outDir);
        System.out.println("[codegen] source: " + args[0] + " + " + args[1]);
    }

    /** 生成所有 PO（聚合根 + 子实体），返回生成数量 */
    static int generate(M1Reader.M1 m1, MmReader.Mm mm, Path outDir, Path ddlOutDir) throws IOException {
        int count = 0;
        for (M1Reader.Aggregate agg : m1.aggregates) {
            writePo(agg, outDir);
            writeDdl(agg, mm, ddlOutDir);
            count++;
        }
        for (M1Reader.Aggregate ent : m1.entities) {
            writePo(ent, outDir);
            writeDdl(ent, mm, ddlOutDir);
            count++;
        }
        // 技术表（无 M1 对应节点）：DDL 全部来自 MM
        for (MmReader.Table t : mm.technicalTables()) {
            writeTechnicalDdl(t, ddlOutDir);
        }
        return count;
    }

    private static void writePo(M1Reader.Aggregate agg, Path outDir) throws IOException {
        // 按完整包路径落盘（<outputDir>/com/jjy/platform/.../po/<sub>/XxxPO.java），
        // 与 Java 源码目录约定一致 —— IDE 索引、漂移检查器都能按包路径定位
        String pkg = PoMapping.packageName(agg.alias);
        Path dir = outDir.resolve(pkg.replace('.', '/'));
        Files.createDirectories(dir);
        Path out = dir.resolve(agg.alias + "PO.java");
        Files.write(out, PoTemplate.render(agg).getBytes(StandardCharsets.UTF_8));
    }

    private static void writeDdl(M1Reader.Aggregate agg, MmReader.Mm mm, Path ddlOutDir) throws IOException {
        String sub = PoMapping.subPackage(agg.alias);
        Files.createDirectories(ddlOutDir);
        MmReader.Table table = mm.tableOf(agg.alias);
        if (table == null) {
            throw new IOException("MM 中找不到实体对应的表: " + agg.alias);
        }
        Path out = ddlOutDir.resolve(sub + "__" + agg.alias + "PO__target.sql");
        Files.write(out, DdlTemplate.render(agg, table).getBytes(StandardCharsets.UTF_8));
    }

    /**
     * 技术表 DDL（无 M1 对应节点，如 event_outbox / event_handler_log）。
     * 列完全来自 MM，MM 里没登记的列不会出现在 DDL 里。
     */
    private static void writeTechnicalDdl(MmReader.Table t, Path ddlOutDir) throws IOException {
        Files.createDirectories(ddlOutDir);
        Path out = ddlOutDir.resolve(t.tableName + "__target.sql");
        Files.write(out, DdlTemplate.renderTechnical(t).getBytes(StandardCharsets.UTF_8));
    }

    /**
     * M1 yaml 路径（相对 project.basedir）。
     * 默认：models/m1-object-model.yaml
     */
    @Parameter(defaultValue = "models/m1-object-model.yaml", property = "m1Yaml")
    private String m1Yaml;

    /**
     * MM 映射模型 yaml 路径（相对 project.basedir）。
     * 默认：models/mm-mapping-model.yaml —— 表名/列名/列类型的唯一真源。
     */
    @Parameter(defaultValue = "models/mm-mapping-model.yaml", property = "mmYaml")
    private String mmYaml;

    /**
     * 生成的 Java 源根目录（相对 project.basedir）。
     * 生成物会按包路径落到其下：{@code <outputDir>/<poRootPkg 转斜杠>/<sub>/}
     */
    @Parameter(defaultValue = "target/generated-sources/po", property = "outputDir")
    private String outputDir;

    /**
     * 生成的 PO 所在的根包名。
     * 底座默认 {@code com.jjy.persistence.po}；
     * 业务项目可改为自己的包（如 {@code com.mycorp.app.persistence.po}）。
     */
    @Parameter(defaultValue = "com.jjy.infrastructure.persistence.po", property = "poRootPkg")
    private String poRootPkg;

    /** 生成的 DDL 输出目录（相对 project.basedir）。 */
    @Parameter(defaultValue = "target/generated-sources/ddl", property = "ddlDir")
    private String ddlDir;

    /** 本模块的 basedir（Maven 注入）。 */
    @Parameter(defaultValue = "${project.basedir}", readonly = true, required = true)
    private java.io.File projectBasedir;

    @Override
    public void execute() throws MojoExecutionException {
        // 用 Maven 注入的 basedir（Maven 的 user.dir 是执行目录，不一定是本模块目录）
        Path baseDir = this.projectBasedir != null
                ? this.projectBasedir.toPath()
                : Paths.get(System.getProperty("user.dir"));

        // 包根可配：底座默认包，业务项目改成本项目自己的包
        if (poRootPkg != null && !poRootPkg.trim().isEmpty()) {
            PoMapping.setPoRootPkg(poRootPkg.trim());
        }

        Path m1File = baseDir.resolve(m1Yaml).normalize();
        if (!Files.isRegularFile(m1File)) {
            getLog().warn("[codegen] M1 yaml 不存在，跳过: " + m1File);
            return;
        }

        M1Reader.M1 m1;
        try {
            m1 = M1Reader.read(m1File);
        } catch (IOException e) {
            throw new MojoExecutionException("读 M1 失败: " + m1File, e);
        }

        // MM 是表名/列名/列类型的唯一真源；缺失时回退到默认命名规则
        Path mmFile = baseDir.resolve(mmYaml).normalize();
        MmReader.Mm mm = null;
        if (Files.isRegularFile(mmFile)) {
            try {
                mm = MmReader.read(mmFile);
                PoMapping.setMm(mm);
                getLog().info("[codegen] MM 映射真源: " + mmFile);
            } catch (IOException e) {
                throw new MojoExecutionException("读 MM 失败: " + mmFile, e);
            }
        } else {
            getLog().warn("[codegen] MM 不存在，回退默认命名: " + mmFile);
        }
        if (mm == null) {
            mm = new MmReader.Mm();
        }

        Path outDir = baseDir.resolve(outputDir);
        Path ddlOutput = baseDir.resolve(ddlDir);
        try {
            // ── 构建期漂移门禁 ──
            // M1 与 MM 必须闭合：M1 声明了字段但 MM 没映射 → 生成的 DDL 会漏列，
            // 表现为「建表成功、运行时报 Unknown column」，极难排查。
            // 这里在构建期就阻断，不依赖开发者是否装了 git 钩子。
            List<String> mappingDrift = checkMappingClosure(m1, mm);
            if (!mappingDrift.isEmpty()) {
                getLog().error("[codegen] M1 ↔ MM 映射不闭合，构建中止：");
                for (String d : mappingDrift) {
                    getLog().error("  " + d);
                }
                getLog().error("  修复：把缺失字段补进 " + mmYaml
                        + " 的 columnMappings，或从 M1 移除该字段。");
                throw new MojoExecutionException("M1 与 MM 映射契约不闭合，拒绝生成 PO/DDL");
            }

            cleanOld(outDir);
            Files.createDirectories(outDir);
            Files.createDirectories(ddlOutput);
            int count = generate(m1, mm, outDir, ddlOutput);
            getLog().info("[codegen] 生成 " + count + " 个 PO 到 " + outDir);
        } catch (IOException e) {
            throw new MojoExecutionException("生成 PO 失败", e);
        }
    }

    /**
     * 构建期 M1 ↔ MM 闭合校验。
     *
     * <p>检查两类漂移：</p>
     * <ul>
     *   <li><b>M1 有字段、MM 未映射</b> → DDL 会漏列，运行期报 Unknown column</li>
     *   <li><b>MM 映射了 M1 已不存在的字段</b> → 悬空映射</li>
     * </ul>
     *
     * @return 漂移描述列表；空列表表示闭合
     */
    private List<String> checkMappingClosure(M1Reader.M1 m1, MmReader.Mm mm) {
        List<String> drifts = new ArrayList<>();
        Map<String, Set<String>> m1Fields = new LinkedHashMap<>();
        for (M1Reader.Aggregate agg : m1.aggregates) {
            m1Fields.put(agg.alias, fieldNames(agg));
        }
        for (M1Reader.Aggregate ent : m1.entities) {
            m1Fields.put(ent.alias, fieldNames(ent));
        }
        for (Map.Entry<String, Set<String>> e : m1Fields.entrySet()) {
            List<MmReader.Table> tables = mm.tablesOf(e.getKey());
            if (tables.isEmpty()) {
                drifts.add(e.getKey() + " : M1 有该实体但 MM 未声明映射（DDL 不会生成）");
                continue;
            }
            // 一个实体可能对应多张表（MAIN + DETAIL），任一张表映射了即可
            Set<String> mmFields = new LinkedHashSet<>();
            for (MmReader.Table t : tables) {
                for (MmReader.Column c : t.columns) {
                    if (c.fieldName != null && !c.fieldName.isEmpty()) {
                        mmFields.add(c.fieldName);
                    } else if (c.fieldPath != null) {
                        int dot = c.fieldPath.indexOf('.');
                        mmFields.add(dot >= 0 ? c.fieldPath.substring(dot + 1) : c.fieldPath);
                    }
                }
            }
            for (String f : e.getValue()) {
                if (!mmFields.contains(f)) {
                    drifts.add(e.getKey() + "." + f
                            + " : M1 声明了该字段但 MM 未映射（DDL 会漏此列）");
                }
            }
        }
        return drifts;
    }

    /** 取聚合根/子实体的业务字段名集合（排除平台基类公共列） */
    private Set<String> fieldNames(M1Reader.Aggregate agg) {
        Set<String> out = new LinkedHashSet<>();
        for (M1Reader.Attr a : agg.attributes) {
            if (a.name == null) continue;
            out.add(a.name);
        }
        return out;
    }

    /** 清掉上次的生成物，避免模型删字段后残留旧类 */
    private void cleanOld(Path outDir) throws IOException {
        if (!Files.isDirectory(outDir)) return;
        try (Stream<Path> stream = Files.walk(outDir)) {
            List<Path> files = new ArrayList<>();
            stream.filter(Files::isRegularFile).forEach(files::add);
            files.sort(Comparator.reverseOrder());
            for (Path f : files) {
                Files.deleteIfExists(f);
            }
        }
    }
}
