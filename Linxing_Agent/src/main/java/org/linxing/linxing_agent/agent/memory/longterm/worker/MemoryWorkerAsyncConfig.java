package org.linxing.linxing_agent.agent.memory.longterm.worker;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.ThreadPoolExecutor;

/**
 * Memory Worker 异步执行器配置。
 *
 * <p><b>同 userId 串行化</b>：目前采用单线程执行器（core=max=1）——**所有用户的** Memory Worker 任务
 * 全局串行
 * TODO：后续若需跨用户并行，可改为按 userId 哈希分桶的多线程执行器。
 *
 * <p><b>拒绝策略 {@link ThreadPoolExecutor.DiscardPolicy}</b>：队列满时静默丢弃——
 * Memory 更新非正确性必需，丢弃不影响主流程（下轮对话仍会再触发）。
 */
@Configuration
public class MemoryWorkerAsyncConfig {

    @Bean("memoryWorkerExecutor")
    public ThreadPoolTaskExecutor memoryWorkerExecutor(
            @Value("${agent.memory.longterm.worker.queue-capacity:64}") int queueCapacity,
            @Value("${agent.memory.longterm.worker.thread-name-prefix:memory-worker-}") String threadNamePrefix) {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(1);
        executor.setMaxPoolSize(1);
        executor.setQueueCapacity(queueCapacity);
        executor.setThreadNamePrefix(threadNamePrefix);
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.DiscardPolicy());
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(10);
        executor.initialize();
        return executor;
    }
}
