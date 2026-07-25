package org.linxing.linxing_agent.agent.service.impl;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.linxing.linxing_agent.agent.entity.ChatMessage;
import org.linxing.linxing_agent.agent.entity.ChatSession;
import org.linxing.linxing_agent.agent.mapper.ChatMessageMapper;
import org.linxing.linxing_agent.agent.mapper.ChatSessionMapper;
import org.linxing.linxing_agent.agent.service.IChatMessageService;
import org.linxing.linxing_agent.agent.service.IRuntimeMirrorService;
import org.linxing.linxing_agent.agent.vo.ChatMessageVO;
import org.linxing.linxing_agent.common.constant.MessageType;
import org.springframework.stereotype.Service;

import java.time.OffsetDateTime;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 聊天消息与会话的持久化服务
 * <p>
 * 2-C 起 Recovery（recoverHistory/toLangchainMessages/loadRecentMessages）下沉至
 * {@code org.linxing.linxing_agent.agent.memory.recovery.HistoryRecoveryService}，
 * 本类仅保留消息持久化、缓存与 VO 职责。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ChatMessageServiceImpl implements IChatMessageService {

    private final ChatMessageMapper chatMessageMapper;
    private final ChatSessionMapper chatSessionMapper;
    private final IRuntimeMirrorService runtimeMirrorService; // P3 Mirror：取代旧 IChatMessageCacheService

    /**
     * 保存用户消息
     * <p>预填 nearest_summary_message_id（thePlan P1-2 语义：本节点回溯路径上之前最近的 summary id），
     * 使后续 Recovery 点查本节点即可定位 summary，不必递归 parent。预填规则：
     * parent 为 summary → parent.id；否则继承 parent 的 nearest；parent 为 null → null。
     */
    public ChatMessage saveUserMessage(Integer userId, Integer sessionId,
                                        Integer parentId, String content) {
        ChatMessage userMsg = ChatMessage.builder()
                .userId(userId)
                .sessionId(sessionId)
                .parentId(parentId)
                .type(MessageType.USER)
                .content(content)
                .sources("[]")
                .nearestSummaryMessageId(resolveNearestSummary(parentId))
                .createdAt(OffsetDateTime.now())
                .build();
        chatMessageMapper.insert(userMsg);
        // P3 Mirror：用户消息入库即镜像到 mirror:msgs（决策 4b：前端可见性 + 下一轮 Recovery 镜像一致）
        runtimeMirrorService.appendMessage(sessionId, userMsg);
        log.debug("[用户{}] 保存用户消息 id={}, sessionId={}, parentId={}, nearestSummary={}",
                userId, userMsg.getId(), userMsg.getSessionId(), userMsg.getParentId(),
                userMsg.getNearestSummaryMessageId());
        return userMsg;
    }

    /**
     * 保存助手消息
     * <p>同 {@link #saveUserMessage}，预填 nearest_summary_message_id 以保证后续消息继承链不中断。
     */
    public ChatMessage saveAssistantMessage(Integer userId, Integer sessionId,
                                             Integer parentId, String content,
                                             String sourcesJson) {
        ChatMessage assistantMsg = ChatMessage.builder()
                .userId(userId)
                .sessionId(sessionId)
                .parentId(parentId)
                .type(MessageType.ASSISTANT)
                .content(content)
                .sources(sourcesJson)
                .nearestSummaryMessageId(resolveNearestSummary(parentId))
                .createdAt(OffsetDateTime.now())
                .build();
        chatMessageMapper.insert(assistantMsg);
        return assistantMsg;
    }

    /**
     * 解析某节点的"之前最近 summary id"（thePlan P1-2 nearest 语义）。
     * <p>O(1) 继承：parent 是 summary 则取其 id，否则继承 parent 的 nearest_summary_message_id，
     * parent 为 null 返回 null。避免沿 parent 链递归回溯。
     */
    private Integer resolveNearestSummary(Integer parentId) {
        if (parentId == null) {
            return null;
        }
        ChatMessage parent = chatMessageMapper.selectById(parentId);
        if (parent == null) {
            return null;
        }
        if (MessageType.SUMMARY.equals(parent.getType())) {
            return parent.getId();
        }
        return parent.getNearestSummaryMessageId();
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
     * 更新会话的最近更新时间
     */
    public void touchSession(Integer sessionId) {
        chatSessionMapper.updateUpdatedAt(sessionId);
    }

    public ChatMessageVO toMessageVO(ChatMessage msg) {
        return ChatMessageVO.builder()
                .id(msg.getId())
                .userId(msg.getUserId())
                .sessionId(msg.getSessionId())
                .parentId(msg.getParentId())
                .type(msg.getType())
                .content(msg.getContent())
                .sources(msg.getSources())
                .createdAt(msg.getCreatedAt())
                .build();
    }

    /**
     * 获取会话消息列表
     * @param sessionId 会话ID
     * @return 消息VO列表
     */
    @Override
    public List<ChatMessageVO> getMessages(Integer sessionId) {
        // 优先读 Mirror：返回的是 ChatMessage 实体（含 nearestSummaryMessageId 等全字段）
        List<ChatMessage> mirrored = runtimeMirrorService.loadMessages(sessionId);
        if (mirrored != null) {
            return mirrored.stream().map(this::toMessageVO).collect(Collectors.toList());
        }

        // Mirror miss/异常 → 回源 DB，并热身 mirror:msgs（cache-aside；steps 由 Recovery/端点懒热）
        List<ChatMessage> messages = chatMessageMapper.selectBySessionId(sessionId);
        List<ChatMessageVO> vos = messages.stream().map(this::toMessageVO).collect(Collectors.toList());
        runtimeMirrorService.replaceAll(sessionId, messages, List.of());
        return vos;
    }

    /**
     * 删除消息及其所有子消息，并同步失效整 session 镜像（下次读重建）
     * @param messageId 根消息ID
     */
    @Override
    public void deleteSubtree(Integer messageId) {
        ChatMessage root = chatMessageMapper.selectById(messageId);
        List<Integer> ids = collectSubtreeIds(messageId);
        if (!ids.isEmpty()) {
            chatMessageMapper.deleteByIds(ids);
        }
        // 子树删除使 session 镜像整体失效，整 session 删除后下次读重建（cache-aside）
        if (root != null) {
            runtimeMirrorService.deleteSession(root.getSessionId());
        }
    }

    /**
     * BFS收集消息及其所有子消息ID
     * @param messageId 根消息ID
     * @return 子树消息ID列表
     */
    private List<Integer> collectSubtreeIds(Integer messageId) {
        ChatMessage root = chatMessageMapper.selectById(messageId);
        if (root == null) {
            return List.of();
        }
        List<ChatMessage> allMessages = chatMessageMapper.selectBySessionId(root.getSessionId());
        //构建parentId→children映射
        Map<Integer, List<Integer>> childrenMap = new HashMap<>();
        for (ChatMessage msg : allMessages) {
            if (msg.getParentId() != null) {
                childrenMap.computeIfAbsent(msg.getParentId(), k -> new ArrayList<>()).add(msg.getId());
            }
        }
        //BFS遍历子树
        List<Integer> result = new ArrayList<>();
        Deque<Integer> queue = new ArrayDeque<>();
        queue.add(messageId);
        result.add(messageId);
        while (!queue.isEmpty()) {
            Integer current = queue.poll();
            List<Integer> children = childrenMap.get(current);
            if (children != null) {
                for (Integer childId : children) {
                    queue.add(childId);
                    result.add(childId);
                }
            }
        }
        return result;
    }
}
