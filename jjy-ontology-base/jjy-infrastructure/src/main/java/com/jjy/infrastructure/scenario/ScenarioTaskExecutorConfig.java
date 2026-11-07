package com.jjy.infrastructure.scenario;

import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;

/**
 * 场景异步任务执行器配置。
 *
 * <p>EventBridge.onDomainEvent 用 @Async 跑在这里，避免阻塞业务事务 AFTER_COMMIT 回调。</p>
 *
 * <p>线程池参数：
 * <ul>
 *   <li>核心 4 / 最大 16 / 队列 200 — 单事件 fan-out 到多场景时不会丢</li>
 *   <li>拒绝策略 CallerRuns — 极端情况下回退到调用线程，不丢任务</li>
 * </ul>
 *
 * @author jjy-base
 */
@Slf4j
@Configuration
@EnableAsync
public class ScenarioTaskExecutorConfig {

    @Bean("scenarioTaskExecutor")
    public Executor scenarioTaskExecutor() {
        ThreadPoolTaskExecutor exec = new ThreadPoolTaskExecutor();
        exec.setCorePoolSize(4);
        exec.setMaxPoolSize(16);
        exec.setQueueCapacity(200);
        exec.setThreadNamePrefix("scenario-bridge-");
        exec.setKeepAliveSeconds(60);
        exec.setRejectedExecutionHandler(new java.util.concurrent.ThreadPoolExecutor.CallerRunsPolicy());
        exec.setWaitForTasksToCompleteOnShutdown(true);
        exec.setAwaitTerminationSeconds(15);
        exec.initialize();
        log.info("[Scenario] 异步任务执行器初始化: core=4, max=16, queue=200");
        return exec;
    }
}
