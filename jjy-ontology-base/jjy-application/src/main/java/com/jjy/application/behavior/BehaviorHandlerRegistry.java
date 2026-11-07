package com.jjy.application.behavior;

import com.jjy.domain.behavior.BehaviorHandler;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.context.event.ContextRefreshedEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.stereotype.Service;

import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/**
 * 行为处理器注册中心：启动时扫描所有 @Service/@Component 类的 @BehaviorHandler 注解方法
 *
 * <p>behaviorId → HandlerEntry（bean + method）的映射</p>
 *
 * <p>校验：M2 中定义的 behavior（USER_ACTION/SYSTEM 触发）必须有 handler；handler 注解的 ID 必须在 M2 中存在</p>
 *
 * @author jjy-base
 */
@Slf4j
@Component
@Order(Ordered.LOWEST_PRECEDENCE)
public class BehaviorHandlerRegistry {

    @Autowired
    private ApplicationContext applicationContext;

    @Autowired
    private com.jjy.domain.registry.ModelRegistry modelRegistry;

    private final Map<String, HandlerEntry> handlerMap = new HashMap<>();

    @EventListener(ContextRefreshedEvent.class)
    public void scanHandlers() {
        String[] beanNames = applicationContext.getBeanDefinitionNames();
        int found = 0;
        for (String name : beanNames) {
            Object bean;
            try {
                bean = applicationContext.getBean(name);
            } catch (Exception e) {
                continue;
            }
            if (bean == null) continue;
            Class<?> clazz = bean.getClass();
            // 处理 CGLIB 代理
            if (clazz.getName().contains("$$")) {
                clazz = clazz.getSuperclass();
            }
            for (Method m : clazz.getDeclaredMethods()) {
                BehaviorHandler bh = m.getAnnotation(BehaviorHandler.class);
                if (bh == null) continue;
                m.setAccessible(true);
                HandlerEntry entry = new HandlerEntry();
                entry.setBean(bean);
                entry.setMethod(m);
                entry.setBehaviorId(bh.value());
                // 同一 behaviorId 出现两个实现 → 直接报错，不允许静默覆盖。
                //
                // 此前是 handlerMap.put() 覆盖语义：后扫到的赢。
                // 后果是「谁生效」取决于 Spring 的 bean 扫描顺序——一个不稳定的、
                // 且完全没有提示的行为。模板生成机制引入后，这个隐患会放大：
                // 生成的骨架与手写实现可能同时存在。
                // 现在把它变成显式失败：宁可启动失败，也不要行为不确定。
                HandlerEntry existing = handlerMap.get(bh.value());
                if (existing != null) {
                    throw new IllegalStateException(String.format(
                            "行为 %s 存在两个实现：%s.%s 与 %s.%s。"
                                    + "同一行为只能有一个 Handler——"
                                    + "若同时存在模板生成物与手写实现，请删除其中一份。",
                            bh.value(),
                            existing.getBean().getClass().getSimpleName(),
                            existing.getMethod().getName(),
                            clazz.getSimpleName(), m.getName()));
                }
                handlerMap.put(bh.value(), entry);
                found++;
                log.debug("[BehaviorHandler] 注册: {} -> {}.{}", bh.value(), clazz.getSimpleName(), m.getName());
            }
        }
        log.info("[BehaviorHandler] 共注册 {} 个行为处理器", found);
    }

    public HandlerEntry get(String behaviorId) {
        return handlerMap.get(behaviorId);
    }

    public boolean has(String behaviorId) {
        return handlerMap.containsKey(behaviorId);
    }

    public Set<String> all() {
        return handlerMap.keySet();
    }

    @Data
    public static class HandlerEntry {
        private Object bean;
        private Method method;
        private String behaviorId;
    }
}
