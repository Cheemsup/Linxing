package org.linxing.linxing_agent.agent.controller;

import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.linxing.linxing_agent.common.userInfoMaintainer.BaseContext;
import org.linxing.linxing_agent.agent.core.AgentStepEvent;
import org.linxing.linxing_agent.agent.core.AgentStepListener;
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
@Slf4j
public class ChatController {

    private final IChatService chatService;
    private final IChatSessionService chatSessionService;
    private final ChatMessageMapper chatMessageMapper;
    private final ChatMessageCacheService chatMessageCacheService;

    @GetMapping("/chat")
    public SseEmitter agentChat(
            @RequestParam String query,
            @RequestParam(required = false) Integer sessionId,
            @RequestParam(required = false) Integer parentMessageId,
            HttpServletResponse httpResponse) {

        // 禁用Tomcat输出缓冲，确保SSE事件实时推送到客户端
        httpResponse.setBufferSize(0);
        httpResponse.setHeader("Cache-Control", "no-cache");
        httpResponse.setHeader("X-Accel-Buffering", "no");

        SseEmitter emitter = new SseEmitter(300_000L);

        Long userId = BaseContext.getCurrentId();
        Integer resolvedUserId = userId != null ? userId.intValue() : null;

        // 流式token计数器（用于日志）
        int[] streamTokenCount = {0};
        int[] streamAccLen = {0};

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

                ChatResponse response = chatService.chat(request, new AgentStepListener() {
                    @Override
                    public void onStep(AgentStepEvent event) {
                        try {
                            Map<String, Object> stepData = new LinkedHashMap<>();
                            stepData.put("type", "step");
                            stepData.put("eventType", event.getEventType());
                            stepData.put("stepNumber", event.getStepNumber());
                            if (event.getToolName() != null) {
                                stepData.put("toolName", event.getToolName());
                            }
                            if (event.getToolArguments() != null) {
                                stepData.put("toolArguments", event.getToolArguments());
                            }
                            if (event.getToolResult() != null) {
                                stepData.put("toolResult", event.getToolResult());
                            }
                            if (event.getAnswer() != null) {
                                stepData.put("answer", event.getAnswer());
                            }
                            if (event.getError() != null) {
                                stepData.put("error", event.getError());
                            }
                            stepData.put("finalStep", event.isFinalStep());

                            // log.info("[SSE] 发送step事件: eventType={}, step={}, final={}",
                            //         event.getEventType(), event.getStepNumber(), event.isFinalStep());

                            synchronized (emitter) {
                                emitter.send(SseEmitter.event()
                                        .name("message")
                                        .data(stepData));
                            }
                        } catch (IOException e) {
                            throw new RuntimeException(e);
                        }
                    }

                    @Override
                    public void onStream(String token) {
                        try {
                            streamTokenCount[0]++;
                            Map<String, Object> streamData = new LinkedHashMap<>();
                            streamData.put("type", "llm_stream");
                            streamData.put("token", token);

                            synchronized (emitter) {
                                emitter.send(SseEmitter.event()
                                        .name("message")
                                        .data(streamData));
                            }

                            // 实时输出接收到的token进度（调试用，取消注释可查看）
                            // if (streamTokenCount[0] % 50 == 1) {
                            //     log.info("[SSE] 流式发送进度: 第{}个token, 累计长度={}",
                            //             streamTokenCount[0], streamAccLen[0]);
                            // }
                            streamAccLen[0] += token.length();
                        } catch (IOException e) {
                            throw new RuntimeException(e);
                        }
                    }
                });

                Map<String, Object> resultData = new LinkedHashMap<>();
                resultData.put("type", "result");
                resultData.put("answer", response.getAnswer());
                resultData.put("sources", response.getSources());
                resultData.put("sourceDetails", response.getSourceDetails());
                resultData.put("sessionId", response.getSessionId());
                resultData.put("messageId", response.getMessageId());

                // log.info("[SSE] 发送result事件: answer长度={}",
                //         response.getAnswer() != null ? response.getAnswer().length() : 0);

                emitter.send(SseEmitter.event()
                        .name("message")
                        .data(resultData));

                Map<String, Object> doneData = new LinkedHashMap<>();
                doneData.put("type", "done");

                // log.info("[SSE] 发送done事件, SSE流结束");

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
            if (cached.size() == dbCount && isValidCache(cached)) {
                return Result.success(cached);
            }
            chatMessageCacheService.deleteSession(sessionId);
        }

        List<ChatMessage> messages = chatMessageMapper.selectBySessionId(sessionId);
        List<ChatMessageVO> vos = messages.stream().map(this::toMessageVO).collect(Collectors.toList());

        chatMessageCacheService.putMessages(sessionId, vos);
        return Result.success(vos);
    }

    private boolean isValidCache(List<ChatMessageVO> messages) {
        return messages.stream().noneMatch(
                m -> "assistant".equals(m.getRole()) && m.getParentId() == null);
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
