package com.jjy.codegen;

import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * 读 M3 规则模型 (m3-rule-model.yaml)，解析出 rule 列表。
 *
 * 39 个 M3 rule 已通过 SpEL/DB 表达式手写实现（散落在 RuleEvaluator、@BehaviorHandler 中）。
 * A3 工具 (RuleRegistryChecker) 已对 M2.appliedRules ↔ M3.rules 做双向 cross-ref。
 * 本 reader 用于"未来新增 rule 时一键生成注册片段"，参见 {@link RuleRegistrationTemplate}。
 */
public final class M3Reader {

    private M3Reader() {}

    /** M3 rule 单个条目（最小集；复杂字段 inputParams/expression 按需扩展） */
    public static final class Rule {
        public final String id;
        public final String name;
        public final String ruleType;        // VALIDATION / COMPUTATION
        public final String engine;          // SPEL / SQL
        public final String expression;
        public final String description;
        public final List<String> reusedBy;
        public Rule(String id, String name, String type, String engine,
                    String expr, String desc, List<String> reused) {
            this.id = id; this.name = name; this.ruleType = type;
            this.engine = engine; this.expression = expr;
            this.description = desc; this.reusedBy = reused;
        }
    }

    public static final class M3 {
        public final List<Rule> rules;
        public M3(List<Rule> r) { this.rules = r; }
    }

    @SuppressWarnings("unchecked")
    public static M3 read(Path m3Yaml) throws IOException {
        List<Rule> out = new ArrayList<>();
        try (InputStream in = Files.newInputStream(m3Yaml)) {
            Object root = new Yaml().load(in);
            if (!(root instanceof Map)) return new M3(out);
            Object arr = ((Map<?, ?>) root).get("rules");
            if (!(arr instanceof List)) return new M3(out);
            for (Object o : (List<?>) arr) {
                if (!(o instanceof Map)) continue;
                Map<String, Object> r = (Map<String, Object>) o;
                out.add(new Rule(
                        str(r, "id"), str(r, "name"), str(r, "ruleType"), str(r, "engine"),
                        str(r, "expression"), str(r, "description"),
                        strList(r, "reusedBy")));
            }
        }
        return new M3(out);
    }

    private static String str(Map<String, Object> m, String k) {
        Object v = m.get(k);
        return v == null ? "" : v.toString();
    }

    @SuppressWarnings("unchecked")
    private static List<String> strList(Map<String, Object> m, String k) {
        Object v = m.get(k);
        if (v instanceof List) {
            List<String> r = new ArrayList<>();
            for (Object x : (List<?>) v) if (x != null) r.add(x.toString());
            return r;
        }
        return Collections.emptyList();
    }
}
