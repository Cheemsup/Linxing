package org.linxing.linxing_agent.agent.memory.projection.snip;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.ThreadPoolExecutor;

/**
 * Snip/Rewrite 小循环的异步执行器配置（thePlan P2-2/P2-3，0717 终稿）。
 *
 * <p>项目原本无 {@code @EnableAsync}，2-E 新增此配置以提供命名线程池 {@code snipTaskExecutor}，
 * 供 {@link SnipLoopExecutor} 异步提交小循环（best-effort，不阻塞主对话流程）。
 *
 * <p><b>拒绝策略 {@link ThreadPoolExecutor.DiscardPolicy}</b>：队列满时静默丢弃新任务——
 * 小循环是上下文优化、非正确性必需，丢弃不影响主流程，符合"允许落后一轮"语义。
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
