package org.linxing.linxing_agent.controller;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.linxing.linxing_agent.context.BaseContext;
import org.linxing.linxing_agent.dto.ChatRequest;
import org.linxing.linxing_agent.dto.ChatResponse;
import org.linxing.linxing_agent.dto.PageResult;
import org.linxing.linxing_agent.entity.ChatMessage;
import org.linxing.linxing_agent.mapper.ChatMessageMapper;
import org.linxing.linxing_agent.result.Result;
import org.linxing.linxing_agent.service.IChatService;
import org.linxing.linxing_agent.service.IChatSessionService;
import org.linxing.linxing_agent.vo.ChatMessageVO;
import org.linxing.linxing_agent.vo.ChatSessionVO;
import org.springframework.web.bind.annotation.*;

import java.util.*;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/rag")
@RequiredArgsConstructor
public class ChatController {

    private final IChatService chatService;
    private final IChatSessionService chatSessionService;
    private final ChatMessageMapper chatMessageMapper;

    @PostMapping("/chat")
    public Result<ChatResponse> chat(@Valid @RequestBody ChatRequest request) {
        ChatResponse response = chatService.chat(request);
        return Result.success(response);
    }

    @GetMapping("/sessions")
    public Result<PageResult<ChatSessionVO>> listSessions(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        Integer userId = getCurrentUserId();
        return Result.success(chatSessionService.listSessions(userId, page, size));
    }

    @DeleteMapping("/sessions/{sessionId}")
    public Result<Void> deleteSession(@PathVariable Integer sessionId) {
        chatSessionService.deleteSession(sessionId);
        return Result.success();
    }

    @GetMapping("/sessions/{sessionId}/messages")
    public Result<List<ChatMessageVO>> getMessages(@PathVariable Integer sessionId) {
        List<ChatMessage> messages = chatMessageMapper.selectBySessionId(sessionId);
        List<ChatMessageVO> vos = messages.stream().map(this::toMessageVO).collect(Collectors.toList());
        return Result.success(vos);
    }

    @DeleteMapping("/messages/{messageId}/subtree")
    public Result<Void> deleteSubtree(@PathVariable Integer messageId) {
        List<Integer> ids = collectSubtreeIds(messageId);
        if (!ids.isEmpty()) {
            chatMessageMapper.deleteByIds(ids);
        }
        return Result.success();
    }

    private List<Integer> collectSubtreeIds(Integer messageId) {
        ChatMessage root = chatMessageMapper.selectById(messageId);
        if (root == null) {
            return List.of();
        }
        List<ChatMessage> allMessages = chatMessageMapper.selectBySessionId(root.getSessionId());
        Map<Integer, List<Integer>> childrenMap = new HashMap<>();
        for (ChatMessage msg : allMessages) {
            if (msg.getParentId() != null) {
                childrenMap.computeIfAbsent(msg.getParentId(), k -> new ArrayList<>()).add(msg.getId());
            }
        }
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

    private static Integer getCurrentUserId() {
        Long userId = BaseContext.getCurrentId();
        if (userId == null) {
            throw new IllegalStateException("用户未登录");
        }
        return userId.intValue();
    }
}
