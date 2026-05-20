package org.linxing.linxing_agent.rag.service.impl;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.linxing.linxing_agent.common.result.PageResult;
import org.linxing.linxing_agent.rag.entity.ChatSession;
import org.linxing.linxing_agent.rag.mapper.ChatMessageMapper;
import org.linxing.linxing_agent.rag.mapper.ChatSessionMapper;
import org.linxing.linxing_agent.rag.service.IChatSessionService;
import org.linxing.linxing_agent.rag.vo.ChatSessionVO;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class ChatSessionServiceImpl implements IChatSessionService {

    private final ChatSessionMapper chatSessionMapper;
    private final ChatMessageMapper chatMessageMapper;
    private final ChatMessageCacheService chatMessageCacheService;

    @Override
    @Transactional
    public ChatSessionVO createSession(Integer userId, String title) {
        ChatSession session = ChatSession.builder()
                .userId(userId)
                .title(title != null && !title.isBlank() ? title : "新对话")
                .build();
        chatSessionMapper.insert(session);
        log.info("用户 {} 创建会话 {}", userId, session.getId());
        return toVO(session);
    }

    @Override
    public PageResult<ChatSessionVO> listSessions(Integer userId, int page, int size) {
        int offset = (page - 1) * size;
        List<ChatSession> sessions = chatSessionMapper.selectByUserId(userId, offset, size);
        long total = chatSessionMapper.countByUserId(userId);

        List<ChatSessionVO> vos = sessions.stream()
                .map(this::toVO)
                .collect(Collectors.toList());

        return PageResult.of(vos, total, page, size);
    }

    @Override
    @Transactional
    public void deleteSession(Integer sessionId) {
        chatMessageMapper.deleteBySessionId(sessionId);
        chatSessionMapper.deleteById(sessionId);
        chatMessageCacheService.deleteSession(sessionId);
        log.info("删除会话 {} 及其所有消息", sessionId);
    }

    @Override
    public void updateTitle(Integer sessionId, String title) {
        chatSessionMapper.updateTitle(sessionId, title);
    }

    private ChatSessionVO toVO(ChatSession session) {
        int messageCount = chatMessageMapper.countBySessionId(session.getId());
        return ChatSessionVO.builder()
                .id(session.getId())
                .title(session.getTitle())
                .createdAt(session.getCreatedAt())
                .updatedAt(session.getUpdatedAt())
                .messageCount(messageCount)
                .build();
    }
}
