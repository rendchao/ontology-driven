package com.jjy.application.report;

import com.jjy.domain.registry.ModelRegistry;
import com.jjy.infrastructure.yaml.PhysicalMappingResolver;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * M7 报表执行器：按 M7 声明生成查询 SQL。
 *
 * <p><b>解决的问题</b>：此前 M7 只是一份「摆设」——模型声明了 {@code resultColumns}、
 * {@code sourceObjects}、{@code orderBy}，但查询 SQL 是 handler 里手写的。
 * 改模型不生效，正是「模型一套、代码一套」。</p>
 *
 * <p>本执行器让 M7 成为查询口径的唯一真源：
 * 改 M7 的 {@code resultColumns} → 返回列跟着变，Java 零改动。</p>
 *
 * <p><b>映射解析</b>：表名与列名一律来自 MM 映射模型（{@link PhysicalMappingResolver}），
 * 本类不硬编码任何表名列名。</p>
 *
 * <p><b>安全</b>：字段名与表名都来自模型（可信来源），参数值一律用占位符绑定，
 * 不做字符串拼接值。</p>
 *
 * @author jjy-base
 */
@Slf4j
@Component
public class QueryReportExecutor {

    @Autowired
    private ModelRegistry registry;

    @Autowired
    private PhysicalMappingResolver physicalMapping;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    /**
     * 执行一份 M7 报表声明。
     *
     * @param reportId M7 报表 ID（如 {@code QR-PRODUCT-001}）
     * @param params   查询参数（按 M7 {@code parameters[].name} 取值）
     * @return {@code rows} / {@code total} / {@code columns}
     */
    public Map<String, Object> execute(String reportId, Map<String, Object> params) {
        Map<String, Object> report = findReport(reportId);
        if (report == null) {
            throw new IllegalArgumentException("M7 报表不存在: " + reportId);
        }
        Map<String, Object> safeParams = params == null ? new LinkedHashMap<>() : params;

        String sql = buildSelect(report, safeParams);
        List<Map<String, Object>> rawRows = jdbcTemplate.queryForList(sql, argsOf(report, safeParams));

        // 行键回映射：物理列名（product_name）→ 模型字段名（productName）
        //
        // M7 的 resultColumns 声明的是<b>模型字段</b>，但 JDBC 返回的是<b>物理列</b>。
        // 不回映射的话，返回行的键全是 snake_case，
        // 前端按模型字段名（camelCase）取不到值——M7 声明的语义在返回处断了。
        List<Map<String, Object>> rows = remapRows(report, rawRows);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("rows", rows);
        out.put("total", countOf(report, safeParams));
        out.put("columns", columnsOf(report));
        return out;
    }

    /**
     * 把结果行的键从物理列名回映射为模型字段名。
     *
     * <p>依据 M7 的 {@code resultColumns[].field}（模型字段名）与 MM 的
     * 列映射（字段名 → 列名）。两者对不上时保留原键（不丢数据）。</p>
     */
    private List<Map<String, Object>> remapRows(Map<String, Object> report,
                                               List<Map<String, Object>> rawRows) {
        List<Map<String, Object>> out = new ArrayList<>();
        if (rawRows == null) {
            return out;
        }
        String table = tableOf(report);
        for (Map<String, Object> raw : rawRows) {
            Map<String, Object> row = new LinkedHashMap<>();
            for (Map.Entry<String, Object> e : raw.entrySet()) {
                String colName = String.valueOf(e.getKey());
                String fieldName = fieldOfColumn(table, colName);
                row.put(fieldName == null ? colName : fieldName, e.getValue());
            }
            out.add(row);
        }
        return out;
    }

    /**
     * 物理列名 → 模型字段名（经 MM 的列映射反查）。
     *
     * @return 模型字段名；未声明映射时返回 null
     */
    private String fieldOfColumn(String table, String columnName) {
        Map<String, String> colToField = physicalMapping.columnToField(table);
        if (colToField == null) {
            return null;
        }
        return colToField.get(columnName);
    }

    /**
     * 按 M7 生成 SELECT 语句。
     */
    private String buildSelect(Map<String, Object> report, Map<String, Object> params) {
        StringBuilder sql = new StringBuilder("SELECT ");
        // 结果列
        List<Map<String, Object>> resultColumns = listOf(report, "resultColumns");
        if (resultColumns.isEmpty()) {
            sql.append('*');
        } else {
            boolean first = true;
            for (Map<String, Object> rc : resultColumns) {
                if (!first) {
                    sql.append(", ");
                }
                first = false;
                sql.append(columnOf(str(rc.get("field")), report));
            }
        }
        // FROM：主数据源
        sql.append(" FROM ").append(tableOf(report)).append(" WHERE flag = 0");

        // 查询条件：M7 parameters（按 matchType 决定等值/模糊）+ conditions
        sql.append(whereClause(report, params));
        // 排序
        List<Map<String, Object>> orderBy = listOf(report, "orderBy");
        if (!orderBy.isEmpty()) {
            sql.append(" ORDER BY ");
            boolean first = true;
            for (Map<String, Object> o : orderBy) {
                if (!first) {
                    sql.append(", ");
                }
                first = false;
                String field = str(o.get("field"));
                String dir = o.get("direction") == null ? "ASC" : str(o.get("direction"));
                sql.append(columnOf(field, report)).append(' ').append(dir);
            }
        }
        // 分页
        if (isTrue(report.get("pagination"))) {
            sql.append(" LIMIT ").append(pageSize(report));
        }
        return sql.toString();
    }

    /**
     * 组装查询参数（顺序必须与 {@link #buildSelect} 的占位符顺序一致）。
     *
     * <p>LIKE 匹配时给值加 {@code %}——加在<b>参数值</b>上而不是拼进 SQL 字符串，
     * 因此不存在注入风险。</p>
     */
    private Object[] argsOf(Map<String, Object> report, Map<String, Object> params) {
        List<Object> args = new ArrayList<>();
        for (Map<String, Object> p : listOf(report, "parameters")) {
            String name = str(p.get("name"));
            if (name == null) {
                continue;
            }
            Object v = params.get(name);
            if (v == null || String.valueOf(v).trim().isEmpty()) {
                continue;
            }
            args.add(likeAware(p, v));
        }
        for (Map<String, Object> c : listOf(report, "conditions")) {
            Object v = c.get("value");
            if (v == null) {
                continue;
            }
            args.add(v);
        }
        return args.toArray();
    }

    /**
     * 参数匹配方式：LIKE 时把值包成 {@code %value%}，EQ 原样返回。
     *
     * <p><b>匹配方式来自 M7 声明</b>（{@code parameters[].matchType}），
     * 不在代码里按字段名猜测——「名称模糊、状态精确」是业务口径，
     * 必须由模型说了算。</p>
     */
    private Object likeAware(Map<String, Object> param, Object value) {
        if (!"LIKE".equalsIgnoreCase(str(param.get("matchType")))) {
            return value;
        }
        return "%" + String.valueOf(value).trim() + "%";
    }

    /**
     * 统计总数（与查询同条件）。
     */
    private int countOf(Map<String, Object> report, Map<String, Object> params) {
        StringBuilder sql = new StringBuilder("SELECT COUNT(1) FROM ")
                .append(tableOf(report)).append(" WHERE flag = 0");
        // 与 buildSelect 共用同一段 WHERE 构造——两处各写一遍必然漂移
        // （曾经 count 用等值、select 用别的，total 与 rows 对不上）
        sql.append(whereClause(report, params));
        Integer total = jdbcTemplate.queryForObject(sql.toString(), Integer.class, argsOf(report, params));
        return total == null ? 0 : total;
    }

    /**
     * 构造 WHERE 子句（返回以 {@code AND} 开头的片段）。
     *
     * <p><b>由 M7 声明决定</b>：</p>
     * <ul>
     *   <li>{@code parameters[]} —— 每个参数按 {@code matchType} 决定等值还是模糊；</li>
     *   <li>{@code conditions[]} —— 显式声明的附加条件（field + operator + value）。</li>
     * </ul>
     * <p>参数值一律用占位符绑定，不拼进 SQL 字符串。</p>
     */
    private String whereClause(Map<String, Object> report, Map<String, Object> params) {
        StringBuilder sb = new StringBuilder();
        // parameters：按 matchType 生成条件
        for (Map<String, Object> p : listOf(report, "parameters")) {
            String name = str(p.get("name"));
            if (name == null) {
                continue;
            }
            Object v = params.get(name);
            if (v == null || String.valueOf(v).trim().isEmpty()) {
                continue;
            }
            String op = "LIKE".equalsIgnoreCase(str(p.get("matchType"))) ? "LIKE" : "=";
            sb.append(" AND ").append(columnOf(name, report)).append(' ').append(op).append(" ?");
        }
        // conditions：显式附加条件
        for (Map<String, Object> c : listOf(report, "conditions")) {
            String field = str(c.get("field"));
            if (field == null) {
                continue;
            }
            String op = c.get("operator") == null ? "=" : str(c.get("operator"));
            Object v = c.get("value");
            if (v == null) {
                continue;
            }
            sb.append(" AND ").append(columnOf(field, report)).append(' ').append(op).append(" ?");
        }
        return sb.toString();
    }

    /**
     * 返回列定义（供前端渲染表头）。
     */
    private List<Map<String, Object>> columnsOf(Map<String, Object> report) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> rc : listOf(report, "resultColumns")) {
            Map<String, Object> col = new LinkedHashMap<>();
            col.put("field", rc.get("field"));
            col.put("label", rc.get("label"));
            col.put("column", columnOf(str(rc.get("field")), report));
            out.add(col);
        }
        return out;
    }

    /** 每页行数（reportOptions.pageSize，缺省 20） */
    @SuppressWarnings("unchecked")
    private int pageSize(Map<String, Object> report) {
        Object opts = report.get("reportOptions");
        if (opts instanceof Map) {
            Object v = ((Map<String, Object>) opts).get("pageSize");
            if (v != null) {
                try {
                    return Integer.parseInt(String.valueOf(v));
                } catch (NumberFormatException ignore) {
                    // 保持默认页长
                }
            }
        }
        return 20;
    }

    // ══════════════════════════════════════════════════════════
    //  模型解析
    // ══════════════════════════════════════════════════════════

    /** 找 M7 报表声明 */
    @SuppressWarnings("unchecked")
    private Map<String, Object> findReport(String reportId) {
        for (Object obj : registry.getQueryReports().values()) {
            if (!(obj instanceof Map)) {
                continue;
            }
            Map<String, Object> r = (Map<String, Object>) obj;
            if (reportId.equals(r.get("id")) || reportId.equals(r.get("queryReportId"))) {
                return r;
            }
        }
        return null;
    }

    /** 主数据源的物理表名（经 MM 映射解析） */
    private String tableOf(Map<String, Object> report) {
        String alias = primaryAlias(report);
        String t = physicalMapping.tableName(alias);
        if (t == null) {
            throw new IllegalStateException("M7 报表主数据源 " + alias + " 无法解析到物理表");
        }
        return t;
    }

    /**
     * 字段名 → 物理列名（经 MM 映射解析）。
     *
     * <p>按字段所属的主数据源 alias 解析表与列——不硬编码任何表名或聚合别名。</p>
     */
    private String columnOf(String field, Map<String, Object> report) {
        if (field == null) {
            throw new IllegalArgumentException("字段名不能为空");
        }
        String alias = primaryAlias(report);
        String table = physicalMapping.tableName(alias);
        String col = physicalMapping.column(table, field);
        if (col == null) {
            throw new IllegalArgumentException("字段 " + field + " 在 MM 中无列映射");
        }
        return col;
    }

    /** 报表主数据源的聚合别名（sourceObjects[].alias） */
    private String primaryAlias(Map<String, Object> report) {
        List<Map<String, Object>> sources = listOf(report, "sourceObjects");
        for (Map<String, Object> s : sources) {
            if (isTrue(s.get("primary"))) {
                String a = str(s.get("alias"));
                if (a != null) {
                    return a;
                }
            }
        }
        for (Map<String, Object> s : sources) {
            String a = str(s.get("alias"));
            if (a != null) {
                return a;
            }
        }
        throw new IllegalStateException("M7 报表未声明 sourceObjects");
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> listOf(Map<String, Object> parent, String key) {
        List<Map<String, Object>> out = new ArrayList<>();
        Object arr = parent.get(key);
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

    private boolean isTrue(Object v) {
        if (v == null) {
            return false;
        }
        if (v instanceof Boolean) {
            return (Boolean) v;
        }
        String s = String.valueOf(v).trim();
        return "true".equalsIgnoreCase(s) || "1".equals(s);
    }

    private String str(Object v) {
        if (v == null) {
            return null;
        }
        String s = v.toString().trim();
        return s.isEmpty() ? null : s;
    }
}
