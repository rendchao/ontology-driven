package com.jjy.infrastructure.ai;

import com.jjy.domain.registry.ModelRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * AI 工具 SQL 安全校验器。
 *
 * <p>三条硬约束：
 * <ol>
 *   <li>只允许 SELECT（禁止 INSERT/UPDATE/DELETE/DDL/DCL）</li>
 *   <li>表必须在白名单内</li>
 *   <li>禁止跨表/跨租户的越权条件注入（AI 不能改 customerId/tenant_id 过滤）</li>
 * </ol>
 *
 * <p>校验失败的 SQL 一律 throw，进入死信/拒绝路径。</p>
 *
 * <h3>白名单从哪来</h3>
 * <p>白名单有<b>两个来源，配置优先</b>：</p>
 * <ol>
 *   <li><b>显式配置</b>：{@code jjy.ai.sql-whitelist-tables} 非空时，以配置为准
 *       （用于「只开放少量表」的场景，如生产环境收窄权限）；</li>
 *   <li><b>模型推导</b>：配置留空时，自动取 MM 映射模型声明的全部物理表——
 *       即「模型里存在映射的表，就是这个系统里合法的表」。</li>
 * </ol>
 * <p>这样白名单本身也是模型的投影，不会在代码里留一份需要人工同步的表名清单。</p>
 *
 * @author jjy-base
 */
@Slf4j
@Component
public class SqlSafetyValidator {

    /** 禁止的 SQL 关键字（全大写比对，词边界） */
    private static final List<String> FORBIDDEN_KEYWORDS = Arrays.asList(
            "INSERT", "UPDATE", "DELETE", "MERGE", "REPLACE",
            "CREATE", "DROP", "ALTER", "TRUNCATE", "RENAME",
            "GRANT", "REVOKE", "COMMIT", "ROLLBACK",
            "CALL", "EXEC", "EXECUTE", "LOAD_FILE", "OUTFILE",
            "INTO", "SET",  // 防 SET @@global.xxx 注入
            "SLEEP", "BENCHMARK"
    );

    /** 跨租户字段名（AI 工具禁止在 WHERE 里用这些字段） */
    private static final List<String> CROSS_TENANT_FIELDS = Arrays.asList(
            "customer_id", "tenant_id", "owner_id", "creator_id"
    );

    private static final Pattern TABLE_PATTERN =
            Pattern.compile("\\b(?:FROM|JOIN|INTO|UPDATE)\\s+[`\"]?([A-Za-z_][A-Za-z0-9_]*)[`\"]?",
                    Pattern.CASE_INSENSITIVE);

    @Autowired
    private ModelRegistry registry;

    /** 显式配置的白名单（小写） */
    private final Set<String> configured;

    /** 模型推导出的白名单（小写） */
    private final Set<String> derived = ConcurrentHashMap.newKeySet();

    /** 生效中的白名单（小写） */
    private final Set<String> whitelist = ConcurrentHashMap.newKeySet();

    public SqlSafetyValidator(AiProperties props) {
        this.configured = new HashSet<>();
        if (props.getSqlWhitelistTables() != null) {
            for (String t : props.getSqlWhitelistTables()) {
                if (t != null && !t.trim().isEmpty()) {
                    this.configured.add(t.trim().toLowerCase(Locale.ROOT));
                }
            }
        }
        if (!configured.isEmpty()) {
            whitelist.addAll(configured);
            log.info("[SqlSafety] 白名单表（{}）: {}（来源：显式配置）", whitelist.size(), whitelist);
        } else {
            log.info("[SqlSafety] 白名单未显式配置，将从 MM 映射模型自动推导");
        }
    }

    /**
     * 从 MM 映射模型推导白名单：MM 里声明了 tableName 的表即合法表。
     *
     * <p>由 {@link com.jjy.infrastructure.yaml.ModelLoadedEvent} 触发——
     * 只有模型装载完成后才知道有哪些表。模型装载前调用是安全的（推导结果为空，
     * 校验会拒绝所有表，不会误放行）。</p>
     */
    @org.springframework.context.event.EventListener
    public void onModelLoaded(com.jjy.infrastructure.yaml.ModelLoadedEvent event) {
        refreshFromModel();
    }

    public void refreshFromModel() {
        if (!configured.isEmpty()) {
            // 显式配置优先，不被模型覆盖
            return;
        }
        derived.clear();
        for (Object mappingObj : registry.getMappings().values()) {
            if (!(mappingObj instanceof Map)) {
                continue;
            }
            Object tablesObj = ((Map<?, ?>) mappingObj).get("tables");
            if (!(tablesObj instanceof List)) {
                continue;
            }
            for (Object tableObj : (List<?>) tablesObj) {
                if (!(tableObj instanceof Map)) {
                    continue;
                }
                Object tableName = ((Map<?, ?>) tableObj).get("tableName");
                if (tableName == null) {
                    continue;
                }
                String t = tableName.toString().trim().toLowerCase(Locale.ROOT);
                if (!t.isEmpty()) {
                    derived.add(t);
                }
            }
        }
        whitelist.clear();
        whitelist.addAll(derived);
        log.info("[SqlSafety] 白名单表（{}）: {}（来源：MM 映射模型）", whitelist.size(), whitelist);
    }

    /**
     * 校验 SQL 是否可被 AI 工具执行
     *
     * @throws IllegalArgumentException 校验失败，错误信息可返回给 LLM 让它修正
     */
    public void validate(String sql) {
        if (sql == null || sql.trim().isEmpty()) {
            throw new IllegalArgumentException("SQL 为空");
        }
        String trimmed = sql.trim();
        // 1) 多语句检测
        if (trimmed.contains(";")) {
            // 允许结尾的 ; 出现一次
            String[] parts = trimmed.split(";");
            int nonEmpty = 0;
            for (String p : parts) if (!p.trim().isEmpty()) nonEmpty++;
            if (nonEmpty > 1) {
                throw new IllegalArgumentException("禁止多语句 SQL");
            }
        }
        // 2) 关键字白名单（必须以 SELECT / WITH 开头）
        String upper = trimmed.toUpperCase(Locale.ROOT);
        if (!upper.startsWith("SELECT") && !upper.startsWith("WITH")) {
            throw new IllegalArgumentException("只允许 SELECT/WITH 开头的查询语句");
        }
        // 3) 禁止关键字
        for (String kw : FORBIDDEN_KEYWORDS) {
            Pattern p = Pattern.compile("\\b" + kw + "\\b", Pattern.CASE_INSENSITIVE);
            if (p.matcher(trimmed).find()) {
                throw new IllegalArgumentException("禁止使用关键字: " + kw);
            }
        }
        // 4) 表名必须在白名单
        Set<String> referenced = new HashSet<>();
        Matcher m = TABLE_PATTERN.matcher(trimmed);
        while (m.find()) {
            referenced.add(m.group(1).toLowerCase(Locale.ROOT));
        }
        for (String t : referenced) {
            if (!whitelist.contains(t)) {
                throw new IllegalArgumentException("表不在白名单: " + t + "（仅允许: " + whitelist + "）");
            }
        }
        // 5) 跨租户字段不能出现在 WHERE/HAVING
        for (String f : CROSS_TENANT_FIELDS) {
            // 形如 WHERE customer_id = ? 由后端注入；AI 写的 SQL 不能直接指定
            Pattern p = Pattern.compile("(WHERE|HAVING)\\s+.*\\b" + f + "\\b",
                    Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
            if (p.matcher(trimmed).find()) {
                throw new IllegalArgumentException("禁止在 WHERE/HAVING 中直接指定租户字段: " + f);
            }
        }
        log.debug("[SqlSafety] 校验通过: {}", trimmed);
    }

    public Set<String> getWhitelist() {
        return whitelist;
    }
}
