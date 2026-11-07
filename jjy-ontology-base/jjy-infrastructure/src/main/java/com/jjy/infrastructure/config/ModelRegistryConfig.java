package com.jjy.infrastructure.config;

import com.jjy.domain.registry.ModelRegistry;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * ModelRegistry 单例 bean 配置
 * <p>
 * ModelRegistry 在 jjy-domain 里只是个 POJO，加 @Configuration 把它注册为 Spring bean，
 * 这样 5 个模块都能注入它。
 *
 * @author jjy-base
 */
@Configuration
public class ModelRegistryConfig {

    @Bean
    public ModelRegistry modelRegistry() {
        return new ModelRegistry();
    }
}
