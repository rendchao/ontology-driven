package com.jjy.codegen;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;

/**
 * M2 模板行为 → Handler 骨架生成器。
 *
 * <p><b>生成什么</b>：M2 中声明 {@code behaviorForm=TEMPLATE} 的行为，
 * 由 {@link BehaviorHandlerTemplate} 产出可编译、可运行的 Handler。</p>
 *
 * <p><b>落在哪</b>：{@code src/main/java/<rootPkg>/generated/<alias 子包>/}。
 * 生成物<b>进版本库</b>——开发者可以直接改它，不必另建类。</p>
 *
 * <p><b>怎么保护开发者的修改</b>：生成物里写入一行指纹注释
 * （生成内容的 SHA-256 前 16 位）。再次生成时：</p>
 * <ul>
 *   <li>文件不存在 → 生成；</li>
 *   <li>文件存在且指纹与<b>上次生成内容</b>一致 → 说明开发者没改 → 可安全覆盖；</li>
 *   <li>文件存在但指纹不符 → 说明开发者改过 → <b>跳过，不覆盖</b>，
 *       并输出提示（让开发者自己决定是否同步模型）。</li>
 * </ul>
 * <p>这就是「生成物可手改」与「模型是唯一真源」之间的平衡点：
 * 手改不会被冲掉，但模型改动也<b>不会</b>自动同步进已改动的文件——
 * 这种情况下由守门员第 10 项报出「生成物与模型不一致」，由人决定。</p>
 */
public final class HandlerGenerator {

    private HandlerGenerator() {
    }

    /**
     * 指纹标记：写在生成物头部，用于判定「是否被开发者改过」。
     *
     * <p><b>不能用 {@code @} 开头</b>：javadoc 里 {@code @xxx} 会被解析成
     * 块标签，{@code @generated-signature} 不是合法标签 →
     * 编译器报「注释不在语句上」。纯文本标记没有这个问题。</p>
     */
    static final String FINGERPRINT_MARK = "generated-signature:";

    /** 生成结果统计 */
    public static final class Result {
        public int created;
        public int refreshed;
        public int skipped;
        public final List<String> skippedFiles = new ArrayList<>();

        public int total() {
            return created + refreshed + skipped;
        }
    }

    /**
     * 生成 Handler 骨架。
     *
     * @param m2       M2 行为模型
     * @param m1       M1 对象模型（用于取真实主键字段）
     * @param srcRoot  源码根目录（生成物的落盘根）
     * @param pkgRoot  生成物的根包名
     * @return 生成统计
     */
    public static Result generate(M2Reader.M2 m2, M1Reader.M1 m1,
                                  Path srcRoot, String pkgRoot) throws IOException {
        Result r = new Result();
        for (M2Reader.Behavior b : m2.templateBehaviors()) {
            String alias = b.alias();
            String pkField = BehaviorHandlerTemplate.pkFieldOf(m1, alias);
            List<String> updatable = BehaviorHandlerTemplate.updatableFieldsOf(m1, alias, pkField);
            String content = BehaviorHandlerTemplate.render(b, pkgRoot, pkField, updatable);
            // 注入公共辅助方法（取 dto 的兼容写法）
            content = injectHelper(content);
            // 指纹签的是「未含指纹行」的内容 → 与 insertFingerprint 写入的指纹自洽
            String sig = fingerprint(content);

            String pkgPath = pkgRoot.replace('.', '/') + "/generated/"
                    + BehaviorHandlerTemplate.subPackage(alias);
            Path dir = srcRoot.resolve(pkgPath);
            Path file = dir.resolve(b.id + "Handler.java");

            // 指纹 = 「去掉指纹行本身」的内容摘要。
            // 这样文件里存着 sig，sig 又是文件其余部分的摘要——自洽。
            String withSig = insertFingerprint(content, sig);

            if (Files.exists(file)) {
                String existing = new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
                String storedSig = readFingerprint(existing);
                // 指纹是「内容的摘要」，不是内容的副本：
                // 取文件里去掉指纹行后的实际内容，重新算摘要，与文件里存的指纹比。
                //   相等 → 开发者没改过这个文件（内容与上次生成完全一致）→ 可安全刷新；
                //   不等 → 开发者改过 → 保留，不覆盖。
                String actualSig = fingerprint(existing);
                if (storedSig != null && storedSig.equals(actualSig)) {
                    // 未被改动 → 安全刷新（让模型变更同步进来）
                    write(file, withSig);
                    r.refreshed++;
                } else {
                    // 已被开发者改动（或指纹缺失/不匹配）→ 保留，不覆盖
                    r.skipped++;
                    r.skippedFiles.add(file.toString());
                }
            } else {
                Files.createDirectories(dir);
                write(file, withSig);
                r.created++;
            }
        }
        return r;
    }

    /**
     * 注入公共辅助方法 {@code extractDto}。
     *
     * <p>放在类体的最后一个方法之后——所有模板都要用它取请求参数，
     * 放在模板里重复生成会让生成物冗余。</p>
     */
    static String injectHelper(String content) {
        String helper = BehaviorHandlerTemplate.EXTRACT_DTO_HELPER;
        // 插到类的最后一个 "}" 之前
        int idx = content.lastIndexOf("}");
        if (idx < 0) {
            return content;
        }
        return content.substring(0, idx) + helper + content.substring(idx);
    }

    /**
     * 在生成内容里插入指纹标记。
     *
     * <p>放在类 javadoc 内（{@code @author} 之前）——既不干扰代码语义，
     * 又能在文件被改后明显地提示「此文件已被修改」。</p>
     *
     * <p><b>指纹的语义</b>：签的是<b>除指纹行以外</b>的全文。
     * 因此「内容没变 → 指纹不变 → 可安全覆盖」，
     * 「内容被改 → 指纹对不上 → 保留」。</p>
     */
    static String insertFingerprint(String content, String sig) {
        String marker = " * " + FINGERPRINT_MARK + sig + "\n";
        // 必须插在 javadoc 结束符（*/）之前 —— 插在之后会破坏注释结构，
        // 表现为「注释不在语句上」的编译错误。
        int idx = content.indexOf(" * @author");
        if (idx < 0) {
            idx = content.indexOf(" */");
            if (idx < 0) {
                return content;
            }
            return content.substring(0, idx) + marker + content.substring(idx);
        }
        return content.substring(0, idx) + marker + content.substring(idx);
    }

    /**
     * 计算「除指纹行外」的全文摘要。
     *
     * <p>把指纹行剔除后再摘要——否则指纹会自我包含，无法稳定比较。</p>
     */
    static String fingerprint(String content) {
        return sha256(stripFingerprint(content));
    }

    /** 剔除指纹行 */
    static String stripFingerprint(String content) {
        int idx = content.indexOf(FINGERPRINT_MARK);
        if (idx < 0) {
            return content;
        }
        int lineStart = content.lastIndexOf('\n', idx) + 1;
        int lineEnd = content.indexOf('\n', idx);
        if (lineEnd < 0) {
            lineEnd = content.length();
        } else {
            lineEnd = lineEnd + 1;
        }
        return content.substring(0, lineStart) + content.substring(lineEnd);
    }

    /*
     * 指纹行必须位于 javadoc 内部（结束符之前）。
     *
     * 这是本实现踩过的坑：指纹最初被插在 javadoc 结束符之后，
     * 生成的 Java 报「注释不在语句上」——而且因为该文件语法错误，
     * Lombok 注解处理器连带失效，整个模块几十个 log 全部报「找不到符号」。
     * 一个生成器的语法错误能拖垮整个编译。
     */

    /** SHA-256 前 16 位 */
    static String sha256(String content) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] d = md.digest(content.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < 8; i++) {
                sb.append(String.format("%02x", d[i]));
            }
            return sb.toString();
        } catch (Exception e) {
            return "unknown";
        }
    }

    /** 从已有文件读指纹 */
    static String readFingerprint(String content) {
        int idx = content.indexOf(FINGERPRINT_MARK);
        if (idx < 0) {
            return null;
        }
        int start = idx + FINGERPRINT_MARK.length();
        int end = content.indexOf('\n', start);
        if (end < 0) {
            end = content.length();
        }
        return content.substring(start, end).trim();
    }

    private static void write(Path file, String content) throws IOException {
        Files.write(file, content.getBytes(StandardCharsets.UTF_8));
    }
}
