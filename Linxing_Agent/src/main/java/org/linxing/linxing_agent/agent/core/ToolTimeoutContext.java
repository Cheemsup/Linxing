package org.linxing.linxing_agent.agent.core;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 工具执行超时上下文
 * 用于实现"分段计时"：ToolExecutionTimeout 只计算工具实际执行时间，遇到 HumanInTheLoop 期间暂停计时，结束后恢复计时。
 */
public class ToolTimeoutContext {

    /** 是否暂停计时（HumanInTheLoop 等待期间为 true） */
    private final AtomicBoolean paused = new AtomicBoolean(false);

    /** 剩余预算（纳秒） */
    private final AtomicLong remainingNanos;

    /** 关联的工作 future，超时时取消 */
    private final CompletableFuture<?> future;

    /** 是否已超时（避免重复取消） */
    private final AtomicBoolean timedOut = new AtomicBoolean(false);

    public ToolTimeoutContext(long timeoutNanos, CompletableFuture<?> future) {
        this.remainingNanos = new AtomicLong(timeoutNanos);
        this.future = future;
    }

    /**
     * 暂停计时（进入 HumanInTheLoop 等待前调用）
     */
    public void pause() {
        paused.set(true);
    }

    /**
     * 恢复计时（HumanInTheLoop 等待结束后调用）
     */
    public void resume() {
        paused.set(false);
    }

    /**
     * 是否处于暂停状态
     */
    public boolean isPaused() {
        return paused.get();
    }

    /**
     * 获取剩余预算（纳秒）
     */
    public long getRemainingNanos() {
        return remainingNanos.get();
    }

    /**
     * watchdog 调用：扣减预算，返回是否已耗尽
     *
     * @param nanos 扣减的纳秒数
     * @return true 如果预算已耗尽（调用方应触发超时取消）
     */
    public boolean decrement(long nanos) {
        if (paused.get()) {
            return false;
        }
        long remaining = remainingNanos.addAndGet(-nanos);
        return remaining <= 0;
    }

    /**
     * 触发超时：取消工作 future
     *
     * @return true 如果本次调用实际触发了取消
     */
    public boolean triggerTimeout() {
        if (timedOut.compareAndSet(false, true)) {
            future.cancel(true);
            return true;
        }
        return false;
    }

    /**
     * 是否已超时
     */
    public boolean isTimedOut() {
        return timedOut.get();
    }
}
