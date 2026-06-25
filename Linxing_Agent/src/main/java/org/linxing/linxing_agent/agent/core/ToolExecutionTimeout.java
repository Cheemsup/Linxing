package org.linxing.linxing_agent.agent.core;

import lombok.extern.slf4j.Slf4j;
import org.linxing.linxing_agent.agent.tool.ToolCallRequest;
import org.linxing.linxing_agent.agent.tool.ToolCallResult;
import org.linxing.linxing_agent.agent.tool.ToolSpec;
import org.springframework.stereotype.Component;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 工具执行超时封装组件（watchdog 模式 + 分段计时），使用独立线程池执行工具，watchdog 定时扣减预算实现超时控制：
 * 工具超时只计算实际执行时间，不包含 HumanInTheLoop 等待用户回复的时间。
 */
@Slf4j
@Component
public class ToolExecutionTimeout {

    /** watchdog 检查间隔（毫秒），1000ms */
    private static final long TICK = TimeUnit.SECONDS.toMillis(1000);

    private final ExecutorService executor;
    private final ScheduledExecutorService scheduler;

    public ToolExecutionTimeout() {
        ThreadFactory workerFactory = new ThreadFactory() {
            private final AtomicInteger counter = new AtomicInteger(0);

            @Override
            public Thread newThread(Runnable r) {
                Thread t = new Thread(r, "tool-exec-" + counter.incrementAndGet());
                t.setDaemon(true);
                return t;
            }
        };
        ThreadFactory schedulerFactory = r -> {
            Thread t = new Thread(r, "tool-watchdog");
            t.setDaemon(true);
            return t;
        };
        this.executor = Executors.newCachedThreadPool(workerFactory);
        this.scheduler = Executors.newSingleThreadScheduledExecutor(schedulerFactory);
    }

    /**
     * 在独立线程中执行工具调用，并施加超时限制（分段计时）
     *
     * @param toolSpec       工具规格
     * @param request        工具调用请求
     * @param context        Agent 运行时上下文
     * @param timeoutSeconds 超时秒数（仅计算工具实际执行时间，不含 HumanInTheLoop 等待）
     * @return 工具调用结果；超时或异常时返回 failure
     */
    public ToolCallResult executeWithTimeout(ToolSpec toolSpec, ToolCallRequest request,
                                              AgentContext context, int timeoutSeconds) {
        String toolName = toolSpec.getName();
        long totalNanos = TimeUnit.SECONDS.toNanos(timeoutSeconds);

        CompletableFuture<ToolCallResult> future = new CompletableFuture<>();
        ToolTimeoutContext timeoutCtx = new ToolTimeoutContext(totalNanos, future);

        // 工作线程：执行工具，完成后 complete future
        executor.submit(() -> {
            CONTEXT_HOLDER.set(timeoutCtx);
            try {
                ToolCallResult result = toolSpec.execute(request, context);//工具执行
                if (!future.isDone()) {
                    future.complete(result);
                }
            } catch (Throwable t) {
                // 工具抛出异常或被 interrupt
                future.completeExceptionally(t);
            } finally {
                CONTEXT_HOLDER.remove();
            }
        });

        // watchdog：定期扣减预算，预算耗尽则取消 future
        ScheduledFuture<?> watchdog = scheduler.scheduleAtFixedRate(() -> {
            if (future.isDone()) {
                return;
            }
            if (timeoutCtx.decrement(TICK)) {
                // 预算耗尽，触发超时
                if (timeoutCtx.triggerTimeout()) {
                    log.warn("[ToolExecutionTimeout] 工具 {} 执行超时（预算{}秒已耗尽），已打断",
                            toolName, timeoutSeconds);
                }
            }
        }, TICK, TICK, TimeUnit.NANOSECONDS);

        // 主线程阻塞等待结果
        try {
            return future.get();
        } catch (ExecutionException e) {
            Throwable cause = e.getCause() != null ? e.getCause() : e;
            if (cause instanceof InterruptedException) {
                log.warn("[ToolExecutionTimeout] 工具 {} 执行被中断", toolName);
                return ToolCallResult.failure(request.getToolCallId(), toolName, "工具执行被中断");
            }
            log.error("[ToolExecutionTimeout] 工具 {} 执行异常: {}", toolName, cause.getMessage(), cause);
            return ToolCallResult.failure(request.getToolCallId(), toolName,
                    "工具执行异常: " + cause.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            future.cancel(true);
            log.warn("[ToolExecutionTimeout] 主线程等待工具 {} 结果时被中断", toolName);
            return ToolCallResult.failure(request.getToolCallId(), toolName, "工具执行被中断");
        } finally {
            watchdog.cancel(false);
        }
    }

    // ---- ThreadLocal 暴露给 HumanInTheLoop 使用 ----

    private static final ThreadLocal<ToolTimeoutContext> CONTEXT_HOLDER = new ThreadLocal<>();

    /**
     * 获取当前工作线程上的工具超时上下文
     *
     * @return 当前线程的 ToolTimeoutContext；非工具执行线程或未设置时返回 null
     */
    public static ToolTimeoutContext getCurrentContext() {
        return CONTEXT_HOLDER.get();
    }
}
