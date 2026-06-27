package org.linxing.linxing_agent.agent.service.impl;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.linxing.linxing_agent.agent.entity.ChatMessage;
import org.linxing.linxing_agent.agent.entity.ChatSession;
import org.linxing.linxing_agent.agent.mapper.ChatMessageMapper;
import org.linxing.linxing_agent.agent.mapper.ChatSessionMapper;
import org.linxing.linxing_agent.agent.vo.ChatMessageVO;
import org.springframework.stereotype.Service;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * 聊天消息与会话的持久化服务
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ChatMessageServiceImpl {

    private static final int MAX_HISTORY_ROUNDS = 10;

    private final ChatMessageMapper chatMessageMapper;
    private final ChatSessionMapper chatSessionMapper;

    /**
     * 保存用户消息
     */
    public ChatMessage saveUserMessage(Integer userId, Integer sessionId,
                                        Integer parentId, String content) {
        ChatMessage userMsg = ChatMessage.builder()
                .userId(userId)
                .sessionId(sessionId)
                .parentId(parentId)
                .role("user")
                .content(content)
                .sources("[]")
                .createdAt(OffsetDateTime.now())
                .build();
        chatMessageMapper.insert(userMsg);
        log.debug("[用户{}] 保存用户消息 id={}, sessionId={}, parentId={}",
                userId, userMsg.getId(), userMsg.getSessionId(), userMsg.getParentId());
        return userMsg;
    }

    /**
     * 保存助手消息
     */
    public ChatMessage saveAssistantMessage(Integer userId, Integer sessionId,
                                             Integer parentId, String content,
                                             String sourcesJson) {
        ChatMessage assistantMsg = ChatMessage.builder()
                .userId(userId)
                .sessionId(sessionId)
                .parentId(parentId)
                .role("assistant")
                .content(content)
                .sources(sourcesJson)
                .createdAt(OffsetDateTime.now())
                .build();
        chatMessageMapper.insert(assistantMsg);
        return assistantMsg;
    }

    /**
     * 解析或创建会话
     */
    public Integer resolveSession(Integer userId, Integer sessionId) {
        if (sessionId != null) {
            ChatSession session = chatSessionMapper.selectById(sessionId);
            if (session != null) {
                return sessionId;
            }
            log.debug("[用户{}] 会话 {} 不存在，将创建新会话", userId, sessionId);
        }
        ChatSession session = ChatSession.builder()
                .userId(userId)
                .title("新对话")
                .build();
        chatSessionMapper.insert(session);
        log.info("[用户{}] 自动创建新会话 id={}", userId, session.getId());
        return session.getId();
    }

    /**
     * 沿 parentId 链路回溯对话历史，返回当前分支上的消息列表（从旧到新）
     */
    public List<ChatMessage> backtrackHistory(Integer currentUserMsgId) {
        ChatMessage currentMsg = chatMessageMapper.selectById(currentUserMsgId);
        if (currentMsg == null) {
            return List.of();
        }

        List<ChatMessage> chain = new ArrayList<>();
        Integer parentId = currentMsg.getParentId();
        while (parentId != null) {
            ChatMessage parentMsg = chatMessageMapper.selectById(parentId);
            if (parentMsg == null) {
                break;
            }
            chain.add(parentMsg);
            parentId = parentMsg.getParentId();
        }

        java.util.Collections.reverse(chain);

        int maxMessages = MAX_HISTORY_ROUNDS * 2;
        if (chain.size() > maxMessages) {
            return chain.subList(chain.size() - maxMessages, chain.size());
        }
        return chain;
    }

    /**
     * 更新会话的最近更新时间
     */
    public void touchSession(Integer sessionId) {
        chatSessionMapper.updateUpdatedAt(sessionId);
    }

    /**
     * 加载会话中的最近消息作为 context 兜底
     */
    public List<ChatMessage> loadRecentMessages(Integer sessionId) {
        List<ChatMessage> allMessages = chatMessageMapper.selectBySessionId(sessionId);
        if (allMessages.isEmpty()) {
            return List.of();
        }
        int maxMessages = MAX_HISTORY_ROUNDS * 2;
        if (allMessages.size() > maxMessages) {
            return allMessages.subList(allMessages.size() - maxMessages, allMessages.size());
        }
        return allMessages;
    }

    public ChatMessageVO toMessageVO(ChatMessage msg) {
        return ChatMessageVO.builder()
                .id(msg.getId())
                .userId(msg.getUserId())
                .sessionId(msg.getSessionId())
                .parentId(msg.getParentId())
                .role(msg.getRole())
                .content(msg.getContent())
                .sources(msg.getSources())
                .createdAt(msg.getCreatedAt())
                .build();
    }
}
