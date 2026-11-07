package com.jjy.infrastructure.yaml;

import com.jjy.domain.registry.ModelRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 物理映射解析器：把「模型别名」解析为「物理表名 + 主键列」。
 *
 * <p><b>这是消除硬编码映射表的关键组件。</b>业务代码需要知道某个聚合根对应的物理表与主键列，
 * 但这些信息已经在 MM（映射契约）里声明过了，代码里不应该再写一遍
 * {@code switch(alias) → 表名} 与 {@code switch(表名) → 主键列}。</p>
 *
 * <p>解析来源（优先级从高到低）：</p>
 * <ol>
 *   <li>MM 模型的 {@code tables[].tableName} + {@code mappedEntity}；</li>
 *   <li>MM 的 {@code columnMappings[].fieldPath} 中形如 {@code Alias.xxxId} 的主键列；</li>
 *   <li>无 MM 声明时，按命名规范兜底推导（{@code t_ + alias 蛇形}、{@code + _id}）。</li>
 * </ol>
 *
 * <p>兜底推导保留了底座的"开箱即用"能力，但只要 MM 里声明了映射，就一律以模型为准。</p>
 *
 * @author jjy-base
 */
@Slf4j
@Component
public class PhysicalMappingResolver {

    @Autowired
    private ModelRegistry registry;

    /** alias → 表名 */
    private final ConcurrentHashMap<String, String> aliasToTable = new ConcurrentHashMap<>();
    /** 表名 → 主键列 */
    private final ConcurrentHashMap<String, String> tableToPk = new ConcurrentHashMap<>();
    /** "表名.字段名" → 列名 */
    private final ConcurrentHashMap<String, String> fieldToColumn = new ConcurrentHashMap<>();
    /** 表名 → （物理列名 → 模型字段名）反向映射 */
    private final ConcurrentHashMap<String, Map<String, String>> tableColToField = new ConcurrentHashMap<>();

    /**
     * 解析实体别名对应的物理表名。
     *
     * @param alias 模型中的实体别名（如 Customer）
     * @return 物理表名；无法解析时返回按命名规范推导的兜底值
     */
    public String tableName(String alias) {
        if (alias == null || alias.trim().isEmpty()) {
            return null;
        }
        String cached = aliasToTable.get(alias);
        if (cached != null) {
            return cached;
        }
        resolveFromModel();
        String t = aliasToTable.get(alias);
        if (t == null) {
            t = "t_" + toSnake(alias);
            log.debug("[PhysicalMap] 未在 MM 中找到 {} 的表声明，按命名规范推导为 {}", alias, t);
        }
        return t;
    }

    /**
     * 解析物理表对应的主键列。
     *
     * @param table 物理表名
     * @return 主键列名；无法解析时返回按命名规范推导的兜底值
     */
    public String pkColumn(String table) {
        if (table == null || table.trim().isEmpty()) {
            return null;
        }
        String cached = tableToPk.get(table);
        if (cached != null) {
            return cached;
        }
        resolveFromModel();
        String pk = tableToPk.get(table);
        if (pk == null) {
            pk = toSnake(table.startsWith("t_") ? table.substring(2) : table) + "_id";
        }
        return pk;
    }

    /**
     * 取某表的「列名 → 字段名」反查表。
     *
     * <p>供结果行回映射使用：M7 的 resultColumns 声明的是<b>模型字段名</b>
     * （camelCase），而 JDBC 返回的是<b>物理列名</b>（snake_case）。
     * 不回映射的话，返回行的键全是 snake_case，
     * 前端按模型字段名取不到值——M7 声明的语义在返回处断了。</p>
     *
     * @param table 物理表名
     * @return 列名 → 字段名；表未声明映射时返回空表
     */
    public Map<String, String> columnToField(String table) {
        if (table == null) {
            return java.util.Collections.emptyMap();
        }
        Map<String, String> cached = tableColToField.get(table);
        if (cached != null) {
            return cached;
        }
        resolveFromModel();
        Map<String, String> out = tableColToField.get(table);
        return out == null ? java.util.Collections.<String, String>emptyMap() : out;
    }

    /**
     * 取 MM 声明的默认列（{@code default_columns}）。
     *
     * <p>默认列是所有表统一的技术列（{@code created_by} / {@code flag} 等），
     * 它们不出现在各表的 {@code columnMappings} 里——但 DDL 生成、
     * 逻辑删除、审计字段填充都要用。此前该声明<b>无人消费</b>，
     * 且连装载都没有；现在由 ModelLoader 装载、此处读取。</p>
     *
     * @return 默认列名列表；未声明返回空列表
     */
    public List<String> defaultColumns() {
        return new ArrayList<>(registry.getDefaultColumns());
    }

    /**
     * 解析物理表中某字段对应的列名。
     *
     * <p>业务 Handler 拼 SQL 时<b>不应该出现列名字面量</b>——列名已在 MM 的
     * {@code columnMappings[].fieldPath ↔ columnName} 里声明过，
     * 代码里再写一遍就是漂移的温床（M1 加个字段改名，Handler 就写错列名）。</p>
     *
     * @param table 物理表名
     * @param field 模型字段名（如 productName）
     * @return 列名；MM 未声明时按命名规范兜底推导（snake_case）
     */
    public String column(String table, String field) {
        if (table == null || field == null || field.trim().isEmpty()) {
            return null;
        }
        String key = table + "." + field;
        String cached = fieldToColumn.get(key);
        if (cached != null) {
            return cached;
        }
        resolveFromModel();
        String col = fieldToColumn.get(key);
        if (col == null) {
            col = toSnake(field);
            log.debug("[PhysicalMap] MM 未声明 {}.{} 的列名，按命名规范推导为 {}",
                    table, field, col);
        }
        return col;
    }

    /**
     * 从 MM 模型解析映射关系（只解析一次，之后走缓存）。
     */
    @SuppressWarnings("unchecked")
    private void resolveFromModel() {
        if (!aliasToTable.isEmpty() || !tableToPk.isEmpty()) {
            return;
        }
        synchronized (this) {
            if (!aliasToTable.isEmpty() || !tableToPk.isEmpty()) {
                return;
            }
            for (Object mappingObj : registry.getMappings().values()) {
                if (!(mappingObj instanceof Map)) {
                    continue;
                }
                Map<String, Object> mapping = (Map<String, Object>) mappingObj;
                Object tablesObj = mapping.get("tables");
                if (!(tablesObj instanceof List)) {
                    continue;
                }
                for (Object tableObj : (List<Object>) tablesObj) {
                    if (!(tableObj instanceof Map)) {
                        continue;
                    }
                    Map<String, Object> table = (Map<String, Object>) tableObj;
                    String tableName = str(table.get("tableName"));
                    String mappedEntity = str(table.get("mappedEntity"));
                    if (tableName == null) {
                        continue;
                    }
                    if (mappedEntity != null) {
                        aliasToTable.put(mappedEntity, tableName);
                    }
                    // 主键列：取 fieldPath 中形如 Alias.xxxId 的那一列
                    Object colsObj = table.get("columnMappings");
                    if (colsObj instanceof List) {
                        for (Object colObj : (List<Object>) colsObj) {
                            if (!(colObj instanceof Map)) {
                                continue;
                            }
                            Map<String, Object> col = (Map<String, Object>) colObj;
                            String fieldPath = str(col.get("fieldPath"));
                            String columnName = str(col.get("columnName"));
                            if (fieldPath == null || columnName == null) {
                                continue;
                            }
                            String[] parts = fieldPath.split("\\.");
                            if (parts.length != 2) {
                                continue;
                            }
                            String entity = parts[0];
                            String field = parts[1];
                            // 字段 → 列名（供业务 Handler 拼 SQL 时按模型解析列名）
                            fieldToColumn.put(tableName + "." + field, columnName);
                            // 列名 → 字段名（反向，供结果行回映射为模型字段名）
                            Map<String, String> rev = tableColToField.get(tableName);
                            if (rev == null) {
                                rev = new java.util.HashMap<>();
                                tableColToField.put(tableName, rev);
                            }
                            rev.put(columnName, field);
                            // 主键判定：字段名以 Id 结尾，或 localId 指定
                            if (field.endsWith("Id") && !tableToPk.containsKey(tableName)) {
                                tableToPk.put(tableName, columnName);
                                if (mappedEntity == null) {
                                    aliasToTable.put(entity, tableName);
                                }
                            }
                        }
                    }
                }
            }
            log.info("[PhysicalMap] 从 MM 模型解析出 {} 个表映射、{} 个主键列",
                    aliasToTable.size(), tableToPk.size());
        }
    }

    /** 已解析的映射（供自检端点输出） */
    public Map<String, String> aliasToTableMap() {
        resolveFromModel();
        return aliasToTable;
    }

    /** 已解析的主键列（供自检端点输出） */
    public Map<String, String> tableToPkMap() {
        resolveFromModel();
        return tableToPk;
    }

    private String str(Object v) {
        if (v == null) {
            return null;
        }
        String s = v.toString().trim();
        return s.isEmpty() ? null : s;
    }

    /** CamelCase → snake_case */
    private String toSnake(String s) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (Character.isUpperCase(c) && i > 0) {
                sb.append('_');
            }
            sb.append(Character.toLowerCase(c));
        }
        return sb.toString();
    }
}
