package org.linxing.linxing_agent.agent.adapter;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.linxing.linxing_agent.common.userInfoMaintainer.BaseContext;
import org.linxing.linxing_agent.agent.core.AgentStepEvent;
import org.linxing.linxing_agent.agent.core.AgentStepListener;
import org.linxing.linxing_agent.agent.dto.ChatRequest;
import org.linxing.linxing_agent.agent.dto.ChatResponse;
import org.linxing.linxing_agent.agent.service.IChatService;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * SSE 流式聊天适配器
 * <p>
 * 将 AgentStepListener 回调映射为 SSE 事件发送，
 * 管理 SseEmitter 生命周期、异步执行、异常处理和 BaseContext 清理。
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class SseChatAdapter {

    private static final long SSE_TIMEOUT_MS = 300_000L;

    private final IChatService chatService;

    /**
     * 创建 SseEmitter 并异步执行聊天，将结果通过 SSE 事件推送。
     *
     * @param request 聊天请求
     * @return 已配置好的 SseEmitter
     */
    public SseEmitter streamChat(ChatRequest request) {
        SseEmitter emitter = new SseEmitter(SSE_TIMEOUT_MS);

        Long userId = BaseContext.getCurrentId();
        Integer resolvedUserId = userId != null ? userId.intValue() : null;

        CompletableFuture.runAsync(() -> {
            try {
                if (resolvedUserId != null) {
                    BaseContext.setCurrentId(userId);
                }

                ChatResponse response = chatService.chat(request, buildListener(emitter));

                sendResult(emitter, response);
                sendDone(emitter);
                emitter.complete();
            } catch (IOException e) {
                emitter.completeWithError(e);
            } catch (Exception e) {
                sendErrorAndComplete(emitter, e);
            } finally {
                if (resolvedUserId != null) {
                    BaseContext.clear();
                }
            }
        });

        return emitter;
    }

    private AgentStepListener buildListener(SseEmitter emitter) {
        return new AgentStepListener() {
            @Override
            public void onStep(AgentStepEvent event) {
                try {
                    Map<String, Object> data = new LinkedHashMap<>();
                    data.put("eventType", event.getEventType());
                    data.put("stepNumber", event.getStepNumber());
                    data.put("phase", event.getPhase());
                    if (event.getStepData() != null && !event.getStepData().isEmpty()) {
                        data.put("stepData", event.getStepData());
                    }
                    if (event.getAnswer() != null) data.put("answer", event.getAnswer());
                    if (event.getError() != null) data.put("error", event.getError());
                    data.put("finalStep", event.isFinalStep());

                    synchronized (emitter) {
                        emitter.send(SseEmitter.event().name("step").data(data));
                    }
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }
            }

            @Override
            public void onStream(String token, int stepNumber) {
                try {
                    Map<String, Object> data = new LinkedHashMap<>();
                    data.put("token", token);
                    data.put("stepNumber", stepNumber);

                    synchronized (emitter) {
                        emitter.send(SseEmitter.event().name("stream").data(data));
                    }
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }
            }
        };
    }

    private void sendResult(SseEmitter emitter, ChatResponse response) throws IOException {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("answer", response.getAnswer());
        data.put("sources", response.getSources());
        data.put("sourceDetails", response.getSourceDetails());
        data.put("sessionId", response.getSessionId());
        data.put("messageId", response.getMessageId());

        synchronized (emitter) {
            emitter.send(SseEmitter.event().name("result").data(data));
        }
    }

    private void sendDone(SseEmitter emitter) throws IOException {
        synchronized (emitter) {
            emitter.send(SseEmitter.event().name("done").data(""));
        }
    }

    private void sendErrorAndComplete(SseEmitter emitter, Exception e) {
        try {
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("message", e.getMessage());
            synchronized (emitter) {
                emitter.send(SseEmitter.event().name("error").data(data));
            }
            emitter.complete();
        } catch (IOException ex) {
            emitter.completeWithError(ex);
        }
    }
}
