package org.linxing.linxing_agent.agent.controller;

import lombok.RequiredArgsConstructor;
import org.linxing.linxing_agent.common.userInfoMaintainer.BaseContext;
import org.linxing.linxing_agent.agent.dto.ChatRequest;
import org.linxing.linxing_agent.agent.dto.ChatResponse;
import org.linxing.linxing_agent.common.result.PageResult;
import org.linxing.linxing_agent.agent.entity.ChatMessage;
import org.linxing.linxing_agent.agent.mapper.ChatMessageMapper;
import org.linxing.linxing_agent.common.result.Result;
import org.linxing.linxing_agent.agent.service.IChatService;
import org.linxing.linxing_agent.agent.service.IChatSessionService;
import org.linxing.linxing_agent.agent.service.impl.ChatMessageCacheService;
import org.linxing.linxing_agent.agent.vo.ChatMessageVO;
import org.linxing.linxing_agent.agent.vo.ChatSessionVO;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/agent")
@RequiredArgsConstructor
public class ChatController {

    private final IChatService chatService;
    private final IChatSessionService chatSessionService;
    private final ChatMessageMapper chatMessageMapper;
    private final ChatMessageCacheService chatMessageCacheService;

    @GetMapping("/chat")
    public SseEmitter agentChat(
            @RequestParam String query,
            @RequestParam(required = false) Integer sessionId,
            @RequestParam(required = false) Integer parentMessageId) {
        SseEmitter emitter = new SseEmitter(300_000L);

        Long userId = BaseContext.getCurrentId();
        Integer resolvedUserId = userId != null ? userId.intValue() : null;

        CompletableFuture.runAsync(() -> {
            try {
                if (resolvedUserId != null) {
                    BaseContext.setCurrentId(userId);
                }

                ChatRequest request = ChatRequest.builder()
                        .question(query)
                        .sessionId(sessionId)
                        .parentMessageId(parentMessageId)
                        .userId(resolvedUserId)
                        .build();

                ChatResponse response = chatService.chat(request);

                Map<String, Object> resultData = new LinkedHashMap<>();
                resultData.put("type", "result");
                resultData.put("answer", response.getAnswer());
                resultData.put("sources", response.getSources());
                resultData.put("sourceDetails", response.getSourceDetails());
                resultData.put("sessionId", response.getSessionId());
                resultData.put("messageId", response.getMessageId());
                emitter.send(SseEmitter.event()
                        .name("message")
                        .data(resultData));

                Map<String, Object> doneData = new LinkedHashMap<>();
                doneData.put("type", "done");
                emitter.send(SseEmitter.event()
                        .name("message")
                        .data(doneData));
                emitter.complete();
            } catch (IOException e) {
                emitter.completeWithError(e);
            } catch (Exception e) {
                try {
                    Map<String, Object> errorData = new LinkedHashMap<>();
                    errorData.put("type", "error");
                    errorData.put("message", e.getMessage());
                    emitter.send(SseEmitter.event()
                            .name("message")
                            .data(errorData));
                    emitter.complete();
                } catch (IOException ex) {
                    emitter.completeWithError(ex);
                }
            } finally {
                if (resolvedUserId != null) {
                    BaseContext.clear();
                }
            }
        });

        return emitter;
    }

    @PostMapping("/sessions")
    public Result<ChatSessionVO> createSession(@RequestBody Map<String, String> body) {
        Integer userId = getCurrentUserId();
        String title = body.getOrDefault("title", "新对话");
        return Result.success(chatSessionService.createSession(userId, title));
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
        List<ChatMessageVO> cached = chatMessageCacheService.getMessages(sessionId);
        if (cached != null) {
            int dbCount = chatMessageMapper.countBySessionId(sessionId);
            if (cached.size() == dbCount) {
                return Result.success(cached);
            }
            chatMessageCacheService.deleteSession(sessionId);
        }

        List<ChatMessage> messages = chatMessageMapper.selectBySessionId(sessionId);
        List<ChatMessageVO> vos = messages.stream().map(this::toMessageVO).collect(Collectors.toList());

        chatMessageCacheService.putMessages(sessionId, vos);
        return Result.success(vos);
    }

    @DeleteMapping("/messages/{messageId}/subtree")
    public Result<Void> deleteSubtree(@PathVariable Integer messageId) {
        ChatMessage root = chatMessageMapper.selectById(messageId);
        List<Integer> ids = collectSubtreeIds(messageId);
        if (!ids.isEmpty()) {
            chatMessageMapper.deleteByIds(ids);
            if (root != null) {
                chatMessageCacheService.deleteMessages(root.getSessionId(), ids);
            }
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
