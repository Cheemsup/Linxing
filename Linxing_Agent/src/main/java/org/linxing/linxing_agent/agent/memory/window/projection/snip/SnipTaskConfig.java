package org.linxing.linxing_agent.agent.memory.window.projection.snip;

import org.linxing.linxing_agent.agent.memory.window.projection.ProjectionLoopExecutor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.ThreadPoolExecutor;

/**
 * Snip/Rewrite 小循环的异步执行器配置，主要是线程池的配置
 *
 * <p>项目原本无 {@code @EnableAsync}，2-E 新增此配置以提供命名线程池 {@code snipTaskExecutor}，
 * 供 {@link ProjectionLoopExecutor} 异步提交小循环（best-effort，不阻塞主对话流程）。
 *
 * <p><b>拒绝策略 {@link ThreadPoolExecutor.DiscardPolicy}</b>：队列满时静默丢弃新任务——
 * 小循环是上下文优化、非正确性必需，丢弃不影响主流程
 *
 * <p>本类提供命名线程池 {@code snipTaskExecutor} Bean，被 {@link ProjectionLoopExecutor}
 * 通过 {@code @Qualifier("snipTaskExecutor")} 注入，作为异步小循环的执行线程池。
 */
@Configuration
@EnableAsync
public class SnipTaskConfig {

    @Bean("snipTaskExecutor")
    public ThreadPoolTaskExecutor snipTaskExecutor(
            @Value("${agent.projection.snip.executor.core-pool-size:2}") int corePoolSize,
            @Value("${agent.projection.snip.executor.max-pool-size:4}") int maxPoolSize,
            @Value("${agent.projection.snip.executor.queue-capacity:32}") int queueCapacity,
            @Value("${agent.projection.snip.executor.thread-name-prefix:snip-}") String threadNamePrefix) {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(corePoolSize);
        executor.setMaxPoolSize(maxPoolSize);
        executor.setQueueCapacity(queueCapacity);
        executor.setThreadNamePrefix(threadNamePrefix);
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.DiscardPolicy());
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(10);
        executor.initialize();
        return executor;
    }
}
