package com.jjy.infrastructure.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;

/**
 * 异步 + 调度配置
 * <p>
 * eventTaskExecutor：事件订阅者异步消费（避免阻塞主流程）
 * outboxTaskExecutor：@Scheduled outbox 调度
 *
 * @author jjy-base
 */
@Configuration
@EnableAsync
@EnableScheduling
public class EventAsyncConfig {

    @Bean("eventTaskExecutor")
    public Executor eventTaskExecutor() {
        ThreadPoolTaskExecutor exec = new ThreadPoolTaskExecutor();
        exec.setCorePoolSize(4);
        exec.setMaxPoolSize(16);
        exec.setQueueCapacity(500);
        exec.setThreadNamePrefix("event-");
        exec.setKeepAliveSeconds(60);
        exec.initialize();
        return exec;
    }
}
