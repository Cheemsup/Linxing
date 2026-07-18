package org.linxing.linxing_agent.agent.memory.deprecated;

import org.linxing.linxing_agent.agent.vo.ChatMessageVO;

import java.util.List;

/**
 * 对话消息缓存服务
 * 
 * 属于旧体系的简单上下文管理机制的一部分，现在由于重新设计了上下文管理机制，已经不再使用。原位置：org.linxing.linxing_agent.agent.service.IChatMessageCacheService
 * 
 * @Deprecated
 */
@Deprecated
public interface IChatMessageCacheService {

    List<ChatMessageVO> getMessages(Integer sessionId);

    void putMessages(Integer sessionId, List<ChatMessageVO> messages);

    void appendMessage(Integer sessionId, ChatMessageVO message);

    void appendMessages(Integer sessionId, List<ChatMessageVO> messages);

    void deleteSession(Integer sessionId);

    void deleteMessages(Integer sessionId, List<Integer> messageIds);
}
