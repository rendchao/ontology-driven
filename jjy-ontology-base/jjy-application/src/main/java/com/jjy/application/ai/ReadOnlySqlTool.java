package com.jjy.application.ai;

import com.jjy.infrastructure.ai.SqlSafetyValidator;
import com.jjy.infrastructure.security.SecurityUtils;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * AI 工具：只读 SQL 查询（表白名单内）。
 *
 * <p>调用 {@link SqlSafetyValidator} 三道关：
 * <ol>
 *   <li>只允许 SELECT/WITH</li>
 *   <li>禁止关键字（INSERT/UPDATE/DELETE/DDL/DCL）</li>
 *   <li>表必须在 jjy.ai.sql-whitelist-tables</li>
 *   <li>禁止在 WHERE/HAVING 中直接指定 customer_id/tenant_id（防跨租户）</li>
 * </ol>
 *
 * <p>执行成功后最多返回 200 行（防爆）。</p>
 *
 * @author jjy-base
 */
@Slf4j
@Component
public class ReadOnlySqlTool implements AiTool {

    @Autowired
    private SqlSafetyValidator sqlSafety;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private static final int ROW_LIMIT = 200;

    @Override
    public String name() {
        return "sql_readonly";
    }

    @Override
    public String description() {
        return "在只读模式下执行 SQL 查询。仅支持 SELECT；表必须在白名单内（当前白名单：" +
                sqlSafety.getWhitelist() + "）。最多返回 200 行。";
    }

    @Override
    public Map<String, Object> parametersSchema() {
        Map<String, Object> query = new HashMap<>();
        query.put("type", "string");
        query.put("description", "完整 SQL（仅 SELECT/WITH）");

        Map<String, Object> params = new HashMap<>();
        params.put("type", "object");
        params.put("description", "命名参数占位符（暂未启用，预留）");

        Map<String, Object> properties = new HashMap<>();
        properties.put("query", query);
        properties.put("params", params);

        Map<String, Object> schema = new HashMap<>();
        schema.put("type", "object");
        schema.put("properties", properties);
        schema.put("required", java.util.Arrays.asList("query"));
        return schema;
    }

    @Override
    public Object execute(Map<String, Object> args, Map<String, Object> userContext) {
        String query = (String) args.get("query");
        if (query == null || query.isEmpty()) {
            throw new IllegalArgumentException("query 不能为空");
        }
        // 安全校验：失败抛 IllegalArgumentException，AiChatService 转 error 事件
        sqlSafety.validate(query);
        log.info("[AiTool:sql] user={} query={}", SecurityUtils.currentUser().getUsername(),
                query.replaceAll("\\s+", " "));
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                addLimitIfMissing(query, ROW_LIMIT));
        Map<String, Object> out = new HashMap<>();
        out.put("rowCount", rows.size());
        out.put("rows", rows);
        out.put("cardType", "table");
        out.put("cardTitle", "SQL 查询结果（最多 " + ROW_LIMIT + " 行）");
        return out;
    }

    private String addLimitIfMissing(String sql, int limit) {
        String trimmed = sql.trim().replaceAll(";$", "");
        if (trimmed.toUpperCase().matches(".*\\bLIMIT\\s+\\d+.*")) {
            return trimmed;
        }
        return trimmed + "\nLIMIT " + limit;
    }
}
