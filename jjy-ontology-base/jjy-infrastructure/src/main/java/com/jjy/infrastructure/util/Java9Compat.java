package com.jjy.infrastructure.util;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Java 9+ 工厂方法的 Java 8 兼容版
 * <p>
 * 背景：项目 pom 锁 1.8，编译用 JDK 1.8。但控制器大量用 Map.of / List.of，
 * 这些是 Java 9+ 才有。本类提供同名 of 工厂方法，行为对齐 java.util.Map.of / List.of：
 * <ul>
 *   <li>{@code Maps.of(k1, v1, k2, v2, ...)}：不可变映射，key 重复抛 IllegalArgumentException</li>
 *   <li>{@code Lists.of(e1, e2, ...)}：不可变列表</li>
 * </ul>
 * 用法：{@code import static ...Java9Compat.*;}{@code Maps.of(...)} / {@code Lists.of(...)}。
 *
 * @author jjy-base
 */
public final class Java9Compat {

    private Java9Compat() {}

    // ============== Map ==============

    @SuppressWarnings("unchecked")
    public static <K, V> Map<K, V> mapOf(Object... kv) {
        if (kv.length % 2 != 0) {
            throw new IllegalArgumentException("mapOf 需要偶数个参数 (k1,v1,k2,v2,...)，实参 " + kv.length);
        }
        Map<K, V> m = new HashMap<>(kv.length / 2 + 1);
        for (int i = 0; i < kv.length; i += 2) {
            K k = (K) kv[i];
            V v = (V) kv[i + 1];
            if (m.containsKey(k)) {
                throw new IllegalArgumentException("重复的 key: " + k);
            }
            m.put(k, v);
        }
        return java.util.Collections.unmodifiableMap(m);
    }

    // ============== List ==============

    @SafeVarargs
    public static <T> List<T> listOf(T... items) {
        return java.util.Collections.unmodifiableList(new ArrayList<>(Arrays.asList(items)));
    }
}
