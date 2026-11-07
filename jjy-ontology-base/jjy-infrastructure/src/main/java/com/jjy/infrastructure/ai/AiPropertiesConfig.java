package com.jjy.infrastructure.ai;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * 启用 {@link AiProperties} 配置类。
 *
 * @author jjy-base
 */
@Configuration
@EnableConfigurationProperties(AiProperties.class)
public class AiPropertiesConfig {
}
