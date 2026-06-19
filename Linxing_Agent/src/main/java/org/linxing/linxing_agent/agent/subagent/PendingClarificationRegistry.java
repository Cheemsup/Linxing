package org.linxing.linxing_agent.agent.subagent;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 待澄清请求注册表
 * <p>
 * 管理 HumanInTheLoop 交互的 pending 状态：
 * <ul>
 *   <li>工作流的 responseProvider 注册一个问题 + CompletableFuture，阻塞等待用户回复</li>
 *   <li>clarify 端点收到用户回复后，通过 clarificationId 完成对应 future</li>
 *   <li>超时或异常时取消 future，工作流以默认值继续</li>
 * </ul>
 */
@Slf4j
@Component
public class PendingClarificationRegistry {

    private final Map<String, PendingClarification> pending = new ConcurrentHashMap<>();

    /**
     * 注册一个待澄清请求
     *
     * @param clarificationId 唯一标识（UUID）
     * @param question        推送给用户的问题
     * @param future          阻塞等待用户回复的 future
     */
    public void register(String clarificationId, String question, CompletableFuture<String> future) {
        pending.put(clarificationId, new PendingClarification(question, future));
        log.info("注册待澄清请求: clarificationId={}, question={}", clarificationId, question);
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
            pc.getFuture().complete(answer);
            log.info("完成待澄清请求: clarificationId={}, answer={}", clarificationId, answer);
            return true;
        }
        log.warn("待澄清请求不存在或已过期: clarificationId={}", clarificationId);
        return false;
    }

    /**
     * 取消待澄清请求（超时或异常）
     */
    public void cancel(String clarificationId) {
        PendingClarification pc = pending.remove(clarificationId);
        if (pc != null) {
            pc.getFuture().cancel(true);
            log.info("取消待澄清请求: clarificationId={}", clarificationId);
        }
    }

    @Data
    @AllArgsConstructor
    public static class PendingClarification {
        private final String question;
        private final CompletableFuture<String> future;
    }
}
