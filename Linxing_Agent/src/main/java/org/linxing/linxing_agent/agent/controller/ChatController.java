package org.linxing.linxing_agent.agent.controller;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.linxing.linxing_agent.common.userInfoMaintainer.BaseContext;
import org.linxing.linxing_agent.agent.adapter.SseChatAdapter;
import org.linxing.linxing_agent.agent.dto.ChatRequest;
import org.linxing.linxing_agent.common.result.PageResult;
import org.linxing.linxing_agent.agent.entity.ChatMessage;
import org.linxing.linxing_agent.agent.mapper.ChatMessageMapper;
import org.linxing.linxing_agent.common.result.Result;
import org.linxing.linxing_agent.agent.service.IChatSessionService;
import org.linxing.linxing_agent.agent.service.impl.ChatMessageCacheService;
import org.linxing.linxing_agent.agent.vo.ChatMessageVO;
import org.linxing.linxing_agent.agent.vo.ChatSessionVO;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import jakarta.servlet.http.HttpServletResponse;
import java.util.*;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/agent")
@RequiredArgsConstructor
@Slf4j
public class ChatController {

    private final SseChatAdapter sseChatAdapter;
    private final IChatSessionService chatSessionService;
    private final ChatMessageMapper chatMessageMapper;
    private final ChatMessageCacheService chatMessageCacheService;

    /**
     * SSE流式对话
     * @param request
     * @param httpResponse
     * @return
     */
    @PostMapping("/chat")
    public SseEmitter agentChat(@RequestBody ChatRequest request,
                                HttpServletResponse httpResponse) {
        httpResponse.setHeader("Cache-Control", "no-cache");//禁止缓存，确保SSE实时推送
        httpResponse.setHeader("X-Accel-Buffering", "no");//禁止Nginx缓冲

        request.setUserId(resolveUserId());
        return sseChatAdapter.streamChat(request);
    }

    /**
     * 从线程上下文中获取当前用户ID并转为Integer
     * @return
     */
    private Integer resolveUserId() {
        Long userId = BaseContext.getCurrentId();
        return userId != null ? userId.intValue() : null;
    }

    /**
     * 创建新对话会话
     * @param body
     * @return
     */
    @PostMapping("/sessions")
    public Result<ChatSessionVO> createSession(@RequestBody Map<String, String> body) {
        Integer userId = getCurrentUserId();
        String title = body.getOrDefault("title", "新对话");
        return Result.success(chatSessionService.createSession(userId, title));
    }

    /**
     * 分页查询当前用户的对话会话列表
     * @param page
     * @param size
     * @return
     */
    @GetMapping("/sessions")
    public Result<PageResult<ChatSessionVO>> listSessions(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        Integer userId = getCurrentUserId();
        return Result.success(chatSessionService.listSessions(userId, page, size));
    }

    /**
     * 删除对话会话
     * @param sessionId
     * @return
     */
    @DeleteMapping("/sessions/{sessionId}")
    public Result<Void> deleteSession(@PathVariable Integer sessionId) {
        chatSessionService.deleteSession(sessionId);
        return Result.success();
    }

    /**
     * 获取会话下的消息列表，优先读缓存，缓存不一致时回源DB并刷新缓存
     * @param sessionId
     * @return
     */
    @GetMapping("/sessions/{sessionId}/messages")
    public Result<List<ChatMessageVO>> getMessages(@PathVariable Integer sessionId) {
        List<ChatMessageVO> cached = chatMessageCacheService.getMessages(sessionId);
        if (cached != null) {
            int dbCount = chatMessageMapper.countBySessionId(sessionId);
            if (cached.size() == dbCount && isValidCache(cached)) {
                return Result.success(cached);
            }
            chatMessageCacheService.deleteSession(sessionId);//缓存与DB不一致，清除缓存
        }

        List<ChatMessage> messages = chatMessageMapper.selectBySessionId(sessionId);
        List<ChatMessageVO> vos = messages.stream().map(this::toMessageVO).collect(Collectors.toList());

        chatMessageCacheService.putMessages(sessionId, vos);//回源后写入缓存
        return Result.success(vos);
    }

    /**
     * 校验缓存有效性：assistant消息必须有parentId
     * @param messages
     * @return
     */
    private boolean isValidCache(List<ChatMessageVO> messages) {
        return messages.stream().noneMatch(
                m -> "assistant".equals(m.getRole()) && m.getParentId() == null);
    }

    /**
     * 删除消息及其所有子消息
     * @param messageId
     * @return
     */
    @DeleteMapping("/messages/{messageId}/subtree")
    public Result<Void> deleteSubtree(@PathVariable Integer messageId) {
        ChatMessage root = chatMessageMapper.selectById(messageId);
        List<Integer> ids = collectSubtreeIds(messageId);
        if (!ids.isEmpty()) {
            chatMessageMapper.deleteByIds(ids);
            if (root != null) {
                chatMessageCacheService.deleteMessages(root.getSessionId(), ids);//同步清除缓存
            }
        }
        return Result.success();
    }

    /**
     * BFS收集消息及其所有子消息ID
     * @param messageId
     * @return
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

    private ChatMessageVO toMessageVO(ChatMessage msg) {
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

    /**
     * 获取当前登录用户ID，未登录则抛异常
     * @return
     */
    private static Integer getCurrentUserId() {
        Long userId = BaseContext.getCurrentId();
        if (userId == null) {
            throw new IllegalStateException("用户未登录");
        }
        return userId.intValue();
    }
}
