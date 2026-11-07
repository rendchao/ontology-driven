package com.jjy;

import com.jjy.infrastructure.scenario.ScenarioInstanceMapper;
import org.mybatis.spring.SqlSessionTemplate;
import org.mybatis.spring.annotation.MapperScan;
import org.mybatis.spring.mapper.MapperFactoryBean;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.transaction.annotation.EnableTransactionManagement;

/**
 * jjy 底座启动类。
 *
 * <p>技术栈：Java 8 + Spring Boot 2.7.18 + MyBatis-Plus 3.5.5 + MySQL 8 + Flyway + Druid + JJWT + Aviator</p>
 * <p>方法论：本体驱动的行为 / 规则 / 事件驱动 + outbox 幂等死信机制</p>
 *
 * <p>注意：{@code @MapperScan} 只列具体 mapper 接口所在的子包，避免扫描范围太宽
 * 把同包下的 {@code StepHandler} / {@code StepResult} 等非 mapper 接口也注册为 mapper。</p>
 */
@SpringBootApplication(scanBasePackages = "com.jjy")
@EnableTransactionManagement
@EnableScheduling
@EnableAsync
@MapperScan({
    "com.jjy.infrastructure.persistence.mapper",
    "com.jjy.infrastructure.persistence.mapper.security",
    "com.jjy.infrastructure.persistence.mapper.integration",
    "com.jjy.infrastructure.event.outbox",
    "com.jjy.infrastructure.event.deadletter",
    "com.jjy.infrastructure.event.idempotent",
    "com.jjy.infrastructure.codegen",
    "com.jjy.infrastructure.flow"
})
public class Application {

    /**
     * 显式注册 {@link ScenarioInstanceMapper}，避免 {@code @MapperScan(...infrastructure.scenario)}
     * 范围过宽把同包下其他非 mapper 接口也注册成 mapper。
     */
    @Bean
    public MapperFactoryBean<ScenarioInstanceMapper> scenarioInstanceMapper(SqlSessionTemplate sqlSessionTemplate) {
        MapperFactoryBean<ScenarioInstanceMapper> factory = new MapperFactoryBean<>();
        factory.setSqlSessionTemplate(sqlSessionTemplate);
        factory.setMapperInterface(ScenarioInstanceMapper.class);
        return factory;
    }

    public static void main(String[] args) {
        SpringApplication.run(Application.class, args);
        System.out.println("\n========================================");
        System.out.println("  jjy base started successfully");
        System.out.println("      API: http://localhost:"
                + System.getProperty("server.port", "8080")
                + System.getProperty("server.servlet.context-path", "/api/v1"));
        System.out.println("========================================\n");
    }
}
