package com.jjy.codegen;

import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 对比 M2 yaml 里声明的 168 个 behavior vs Java 代码里 @BehaviorHandler 注解注册的实现。
 *
 * 双向扫：
 *  1) yaml 有但代码无实现 → "orphaned"（behavior 调起来会 BizException "无 @BehaviorHandler 实现"）
 *  2) 代码有但 yaml 无声明 → "stray"（不被任何 behavior 调，疑似历史遗留）
 *
 * 用法：
 *   java com.jjy.codegen.BehaviorRegistryChecker
 *     <m2.yaml>  <java-source-root>
 *   例：
 *     java ... m2-behavior-model.yaml jjy-application/src/main/java
 *
 * 退出码：发现任一漂移 → 1；无 → 0。
 */
public final class BehaviorRegistryChecker {

    /** 匹配 @BehaviorHandler("Xxx_Yyy") 里的 id 字符串 */
    private static final Pattern BEHAVIOR_HANDLER_ANN = Pattern.compile(
        "@BehaviorHandler\\s*\\(\\s*\"([^\"]+)\"\\s*\\)");

    private BehaviorRegistryChecker() {}

    public static void main(String[] args) throws Exception {
        if (args.length < 2) {
            System.err.println("Usage: BehaviorRegistryChecker <m2.yaml> <java-source-root>");
            System.exit(2);
        }
        Path yaml = Paths.get(args[0]);
        Path javaRoot = Paths.get(args[1]);

        Set<String> yamlBehaviors = readM2Behaviors(yaml);
        Set<String> codeBehaviors = scanJavaBehaviors(javaRoot);

        Set<String> orphaned = new TreeSet<>(yamlBehaviors);
        orphaned.removeAll(codeBehaviors);
        Set<String> stray = new TreeSet<>(codeBehaviors);
        stray.removeAll(yamlBehaviors);

        System.out.println("=== BehaviorRegistry 检查 ===");
        System.out.println("M2 yaml 声明 behavior 总数 : " + yamlBehaviors.size());
        System.out.println("代码 @BehaviorHandler 总数 : " + codeBehaviors.size());
        System.out.println("  - 已对齐                : " + (yamlBehaviors.size() - orphaned.size()));
        System.out.println("  - yaml 有/代码无 (orphaned): " + orphaned.size());
        System.out.println("  - 代码有/yaml 无 (stray)   : " + stray.size());

        if (!orphaned.isEmpty()) {
            System.out.println("\n--- [orphaned] yaml 声明但代码无实现（运行时会抛 BizException） ---");
            orphaned.forEach(b -> System.out.println("  " + b));
        }
        if (!stray.isEmpty()) {
            System.out.println("\n--- [stray] 代码有 @BehaviorHandler 但 yaml 未声明（疑似历史遗留/测试） ---");
            stray.forEach(b -> System.out.println("  " + b));
        }
        System.exit((!orphaned.isEmpty() || !stray.isEmpty()) ? 1 : 0);
    }

    /** 读 m2-behavior-model.yaml，取所有 behaviors[].id */
    static Set<String> readM2Behaviors(Path yamlFile) throws IOException {
        Set<String> result = new TreeSet<>();
        try (java.io.InputStream in = Files.newInputStream(yamlFile)) {
            Object root = new Yaml().load(in);
            if (!(root instanceof Map)) return result;
            Object behaviorsObj = ((Map<?, ?>) root).get("behaviors");
            if (!(behaviorsObj instanceof List)) return result;
            for (Object b : (List<?>) behaviorsObj) {
                if (b instanceof Map) {
                    Object id = ((Map<?, ?>) b).get("id");
                    if (id != null) result.add(id.toString());
                }
            }
        }
        return result;
    }

    /** 扫 java 根目录所有 .java 文件，提取 @BehaviorHandler("...") 里的 id */
    static Set<String> scanJavaBehaviors(Path javaRoot) throws IOException {
        Set<String> result = new TreeSet<>();
        if (!Files.isDirectory(javaRoot)) return result;
        try (java.util.stream.Stream<Path> stream = Files.walk(javaRoot)) {
            stream.filter(p -> p.toString().endsWith(".java"))
                  .forEach(p -> {
                      try {
                          String src = new String(Files.readAllBytes(p), StandardCharsets.UTF_8);
                          // 简单去注释（够用：行注释、块注释）
                          src = src.replaceAll("(?s)/\\*.*?\\*/", "");
                          Matcher m = BEHAVIOR_HANDLER_ANN.matcher(src);
                          while (m.find()) result.add(m.group(1));
                      } catch (IOException e) {
                          // 单文件失败不影响整体
                      }
                  });
        }
        return result;
    }
}
