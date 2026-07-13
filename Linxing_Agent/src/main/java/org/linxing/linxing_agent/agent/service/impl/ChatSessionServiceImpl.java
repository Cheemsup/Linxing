package org.linxing.linxing_agent.agent.service.impl;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.linxing.linxing_agent.common.config.LlmManager;
import org.linxing.linxing_agent.common.result.PageResult;
import org.linxing.linxing_agent.agent.entity.ChatMessage;
import org.linxing.linxing_agent.agent.entity.ChatSession;
import org.linxing.linxing_agent.agent.mapper.ChatMessageMapper;
import org.linxing.linxing_agent.agent.mapper.ChatSessionMapper;
import org.linxing.linxing_agent.agent.service.IChatMessageCacheService;
import org.linxing.linxing_agent.agent.service.IChatSessionService;
import org.linxing.linxing_agent.agent.vo.ChatSessionVO;
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
    private final IChatMessageCacheService chatMessageCacheService;
    private final LlmManager llmManager;

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

    @Override
    public String autoGenerateTitle(Integer sessionId) {
        ChatSession session = chatSessionMapper.selectById(sessionId);
        if (session == null) {
            throw new IllegalArgumentException("会话不存在: " + sessionId);
        }
        // 仅当标题仍为默认占位时才自动命名，避免覆盖用户已自定义的标题
        String currentTitle = session.getTitle();
        if (currentTitle != null && !currentTitle.isBlank()
                && !"新对话".equals(currentTitle) && !currentTitle.startsWith("新对话")) {
            return currentTitle;
        }

        List<ChatMessage> messages = chatMessageMapper.selectBySessionId(sessionId);
        String firstUser = messages.stream()
                .filter(m -> "user".equals(m.getRole()))
                .map(ChatMessage::getContent)
                .findFirst()
                .orElse(null);
        String firstAssistant = messages.stream()
                .filter(m -> "assistant".equals(m.getRole()))
                .map(ChatMessage::getContent)
                .findFirst()
                .orElse(null);
        if (firstUser == null) {
            return currentTitle;
        }

        String prompt = "你是一个标题生成助手。请根据以下对话生成一个简短的中文标题（不超过12个字，不要加引号、不要加句号），只输出标题文本：\n"
                + "用户：" + truncate(firstUser, 200) + "\n"
                + (firstAssistant != null ? "助手：" + truncate(firstAssistant, 200) : "");

        try {
            String title = llmManager.getDefaultModel().chat(prompt);
            if (title != null) {
                title = title.trim().replaceAll("^[\"'\u201C\u201D\u2018\u2019]+|[\"'\u201C\u201D\u2018\u2019]+$", "");
                if (title.length() > 20) {
                    title = title.substring(0, 20);
                }
                if (!title.isBlank()) {
                    chatSessionMapper.updateTitle(sessionId, title);
                    log.info("会话 {} 自动命名为: {}", sessionId, title);
                    return title;
                }
            }
        } catch (Exception e) {
            log.warn("会话 {} 自动命名失败: {}", sessionId, e.getMessage());
        }
        // 命名失败时回退到首条用户消息截断
        String fallback = firstUser.length() > 12 ? firstUser.substring(0, 12) + "..." : firstUser;
        chatSessionMapper.updateTitle(sessionId, fallback);
        return fallback;
    }

    private String truncate(String text, int max) {
        if (text == null) return "";
        return text.length() > max ? text.substring(0, max) + "..." : text;
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
