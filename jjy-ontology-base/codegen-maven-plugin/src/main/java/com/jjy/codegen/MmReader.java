package com.jjy.codegen;

import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 读 MM 映射模型 (mm-mapping-model.yaml)，解析出「M1 字段 → DB 列」的映射契约。
 *
 * <p>MM 是持久化层的映射契约层：
 * <ul>
 *   <li>M1 说「有什么字段、什么语义」</li>
 *   <li>MM 说「落到哪张表哪个列、什么 SQL 类型」</li>
 * </ul>
 * 目标态 DDL 由 MM 驱动生成 —— 这样物理结构不再是手写的，
 * 而是从映射契约单向推导出来的。
 *
 * <p>技术表（{@code aggregateRef: null}）没有 M1 对应节点，其列直接按 DB 现状登记，
 * 不参与 M1 ↔ MM 的字段级校验。
 */
public final class MmReader {

    private MmReader() {}

    /** 一条列映射 */
    public static final class Column {
        public final String fieldPath;   // Customer.customerId；技术表可能为空
        public final String fieldName;   // customerId；技术表可能为空
        public final String columnName;  // customer_id
        public final String columnType;  // varchar(64)
        public final String description;
        public final boolean pk;         // 主键列

        public Column(String fieldPath, String fieldName, String columnName,
                      String columnType, String description) {
            this(fieldPath, fieldName, columnName, columnType, description, false);
        }

        public Column(String fieldPath, String fieldName, String columnName,
                      String columnType, String description, boolean pk) {
            this.fieldPath = fieldPath;
            this.fieldName = fieldName;
            this.columnName = columnName;
            this.columnType = columnType;
            this.description = description;
            this.pk = pk;
        }
    }

    /** 一张表 */
    public static final class Table {
        public final String tableName;
        public final String tableType;     // MAIN / DETAIL
        public final String mappedEntity;  // Customer
        public final List<Column> columns = new ArrayList<>();

        public Table(String tableName, String tableType, String mappedEntity) {
            this.tableName = tableName;
            this.tableType = tableType;
            this.mappedEntity = mappedEntity;
        }

        /** 找某字段（不含实体名）对应的列映射 */
        public Column columnOf(String fieldName) {
            for (Column c : columns) {
                if (fieldName.equals(c.fieldName)) return c;
            }
            return null;
        }
    }

    /** 一条 mapping（一个聚合 → 一到多张表） */
    public static final class Mapping {
        public final String mappingId;
        public final String aggregateRef;  // null = 技术表
        public final List<Table> tables = new ArrayList<>();

        public Mapping(String mappingId, String aggregateRef) {
            this.mappingId = mappingId;
            this.aggregateRef = aggregateRef;
        }

        public boolean isTechnical() {
            return aggregateRef == null || aggregateRef.isEmpty();
        }
    }

    /** MM 解析结果 */
    public static final class Mm {
        public final List<String> defaultColumns = new ArrayList<>();
        public final List<Mapping> mappings = new ArrayList<>();

        /** 实体别名 → 表（同一实体可能映射到多张表，取第一张 MAIN） */
        public Table tableOf(String entityAlias) {
            for (Mapping m : mappings) {
                for (Table t : m.tables) {
                    if (entityAlias.equals(t.mappedEntity)) return t;
                }
            }
            return null;
        }

        /** 实体别名 → 全部表 */
        public List<Table> tablesOf(String entityAlias) {
            List<Table> out = new ArrayList<>();
            for (Mapping m : mappings) {
                for (Table t : m.tables) {
                    if (entityAlias.equals(t.mappedEntity)) out.add(t);
                }
            }
            return out;
        }

        /** 全部技术表 */
        public List<Table> technicalTables() {
            List<Table> out = new ArrayList<>();
            for (Mapping m : mappings) {
                if (!m.isTechnical()) continue;
                out.addAll(m.tables);
            }
            return out;
        }

        /**
         * 实体别名 → 表名（MM 是表名的唯一真源）。
         * 同一实体映射到多张表时取第一张。
         */
        public String tableNameOf(String entityAlias) {
            Table t = tableOf(entityAlias);
            return t == null ? null : t.tableName;
        }

        /**
         * 实体别名 + 字段名 → 列映射。
         * <p>同义双字段（两个 M1 字段共用一列）时取第一个声明的。</p>
         */
        public Column columnOf(String entityAlias, String fieldName) {
            for (Table t : tablesOf(entityAlias)) {
                Column c = t.columnOf(fieldName);
                if (c != null) return c;
            }
            return null;
        }

        /**
         * 实体别名 → 主键列名。
         * <p>判定顺序：MM 里标了 {@code pk: true} 的列 → 否则该实体表中
         * 与 {@link PoMapping#pkField(String)} 同名的列 → 否则第一列。</p>
         */
        public String pkColumnOf(String entityAlias) {
            Table t = tableOf(entityAlias);
            if (t == null) return null;
            for (Column c : t.columns) {
                if (c.pk) return c.columnName;
            }
            String pkField = PoMapping.pkField(entityAlias);
            if (pkField != null) {
                Column c = t.columnOf(pkField);
                if (c != null) return c.columnName;
            }
            return t.columns.isEmpty() ? null : t.columns.get(0).columnName;
        }
    }

    /** 从 yaml 文件读 MM */
    public static Mm read(Path yamlFile) throws IOException {
        try (InputStream in = Files.newInputStream(yamlFile)) {
            Object root = new Yaml().load(in);
            if (!(root instanceof Map)) {
                throw new IOException("MM yaml 根节点不是 Map: " + yamlFile);
            }
            Map<?, ?> rootMap = (Map<?, ?>) root;
            Mm mm = new Mm();

            Object defaults = rootMap.get("default_columns");
            if (defaults instanceof List) {
                for (Object d : (List<?>) defaults) {
                    if (d != null) mm.defaultColumns.add(d.toString());
                }
            }

            Object mappingsObj = rootMap.get("mappings");
            if (!(mappingsObj instanceof List)) {
                throw new IOException("MM yaml 缺 'mappings' 列表: " + yamlFile);
            }
            for (Object mpObj : (List<?>) mappingsObj) {
                if (!(mpObj instanceof Map)) continue;
                Map<?, ?> mp = (Map<?, ?>) mpObj;
                String mappingId = str(mp.get("mappingId"));
                String aggregateRef = str(mp.get("aggregateRef"));
                Mapping mapping = new Mapping(mappingId, aggregateRef);

                Object tablesObj = mp.get("tables");
                if (tablesObj instanceof List) {
                    for (Object tObj : (List<?>) tablesObj) {
                        if (!(tObj instanceof Map)) continue;
                        Map<?, ?> t = (Map<?, ?>) tObj;
                        Table table = new Table(
                                str(t.get("tableName")),
                                str(t.get("tableType")),
                                str(t.get("mappedEntity")));
                        Object cms = t.get("columnMappings");
                        if (cms instanceof List) {
                            for (Object cObj : (List<?>) cms) {
                                if (!(cObj instanceof Map)) continue;
                                Map<?, ?> c = (Map<?, ?>) cObj;
                                String fp = str(c.get("fieldPath"));
                                String fname = null;
                                if (fp != null) {
                                    int dot = fp.indexOf('.');
                                    fname = dot >= 0 ? fp.substring(dot + 1) : fp;
                                }
                                table.columns.add(new Column(
                                        fp,
                                        fname,
                                        str(c.get("columnName")),
                                        str(c.get("columnType")),
                                        str(c.get("description")),
                                        bool(c.get("pk"))));
                            }
                        }
                        mapping.tables.add(table);
                    }
                }
                mm.mappings.add(mapping);
            }
            return mm;
        }
    }

    private static String str(Object o) {
        if (o == null) return null;
        String s = o.toString().trim();
        if ("null".equals(s) || "~".equals(s)) return null;
        return s;
    }

    private static boolean bool(Object o) {
        if (o == null) return false;
        if (o instanceof Boolean) return (Boolean) o;
        return Boolean.parseBoolean(o.toString());
    }
}
