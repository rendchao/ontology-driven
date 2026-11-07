package com.jjy;

import org.mybatis.spring.SqlSessionTemplate;
import org.mybatis.spring.annotation.MapperScan;
import org.mybatis.spring.mapper.MapperFactoryBean;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.FilterType;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.transaction.annotation.EnableTransactionManagement;

import com.jjy.infrastructure.scenario.ScenarioInstanceMapper;

/**
 * application 模块的测试启动类。
 *
 * <p>底座真实启动类在 jjy-boot，但 boot 依赖 application，直接在 application 内测会形成
 * 循环依赖，因此在此声明一个覆盖 domain + infrastructure + application 的最小上下文。
 */
@SpringBootApplication
@EnableTransactionManagement
@EnableScheduling
@EnableAsync
@ComponentScan(basePackages = {"com.jjy.domain",
        "com.jjy.infrastructure",
        "com.jjy.application"},
        excludeFilters = @ComponentScan.Filter(type = FilterType.REGEX,
                pattern = "com\\.jjy\\.base\\.infrastructure\\.persistence\\.mapper\\..*"))
@MapperScan({"com.jjy.infrastructure.persistence.mapper",
        "com.jjy.infrastructure.event.outbox",
        "com.jjy.infrastructure.event.deadletter",
        "com.jjy.infrastructure.event.idempotent",
        "com.jjy.infrastructure.codegen",
        "com.jjy.infrastructure.flow"})
@Configuration
public class Application {

    /**
     * 显式注册 {@link ScenarioInstanceMapper}：它与同包下的非 mapper 接口（StepHandler 等）
     * 混在一起，不能靠包扫描自动注册。
     */
    @Bean
    public MapperFactoryBean<ScenarioInstanceMapper> scenarioInstanceMapper(
            SqlSessionTemplate sqlSessionTemplate) {
        MapperFactoryBean<ScenarioInstanceMapper> factory = new MapperFactoryBean<>();
        factory.setSqlSessionTemplate(sqlSessionTemplate);
        factory.setMapperInterface(ScenarioInstanceMapper.class);
        return factory;
    }
}
