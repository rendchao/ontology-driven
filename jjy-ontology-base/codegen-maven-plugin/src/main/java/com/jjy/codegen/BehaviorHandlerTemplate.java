package com.jjy.codegen;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 根据 M2 behavior 生成 {@code @BehaviorHandler} 类的 Java 源。
 *
 * <p><b>模板形态</b>（M2 的 {@code templateKind}）：</p>
 * <table border="1">
 *   <caption>支持的模板</caption>
 *   <tr><th>形态</th><th>生成的行为</th></tr>
 *   <tr><td>{@code DELETE}</td><td>按主键逻辑删除（flag 置 1）</td></tr>
 *   <tr><td>{@code UPDATE}</td><td>按主键更新可变字段（字段集由 M1 推导）</td></tr>
 *   <tr><td>{@code BATCH_DELETE}</td><td>按条件批量逻辑删除</td></tr>
 *   <tr><td>{@code QUERY_PAGE}</td><td>分页查询（走 M7 报表口径）</td></tr>
 * </table>
 *
 * <p><b>铁律：生成物里不出现任何硬编码的业务语义</b>——表名、主键列、列名全部
 * 运行时问 MM 映射模型；主键字段问 M1；查询口径问 M7；状态目标值问 M2 的
 * postconditions。生成物只负责「按模型声明的形状把数据写进去」。</p>
 *
 * @author jjy-base
 */
public final class BehaviorHandlerTemplate {

    private BehaviorHandlerTemplate() {
    }

    /** 静态方法名首字母小写驼峰：Product_Delete → delete */
    public static String methodName(String behaviorId) {
        int us = behaviorId.indexOf('_');
        String tail = us >= 0 ? behaviorId.substring(us + 1) : behaviorId;
        if (tail.isEmpty()) {
            return "execute";
        }
        return Character.toLowerCase(tail.charAt(0)) + tail.substring(1);
    }

    /** 子包名：Product → product */
    public static String subPackage(String alias) {
        if (alias == null || alias.isEmpty()) {
            return "misc";
        }
        return alias.toLowerCase();
    }

    /**
     * 生成 Java 源。
     *
     * @param b       行为声明
     * @param pkgRoot 生成物的根包（业务工程可改成自己的包）
     * @param pkField 该聚合根的主键字段名（来自 M1 声明，不猜）
     * @param updatable 可更新字段（来自 M1；UPDATE 模板用）
     */
    public static String render(M2Reader.Behavior b, String pkgRoot, String pkField,
                                List<String> updatable) {
        String alias = b.alias();
        String pkg = pkgRoot + ".generated." + subPackage(alias);
        String kind = b.templateKind == null ? "DELETE" : b.templateKind;

        String bodyTemplate;
        if ("UPDATE".equalsIgnoreCase(kind)) {
            bodyTemplate = updateTemplate();
        } else if ("BATCH_DELETE".equalsIgnoreCase(kind)) {
            bodyTemplate = batchDeleteTemplate();
        } else if ("QUERY_PAGE".equalsIgnoreCase(kind)) {
            bodyTemplate = queryPageTemplate();
        } else {
            bodyTemplate = deleteTemplate();
        }

        Map<String, String> vars = new LinkedHashMap<String, String>();
        vars.put("pkg", pkg);
        vars.put("name", nullTo(b.name));
        vars.put("id", nullTo(b.id));
        vars.put("owner", nullTo(b.ownerEntity));
        vars.put("type", nullTo(b.behaviorType) + " / " + nullTo(b.triggerType));
        vars.put("kind", kind);
        vars.put("desc", nullTo(b.description));
        vars.put("pre", b.preconditions.toString());
        vars.put("post", postSummary(b.postconditions));
        vars.put("rules", b.appliedRules.toString());
        vars.put("perms", b.requiredPermissions.toString());
        vars.put("events", b.producedEvents.toString());
        vars.put("subs", b.subscribedEvents.toString());
        vars.put("alias", alias);
        vars.put("pkField", pkField);
        vars.put("method", methodName(b.id));
        vars.put("updatableArray", javaStringArray(updatable));
        vars.put("updatableCount", updatable == null ? "0" : String.valueOf(updatable.size()));
        vars.put("reportRef", nullTo(b.queryReportRef));

        String out = header(b, kind) + bodyTemplate + "}\n";
        for (Map.Entry<String, String> e : vars.entrySet()) {
            out = out.replace("#{" + e.getKey() + "}", e.getValue());
        }
        return out;
    }

    /** 类头（包声明 / import / 类注释 / 注解 / 字段声明） */
    private static String header(M2Reader.Behavior b, String kind) {
        StringBuilder sb = new StringBuilder();
        sb.append("package #{pkg};\n\n");
        sb.append("import com.jjy.application.behavior.BehaviorContext;\n");
        sb.append("import com.jjy.domain.behavior.BehaviorHandler;\n");
        sb.append("import com.jjy.infrastructure.yaml.PhysicalMappingResolver;\n");
        sb.append("import org.springframework.beans.factory.annotation.Autowired;\n");
        sb.append("import org.springframework.jdbc.core.JdbcTemplate;\n");
        sb.append("import org.springframework.stereotype.Component;\n");
        sb.append("import org.springframework.transaction.annotation.Transactional;\n\n");
        sb.append("import java.util.LinkedHashMap;\n");
        sb.append("import java.util.Map;\n\n");
        sb.append("/**\n");
        sb.append(" * #{name}（M2 模板生成 · behaviorForm=TEMPLATE · templateKind=#{kind}）\n");
        sb.append(" *\n");
        sb.append(" * <p><b>本文件由 codegen 自动生成</b>，源头是 models/m2-behavior-model.yaml。\n");
        sb.append(" * 重新生成时若本文件已被修改，会被保留（按内容指纹判定）。</p>\n");
        sb.append(" *\n");
        sb.append(" * <p>生成物遵循一条铁律：<b>不硬编码任何业务语义</b>——\n");
        sb.append(" * 表名 / 主键列 / 列名运行时问 MM 映射模型；\n");
        sb.append(" * 主键与可更新字段问 M1；查询口径问 M7；\n");
        sb.append(" * 权限与规则由 dispatcher 按 M2/M5/M3 校验。\n");
        sb.append(" * 这里的代码只负责「按模型声明的形状把数据写进去」。</p>\n");
        sb.append(" *\n");
        sb.append(" * <p><b>注意</b>：{@code @BehaviorHandler} 的 Target 是 METHOD，\n");
        sb.append(" * 必须标在方法上，不能标在类上（标错位置编译报「注释不在语句上」）。</p>\n");
        sb.append(" *\n");
        sb.append(" * <pre>\n");
        sb.append(" * id      : #{id}\n");
        sb.append(" * owner   : #{owner}\n");
        sb.append(" * type    : #{type}\n");
        sb.append(" * kind    : #{kind}\n");
        sb.append(" * desc    : #{desc}\n");
        sb.append(" * pre     : #{pre}\n");
        sb.append(" * post    : #{post}\n");
        sb.append(" * rules   : #{rules}\n");
        sb.append(" * perms   : #{perms}\n");
        sb.append(" * events  : #{events}\n");
        sb.append(" * subs    : #{subs}\n");
        sb.append(" * </pre>\n");
        sb.append(" *\n");
        sb.append(" * @author jjy-base (generated)\n");
        sb.append(" */\n");
        sb.append("@Component\n");
        sb.append("public class #{id}Handler {\n\n");
        sb.append("    /** 物理映射解析器：表名 / 主键列 / 列名全部来自 MM 模型 */\n");
        sb.append("    @Autowired\n");
        sb.append("    private PhysicalMappingResolver physicalMapping;\n\n");
        sb.append("    @Autowired\n");
        sb.append("    private JdbcTemplate jdbcTemplate;\n\n");
        // QUERY_PAGE 模板需要 M7 报表执行器；其它模板用不到，不生成该字段
        if ("QUERY_PAGE".equalsIgnoreCase(kind)) {
            sb.append("    /** M7 报表执行器：查询口径由 M7 声明 */\n");
            sb.append("    @Autowired\n");
            sb.append("    private com.jjy.application.report.QueryReportExecutor reportExecutor;\n\n");
        }
        return sb.toString();
    }

    /** DELETE 模板：按主键逻辑删除 */
    private static String deleteTemplate() {
        return ""
                + "    /** #{name} */\n"
                + "    @BehaviorHandler(\"#{id}\")\n"
                + "    @Transactional\n"
                + "    public Map<String, Object> #{method}(BehaviorContext ctx) {\n"
                + "        Map<String, Object> dto = extractDto(ctx);\n"
                + "        String table = physicalMapping.tableName(\"#{alias}\");\n"
                + "        String pk = physicalMapping.pkColumn(table);\n"
                + "        String flagCol = physicalMapping.column(table, \"flag\");\n"
                + "\n"
                + "        Object pkObj = dto.get(\"#{pkField}\");\n"
                + "        if (pkObj == null || String.valueOf(pkObj).trim().isEmpty()) {\n"
                + "            throw new IllegalArgumentException(\n"
                + "                    \"#{id} 缺少主键 #{pkField}\");\n"
                + "        }\n"
                + "        String id = String.valueOf(pkObj);\n"
                + "\n"
                + "        int updated = jdbcTemplate.update(\n"
                + "                \"UPDATE \" + table + \" SET \" + flagCol + \" = 1\"\n"
                + "                        + \" WHERE \" + pk + \" = ? AND \" + flagCol + \" = 0\",\n"
                + "                id);\n"
                + "        if (updated == 0) {\n"
                + "            throw new IllegalStateException(\n"
                + "                    \"#{name}失败：记录不存在或已删除 (\" + pk + \"=\" + id + \")\");\n"
                + "        }\n"
                + "\n"
                + "        Map<String, Object> result = new LinkedHashMap<String, Object>();\n"
                + "        result.put(\"#{pkField}\", id);\n"
                + "        result.put(\"deleted\", Boolean.TRUE);\n"
                + "        return result;\n"
                + "    }\n";
    }

    /** UPDATE 模板：按主键更新可变字段 */
    private static String updateTemplate() {
        return ""
                + "    /** #{name} */\n"
                + "    @BehaviorHandler(\"#{id}\")\n"
                + "    @Transactional\n"
                + "    public Map<String, Object> #{method}(BehaviorContext ctx) {\n"
                + "        Map<String, Object> dto = extractDto(ctx);\n"
                + "        String table = physicalMapping.tableName(\"#{alias}\");\n"
                + "        String pk = physicalMapping.pkColumn(table);\n"
                + "        String flagCol = physicalMapping.column(table, \"flag\");\n"
                + "\n"
                + "        Object pkObj = dto.get(\"#{pkField}\");\n"
                + "        if (pkObj == null || String.valueOf(pkObj).trim().isEmpty()) {\n"
                + "            throw new IllegalArgumentException(\n"
                + "                    \"#{id} 缺少主键 #{pkField}\");\n"
                + "        }\n"
                + "        String id = String.valueOf(pkObj);\n"
                + "\n"
                + "        // 可更新字段集由 M1 推导（这里声明哪些字段参与更新）\n"
                + "        String[] updatable = #{updatableArray};\n"
                + "        StringBuilder sets = new StringBuilder();\n"
                + "        java.util.List<Object> args = new java.util.ArrayList<Object>();\n"
                + "        for (String f : updatable) {\n"
                + "            if (!dto.containsKey(f)) {\n"
                + "                continue;   // 未传的字段不改\n"
                + "            }\n"
                + "            String col = physicalMapping.column(table, f);\n"
                + "            if (col == null) {\n"
                + "                continue;\n"
                + "            }\n"
                + "            if (sets.length() > 0) {\n"
                + "                sets.append(\", \");\n"
                + "            }\n"
                + "            sets.append(col).append(\" = ?\");\n"
                + "            args.add(dto.get(f));\n"
                + "        }\n"
                + "        if (sets.length() == 0) {\n"
                + "            throw new IllegalArgumentException(\"#{id} 未提供任何可更新字段\");\n"
                + "        }\n"
                + "\n"
                + "        args.add(id);\n"
                + "        int updated = jdbcTemplate.update(\n"
                + "                \"UPDATE \" + table + \" SET \" + sets\n"
                + "                        + \" WHERE \" + pk + \" = ? AND \" + flagCol + \" = 0\",\n"
                + "                args.toArray());\n"
                + "        if (updated == 0) {\n"
                + "            throw new IllegalStateException(\n"
                + "                    \"#{name}失败：记录不存在 (\" + pk + \"=\" + id + \")\");\n"
                + "        }\n"
                + "\n"
                + "        Map<String, Object> result = new LinkedHashMap<String, Object>();\n"
                + "        result.put(\"#{pkField}\", id);\n"
                + "        result.put(\"updated\", Boolean.TRUE);\n"
                + "        result.put(\"updatedFields\", updatable.length);\n"
                + "        return result;\n"
                + "    }\n";
    }

    /** BATCH_DELETE 模板：按条件批量逻辑删除 */
    private static String batchDeleteTemplate() {
        return ""
                + "    /** #{name} */\n"
                + "    @BehaviorHandler(\"#{id}\")\n"
                + "    @Transactional\n"
                + "    public Map<String, Object> #{method}(BehaviorContext ctx) {\n"
                + "        Map<String, Object> dto = extractDto(ctx);\n"
                + "        String table = physicalMapping.tableName(\"#{alias}\");\n"
                + "        String flagCol = physicalMapping.column(table, \"flag\");\n"
                + "\n"
                + "        // 批量删除的条件由调用方给出（字段 → 值），与 M7 的 conditions 同构。\n"
                + "        // 没有任何条件时拒绝执行——避免误删全表。\n"
                + "        if (dto.isEmpty()) {\n"
                + "            throw new IllegalArgumentException(\"#{id} 缺少批量删除条件，拒绝执行\");\n"
                + "        }\n"
                + "\n"
                + "        StringBuilder where = new StringBuilder(\" WHERE \").append(flagCol).append(\" = 0\");\n"
                + "        java.util.List<Object> args = new java.util.ArrayList<Object>();\n"
                + "        for (Map.Entry<String, Object> e : dto.entrySet()) {\n"
                + "            String col = physicalMapping.column(table, e.getKey());\n"
                + "            if (col == null) {\n"
                + "                continue;\n"
                + "            }\n"
                + "            where.append(\" AND \").append(col).append(\" = ?\");\n"
                + "            args.add(e.getValue());\n"
                + "        }\n"
                + "\n"
                + "        int updated = jdbcTemplate.update(\n"
                + "                \"UPDATE \" + table + \" SET \" + flagCol + \" = 1\" + where,\n"
                + "                args.toArray());\n"
                + "\n"
                + "        Map<String, Object> result = new LinkedHashMap<String, Object>();\n"
                + "        result.put(\"deletedCount\", updated);\n"
                + "        return result;\n"
                + "    }\n";
    }

    /** QUERY_PAGE 模板：分页查询（走 M7 报表口径） */
    private static String queryPageTemplate() {
        return ""
                + "    /** #{name} */\n"
                + "    @BehaviorHandler(\"#{id}\")\n"
                + "    public Map<String, Object> #{method}(BehaviorContext ctx) {\n"
                + "        Map<String, Object> dto = extractDto(ctx);\n"
                + "\n"
                + "        // 查询口径来自 M7 报表（结果列 / 数据源 / 参数 / 排序 / 分页），\n"
                + "        // 由 QueryReportExecutor 按模型生成 SQL——改 M7 即刻生效。\n"
                + "        Map<String, Object> params = new LinkedHashMap<String, Object>();\n"
                + "        for (Map.Entry<String, Object> e : dto.entrySet()) {\n"
                + "            if (e.getValue() != null && !String.valueOf(e.getValue()).trim().isEmpty()) {\n"
                + "                params.put(e.getKey(), e.getValue());\n"
                + "            }\n"
                + "        }\n"
                + "        return reportExecutor.execute(\"#{reportRef}\", params);\n"
                + "    }\n";
    }

    /**
     * 从上下文取请求参数（兼容 dto 包装与顶层两种写法）。
     *
     * <p>放在每个生成模板的公共位置——由 {@link HandlerGenerator} 在生成时展开。</p>
     */
    static final String EXTRACT_DTO_HELPER =
            ""
                    + "    /** 取请求参数：兼容 dto 包装与顶层两种写法 */\n"
                    + "    private Map<String, Object> extractDto(BehaviorContext ctx) {\n"
                    + "        Map<String, Object> input = ctx.getInput();\n"
                    + "        Object dtoObj = input == null ? null : input.get(\"dto\");\n"
                    + "        Map<String, Object> dto = dtoObj instanceof Map\n"
                    + "                ? (Map<String, Object>) dtoObj : input;\n"
                    + "        return dto == null ? new LinkedHashMap<String, Object>() : dto;\n"
                    + "    }\n";

    /**
     * 从 M1 查出该聚合根的主键字段名。
     *
     * <p><b>不猜</b>：按 codegen 的 PO 生成规则——唯一满足
     * {@code required + unique + 名字以 Id 结尾} 的属性即为主键；
     * 找不到则返回 {@code id}（并由调用方告警）。</p>
     */
    public static String pkFieldOf(M1Reader.M1 m1, String alias) {
        if (m1 != null) {
            for (M1Reader.Aggregate agg : m1.aggregates) {
                if (!alias.equals(agg.alias)) {
                    continue;
                }
                for (M1Reader.Attr at : agg.attributes) {
                    if (at.pk) {
                        return at.name;
                    }
                }
                String candidate = null;
                for (M1Reader.Attr at : agg.attributes) {
                    if (at.required && at.unique && at.name.endsWith("Id")) {
                        if (candidate != null) {
                            return "id";
                        }
                        candidate = at.name;
                    }
                }
                if (candidate != null) {
                    return candidate;
                }
            }
        }
        return "id";
    }

    /**
     * 从 M1 查出该聚合根的可更新字段（排除主键与状态）。
     *
     * <p>「状态」不可直接更新——状态迁移必须走 M2 的行为（状态机校验），
     * 直接 set 会绕过 {@code LifecycleGuard}。</p>
     */
    public static List<String> updatableFieldsOf(M1Reader.M1 m1, String alias, String pkField) {
        List<String> out = new java.util.ArrayList<String>();
        if (m1 == null) {
            return out;
        }
        for (M1Reader.Aggregate agg : m1.aggregates) {
            if (!alias.equals(agg.alias)) {
                continue;
            }
            for (M1Reader.Attr at : agg.attributes) {
                if (at.name == null || at.name.equals(pkField)) {
                    continue;
                }
                if ("status".equals(at.name)) {
                    continue;   // 状态由状态机管，不在通用更新里
                }
                out.add(at.name);
            }
        }
        return out;
    }

    /**
     * 生成合法的 Java 字符串数组字面量。
     *
     * <p>直接用 {@code List.toString()} 会得到 {@code [a, b]}——那不是合法的
     * Java 数组字面量（编译报「非法表达式开始」）。</p>
     */
    static String javaStringArray(List<String> items) {
        if (items == null || items.isEmpty()) {
            return "new String[0]";
        }
        StringBuilder sb = new StringBuilder("new String[] {");
        for (int i = 0; i < items.size(); i++) {
            if (i > 0) {
                sb.append(", ");
            }
            sb.append("\"").append(items.get(i).replace("\"", "\\\"")).append("\"");
        }
        sb.append("}");
        return sb.toString();
    }

    /** 后置条件摘要（field → setValue） */
    private static String postSummary(List<Map<String, String>> post) {
        StringBuilder sb = new StringBuilder();
        for (Map<String, String> p : post) {
            if (sb.length() > 0) {
                sb.append(", ");
            }
            sb.append(p.get("field")).append("→").append(p.get("setValue"));
        }
        return sb.toString();
    }

    private static String nullTo(String s) {
        return s == null ? "" : s;
    }
}
