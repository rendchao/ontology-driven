package com.jjy.infrastructure.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * 启用 Spring 调度（@Scheduled）。
 * 业务指标刷新 / M4 场景超时扫描 / 死信清理等周期任务都依赖此启用。
 *
 * @author jjy-base
 */
@Configuration
@EnableScheduling
public class SchedulingConfig {
}
