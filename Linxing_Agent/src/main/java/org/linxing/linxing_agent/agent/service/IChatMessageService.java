package org.linxing.linxing_agent.agent.service;

import org.linxing.linxing_agent.agent.entity.ChatMessage;
import org.linxing.linxing_agent.agent.vo.ChatMessageVO;

import java.util.List;

/**
 * 聊天消息持久化与编排服务
 * <p>
 * 2-C 起 Recovery（recoverHistory/loadRecentMessages）下沉至
 * {@code org.linxing.linxing_agent.agent.memory.recovery.HistoryRecoveryService}，
 * 本接口仅保留消息持久化、缓存与 VO 职责。
 */
public interface IChatMessageService {

    ChatMessage saveUserMessage(Integer userId, Integer sessionId, Integer parentId, String content);

    ChatMessage saveAssistantMessage(Integer userId, Integer sessionId, Integer parentId, String content, String sourcesJson);

    Integer resolveSession(Integer userId, Integer sessionId);

    void touchSession(Integer sessionId);

    ChatMessageVO toMessageVO(ChatMessage msg);

    /**
     * 获取会话消息列表（优先读缓存，缓存与DB不一致时回源并刷新缓存）
     * @param sessionId 会话ID
     * @return 消息VO列表
     */
    List<ChatMessageVO> getMessages(Integer sessionId);

    /**
     * 删除消息及其所有子消息，并同步清除缓存
     * @param messageId 根消息ID
     */
    void deleteSubtree(Integer messageId);
}
