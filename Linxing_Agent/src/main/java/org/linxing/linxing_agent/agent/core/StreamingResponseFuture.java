package org.linxing.linxing_agent.agent.core;

import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.chat.response.StreamingChatResponseHandler;
import lombok.extern.slf4j.Slf4j;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 流式聊天响应的同步等待封装
 * 使用方式：
 * <pre>
 *   StreamingResponseFuture future = new StreamingResponseFuture(listener, stepNumber);
 *   chatModel.chat(request, future);
 *   ChatResponse response = future.await(120, TimeUnit.SECONDS);
 * </pre>
 * 
 */
@Slf4j
public class StreamingResponseFuture implements StreamingChatResponseHandler {

    private final CountDownLatch latch = new CountDownLatch(1);
    private final AtomicReference<ChatResponse> responseHolder = new AtomicReference<>();
    private final AtomicReference<Throwable> errorHolder = new AtomicReference<>();

    private final AgentStepListener listener;
    private final int stepNumber;

    public StreamingResponseFuture(AgentStepListener listener, int stepNumber) {
        this.listener = listener;
        this.stepNumber = stepNumber;
    }

    @Override
    public void onPartialResponse(String partialResponse) {
        listener.onStream(partialResponse, stepNumber);
    }

    @Override
    public void onCompleteResponse(ChatResponse completeResponse) {
        responseHolder.set(completeResponse);
        latch.countDown();
    }

    @Override
    public void onError(Throwable error) {
        log.error("[StreamingResponseFuture] 流式错误: error={}", error.getMessage());
        errorHolder.set(error);
        latch.countDown();
    }

    /**
     * 同步等待流式响应完成，返回 ChatResponse。
     *
     * @param timeout 超时时间
     * @param unit    时间单位
     * @return ChatResponse
     * @throws RuntimeException 超时或流式调用失败时抛出
     */
    public ChatResponse await(long timeout, TimeUnit unit) {
        boolean completed;
        try {
            completed = latch.await(timeout, unit);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("LLM调用被中断", e);
        }

        if (!completed) {
            throw new RuntimeException("LLM调用超时 (" + timeout + "秒)");
        }
        if (errorHolder.get() != null) {
            throw new RuntimeException("LLM流式调用失败: " + errorHolder.get().getMessage(), errorHolder.get());
        }

        return responseHolder.get();
    }
}
