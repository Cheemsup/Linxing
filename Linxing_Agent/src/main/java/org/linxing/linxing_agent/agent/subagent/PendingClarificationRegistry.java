package org.linxing.linxing_agent.agent.subagent;

import jakarta.annotation.PreDestroy;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 待澄清请求注册表
 * 能够管理 HumanInTheLoop 交互的 pending 状态：包括超时澄清时的上下文清除、避免同一对话流中的内容残留
 *
 * TODO：后续需要跟随HumanInTheLoop一同移动到core包下，因为这是整个系统的公共性质组件
 */
@Slf4j
@Component
public class PendingClarificationRegistry {

    private final Map<String, PendingClarification> pending = new ConcurrentHashMap<>();
    private final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(
            1, r -> {
                Thread t = new Thread(r, "clarify-timeout");
                t.setDaemon(true);
                return t;
            });

    /**
     * 注册一个待澄清请求，并启动超时自清理任务。
     *
     * @param clarificationId 唯一标识（当前使用 sessionId）
     * @param question        推送给用户的问题
     * @param future          阻塞等待用户回复的 future
     * @param timeoutSeconds  超时秒数，超时后以 defaultAnswer 完成 future
     * @param defaultAnswer   超时时使用的默认回复
     * @return 超时标志位，responseProvider 在 future.get() 返回后通过 {@code get()} 判断是否为超时触发
     */
    public AtomicBoolean register(String clarificationId, String question,
                                  CompletableFuture<String> future,
                                  long timeoutSeconds, String defaultAnswer) {
        // 版本/陈旧校验：同一 id 重复注册时，先取消旧的 pending 请求
        PendingClarification existing = pending.remove(clarificationId);
        if (existing != null) {
            existing.getFuture().cancel(false);
            if (existing.getTimeoutTask() != null) {
                existing.getTimeoutTask().cancel(false);
            }
            log.warn("替换陈旧的待澄清请求: clarificationId={}", clarificationId);
        }

        AtomicBoolean timedOut = new AtomicBoolean(false);
        ScheduledFuture<?> timeoutTask = scheduler.schedule(() -> {
            // 仅在用户尚未回复时以默认值完成（complete 返回 false 表示已被用户完成）
            if (future.complete(defaultAnswer)) {
                timedOut.set(true);
                pending.remove(clarificationId);
                log.warn("待澄清请求超时，使用默认值继续: clarificationId={}, default='{}'",
                        clarificationId, defaultAnswer);
            }
        }, timeoutSeconds, TimeUnit.SECONDS);

        pending.put(clarificationId, new PendingClarification(question, future, timedOut, timeoutTask));
        log.info("注册待澄清请求: clarificationId={}, question={}, timeout={}s",
                clarificationId, question, timeoutSeconds);
        return timedOut;
    }

    /**
     * 获取待澄清请求（用于查询状态）
     */
    public PendingClarification get(String clarificationId) {
        return pending.get(clarificationId);
    }

    /**
     * 完成待澄清请求（用户已回复）
     *
     * @param clarificationId 唯一标识
     * @param answer          用户的回复
     * @return true 如果成功完成；false 如果找不到对应请求（可能已超时或不存在）
     */
    public boolean complete(String clarificationId, String answer) {
        PendingClarification pc = pending.remove(clarificationId);
        if (pc != null) {
            // 取消超时定时任务，避免资源浪费
            if (pc.getTimeoutTask() != null) {
                pc.getTimeoutTask().cancel(false);
            }
            pc.getFuture().complete(answer);
            log.info("完成待澄清请求: clarificationId={}, answer={}", clarificationId, answer);
            return true;
        }
        log.warn("待澄清请求不存在或已过期: clarificationId={}", clarificationId);
        return false;
    }

    /**
     * 清除待澄清内容（工作流异常/中断时调用）
     */
    public void cancel(String clarificationId) {
        PendingClarification pc = pending.remove(clarificationId);
        if (pc != null) {
            if (pc.getTimeoutTask() != null) {
                pc.getTimeoutTask().cancel(false);
            }
            pc.getFuture().cancel(true);
            log.info("取消待澄清请求: clarificationId={}", clarificationId);
        }
    }

    @PreDestroy
    public void shutdown() {
        scheduler.shutdownNow();
        log.info("PendingClarificationRegistry scheduler 已关闭");
    }

    @Data
    @AllArgsConstructor
    public static class PendingClarification {
        private final String question;
        private final CompletableFuture<String> future;
        private final AtomicBoolean timedOut;
        private final ScheduledFuture<?> timeoutTask;
    }
}
