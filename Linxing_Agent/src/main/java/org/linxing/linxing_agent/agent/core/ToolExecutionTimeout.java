package org.linxing.linxing_agent.agent.core;

import lombok.extern.slf4j.Slf4j;
import org.linxing.linxing_agent.agent.tool.ToolCallRequest;
import org.linxing.linxing_agent.agent.tool.ToolCallResult;
import org.linxing.linxing_agent.agent.tool.ToolSpec;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;
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
 *
 * <p>0724 改造：
 * <ul>
 *   <li>修复 TICK 单位 bug：原 {@code TimeUnit.SECONDS.toMillis(1000)} 误把 1000 当秒数（实得 1e6ms），
 *       导致 watchdog 调度周期与 decrement 扣减量双双失真、超时保护形同虚设。改为 {@code 1000L}（毫秒）。</li>
 *   <li>新增独立心跳任务：工具执行期间每 {@link #HEARTBEAT_INTERVAL_SECONDS} 秒推送一次 tool_progress，
 *       驱动前端四芒星动画 + "已 N 秒"计时，并重置中间件空闲超时保活。仅 SSE 不入库。</li>
 * </ul>
 */
@Slf4j
@Component
public class ToolExecutionTimeout {

    /** watchdog 检查间隔（毫秒），1000ms = 1 秒 */
    private static final long TICK_MS = 1000L;

    /** TICK 换算为纳秒，供 {@link ToolTimeoutContext#decrement} 扣减（每次扣 1 秒预算） */
    private static final long TICK_NANOS = TICK_MS * 1_000_000L;

    /**
     * 工具执行心跳推送间隔（秒）。每 N 秒推一次 tool_progress：
     * 兼具驱动前端动画 + 防中间件空闲超时断连（如 Nginx 默认 proxy_read_timeout=60s）。
     * <p>0724 计时准确性改造：间隔由 3s 调为 1s，使前端"已 N 秒"计时粒度到秒、不再按 3 跳变。
     * SSE 推送频率提升但仍不入库，开销可忽略。
     */
    private static final long HEARTBEAT_INTERVAL_SECONDS = 1L;

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
     * 在独立线程中执行工具调用，并施加超时限制（分段计时）。
     * 执行期间通过独立心跳任务每 {@link #HEARTBEAT_INTERVAL_SECONDS} 秒推送 tool_progress（仅 SSE，不入库）。
     *
     * @param toolSpec       工具规格
     * @param request        工具调用请求
     * @param context        Agent 运行时上下文（持有 stepListener，用于心跳推送）
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

        // watchdog：定期扣减预算，预算耗尽则取消 future（周期 TICK_MS 毫秒 = 1 秒）
        ScheduledFuture<?> watchdog = scheduler.scheduleAtFixedRate(() -> {
            if (future.isDone()) {
                return;
            }
            if (timeoutCtx.decrement(TICK_NANOS)) {
                // 预算耗尽，触发超时
                if (timeoutCtx.triggerTimeout()) {
                    log.warn("[ToolExecutionTimeout] 工具 {} 执行超时（预算{}秒已耗尽），已打断",
                            toolName, timeoutSeconds);
                }
            }
        }, TICK_MS, TICK_MS, TimeUnit.MILLISECONDS);

        // 心跳任务：独立调度，每 HEARTBEAT_INTERVAL_SECONDS 秒推一次 tool_progress（仅 SSE 不入库）
        // 与 watchdog 解耦——不复用 TICK，语义清晰；future 完成后由 finally 取消
        // 走 recorder.recordHeartbeatOnly 统一入口（recorder 内部判断 tool_progress 不入库不进 recordedSteps）
        StepRecorder heartbeatRecorder = context != null ? context.getStepRecorder() : null;
        String toolCallId = request.getToolCallId();
        ScheduledFuture<?> heartbeat = heartbeatRecorder != null
                ? scheduler.scheduleAtFixedRate(new ToolHeartbeatTask(
                        heartbeatRecorder, toolName, toolCallId, future), 0L,
                        HEARTBEAT_INTERVAL_SECONDS, TimeUnit.SECONDS)
                : null;

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
            if (heartbeat != null) {
                heartbeat.cancel(false);
            }
        }
    }

    /**
     * 工具执行心跳任务：每次 tick 检查 future 是否完成，未完成则推送 tool_progress。
     * <p>0724 计时准确性改造：elapsedSeconds 改用真实墙钟时间（{@code now - startNanos}），
     * 而非 tick 计数 × 间隔。前者与手表对得上、不受调度器抖动影响；后者在 scheduleAtFixedRate
     * 延迟/合并 tick 时会与真实经过秒数脱钩。
     * <p>首次 tick（initialDelay=0）若 future 未完成也会推送，elapsed 接近 0，
     * 让前端动画立即起、计时从 0 开始累计（修复短工具完全不显示计时的问题）。
     * <p>走 {@link StepRecorder#recordHeartbeatOnly}，不落库、不进 recordedSteps（保活信号，非业务步骤）。
     */
    private static final class ToolHeartbeatTask implements Runnable {
        private final StepRecorder recorder;
        private final String toolName;
        private final String toolCallId;
        private final CompletableFuture<?> future;
        /** 工具开始执行的时刻（纳秒），构造时记录，近似 tool_call 推送后的执行起点 */
        private final long startNanos;

        ToolHeartbeatTask(StepRecorder recorder, String toolName, String toolCallId,
                          CompletableFuture<?> future) {
            this.recorder = recorder;
            this.toolName = toolName;
            this.toolCallId = toolCallId;
            this.future = future;
            this.startNanos = System.nanoTime();
        }

        @Override
        public void run() {
            if (future.isDone()) {
                return;
            }
            // 真实墙钟耗时：now - start，转秒（向下取整，与"已 N 秒"语义一致）
            long elapsedNanos = System.nanoTime() - startNanos;
            int elapsedSeconds = (int) (elapsedNanos / 1_000_000_000L);
            try {
                Map<String, Object> stepData = new HashMap<>();
                stepData.put(AgentStepTypes.KEY_TOOL_NAME, toolName);
                stepData.put(AgentStepTypes.KEY_TOOL_CALL_ID, toolCallId);
                stepData.put(AgentStepTypes.KEY_ELAPSED_SECONDS, elapsedSeconds);
                AgentStepEvent event = AgentStepEvent.builder()
                        .eventType(AgentStepTypes.TOOL_PROGRESS)
                        .stepNumber(0)
                        .phase(AgentStepTypes.PHASE_THINKING)
                        .label("执行中")
                        .stepData(stepData)
                        .build();
                recorder.recordHeartbeatOnly(event);
            } catch (Exception e) {
                // 心跳失败不影响工具执行本身，仅记录
                log.debug("[ToolHeartbeat] 心跳推送失败 tool={}: {}", toolName, e.getMessage());
            }
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
