package com.jjy.application.rule;

import com.googlecode.aviator.AviatorEvaluator;
import com.googlecode.aviator.AviatorEvaluatorInstance;
import com.googlecode.aviator.Expression;
import com.googlecode.aviator.Options;
import com.googlecode.aviator.runtime.function.AbstractFunction;
import com.googlecode.aviator.runtime.type.AviatorObject;
import com.googlecode.aviator.runtime.type.AviatorRuntimeJavaType;
import com.jjy.domain.rule.Rule;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * M3 规则求值器：基于 Aviator 5.3
 *
 * <p>安全策略：
 * <ul>
 *   <li>不预置任何 Java 类（aviator 不允许 import/exec）</li>
 *   <li>只注册受控的内置函数 NOW / UUID / SUM / ABS / COALESCE / CONCAT / IS_NULL</li>
 *   <li>输入参数由 M3.inputParams.sourceField 决定，使用方传入 Map</li>
 * </ul>
 *
 * <p>求值返回 true / false（M3 都是布尔型规则）</p>
 *
 * @author jjy-base
 */
@Slf4j
@Primary
@Component
public class RuleEvaluator {

    private final AviatorEvaluatorInstance evaluator;

    public RuleEvaluator() {
        this.evaluator = AviatorEvaluator.newInstance();
        try {
            this.evaluator.setOption(Options.valueOf("ALLOW_NULL_LITERAL"), true);
        } catch (Exception ignore) {}
        try {
            this.evaluator.setOption(Options.valueOf("ALLOW_NULL_OPERATOR"), true);
        } catch (Exception ignore) {}
        registerBuiltinFunctions();
    }

    private void registerBuiltinFunctions() {
        // NOW() → 当前时间字符串
        evaluator.addFunction(new AbstractFunction() {
            @Override
            public AviatorObject call(Map<String, Object> env) {
                return new AviatorRuntimeJavaType(java.time.LocalDateTime.now().toString());
            }
            @Override
            public String getName() { return "NOW"; }
        });
        // TODAY() → 当前日期字符串
        evaluator.addFunction(new AbstractFunction() {
            @Override
            public AviatorObject call(Map<String, Object> env) {
                return new AviatorRuntimeJavaType(java.time.LocalDate.now().toString());
            }
            @Override
            public String getName() { return "TODAY"; }
        });
        // UUID() → 32 位 UUID
        evaluator.addFunction(new AbstractFunction() {
            @Override
            public AviatorObject call(Map<String, Object> env) {
                return new AviatorRuntimeJavaType(java.util.UUID.randomUUID().toString().replace("-", ""));
            }
            @Override
            public String getName() { return "UUID"; }
        });
        // ABS(x) → 绝对值
        evaluator.addFunction(new AbstractFunction() {
            @Override
            public AviatorObject call(Map<String, Object> env, AviatorObject arg1) {
                Object v = arg1.getValue(env);
                if (v instanceof Number) {
                    return new AviatorRuntimeJavaType(Math.abs(((Number) v).doubleValue()));
                }
                return arg1;
            }
            @Override
            public String getName() { return "ABS"; }
        });
        // SUM(list, field) → 集合某字段求和（list 是 List<Map>，field 是 key）
        evaluator.addFunction(new AbstractFunction() {
            @Override
            public AviatorObject call(Map<String, Object> env, AviatorObject listArg, AviatorObject fieldArg) {
                Object list = listArg.getValue(env);
                String field = String.valueOf(fieldArg.getValue(env));
                double sum = 0;
                if (list instanceof List) {
                    for (Object o : (List<?>) list) {
                        if (o instanceof Map) {
                            Object v = ((Map<?, ?>) o).get(field);
                            if (v instanceof Number) sum += ((Number) v).doubleValue();
                        }
                    }
                }
                return new AviatorRuntimeJavaType(sum);
            }
            @Override
            public String getName() { return "SUM"; }
        });
        // COUNT(list) → 集合大小
        evaluator.addFunction(new AbstractFunction() {
            @Override
            public AviatorObject call(Map<String, Object> env, AviatorObject arg1) {
                Object v = arg1.getValue(env);
                if (v instanceof List) return new AviatorRuntimeJavaType(((List<?>) v).size());
                if (v instanceof Map) return new AviatorRuntimeJavaType(((Map<?, ?>) v).size());
                return new AviatorRuntimeJavaType(0);
            }
            @Override
            public String getName() { return "COUNT"; }
        });
        // CONTAINS(list, val) → 集合是否包含
        evaluator.addFunction(new AbstractFunction() {
            @Override
            public AviatorObject call(Map<String, Object> env, AviatorObject listArg, AviatorObject valArg) {
                Object list = listArg.getValue(env);
                Object val = valArg.getValue(env);
                if (list instanceof List) return AviatorRuntimeJavaType.valueOf(((List<?>) list).contains(val));
                if (list instanceof String) return AviatorRuntimeJavaType.valueOf(((String) list).contains(String.valueOf(val)));
                return AviatorRuntimeJavaType.valueOf(false);
            }
            @Override
            public String getName() { return "CONTAINS"; }
        });
    }

    /**
     * 求值 M3 规则
     *
     * @param rule    M3 规则定义
     * @param params  参数：key 为 M3.inputParams.name，value 为该参数对应的实际值
     * @return 求值结果（true/false）；异常时返回 false 并记录日志
     */
    public boolean evaluate(Rule rule, Map<String, Object> params) {
        if (rule == null || rule.getExpression() == null || rule.getExpression().isEmpty()) {
            return true;
        }
        try {
            Expression exp = evaluator.compile(rule.getExpression(), true);
            Object result = exp.execute(params == null ? new java.util.HashMap<>() : params);
            log.error("[Rule] {} raw result: {} ({}), type: {}", rule.getId(), result, rule.getExpression(), result == null ? "null" : result.getClass().getName());
            if (result instanceof Boolean) return (Boolean) result;
            return result != null && Boolean.parseBoolean(String.valueOf(result));
        } catch (Exception e) {
            log.warn("[Rule] 规则 {} 求值失败: {} | params={}", rule.getId(), e.getMessage(), params);
            return false;
        }
    }
}
