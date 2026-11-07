package com.jjy.codegen;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 根据 M3 rule 生成 RuleEvaluator 注册片段（骨架版）。
 *
 * 39 个 M3 rule 已散落在 jjy-application/.../rule/ 下通过 @Component 注册。
 * 本模板生成"未来新增 rule 时"的注册 Java 代码骨架（engine 分支：SPEL / SQL）。
 *
 * 包路径约定：
 *   com.jjy.application.rule.generated.<idSub>.<Id>Rule
 *   例：RULE-CUS-001 → cus/RULE-CUS-001Rule
 */
public final class RuleRegistrationTemplate {

    private RuleRegistrationTemplate() {}

    /** 截取 RULE-XXX-001 第二段作为子包：RULE-CUS-001 → cus */
    public static String subPackage(String ruleId) {
        if (ruleId == null) return "misc";
        String[] parts = ruleId.split("-");
        return parts.length >= 2 ? parts[1].toLowerCase() : "misc";
    }

    /** 生成 Rule Java 源（骨架） */
    public static String render(M3Reader.Rule r) {
        StringBuilder sb = new StringBuilder();
        sb.append("package com.jjy.application.rule.generated.")
          .append(subPackage(r.id)).append(";\n\n");
        sb.append("import com.jjy.application.rule.RuleContext;\n");
        sb.append("import com.jjy.application.rule.RuleResult;\n");
        sb.append("import com.jjy.application.rule.annotation.Rule;\n");
        sb.append("import org.springframework.stereotype.Component;\n\n");
        sb.append("/**\n");
        sb.append(" * ").append(r.name).append("（M3 自动生成骨架，TODO 补表达式）\n");
        sb.append(" * id      : ").append(r.id).append("\n");
        sb.append(" * engine  : ").append(r.engine).append("\n");
        if (r.description != null && !r.description.isEmpty())
            sb.append(" * desc    : ").append(r.description.replace("\n", " ")).append("\n");
        if (!r.reusedBy.isEmpty()) sb.append(" * reusedBy: ").append(r.reusedBy).append("\n");
        sb.append(" */\n");
        sb.append("@Component\n");
        sb.append("@Rule(id = \"").append(r.id).append("\", engine = \"").append(r.engine).append("\")\n");
        sb.append("public class ").append(safeClassName(r.id)).append("Rule {\n\n");
        sb.append("    public RuleResult evaluate(RuleContext ctx) {\n");
        if ("SPEL".equalsIgnoreCase(r.engine)) {
            sb.append("        // SPEL: ").append(truncate(r.expression, 200)).append("\n");
            sb.append("        return RuleResult.pass(); // TODO 替换为真实 SPEL 评估\n");
        } else if ("SQL".equalsIgnoreCase(r.engine)) {
            sb.append("        // SQL : ").append(truncate(r.expression, 200)).append("\n");
            sb.append("        return RuleResult.pass(); // TODO 替换为真实 SQL 查询\n");
        } else {
            sb.append("        return RuleResult.pass();\n");
        }
        sb.append("    }\n");
        sb.append("}\n");
        return sb.toString();
    }

    /** RULE-CUS-001 → RuleCus001（Java 类名合法化） */
    public static String safeClassName(String ruleId) {
        if (ruleId == null) return "Unknown";
        String[] parts = ruleId.split("-");
        StringBuilder sb = new StringBuilder("Rule");
        for (int i = 1; i < parts.length; i++) {
            if (parts[i].isEmpty()) continue;
            sb.append(Character.toUpperCase(parts[i].charAt(0)))
              .append(parts[i].substring(1).toLowerCase());
        }
        return sb.toString();
    }

    private static String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max) + "...";
    }
}
