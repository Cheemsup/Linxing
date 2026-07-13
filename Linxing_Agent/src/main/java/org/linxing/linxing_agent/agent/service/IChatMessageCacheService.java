package org.linxing.linxing_agent.agent.service;

import org.linxing.linxing_agent.agent.vo.ChatMessageVO;

import java.util.List;

/**
 * 对话消息缓存服务
 */
public interface IChatMessageCacheService {

    List<ChatMessageVO> getMessages(Integer sessionId);

    void putMessages(Integer sessionId, List<ChatMessageVO> messages);

    void appendMessage(Integer sessionId, ChatMessageVO message);

    void appendMessages(Integer sessionId, List<ChatMessageVO> messages);

    void deleteSession(Integer sessionId);

    void deleteMessages(Integer sessionId, List<Integer> messageIds);
}
