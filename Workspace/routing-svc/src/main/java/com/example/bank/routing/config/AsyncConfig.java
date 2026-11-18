package com.example.bank.routing.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.task.TaskExecutor;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

/** 路由匹配与业务事件回调使用独立线程池，避免下游慢导致匹配线程被占满。 */
@Configuration
public class AsyncConfig {

    @Bean("routingMatcherExecutor")
    public TaskExecutor routingMatcherExecutor() {
        return executor("routing-matcher", 2, 8, 1000);
    }

    @Bean("routingEventExecutor")
    public TaskExecutor routingEventExecutor() {
        return executor("routing-event", 2, 16, 2000);
    }

    private ThreadPoolTaskExecutor executor(String prefix, int core, int max, int queueCapacity) {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(core);
        executor.setMaxPoolSize(max);
        executor.setQueueCapacity(queueCapacity);
        executor.setThreadNamePrefix(prefix + "-");
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.initialize();
        return executor;
    }
}
