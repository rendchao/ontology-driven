package com.jjy.codegen;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * 独立 main 入口（不依赖 maven 运行时）。
 * 用法：java com.jjy.codegen.Main &lt;m1Yaml&gt; &lt;mmYaml&gt; &lt;outDir&gt; &lt;ddlDir&gt;
 *
 * <p>生成聚合根 PO + 子实体 PO，以及目标态 DDL。
 * MM 是表名/列名/列类型的唯一真源，M1 提供字段语义。</p>
 */
public final class Main {
    private Main() {}

    public static void main(String[] args) throws Exception {
        if (args.length < 4) {
            System.err.println("Usage: Main <m1Yaml> <mmYaml> <outDir> <ddlDir>");
            System.exit(1);
        }
        String m1Path = args[0];
        String mmPath = args[1];
        String outDir = args[2];
        String ddlOutDir = args[3];

        M1Reader.M1 m1 = M1Reader.read(Paths.get(m1Path));
        MmReader.Mm mm = MmReader.read(Paths.get(mmPath));
        // 注入给 PoMapping / PoTemplate：表名与列名以 MM 为准
        PoMapping.setMm(mm);

        Files.createDirectories(Paths.get(outDir));
        Files.createDirectories(Paths.get(ddlOutDir));

        int javaCount = 0, sqlCount = 0;
        for (M1Reader.Aggregate agg : m1.aggregates) {
            String sub = PoMapping.subPackage(agg.alias);
            String clsName = agg.alias + "PO";
            // 按完整包路径落盘，与 Java 源码目录约定一致
            Path javaOut = Paths.get(outDir,
                    PoMapping.packageName(agg.alias).replace('.', '/'), clsName + ".java");
            Files.createDirectories(javaOut.getParent());
            Files.write(javaOut, PoTemplate.render(agg).getBytes(StandardCharsets.UTF_8));
            javaCount++;
            MmReader.Table table = mm.tableOf(agg.alias);
            if (table != null) {
                Path sqlOut = Paths.get(ddlOutDir, sub + "__" + clsName + "__target.sql");
                Files.write(sqlOut, DdlTemplate.render(agg, table).getBytes(StandardCharsets.UTF_8));
                sqlCount++;
            }
        }
        // 子实体 PO（明细行 / 联系人 / 轨迹等）
        for (M1Reader.Aggregate ent : m1.entities) {
            String sub = PoMapping.subPackage(ent.alias);
            String clsName = ent.alias + "PO";
            Path javaOut = Paths.get(outDir,
                    PoMapping.packageName(ent.alias).replace('.', '/'), clsName + ".java");
            Files.createDirectories(javaOut.getParent());
            Files.write(javaOut, PoTemplate.render(ent).getBytes(StandardCharsets.UTF_8));
            javaCount++;
            MmReader.Table table = mm.tableOf(ent.alias);
            if (table != null) {
                Path sqlOut = Paths.get(ddlOutDir, sub + "__" + clsName + "__target.sql");
                Files.write(sqlOut, DdlTemplate.render(ent, table).getBytes(StandardCharsets.UTF_8));
                sqlCount++;
            }
        }
        // 技术表 DDL（无 M1 对应节点，如 event_outbox / event_handler_log）
        for (MmReader.Table t : mm.technicalTables()) {
            Path sqlOut = Paths.get(ddlOutDir, t.tableName + "__target.sql");
            Files.write(sqlOut, DdlTemplate.renderTechnical(t).getBytes(StandardCharsets.UTF_8));
            sqlCount++;
        }
        System.out.println("[jjy-codegen] generated " + javaCount + " PO to " + outDir);
        System.out.println("[jjy-codegen] generated " + sqlCount + " DDL to " + ddlOutDir);
        System.out.println("[jjy-codegen] source: " + m1Path + " + " + mmPath);
    }
}
