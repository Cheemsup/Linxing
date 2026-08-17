package org.linxing.linxing_agent.common;

import dev.langchain4j.exception.InternalServerException;
import dev.langchain4j.exception.RateLimitException;
import dev.langchain4j.exception.TimeoutException;
import dev.langchain4j.exception.UnresolvedModelServerException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.http.HttpTimeoutException;
import java.nio.channels.UnresolvedAddressException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * LLM 调用重试轻量指标（0814 重试机制改造，改造 D）。
 * <p>用 AtomicLong 计数 + 定时日志汇总，不引入 Micrometer。
 * 滚动窗口：定时输出当前窗口汇总后清零，只统计窗口内的重试情况。
 * 计数口径与 {@code AgentExecutor.isRetryable}/{@code invokeWithRetry} 对应：
 * 重试次数、重试后成功数、重试耗尽最终失败数、不可重试/已 emit 直接失败数、按异常类型分布。
 */
@Slf4j
@Component
public class RetryMetrics {

    /** 实际发起的重试次数 */
    private final AtomicLong retryAttempts = new AtomicLong();
    /** 重试后成功的次数（首次即成功不计入） */
    private final AtomicLong retrySuccesses = new AtomicLong();
    /** 重试后仍失败的次数 */
    private final AtomicLong retryExhausted = new AtomicLong();
    /** 不可重试 / 已 emit 直接失败的次数 */
    private final AtomicLong nonRetryableFailures = new AtomicLong();
    /** 按异常类型分布的"重试次数"（key 为 {@link #classify} 结果） */
    private final Map<String, AtomicLong> retryByType = new ConcurrentHashMap<>();

    /** 发起一次重试（sleep 前调用），按类型累计分布 */
    public void onRetry(String type) {
        retryAttempts.incrementAndGet();
        retryByType.computeIfAbsent(type, k -> new AtomicLong()).incrementAndGet();
    }

    /** 重试后成功 */
    public void onRetrySuccess() {
        retrySuccesses.incrementAndGet();
    }

    /** 重试耗尽仍失败 */
    public void onRetryExhausted(String type) {
        retryExhausted.incrementAndGet();
        retryByType.computeIfAbsent(type, k -> new AtomicLong()).incrementAndGet();
    }

    /** 不可重试 / 已 emit 直接失败 */
    public void onNonRetryable(String type) {
        nonRetryableFailures.incrementAndGet();
        retryByType.computeIfAbsent(type, k -> new AtomicLong()).incrementAndGet();
    }

    /**
     * 定时输出本窗口汇总并清零；窗口内无任何计数时静默。
     */
    @Scheduled(fixedDelay = 1_800_000, initialDelay = 1_800_000) // 30 分钟滚动窗口
    public void logSummary() {
        long attempts = retryAttempts.getAndSet(0);
        long successes = retrySuccesses.getAndSet(0);
        long exhausted = retryExhausted.getAndSet(0);
        long nonRetryable = nonRetryableFailures.getAndSet(0);
        Map<String, AtomicLong> byType = new ConcurrentHashMap<>(retryByType);
        retryByType.clear();
        if (attempts + successes + exhausted + nonRetryable == 0) {
            return;
        }
        log.info("[retry-metrics] LLM 重试汇总（本窗口）: retryAttempts={}, retrySuccess={}, "
                        + "retryExhausted={}, nonRetryable={}, byType={}",
                attempts, successes, exhausted, nonRetryable, byType);
    }

    /**
     * 将异常归类为简短类型标签，用于重试日志与指标分布统计。
     * <p>沿 cause 链逐层判定（流式失败经 {@code await()} 包装后根因在链上），
     * 与 {@code AgentExecutor.isRetryable} 的分类口径一致。
     */
    public static String classify(Throwable t) {
        Throwable c = t;
        while (c != null) {
            if (c instanceof RateLimitException) {
                return "RATE_LIMIT";
            }
            if (c instanceof InternalServerException) {
                return "SERVER_ERROR";
            }
            if (c instanceof TimeoutException) {
                return "TIMEOUT";
            }
            if (c instanceof UnresolvedModelServerException || c instanceof UnresolvedAddressException) {
                return "DNS";
            }
            if (c instanceof HttpTimeoutException) {
                return "NETWORK_TIMEOUT";
            }
            if (c instanceof IOException) {
                return "NETWORK";
            }
            c = c.getCause();
        }
        return "OTHER";
    }
}
