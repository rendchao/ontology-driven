package com.jjy.codegen;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 根据 M1 模型生成 PO Java 源。
 *
 * <p>生成规则：
 * <ul>
 *   <li>包名 / 表名 / 主键 / 显式列映射 → 见 {@link PoMapping}</li>
 *   <li>字段 Java 类型由 M1 的 {@code type} 决定（DictionaryRef / AggregateRootRef → String）</li>
 *   <li>需要映射到非默认列名的字段，用 {@code @TableField("列名")} 显式声明</li>
 *   <li>继承 BaseAuditPO 的 PO 不重复声明 5 个公共列；不继承的自行声明</li>
 * </ul>
 *
 * <p>使用 Lombok {@code @Data}，与项目其它 PO 保持一致（不手写 getter/setter）。</p>
 */
public final class PoTemplate {

    private PoTemplate() {}

    /** M1 type → Java 类型 */
    private static final Map<String, String> JAVA_TYPES = new LinkedHashMap<>();
    static {
        JAVA_TYPES.put("String", "String");
        JAVA_TYPES.put("Integer", "Integer");
        JAVA_TYPES.put("Long", "Long");
        JAVA_TYPES.put("Boolean", "Boolean");
        JAVA_TYPES.put("Double", "Double");
        JAVA_TYPES.put("Float", "Float");
        JAVA_TYPES.put("Money", "BigDecimal");
        JAVA_TYPES.put("Decimal", "BigDecimal");
        JAVA_TYPES.put("Date", "LocalDate");
        JAVA_TYPES.put("DateTime", "LocalDateTime");
        JAVA_TYPES.put("DictionaryRef", "String");
        JAVA_TYPES.put("AggregateRootRef", "String");
        JAVA_TYPES.put("Enum", "String");
    }

    /** 需要 import java.math / java.time 的类型 */
    private static final Map<String, String> IMPORTS = new LinkedHashMap<>();
    static {
        IMPORTS.put("BigDecimal", "import java.math.BigDecimal;");
        IMPORTS.put("LocalDate", "import java.time.LocalDate;");
        IMPORTS.put("LocalDateTime", "import java.time.LocalDateTime;");
    }

    /** 生成 PO Java 源 */
    public static String render(M1Reader.Aggregate agg) {
        StringBuilder sb = new StringBuilder(4096);
        String alias = agg.alias;
        String clsName = alias + "PO";
        String pkg = PoMapping.packageName(alias);
        String tableName = PoMapping.tableName(alias);
        boolean base = PoMapping.extendsBase(alias);
        String explicitPk = PoMapping.resolvePkField(alias, agg.attributes);

        // package
        sb.append("package ").append(pkg).append(";\n\n");

        // imports
        sb.append("import com.baomidou.mybatisplus.annotation.IdType;\n");
        sb.append("import com.baomidou.mybatisplus.annotation.TableField;\n");
        sb.append("import com.baomidou.mybatisplus.annotation.TableId;\n");
        sb.append("import com.baomidou.mybatisplus.annotation.TableName;\n");
        if (base) {
            // 基类所在包随 PO 根包一起变化，不能写死
            sb.append("import ").append(PoMapping.poRootPkg()).append(".BaseAuditPO;\n");
        }
        boolean needBigDecimal = false, needDate = false, needDateTime = false;
        for (M1Reader.Attr a : agg.attributes) {
            String jt = JAVA_TYPES.getOrDefault(a.type, "String");
            if ("BigDecimal".equals(jt)) needBigDecimal = true;
            if ("LocalDate".equals(jt)) needDate = true;
            if ("LocalDateTime".equals(jt)) needDateTime = true;
        }
        if (needBigDecimal) sb.append("import java.math.BigDecimal;\n");
        if (needDate) sb.append("import java.time.LocalDate;\n");
        if (needDateTime) sb.append("import java.time.LocalDateTime;\n");
        sb.append("import lombok.Data;\n");
        sb.append("\n");

        // class javadoc
        sb.append("/**\n");
        sb.append(" * ").append(clsName).append(" — 由 M1 对象模型自动生成，请勿手改。\n");
        sb.append(" * 模型来源: models/m1-object-model.yaml#").append(agg.id).append("\n");
        sb.append(" * <p>重新生成: {@code mvn compile}（绑 generate-sources 阶段）</p>\n");
        sb.append(" */\n");

        // class
        sb.append("@Data\n");
        sb.append("@TableName(\"").append(tableName).append("\")\n");
        sb.append("public class ").append(clsName);
        if (base) {
            sb.append(" extends BaseAuditPO");
        }
        sb.append(" {\n\n");

        // 字段
        boolean first = true;
        // 同义双字段（M1 里两个字段语义等价、物理上共用一列）：
        // 只有第一个字段真正持久化，其余标 exist=false —— 否则 MyBatis-Plus
        // 会在 insert 时往同一列写两次，报 "Column specified twice"。
        java.util.Set<String> usedColumns = new java.util.LinkedHashSet<>();
        for (M1Reader.Attr a : agg.attributes) {
            String javaType = JAVA_TYPES.getOrDefault(a.type, "String");
            String column = PoMapping.columnName(alias, a.name);
            // 仅当列名与「驼峰转下划线」不一致时显式标注 @TableField，
            // 否则交给 MyBatis-Plus 默认映射，避免 PO 里堆满冗余注解。
            boolean needExplicitColumn = !column.equals(camelToSnake(a.name));
            boolean duplicateColumn = !usedColumns.add(column);

            if (first) first = false;
            sb.append("    /** ").append(a.label).append(" */\n");
            boolean isPk = explicitPk != null ? explicitPk.equals(a.name) : a.pk;
            if (isPk) {
                sb.append("    @TableId(type = IdType.").append(PoMapping.pkIdType(alias)).append(")\n");
            }
            if (duplicateColumn) {
                sb.append("    @TableField(value = \"").append(column).append("\", exist = false)\n");
            } else if (needExplicitColumn) {
                sb.append("    @TableField(\"").append(column).append("\")\n");
            }
            sb.append("    private ").append(javaType).append(" ").append(a.name).append(";\n\n");
        }

        // 不继承 BaseAuditPO 的 PO 需自行声明公共列
        if (!base) {
            sb.append("    // ---- 公共审计列（不继承 BaseAuditPO 的表自行声明）----\n");
            sb.append("    private LocalDateTime createdAt;\n");
            sb.append("    private LocalDateTime updatedAt;\n");
            sb.append("    private String createdBy;\n");
            sb.append("    private String updatedBy;\n");
            sb.append("    private Integer flag;\n\n");
        }

        sb.append("}\n");
        return sb.toString();
    }

    /** "Customer" → "customer", "OutboundOrder" → "outbound" */
    static String subPackage(String alias) {
        return PoMapping.subPackage(alias);
    }

    static String camelToSnake(String s) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (i > 0 && Character.isUpperCase(c)) sb.append('_');
            sb.append(Character.toLowerCase(c));
        }
        return sb.toString();
    }

    static String capitalize(String s) {
        if (s.isEmpty()) return s;
        return Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }
}
