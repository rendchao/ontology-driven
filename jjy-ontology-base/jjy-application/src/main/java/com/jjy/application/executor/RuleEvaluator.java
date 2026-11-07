package com.jjy.application.executor;

import com.googlecode.aviator.AviatorEvaluator;
import com.googlecode.aviator.AviatorEvaluatorInstance;
import com.jjy.domain.rule.Rule;
import com.jjy.domain.entity.Aggregate;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;

/**
 * 规则求值器（Aviator 5.x）
 * <p>
 * Aviator 是纯 Java 8 兼容的表达式引擎（无需 Nashorn/GraalVM）。
 * 表达式求值上下文 = 聚合状态（state） + 运行时上下文（userId / tenantId / now）。
 * <p>
 * 安全约束（生产必须开启）：
 * - 禁止 import / new / 反射
 * - 只允许基本运算 + 字典查找 + 状态字段
 *
 * @author jjy-base
 */
@Slf4j
@Component("executorRuleEvaluator")
public class RuleEvaluator {

    private final Map<String, String> lastErrors = new HashMap<>();

    static {
        // 全局安全开关：Aviator 5.3.1 的 Options 中没有 ALLOW_* 常量
        // 这里仅做一次实例获取（触发 Aviator 类加载），不修改全局选项
        // 表达式层级的安全控制由 ME 表达式白名单把关
        try {
            AviatorEvaluator.getInstance();
        } catch (Throwable t) {
            // 忽略 Aviator 初始化异常
        }
    }

    /**
     * @param rule M3 规则元数据
     * @param state 聚合当前状态（attrs）
     * @param context 运行时上下文（userId / tenantId / now）
     * @return true=通过，false=违反
     */
    public boolean evaluate(Rule rule, Map<String, Object> state, Map<String, Object> context) {
        String expression = rule.getExpression();
        if (expression == null || expression.trim().isEmpty()) {
            return true; // 无表达式 = 无约束
        }

        // 合并 state + context
        Map<String, Object> env = new HashMap<>(state);
        if (context != null) {
            env.putAll(context);
        }
        env.put("now", System.currentTimeMillis());

        try {
            AviatorEvaluatorInstance aviator = AviatorEvaluator.getInstance();
            Object result = aviator.execute(expression, env);
            if (result instanceof Boolean) {
                return (Boolean) result;
            }
            log.warn("[RuleEvaluator] 表达式返回非布尔值: ruleId={} result={}", rule.getId(), result);
            return true;

        } catch (Exception e) {
            String msg = "规则求值异常: " + e.getMessage();
            lastErrors.put(rule.getId(), msg);
            log.error("[RuleEvaluator] ruleId={} expression={}", rule.getId(), expression, e);
            return false;
        }
    }

    public String getLastError(String ruleId) {
        return lastErrors.get(ruleId);
    }
}
