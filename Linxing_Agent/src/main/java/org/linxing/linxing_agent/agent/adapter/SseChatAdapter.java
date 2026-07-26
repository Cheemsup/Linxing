package org.linxing.linxing_agent.agent.adapter;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.linxing.linxing_agent.common.userInfoMaintainer.BaseContext;
import org.linxing.linxing_agent.agent.core.AgentStepEvent;
import org.linxing.linxing_agent.agent.core.AgentStepListener;
import org.linxing.linxing_agent.agent.dto.ChatRequest;
import org.linxing.linxing_agent.agent.dto.ChatResponse;
import org.linxing.linxing_agent.agent.service.IChatService;
import org.linxing.linxing_agent.rag.config.RagProperties;
import org.linxing.linxing_agent.rag.constant.RedisKeysPrefix;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;

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

    /**
     * SSE 连接超时（毫秒）。需大于 study_plan 工作流的澄清等待超时（25 分钟），
     * 确保澄清等待期间 SSE 不会提前断开。设为 30 分钟。
     *
     */
    private static final long SSE_TIMEOUT_MS = 1_800_000L; // 1_800_000L

    private final IChatService chatService;
    private final StringRedisTemplate stringRedisTemplate;
    private final ObjectMapper objectMapper;
    private final RagProperties ragProperties;

    /**
     * 创建 SseEmitter 并异步执行聊天，将结果通过 SSE 事件推送。
     * <p>幂等键（requestId）处理：reset 只在原请求结束后（空闲 30 分钟）发生，
     * 故 retry 复用同一 requestId 进来时，原请求早已完成且结果已落 Redis 缓存。
     * 命中缓存则直接复用推送，不重跑推理、不重复落库；进行中并发兜底返回"处理中"。
     * requestId 为空（旧客户端）时退化为非幂等，走原路径。
     *
     * @param request 聊天请求
     * @return 已配置好的 SseEmitter
     */
    public SseEmitter streamChat(ChatRequest request) {
        SseEmitter emitter = new SseEmitter(SSE_TIMEOUT_MS);
        // emitter 失效标志：listener/onResult 推送失败或 emitter 生命周期终止时置 true。
        // 置 true 后停止后续 SSE 推送，但不抛异常——保证 chatService.chat 继续跑完推理与落库
        // （plan/exam/message 等副作用不受 SSE 断连影响）。
        AtomicBoolean emitterInvalid = new AtomicBoolean(false);
        registerLifecycleCallbacks(emitter, emitterInvalid);

        // 幂等键命中：reset 后 retry 复用同一 requestId，原请求已完成结果直接推送
        String requestId = request.getRequestId();
        if (requestId != null && !requestId.isBlank()) {
            ChatResponse cached = readCachedResponse(requestId);
            if (cached != null) {
                log.info("[Idempotent] requestId={} 命中已完成结果缓存，直接复用推送", requestId);
                final AtomicBoolean inv = emitterInvalid;
                CompletableFuture.runAsync(() -> {
                    try {
                        sendResult(emitter, cached, inv);
                        sendDone(emitter, inv);
                        emitter.complete();
                    } catch (IOException e) {
                        emitter.completeWithError(e);
                    }
                });
                return emitter;
            }
            // 进行中并发兜底：同 requestId 请求正在跑时，第二个返回"处理中"，不重跑
            if (markInProgress(requestId)) {
                // BaseContext 是 ThreadLocal，必须在主线程（Controller 线程）读出再传入异步块
                Long ctxUserId = BaseContext.getCurrentId();
                Integer resolvedUserId2 = ctxUserId != null ? ctxUserId.intValue() : null;
                CompletableFuture.runAsync(() -> {
                    try {
                        if (resolvedUserId2 != null) {
                            BaseContext.setCurrentId(ctxUserId);
                        }
                        try {
                            ChatResponse response = chatService.chat(request, buildListener(emitter, emitterInvalid));
                            cacheResponse(requestId, response);
                            sendResult(emitter, response, emitterInvalid);
                            sendDone(emitter, emitterInvalid);
                            emitter.complete();
                        } catch (IOException e) {
                            emitter.completeWithError(e);
                        } catch (Exception e) {
                            sendErrorAndComplete(emitter, e);
                        } finally {
                            clearInProgress(requestId);
                            if (resolvedUserId2 != null) {
                                BaseContext.clear();
                            }
                        }
                    } catch (Throwable t) {
                        log.warn("[Idempotent] requestId={} 异步推送失败: {}", requestId, t.getMessage());
                    }
                });
                return emitter;
            }
            // 进行中标记已被占（原请求还在跑）→ 拒绝重复发起
            log.info("[Idempotent] requestId={} 原请求进行中，拒绝重复发起", requestId);
            CompletableFuture.runAsync(() -> sendErrorAndComplete(emitter,
                    new IllegalStateException("请求处理中，请等待原请求结果")));
            return emitter;
        }

        // 无 requestId（旧客户端）→ 走原路径
        Long userId = BaseContext.getCurrentId();
        Integer resolvedUserId = userId != null ? userId.intValue() : null;

        CompletableFuture.runAsync(() -> {
            try {
                if (resolvedUserId != null) {
                    BaseContext.setCurrentId(userId);
                }

                ChatResponse response = chatService.chat(request, buildListener(emitter, emitterInvalid));

                sendResult(emitter, response, emitterInvalid);
                sendDone(emitter, emitterInvalid);
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

    /**
     * 标记 requestId 为"进行中"（SETNX）。
     * <p>TTL 复用 chatResponseTtl 作为兜底防泄漏——正常路径 clearInProgress 主动删除，
     * 仅当线程异常退出（如 JVM 崩溃）时由 TTL 兜底清理。
     * @return true 表示当前线程抢到，false 表示已被占用
     */
    private boolean markInProgress(String requestId) {
        try {
            String key = RedisKeysPrefix.CHAT_RESPONSE + "running:" + requestId;
            Boolean ok = stringRedisTemplate.opsForValue()
                    .setIfAbsent(key, "1", Duration.ofSeconds(ragProperties.getCache().getChatResponseTtl()));
            return Boolean.TRUE.equals(ok);
        } catch (Exception e) {
            log.warn("[Idempotent] markInProgress 失败，降级为放行: requestId={}, err={}", requestId, e.getMessage());
            return true;
        }
    }

    private void clearInProgress(String requestId) {
        try {
            stringRedisTemplate.delete(RedisKeysPrefix.CHAT_RESPONSE + "running:" + requestId);
        } catch (Exception e) {
            log.warn("[Idempotent] clearInProgress 失败: requestId={}, err={}", requestId, e.getMessage());
        }
    }

    /** 写已完成结果到 Redis（TTL 同 chatResponseTtl），失败降级不抛 */
    private void cacheResponse(String requestId, ChatResponse response) {
        try {
            String json = objectMapper.writeValueAsString(response);
            stringRedisTemplate.opsForValue().set(
                    RedisKeysPrefix.CHAT_RESPONSE + requestId, json,
                    Duration.ofSeconds(ragProperties.getCache().getChatResponseTtl()));
        } catch (Exception e) {
            log.warn("[Idempotent] cacheResponse 失败: requestId={}, err={}", requestId, e.getMessage());
        }
    }

    /** 读已完成结果缓存，失败或不存在返回 null */
    private ChatResponse readCachedResponse(String requestId) {
        try {
            String json = stringRedisTemplate.opsForValue().get(RedisKeysPrefix.CHAT_RESPONSE + requestId);
            if (json == null || json.isBlank()) {
                return null;
            }
            return objectMapper.readValue(json, ChatResponse.class);
        } catch (Exception e) {
            log.warn("[Idempotent] readCachedResponse 失败: requestId={}, err={}", requestId, e.getMessage());
            return null;
        }
    }

    private AgentStepListener buildListener(SseEmitter emitter, AtomicBoolean emitterInvalid) {
        return new AgentStepListener() {
            @Override
            public void onStep(AgentStepEvent event) {
                if (emitterInvalid.get()) return;
                try {
                    Map<String, Object> data = new LinkedHashMap<>();
                    data.put("eventType", event.getEventType());
                    data.put("stepNumber", event.getStepNumber());
                    data.put("phase", event.getPhase());
                    if (event.getLabel() != null && !event.getLabel().isBlank()) {
                        data.put("label", event.getLabel());
                    }
                    if (event.getStepData() != null && !event.getStepData().isEmpty()) {
                        data.put("stepData", event.getStepData());
                    }
                    if (event.getAnswer() != null) data.put("answer", event.getAnswer());
                    if (event.getError() != null) data.put("error", event.getError());
                    data.put("finalStep", event.isFinalStep());
                    //透传层级字段，供前端流式 onStep 实时归集到树
                    if (event.getStepId() != null) data.put("stepId", event.getStepId());
                    if (event.getParentStepId() != null) data.put("parentStepId", event.getParentStepId());
                    if (event.getAgentId() != null) data.put("agentId", event.getAgentId());

                    synchronized (emitter) {
                        emitter.send(SseEmitter.event().name("step").data(data));
                    }
                } catch (IOException e) {
                    // emitter 已断连（客户端关页/网络 reset/超时终止）。
                    // 不抛异常——标记失效后停止后续推送，但让 chatService.chat 继续跑完推理与落库
                    // （plan/exam/message 等副作用不受 SSE 断连影响）。
                    if (emitterInvalid.compareAndSet(false, true)) {
                        log.info("[SSE] onStep 推送失败，标记 emitter 失效（推理继续）: {}", e.getMessage());
                    }
                }
            }

            @Override
            public void onStream(String token, String type, int stepNumber) {
                if (emitterInvalid.get()) return;
                try {
                    Map<String, Object> data = new LinkedHashMap<>();
                    data.put("token", token);
                    data.put("type", type);
                    data.put("stepNumber", stepNumber);

                    synchronized (emitter) {
                        emitter.send(SseEmitter.event().name("stream").data(data));
                    }
                } catch (IOException e) {
                    if (emitterInvalid.compareAndSet(false, true)) {
                        log.info("[SSE] onStream 推送失败，标记 emitter 失效（推理继续）: {}", e.getMessage());
                    }
                }
            }
        };
    }

    /**
     * 注册 SseEmitter 生命周期回调。
     * <p>onTimeout/onError/onCompletion 任一触发即标记 emitter 失效，停止后续推送。
     * <p>注意：不在此清理 Redis 幂等缓存——reset 后前端 retry 复用同一 requestId 仍需命中已完成结果缓存。
     * emitter 终止只代表"当前这条 SSE 连接断了"，不代表"请求结果没了"。
     */
    private void registerLifecycleCallbacks(SseEmitter emitter, AtomicBoolean emitterInvalid) {
        emitter.onTimeout(() -> {
            if (emitterInvalid.compareAndSet(false, true)) {
                log.info("[SSE] emitter onTimeout，标记失效（推理继续，缓存保留供 retry 复用）");
            }
            emitter.complete();
        });
        emitter.onError(t -> {
            if (emitterInvalid.compareAndSet(false, true)) {
                log.info("[SSE] emitter onError，标记失效（推理继续，缓存保留供 retry 复用）: {}", t.getMessage());
            }
        });
        emitter.onCompletion(() -> {
            emitterInvalid.set(true);
        });
    }

    private void sendResult(SseEmitter emitter, ChatResponse response, AtomicBoolean emitterInvalid) throws IOException {
        if (emitterInvalid.get()) return;
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

    private void sendDone(SseEmitter emitter, AtomicBoolean emitterInvalid) throws IOException {
        if (emitterInvalid.get()) return;
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
