package com.jjy.application.rule;

import org.springframework.expression.EvaluationContext;
import org.springframework.expression.PropertyAccessor;
import org.springframework.expression.TypedValue;

import java.util.Map;

/**
 * 自定义 SpEL PropertyAccessor：让表达式里能直接用标识符 `highAmountFlag` 读 root Map 里的 key。
 *
 * <p>Spring 自带的 MapAccessor 在 spring-expression 5.3 里没有，所以这里写一个。
 * P5 范围内仅支持读（get），写（write）不需要。</p>
 */
public class MapPropertyAccessor implements PropertyAccessor {

    @Override
    public Class<?>[] getSpecificTargetClasses() {
        return new Class<?>[] { Map.class };
    }

    @Override
    public boolean canRead(EvaluationContext context, Object target, String name) {
        return (target instanceof Map) && ((Map<?, ?>) target).containsKey(name);
    }

    @Override
    public TypedValue read(EvaluationContext context, Object target, String name) {
        Map<?, ?> map = (Map<?, ?>) target;
        Object value = map.get(name);
        return new TypedValue(value);
    }

    @Override
    public boolean canWrite(EvaluationContext context, Object target, String name) {
        return false;
    }

    @Override
    public void write(EvaluationContext context, Object target, String name, Object newValue) {
        // 不支持写
        throw new UnsupportedOperationException("MapPropertyAccessor 是只读的");
    }
}
