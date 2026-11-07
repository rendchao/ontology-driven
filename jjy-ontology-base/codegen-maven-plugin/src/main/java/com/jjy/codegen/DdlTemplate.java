package com.jjy.codegen;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 生成目标态 CREATE TABLE DDL（MySQL 方言）。
 *
 * <p><b>DDL 由 MM 映射模型驱动</b>，不再由 M1 的粗粒度类型推导：
 * <ul>
 *   <li>列名 / SQL 类型 ← MM 的 columnMappings（{@code columnName} / {@code columnType}）</li>
 *   <li>COMMENT / NOT NULL ← M1 的字段语义（label / required）</li>
 *   <li>PRIMARY KEY ← PoMapping 登记的主键字段</li>
 * </ul>
 * 这样物理结构是「M1 语义 + MM 映射」共同推导出来的，与真实库结构同源。
 */
public final class DdlTemplate {

    private DdlTemplate() {}

    /** M1 type → SQL 类型（仅当 MM 未声明 columnType 时的兜底） */
    private static final Map<String, String> FALLBACK_TYPES = new LinkedHashMap<>();
    static {
        FALLBACK_TYPES.put("String", "VARCHAR(64)");
        FALLBACK_TYPES.put("Integer", "INT");
        FALLBACK_TYPES.put("Long", "BIGINT");
        FALLBACK_TYPES.put("Boolean", "TINYINT(1)");
        FALLBACK_TYPES.put("Double", "DOUBLE");
        FALLBACK_TYPES.put("Float", "FLOAT");
        FALLBACK_TYPES.put("Money", "DECIMAL(18,4)");
        FALLBACK_TYPES.put("Decimal", "DECIMAL(18,4)");
        FALLBACK_TYPES.put("Date", "DATE");
        FALLBACK_TYPES.put("DateTime", "DATETIME");
        FALLBACK_TYPES.put("DictionaryRef", "VARCHAR(64)");
        FALLBACK_TYPES.put("AggregateRootRef", "VARCHAR(64)");
        FALLBACK_TYPES.put("Enum", "VARCHAR(32)");
    }

    /**
     * 渲染一张表的 CREATE TABLE（MM 未声明该实体时的兜底：按 M1 类型推导）。
     */
    public static String render(M1Reader.Aggregate agg) {
        StringBuilder sb = new StringBuilder(1024);
        String explicitPk = PoMapping.resolvePkField(agg.alias, agg.attributes);
        sb.append("-- ").append(agg.id).append(" ").append(agg.name)
          .append(" → ").append(PoMapping.tableName(agg.alias)).append(" (由 M1 推导，MM 未声明)\n");
        sb.append("CREATE TABLE IF NOT EXISTS `").append(PoMapping.tableName(agg.alias)).append("` (\n");
        boolean first = true;
        for (M1Reader.Attr a : agg.attributes) {
            if (!first) sb.append(",\n");
            first = false;
            String type = FALLBACK_TYPES.getOrDefault(a.type, "VARCHAR(64)");
            sb.append("  ").append(PoMapping.columnName(agg.alias, a.name)).append(" ").append(type);
            boolean isPk = explicitPk != null && explicitPk.equals(a.name);
            if (isPk) {
                // MySQL 要求主键列必须 NOT NULL
                sb.append(" NOT NULL PRIMARY KEY");
            } else if (a.required) sb.append(" NOT NULL");
            if (a.unique && !isPk) sb.append(" UNIQUE");
            sb.append(" COMMENT '").append(a.label.replace("'", "''")).append("'");
        }
        sb.append(",\n  flag TINYINT(1) NOT NULL DEFAULT 0");
        sb.append(",\n  created_by VARCHAR(64) DEFAULT NULL");
        sb.append(",\n  created_at DATETIME DEFAULT CURRENT_TIMESTAMP");
        sb.append(",\n  updated_by VARCHAR(64) DEFAULT NULL");
        sb.append(",\n  updated_at DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP");
        sb.append("\n) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci")
          .append(" COMMENT='").append(agg.name).append("';\n");
        return sb.toString();
    }

    /**
     * 渲染一张表的 CREATE TABLE。
     *
     * @param agg  M1 聚合（或子实体），提供字段语义
     * @param table MM 里的表定义，提供列名与 SQL 类型
     */
    public static String render(M1Reader.Aggregate agg, MmReader.Table table) {
        StringBuilder sb = new StringBuilder(1024);
        // 主键优先取 MM 显式声明（pk: true），其次按 M1 语义推导（required + unique）
        String explicitPk = PoMapping.resolvePkField(agg.alias, agg.attributes);
        MmReader.Mm mmRef = PoMapping.mm();
        String mmPk = (mmRef == null || agg.alias == null) ? null : mmRef.pkColumnOf(agg.alias);
        if (mmPk != null && table != null) {
            // MM 声明的主键列名 → 反查对应的 M1 字段名
            for (MmReader.Column c : table.columns) {
                if (mmPk.equals(c.columnName) && c.fieldName != null) {
                    explicitPk = c.fieldName;
                    break;
                }
            }
        }

        sb.append("-- ").append(agg.id).append(" ").append(agg.name)
          .append(" → ").append(table.tableName).append(" (由 M1 + MM 生成)\n");
        sb.append("CREATE TABLE IF NOT EXISTS `").append(table.tableName).append("` (\n");

        // M1 字段语义索引：字段名 → Attr
        Map<String, M1Reader.Attr> attrIndex = new LinkedHashMap<>();
        for (M1Reader.Attr a : agg.attributes) {
            attrIndex.put(a.name, a);
        }

        boolean first = true;
        for (MmReader.Column c : table.columns) {
            if (!first) sb.append(",\n");
            first = false;

            M1Reader.Attr attr = c.fieldName == null ? null : attrIndex.get(c.fieldName);
            String type = c.columnType != null ? c.columnType
                    : (attr != null ? FALLBACK_TYPES.getOrDefault(attr.type, "VARCHAR(64)") : "VARCHAR(64)");

            sb.append("  ").append(c.columnName).append(" ").append(type);

            boolean isPk = explicitPk != null && explicitPk.equals(c.fieldName);
            if (isPk) {
                // MySQL 要求主键列必须 NOT NULL：PRIMARY KEY 隐含 NOT NULL，
                // 但列定义里显式写 NULL 会报 "All parts of a PRIMARY KEY must be NOT NULL"
                sb.append(" NOT NULL PRIMARY KEY");
            } else if (attr != null && attr.required) {
                sb.append(" NOT NULL");
            }
            if (attr != null && attr.unique && !isPk) {
                sb.append(" UNIQUE");
            }

            String comment = attr != null ? attr.label : c.description;
            if (comment == null || comment.isEmpty()) comment = c.description;
            if (comment != null && !comment.isEmpty()) {
                sb.append(" COMMENT '").append(comment.replace("'", "''")).append("'");
            }
        }
        sb.append(",\n  flag TINYINT(1) NOT NULL DEFAULT 0");
        sb.append(",\n  created_by VARCHAR(64) DEFAULT NULL");
        sb.append(",\n  created_at DATETIME DEFAULT CURRENT_TIMESTAMP");
        sb.append(",\n  updated_by VARCHAR(64) DEFAULT NULL");
        sb.append(",\n  updated_at DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP");
        sb.append("\n) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci");
        sb.append(" COMMENT='").append(agg.name).append("';\n");
        return sb.toString();
    }

    /**
     * 渲染技术表（无 M1 对应节点）的 CREATE TABLE —— 列与类型全部来自 MM。
     */
    public static String renderTechnical(MmReader.Table table) {
        StringBuilder sb = new StringBuilder(512);
        sb.append("-- 技术表 ").append(table.tableName).append(" (由 MM 生成，无 M1 对应节点)\n");
        sb.append("CREATE TABLE IF NOT EXISTS `").append(table.tableName).append("` (\n");
        boolean first = true;
        for (MmReader.Column c : table.columns) {
            if (!first) sb.append(",\n");
            first = false;
            sb.append("  ").append(c.columnName).append(" ")
              .append(c.columnType != null ? c.columnType : "VARCHAR(64)");
            if (c.description != null && !c.description.isEmpty()) {
                sb.append(" COMMENT '").append(c.description.replace("'", "''")).append("'");
            }
        }
        sb.append("\n) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;\n");
        return sb.toString();
    }
}
