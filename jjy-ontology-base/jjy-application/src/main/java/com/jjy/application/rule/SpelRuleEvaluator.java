package com.jjy.application.rule;

import com.jjy.domain.rule.Rule;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.expression.EvaluationContext;
import org.springframework.expression.Expression;
import org.springframework.expression.ExpressionParser;
import org.springframework.expression.ParserContext;
import org.springframework.expression.common.TemplateParserContext;
import org.springframework.expression.spel.standard.SpelExpressionParser;
import org.springframework.expression.spel.support.StandardEvaluationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * M3 规则 SpEL 求值器
 *
 * <p>设计目标：解决 Aviator 5.3 不支持 IF/ELSE、EXISTS (SELECT ...) 等 DSL 语法的问题。
 * 使用 Spring Expression Language (SpEL) 作为底层引擎，扩展以下自定义函数：
 * <ul>
 *   <li>{@code T(Exists).check('SELECT 1 FROM ...', params)} —— 包装 SQL EXISTS 查询</li>
 *   <li>{@code T(NotExists).check(...)} —— 包装 NOT EXISTS</li>
 *   <li>{@code params.path} —— 支持 SpEL 路径访问（自动展平输入参数）</li>
 * </ul>
 *
 * <p>规则表达式示例（SpEL 语法）：
 * <ul>
 *   <li>{@code submittedBy != approvedBy}</li>
 *   <li>{@code User.userType == 'CUSTOMER' ? actorCustomerId == targetCustomerId : true}</li>
 *   <li>{@code !T(Exists).check('SELECT 1 FROM t_customer WHERE customer_name=:customerName AND tax_no=:taxNo', #params)}</li>
 * </ul>
 *
 * @author jjy-base
 */
@Slf4j
@Component
public class SpelRuleEvaluator {

    private final ExpressionParser parser = new SpelExpressionParser();
    /** 编译缓存：避免每次重新 parse */
    private final Map<String, Expression> cache = new ConcurrentHashMap<>();
    @Autowired
    private JdbcTemplate jdbcTemplate;

    private NamedParameterJdbcTemplate namedJdbc;

    /**
     * 求值 M3 SpEL 规则
     *
     * @param rule    规则定义
     * @param params  实际入参（key 为规则 inputParams.name，value 为值）
     * @return 布尔值（true=规则通过）
     */
    /**
     * 纯 SpEL 表达式求值（不带 Rule 对象，给 M6 FlowEngine 的 GATEWAY 分支用）。
     *
     * <p>注意：不注册 Exists/NotExists helper——flow 的 binding 是已经在内存里的 Map，
     * 不需要在表达式里写 SQL。</p>
     */
    public boolean evaluate(String spelExpr, Map<String, Object> binding) {
        if (spelExpr == null || spelExpr.isEmpty()) {
            return true;
        }
        try {
            Expression compiled = cache.computeIfAbsent(spelExpr, parser::parseExpression);
            StandardEvaluationContext ctx = new StandardEvaluationContext();
            // root = binding，让表达式里能直接写 `customerId == 'CUS-001'`
            Map<String, Object> root = binding == null ? new HashMap<String, Object>() : binding;
            ctx.setRootObject(root);
            // P5 兼容：让直接标识符 `highAmountFlag` 也能从 root Map 里取到
            ctx.addPropertyAccessor(new MapPropertyAccessor());
            if (binding != null) {
                for (Map.Entry<String, Object> e : binding.entrySet()) {
                    ctx.setVariable(e.getKey(), e.getValue());
                }
            }
            Object r = compiled.getValue(ctx);
            if (r instanceof Boolean) return (Boolean) r;
            return r != null && Boolean.parseBoolean(String.valueOf(r));
        } catch (Exception e) {
            log.warn("[SpEL Flow] 表达式求值失败: {} | expr={} | binding={}", e.getMessage(), spelExpr, binding, e);
            return false;
        }
    }

    public boolean evaluate(Rule rule, Map<String, Object> params) {
        String expr = rule.getExpression();
        if (expr == null || expr.isEmpty()) {
            return true;
        }
        try {
            if (namedJdbc == null) {
                namedJdbc = new NamedParameterJdbcTemplate(jdbcTemplate);
            }
            Expression compiled = cache.computeIfAbsent(expr, parser::parseExpression);
            StandardEvaluationContext ctx = new StandardEvaluationContext();
            ctx.setRootObject(params);
            ctx.addPropertyAccessor(new MapPropertyAccessor());
            ctx.setVariable("params", params);
            ctx.setVariable("Exists", new ExistsHelper(namedJdbc, params));
            ctx.setVariable("NotExists", new NotExistsHelper(namedJdbc, params));
            if (params != null) {
                for (Map.Entry<String, Object> e : params.entrySet()) {
                    ctx.setVariable(e.getKey(), e.getValue());
                }
            }
            Object result = compiled.getValue(ctx);
            log.info("[SpEL Rule] 规则 {} 表达式={} | params={} | result={}", rule.getId(), expr, params, result);
            if (result instanceof Boolean) {
                return (Boolean) result;
            }
            return result != null && Boolean.parseBoolean(String.valueOf(result));
        } catch (Exception e) {
            log.warn("[SpEL Rule] 规则 {} 求值失败: {} | params={}", rule.getId(), e.getMessage(), params, e);
            return false;
        }
    }

    /**
     * EXISTS SQL 辅助：在 SpEL 中用 {@code #Exists.check(sql, params)} 或 {@code #NotExists.check(...)} 调用。
     * 接受 :paramName 命名参数，使用 NamedParameterJdbcTemplate 执行。
     */
    public class ExistsHelper {
        private final NamedParameterJdbcTemplate jdbc;
        private final Map<String, Object> baseParams;
        public ExistsHelper(NamedParameterJdbcTemplate jdbc, Map<String, Object> baseParams) {
            this.jdbc = jdbc;
            this.baseParams = baseParams;
        }
        public boolean check(String sql) {
            return doCheck(sql, null);
        }
        public boolean check(String sql, Map<String, Object> extraParams) {
            return doCheck(sql, extraParams);
        }
        private boolean doCheck(String sql, Map<String, Object> extraParams) {
            try {
                // 合并参数：baseParams + extraParams
                Map<String, Object> merged = new java.util.HashMap<>();
                if (baseParams != null) merged.putAll(baseParams);
                if (extraParams != null) merged.putAll(extraParams);
                // 把 "SELECT 1" 改造成 "SELECT COUNT(*)"
                String s = sql;
                if (s.toUpperCase().contains("SELECT 1 FROM")) {
                    s = s.replaceFirst("(?i)SELECT\\s+1\\s+FROM", "SELECT COUNT(*) FROM");
                }
                Integer cnt = jdbc.queryForObject(s, new MapSqlParameterSource(merged), Integer.class);
                log.info("[Exists] sql={} | merged={} | cnt={}", s, merged, cnt);
                return cnt != null && cnt > 0;
            } catch (Exception e) {
                log.warn("[Exists] check failed: {} | sql={}", e.getMessage(), sql);
                return false;
            }
        }
    }
    public class NotExistsHelper extends ExistsHelper {
        public NotExistsHelper(NamedParameterJdbcTemplate jdbc, Map<String, Object> baseParams) {
            super(jdbc, baseParams);
        }
        @Override
        public boolean check(String sql) {
            return !super.check(sql);
        }
        @Override
        public boolean check(String sql, Map<String, Object> extraParams) {
            return !super.check(sql, extraParams);
        }
    }
}
