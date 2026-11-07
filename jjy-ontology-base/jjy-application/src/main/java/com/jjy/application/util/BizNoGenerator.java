package com.jjy.application.util;

import com.jjy.domain.registry.ModelRegistry;
import com.jjy.infrastructure.util.DateUtil;
import com.jjy.infrastructure.yaml.ModelLoadedEvent;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 业务编号生成器。
 *
 * <p><b>编号规则来自 M1 模型，不在代码里硬编码。</b>底座不再维护「表名 → 编号列 → 前缀」
 * 的硬编码映射表，而是读取 M1 聚合根的编号声明：</p>
 *
 * <pre>
 * aggregates:
 *   - id: AGG-PRODUCT-001
 *     alias: Product
 *     noRule:                       # ← 编号规则声明
 *       table: product              # 物理表（来自 MM 时可省略）
 *       column: product_no         # 编号列
 *       prefix: PRD                 # 前缀（缺省取 alias 前 3 位大写）
 * </pre>
 *
 * <p>这样业务工程新增聚合根时，只改模型即可获得编号能力，
 * 不需要动底座代码——符合「模型是唯一语义来源」的核心思想。</p>
 *
 * <p>编号格式：{@code 前缀 + yyyyMMdd + 4 位流水}，例如 {@code PRD202610310001}。
 * 启动时按当日最大值预热计数器，避免重启后重号。</p>
 *
 * @author jjy-base
 */
@Slf4j
@Component
public class BizNoGenerator {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private ModelRegistry registry;

    private final ConcurrentHashMap<String, AtomicLong> counters = new ConcurrentHashMap<>();
    private volatile String today = "";

    /**
     * 编号规则：prefix → (表名, 编号列)。
     *
     * <p>启动时从 M1 聚合根的 {@code noRule} 声明构建；未声明编号规则的聚合根被跳过。</p>
     */
    private final Map<String, NoRule> rules = new LinkedHashMap<>();

    @EventListener(ModelLoadedEvent.class)
    public void warmUp(ModelLoadedEvent event) {
        try {
            loadRulesFromModel();
            String today = DateUtil.todayCompact();
            for (Map.Entry<String, NoRule> e : rules.entrySet()) {
                NoRule r = e.getValue();
                try {
                    // 编号格式：前缀(3) + yyyyMMdd(8) + 流水(4)，如 PRD202611010001
                    // 取流水最大值时须从第 (前缀长度 + 日期长度 + 1) 位开始截（1-based），
                    // 截掉「前缀 + 日期」部分。SUBSTRING 的 pos 不能与 LIKE 的 ? 混用占位，
                    // 否则 JDBC 参数顺序会错位（曾导致把整串当整数解析而溢出）。
                    int seqStart = r.prefix.length() + today.length() + 1;
                    Integer max = jdbcTemplate.queryForObject(
                            "SELECT COALESCE(MAX(CAST(SUBSTRING(" + r.column + ", " + seqStart
                                    + ") AS UNSIGNED)), 0) "
                                    + "FROM " + r.table + " WHERE " + r.column + " LIKE ?",
                            Integer.class, r.prefix + today + "%");
                    counters.put(r.prefix, new AtomicLong(max != null ? max + 1 : 1));
                } catch (Exception ignore) {
                    // 表不存在（业务工程尚未建表）或列不匹配，跳过预热
                    counters.put(r.prefix, new AtomicLong(1));
                }
            }
            this.today = today;
            log.info("[BizNo] 编号规则来自 M1 模型，共 {} 条: {}", rules.size(), rules.keySet());
        } catch (Exception e) {
            log.warn("[BizNo] 计数器预热失败: {}", e.getMessage());
        }
    }

    /**
     * 从 M1 模型读取编号规则。
     *
     * <p>读取聚合根的 {@code noRule} 声明（{@code prefix} / {@code table} / {@code column}）。
     * 未声明编号规则的聚合根被跳过——编号能力是可选的，不是每个聚合根都需要。</p>
     */
    private void loadRulesFromModel() {
        rules.clear();
        for (Object defObj : registry.getAggregateDefs().values()) {
            if (!(defObj instanceof Map)) {
                continue;
            }
            @SuppressWarnings("unchecked")
            Map<String, Object> aggMap = (Map<String, Object>) defObj;
            String alias = str(aggMap.get("alias"));
            Object noRuleObj = aggMap.get("noRule");
            if (!(noRuleObj instanceof Map)) {
                continue;
            }
            @SuppressWarnings("unchecked")
            Map<String, Object> nr = (Map<String, Object>) noRuleObj;
            String prefix = str(nr.get("prefix"));
            String table = str(nr.get("table"));
            String column = str(nr.get("column"));
            if (prefix == null && alias != null) {
                prefix = alias.replaceAll("[^A-Za-z]", "").toUpperCase();
                if (prefix.length() > 3) {
                    prefix = prefix.substring(0, 3);
                }
            }
            if (table != null && column != null && prefix != null) {
                rules.put(alias == null ? prefix : alias, new NoRule(prefix, table, column));
            }
        }
    }

    /**
     * 生成下一个业务编号（按模型别名）。
     *
     * <p>业务代码应当调用这个方法，而不是自己拼前缀——
     * 前缀、编号列、编号表都在 M1 的 {@code noRule} 里声明。</p>
     *
     * @param alias 聚合根别名（如 Product）
     * @return 形如 {@code PRD202611010001} 的业务编号
     */
    public String nextFor(String alias) {
        NoRule r = rules.get(alias);
        if (r == null) {
            throw new IllegalStateException(
                    "聚合根 " + alias + " 未在 M1 模型中声明 noRule（业务编号规则），无法生成编号");
        }
        return next(r.prefix);
    }

    /**
     * 生成下一个业务编号。
     *
     * @param prefix 编号前缀（需与 M1 中声明的 prefix 一致）
     */
    public String next(String prefix) {
        String d = DateUtil.todayCompact();
        if (!d.equals(today)) {
            synchronized (this) {
                if (!d.equals(today)) {
                    counters.clear();
                    today = d;
                }
            }
        }
        AtomicLong c = counters.computeIfAbsent(prefix, k -> new AtomicLong(1));
        long seq = c.getAndIncrement();
        if (seq > 9999) {
            log.warn("业务编号 {} 当日流水已超 9999，可能重复", prefix);
        }
        return String.format("%s%s%04d", prefix, d, seq);
    }

    private String str(Object v) {
        if (v == null) {
            return null;
        }
        String s = v.toString().trim();
        return s.isEmpty() ? null : s;
    }

    /** 编号规则 */
    private static class NoRule {
        final String prefix;
        final String table;
        final String column;

        NoRule(String prefix, String table, String column) {
            this.prefix = prefix;
            this.table = table;
            this.column = column;
        }
    }
}
