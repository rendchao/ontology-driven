package com.jjy.codegen;

import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;

/**
 * 对比 M3 yaml 声明的 rule vs M2 yaml 行为里引用的 rule 集合。
 *
 * M3 39 个 rule 是声明；它们被使用的方式有两种：
 *  1) M2 behavior.appliedRules[]（行为执行前预检，里面是 RULE-XXX id）
 *  2) M3 rule.reusedBy[]（被哪些 behavior 复用，里面是 Behavior_XXX id）
 *
 * 双向闭合检查：
 *  - M3 声明但 M2/M3 都没引用 → 死规则
 *  - M2.appliedRules 引用了 M3 没声明的 rule → 漂移引用
 *  - M3.reusedBy 引用了 M2 没声明的 behavior → 反向索引漂移
 *
 * 注：代码里"动态构造"的 rule id（"RULE-LOS-" + type）暂不在此 check 范围内。
 *
 * 用法：
 *   java com.jjy.codegen.RuleRegistryChecker
 *     <m3.yaml>  <m2.yaml>
 */
public final class RuleRegistryChecker {

    private RuleRegistryChecker() {}

    public static void main(String[] args) throws Exception {
        if (args.length < 2) {
            System.err.println("Usage: RuleRegistryChecker <m3.yaml> <m2.yaml> [m4.yaml]");
            System.exit(2);
        }
        Path m3 = Paths.get(args[0]);
        Path m2 = Paths.get(args[1]);
        Path m4 = args.length >= 3 ? Paths.get(args[2]) : null;

        Set<String> declared = readM3Rules(m3);
        Set<String> usedByBehavior = readM2AppliedRules(m2);
        Set<String> reusedByM3 = readM3ReusedBy(m3);
        Set<String> m2BehaviorIds = readM2BehaviorIds(m2);
        Set<String> usedByScenario = m4 != null ? readM4RuleRefs(m4) : Collections.emptySet();
        // 自身声明了 onMatchBehavior 的 rule 视为"有触发目标"，不算 dead
        Set<String> selfTargeted = readM3OnMatchOwners(m3);

        // "rule 被使用"全集：M2.appliedRules ∪ M4.ruleRef ∪ 自身有 onMatchBehavior
        // （reusedByM3 收集的是 behavior 名，不是 rule 名，不能并入 rule 启用信号）
        Set<String> used = new TreeSet<>(usedByBehavior);
        used.addAll(usedByScenario);
        used.addAll(selfTargeted);

        // check 1: M2.appliedRules 引用的 rule 必须在 M3 声明中
        Set<String> strayRules = new TreeSet<>(usedByBehavior);
        strayRules.removeAll(declared);

        // check 2: M3 声明但任何调用方都没引用
        Set<String> dead = new TreeSet<>(declared);
        dead.removeAll(used);

        // check 3: M3.reusedBy 引用的 behavior 必须在 M2.behaviors 存在
        Set<String> strayBehaviors = new TreeSet<>(reusedByM3);
        strayBehaviors.removeAll(m2BehaviorIds);

        // check 4: M2.preconditions 引用的 rule 必须在 M3 声明
        //          （preconditions 已从自由文本改为引用 M3 规则 ID，
        //           若仍写自由文本就没人能校验它是否真实存在）
        Set<String> strayPreconditions = readM2PreconditionRules(m2);
        strayPreconditions.removeAll(declared);

        // check 5: M3.reusedBy 与 M2.appliedRules 必须双向一致
        //   · M3 说「规则 R 被行为 B 复用」，B 的 appliedRules 里就必须有 R；
        //   · B 的 appliedRules 里有 R，R 的 reusedBy 里就必须有 B。
        //   两边是同一事实的两份索引，不一致就是漂移——
        //   表现为「模型说规则生效了，实际没挂」或反之，且完全不报错。
        Set<String> inconsistentReuse = new TreeSet<>();
        // 5a. M3.reusedBy → M2.appliedRules
        //     逐个（规则, 行为）对检查：该行为自己的 appliedRules 里必须含该规则。
        //     注意不能把多个行为的 appliedRules 合并后再查——那样「A 行为有 R、
        //     B 行为没有 R」会被误判为一致。
        Map<String, Set<String>> m2AppliedPairs = readM2AppliedRulePairs(m2);
        for (Map.Entry<String, Set<String>> e : readM3ReusePairs(m3).entrySet()) {
            String ruleId = e.getKey();
            for (String bhv : e.getValue()) {
                Set<String> appliedOfBhv = m2AppliedPairs.get(bhv);
                if (appliedOfBhv == null || !appliedOfBhv.contains(ruleId)) {
                    inconsistentReuse.add(ruleId + " ← M3.reusedBy 声明 " + bhv
                            + " 复用，但该行为的 appliedRules 未包含它");
                }
            }
        }
        // 5b. M2.appliedRules → M3.reusedBy
        Set<String> reusedPairs = new TreeSet<>();
        for (Map.Entry<String, Set<String>> e : readM3ReusePairs(m3).entrySet()) {
            for (String bhv : e.getValue()) {
                reusedPairs.add(e.getKey() + "|" + bhv);
            }
        }
        for (Map.Entry<String, Set<String>> e : readM2AppliedRulePairs(m2).entrySet()) {
            String bhv = e.getKey();
            for (String ruleId : e.getValue()) {
                if (!reusedPairs.contains(ruleId + "|" + bhv)) {
                    inconsistentReuse.add(ruleId + " → 行为 " + bhv
                            + " 的 appliedRules 引用了它，但 M3.reusedBy 未声明该复用关系");
                }
            }
        }

        System.out.println("=== RuleRegistry 检查（M2↔M3 cross-ref）===");
        System.out.println("M3 声明 rule 总数          : " + declared.size());
        System.out.println("M2 behavior 总数           : " + m2BehaviorIds.size());
        System.out.println("M2 appliedRules 唯一 rule   : " + usedByBehavior.size());
        System.out.println("M3 reusedBy 唯一 behavior   : " + reusedByM3.size());
        System.out.println("  - M2.appliedRules 引用但 M3 未声明 (stray rule)      : " + strayRules.size());
        System.out.println("  - M3 声明但 M2 未引用的 (dead)                       : " + dead.size());
        System.out.println("  - M3.reusedBy 引用了 M2 不存在的 behavior (stray bhv) : " + strayBehaviors.size());
        System.out.println("  - M2.preconditions 引用了 M3 未声明的 rule          : " + strayPreconditions.size());
        System.out.println("  - M3.reusedBy ↔ M2.appliedRules 不一致 (双向索引漂移)  : " + inconsistentReuse.size());

        if (!strayRules.isEmpty()) {
            System.out.println("\n--- [stray rule] M2.appliedRules 引用了 M3 未声明的 rule ---");
            strayRules.forEach(r -> System.out.println("  " + r));
        }
        if (!dead.isEmpty()) {
            System.out.println("\n--- [dead] M3 声明但 M2.appliedRules 都没引用 ---");
            dead.forEach(r -> System.out.println("  " + r));
        }
        if (!strayBehaviors.isEmpty()) {
            System.out.println("\n--- [stray behavior] M3.reusedBy 引用了 M2 不存在的 behavior ---");
            strayBehaviors.forEach(r -> System.out.println("  " + r));
        }
        if (!strayPreconditions.isEmpty()) {
            System.out.println("\n--- [stray precondition] M2.preconditions 引用了 M3 未声明的 rule ---");
            strayPreconditions.forEach(r -> System.out.println("  " + r));
        }
        if (!inconsistentReuse.isEmpty()) {
            System.out.println("\n--- [reuse drift] M3.reusedBy 与 M2.appliedRules 不一致 ---");
            inconsistentReuse.forEach(r -> System.out.println("  " + r));
            System.out.println("\n  这类漂移不会让程序报错，只会让「模型说的」和「实际做的」不一致：");
            System.out.println("  规则可能根本没挂上（白写），或已废弃的规则仍被挂载（误拦）。");
        }
        boolean drift = !strayRules.isEmpty() || !dead.isEmpty() || !strayBehaviors.isEmpty()
                || !strayPreconditions.isEmpty() || !inconsistentReuse.isEmpty();
        System.exit(drift ? 1 : 0);
    }

    /** M3 rules[].id */
    static Set<String> readM3Rules(Path m3Yaml) throws IOException {
        Set<String> out = new TreeSet<>();
        try (InputStream in = Files.newInputStream(m3Yaml)) {
            Object root = new Yaml().load(in);
            if (!(root instanceof Map)) return out;
            Object rulesObj = ((Map<?, ?>) root).get("rules");
            if (!(rulesObj instanceof List)) return out;
            for (Object r : (List<?>) rulesObj) {
                if (r instanceof Map) {
                    Object id = ((Map<?, ?>) r).get("id");
                    if (id != null) out.add(id.toString());
                }
            }
        }
        return out;
    }

    /** M3 rules[].reusedBy[]  平铺 */
    static Set<String> readM3ReusedBy(Path m3Yaml) throws IOException {
        Set<String> out = new TreeSet<>();
        try (InputStream in = Files.newInputStream(m3Yaml)) {
            Object root = new Yaml().load(in);
            if (!(root instanceof Map)) return out;
            Object rulesObj = ((Map<?, ?>) root).get("rules");
            if (!(rulesObj instanceof List)) return out;
            for (Object r : (List<?>) rulesObj) {
                if (r instanceof Map) {
                    Object arr = ((Map<?, ?>) r).get("reusedBy");
                    if (arr instanceof List) {
                        for (Object x : (List<?>) arr) {
                            if (x != null) out.add(x.toString());
                        }
                    }
                }
            }
        }
        return out;
    }

    /** M2 behaviors[].id */
    static Set<String> readM2BehaviorIds(Path m2Yaml) throws IOException {
        Set<String> out = new TreeSet<>();
        try (InputStream in = Files.newInputStream(m2Yaml)) {
            Object root = new Yaml().load(in);
            if (!(root instanceof Map)) return out;
            Object behaviorsObj = ((Map<?, ?>) root).get("behaviors");
            if (!(behaviorsObj instanceof List)) return out;
            for (Object b : (List<?>) behaviorsObj) {
                if (b instanceof Map) {
                    Object id = ((Map<?, ?>) b).get("id");
                    if (id != null) out.add(id.toString());
                }
            }
        }
        return out;
    }

    /** M2 behaviors[].appliedRules[]  平铺（去重） */
    static Set<String> readM2AppliedRules(Path m2Yaml) throws IOException {
        Set<String> out = new TreeSet<>();
        try (InputStream in = Files.newInputStream(m2Yaml)) {
            Object root = new Yaml().load(in);
            if (!(root instanceof Map)) return out;
            Object behaviorsObj = ((Map<?, ?>) root).get("behaviors");
            if (!(behaviorsObj instanceof List)) return out;
            for (Object b : (List<?>) behaviorsObj) {
                if (b instanceof Map) {
                    Object arr = ((Map<?, ?>) b).get("appliedRules");
                    if (arr instanceof List) {
                        for (Object x : (List<?>) arr) {
                            if (x != null) out.add(x.toString());
                        }
                    }
                }
            }
        }
        return out;
    }

    /** M4 scenarios[].steps[].ruleRef  平铺（去重） */
    static Set<String> readM4RuleRefs(Path m4Yaml) throws IOException {
        Set<String> out = new TreeSet<>();
        try (InputStream in = Files.newInputStream(m4Yaml)) {
            Object root = new Yaml().load(in);
            if (!(root instanceof Map)) return out;
            Object scenariosObj = ((Map<?, ?>) root).get("scenarios");
            if (!(scenariosObj instanceof List)) return out;
            for (Object s : (List<?>) scenariosObj) {
                if (!(s instanceof Map)) continue;
                Object steps = ((Map<?, ?>) s).get("steps");
                if (!(steps instanceof List)) continue;
                for (Object st : (List<?>) steps) {
                    if (!(st instanceof Map)) continue;
                    Object ruleRef = ((Map<?, ?>) st).get("ruleRef");
                    if (ruleRef != null) out.add(ruleRef.toString());
                }
            }
        }
        return out;
    }

    /** M3 rules[].id 凡是声明了 onMatchBehavior 的都返回（"有触发目标的 rule"） */
    static Set<String> readM3OnMatchOwners(Path m3Yaml) throws IOException {
        Set<String> out = new TreeSet<>();
        try (InputStream in = Files.newInputStream(m3Yaml)) {
            Object root = new Yaml().load(in);
            if (!(root instanceof Map)) return out;
            Object rulesObj = ((Map<?, ?>) root).get("rules");
            if (!(rulesObj instanceof List)) return out;
            for (Object r : (List<?>) rulesObj) {
                if (!(r instanceof Map)) continue;
                Object id = ((Map<?, ?>) r).get("id");
                Object omb = ((Map<?, ?>) r).get("onMatchBehavior");
                if (id != null && omb != null) out.add(id.toString());
            }
        }
        return out;
    }

    /**
     * 取某顶层数组下的所有 Map 元素。
     */
    @SuppressWarnings("unchecked")
    static List<Map<String, Object>> listOf(Object root, String key) {
        List<Map<String, Object>> out = new ArrayList<>();
        if (!(root instanceof Map)) {
            return out;
        }
        Object arr = ((Map<?, ?>) root).get(key);
        if (!(arr instanceof List)) {
            return out;
        }
        for (Object o : (List<?>) arr) {
            if (o instanceof Map) {
                out.add((Map<String, Object>) o);
            }
        }
        return out;
    }

    /**
     * 读 YAML 文件为对象树。
     *
     * <p>文件不存在时返回 null（调用方按「无声明」处理），不抛异常——
     * 守门员应当在模型缺失时给出明确结论，而不是自己先崩掉。</p>
     */
    static Object load(Path yaml) throws IOException {
        if (!Files.isRegularFile(yaml)) {
            return null;
        }
        try (InputStream in = Files.newInputStream(yaml)) {
            return new Yaml().load(in);
        }
    }

    /** M2 behaviors[].preconditions[]（规则 ID）平铺 */
    static Set<String> readM2PreconditionRules(Path m2Yaml) throws IOException {
        Set<String> out = new TreeSet<>();
        for (Map<String, Object> b : listOf(load(m2Yaml), "behaviors")) {
            Object arr = b.get("preconditions");
            if (arr instanceof List) {
                for (Object x : (List<?>) arr) {
                    if (x != null) out.add(x.toString());
                }
            }
        }
        return out;
    }

    /** M3 ruleId → 声明复用它的 behaviorId 集合 */
    static Map<String, Set<String>> readM3ReusePairs(Path m3Yaml) throws IOException {
        Map<String, Set<String>> out = new java.util.LinkedHashMap<>();
        Object root = load(m3Yaml);
        Object rulesObj = ((Map<?, ?>) root).get("rules");
        if (!(rulesObj instanceof List)) {
            return out;
        }
        for (Object r : (List<?>) rulesObj) {
            if (!(r instanceof Map)) continue;
            Map<?, ?> m = (Map<?, ?>) r;
            Object id = m.get("id");
            Object arr = m.get("reusedBy");
            if (id == null || !(arr instanceof List)) continue;
            Set<String> set = new TreeSet<>();
            for (Object x : (List<?>) arr) {
                if (x != null) set.add(x.toString());
            }
            if (!set.isEmpty()) {
                out.put(id.toString(), set);
            }
        }
        return out;
    }

    /** M2 behaviorId → 其 appliedRules 里引用到的、且被 M3 声明复用的规则集合 */
    static Set<String> readM2AppliedRulesOf(Path m2Yaml, Set<String> behaviors) throws IOException {
        Set<String> out = new TreeSet<>();
        for (Map.Entry<String, Set<String>> e : readM2AppliedRulePairs(m2Yaml).entrySet()) {
            if (behaviors.contains(e.getKey())) {
                out.addAll(e.getValue());
            }
        }
        return out;
    }

    /** M2 behaviorId → appliedRules 集合 */
    static Map<String, Set<String>> readM2AppliedRulePairs(Path m2Yaml) throws IOException {
        Map<String, Set<String>> out = new java.util.LinkedHashMap<>();
        Object root = load(m2Yaml);
        Object behaviorsObj = ((Map<?, ?>) root).get("behaviors");
        if (!(behaviorsObj instanceof List)) {
            return out;
        }
        for (Object b : (List<?>) behaviorsObj) {
            if (!(b instanceof Map)) continue;
            Map<?, ?> m = (Map<?, ?>) b;
            Object id = m.get("id");
            Object arr = m.get("appliedRules");
            if (id == null || !(arr instanceof List)) continue;
            Set<String> set = new TreeSet<>();
            for (Object x : (List<?>) arr) {
                if (x != null) set.add(x.toString());
            }
            if (!set.isEmpty()) {
                out.put(id.toString(), set);
            }
        }
        return out;
    }
}
