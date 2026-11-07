package com.jjy.codegen;

import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * M2 模板行为 → Handler 骨架生成（goal: {@code generate-handlers}）。
 *
 * <p>与 {@link CodegenMojo}（生成 PO/DDL）分开：
 * 前者产出到 {@code target/}（构建产物，不进版本库）；
 * 本者产出到 {@code src/}（生成物进版本库，开发者可直接改）。</p>
 *
 * <p><b>为什么生成物进版本库</b>：模板行为生成的是「可运行但需要业务微调」
 * 的骨架——开发者会直接改它。把它放在 {@code target/} 里，
 * 开发者改的东西下次构建就没了；而放在 {@code src/} 又有「模型改了会不会
 * 冲掉我的修改」的顾虑。本 Mojo 用<b>内容指纹</b>解决后者：
 * 改过的文件不会被覆盖，只会被守门员报出「与模型不一致」。</p>
 *
 * <p>用法（Maven）：</p>
 * <pre>
 *   &lt;plugin&gt;
 *     &lt;groupId&gt;com.jjy&lt;/groupId&gt;
 *     &lt;artifactId&gt;codegen-maven-plugin&lt;/artifactId&gt;
 *     &lt;version&gt;1.0.0&lt;/version&gt;
 *     &lt;executions&gt;
 *       &lt;execution&gt;
 *         &lt;goals&gt;&lt;goal&gt;generate-handlers&lt;/goal&gt;&lt;/goals&gt;
 *       &lt;/execution&gt;
 *     &lt;/executions&gt;
 *     &lt;configuration&gt;
 *       &lt;m2Yaml&gt;../models/m2-behavior-model.yaml&lt;/m2Yaml&gt;
 *       &lt;m1Yaml&gt;../models/m1-object-model.yaml&lt;/m1Yaml&gt;
 *       &lt;srcDir&gt;src/main/java&lt;/srcDir&gt;
 *       &lt;handlerRootPkg&gt;com.jjy.application.handler&lt;/handlerRootPkg&gt;
 *     &lt;/configuration&gt;
 *   &lt;/plugin&gt;
 * </pre>
 */
@Mojo(name = "generate-handlers", defaultPhase = LifecyclePhase.GENERATE_SOURCES, threadSafe = true)
public class HandlerGenMojo extends AbstractMojo {

    /** M2 行为模型 yaml 路径（相对 project.basedir） */
    @Parameter(defaultValue = "../models/m2-behavior-model.yaml", property = "m2Yaml")
    private String m2Yaml;

    /** M1 对象模型 yaml 路径（相对 project.basedir），用于取真实主键字段 */
    @Parameter(defaultValue = "../models/m1-object-model.yaml", property = "m1Yaml")
    private String m1Yaml;

    /** 生成物落盘的源码根（相对 project.basedir） */
    @Parameter(defaultValue = "src/main/java", property = "srcDir")
    private String srcDir;

    /** 生成物的根包名 */
    @Parameter(defaultValue = "com.jjy.application.handler", property = "handlerRootPkg")
    private String handlerRootPkg;

    /** 本模块的 basedir（Maven 注入） */
    @Parameter(defaultValue = "${project.basedir}", readonly = true, required = true)
    private java.io.File projectBasedir;

    @Override
    public void execute() throws MojoExecutionException {
        Path baseDir = this.projectBasedir != null
                ? this.projectBasedir.toPath()
                : Paths.get(System.getProperty("user.dir"));

        Path m2File = baseDir.resolve(m2Yaml).normalize();
        if (!Files.isRegularFile(m2File)) {
            getLog().warn("[handler-gen] M2 yaml 不存在，跳过: " + m2File);
            return;
        }
        Path m1File = baseDir.resolve(m1Yaml).normalize();
        if (!Files.isRegularFile(m1File)) {
            getLog().warn("[handler-gen] M1 yaml 不存在，跳过: " + m1File);
            return;
        }

        M2Reader.M2 m2;
        M1Reader.M1 m1;
        try {
            m2 = M2Reader.read(m2File);
            m1 = M1Reader.read(m1File);
        } catch (IOException e) {
            throw new MojoExecutionException("读 M1/M2 失败", e);
        }

        int templateCount = m2.templateBehaviors().size();
        if (templateCount == 0) {
            getLog().info("[handler-gen] M2 中无 behaviorForm=TEMPLATE 的行为，跳过");
            return;
        }

        Path srcRoot = baseDir.resolve(srcDir).normalize();
        try {
            Files.createDirectories(srcRoot);
            HandlerGenerator.Result r = HandlerGenerator.generate(m2, m1, srcRoot, handlerRootPkg);
            getLog().info("[handler-gen] 模板行为 " + templateCount + " 个 → "
                    + "新建 " + r.created + " / 刷新 " + r.refreshed + " / 跳过 " + r.skipped);
            for (String f : r.skippedFiles) {
                getLog().warn("[handler-gen] 已被开发者修改，保留不覆盖: " + f);
                getLog().warn("           若模型已变更，请人工同步该文件（或删除后重新生成）。");
            }
        } catch (IOException e) {
            throw new MojoExecutionException("生成 Handler 失败", e);
        }
    }
}
