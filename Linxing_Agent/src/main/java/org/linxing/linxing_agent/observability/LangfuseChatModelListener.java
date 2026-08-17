package org.linxing.linxing_agent.observability;

import dev.langchain4j.model.chat.listener.ChatModelErrorContext;
import dev.langchain4j.model.chat.listener.ChatModelListener;
import dev.langchain4j.model.chat.listener.ChatModelRequestContext;
import dev.langchain4j.model.chat.listener.ChatModelResponseContext;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.output.TokenUsage;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Context;
import lombok.extern.slf4j.Slf4j;

/**
 * Langfuse generation span 打点：在每轮 LLM 调用上创建/结束一个 generation span（3.3）。
 * <p>挂载点：LlmManager 两个 builder（流式+非流式）均 {@code .listeners(factory.create(provider))}，
 * 一经注册全站覆盖（主循环 + 子 Agent + 离线调用）。
 * <p>关键机制：
 * <ul>
 *   <li><b>onRequest</b>（agent/工具线程，同步）：读 {@link ObservableContext#current()} 拿到父上下文建 span，
 *       span 引用存入 attributes map（从 onRequest 贯穿到 onResponse/onError）；无外层上下文且未开
 *       离线 tracing 时静默跳过；</li>
 *   <li><b>onResponse/onError</b>（OpenAI 回调线程，异步）：经 attributes map 取回 span 引用，写 output/usage，
 *       标状态并 end——不依赖回调线程的 ThreadLocal。</li>
 * </ul>
 */
@Slf4j
public class LangfuseChatModelListener implements ChatModelListener {

    /** attributes map 中 span 引用的 key（同一 map 贯穿三回调，见 ChatModel*Context.attributes()） */
    private static final Object SPAN_KEY = new Object();

    private final Tracer tracer;
    private final MessageSerializer serializer;
    private final LangfuseProperties props;
    private final AgentObservability agentObservability;
    private final String provider;

    public LangfuseChatModelListener(Tracer tracer, MessageSerializer serializer, LangfuseProperties props,
                                     AgentObservability agentObservability, String provider) {
        this.tracer = tracer;
        this.serializer = serializer;
        this.props = props;
        this.agentObservability = agentObservability;
        this.provider = provider;
    }

    @Override
    public void onRequest(ChatModelRequestContext requestContext) {
        ObservableContext obs = ObservableContext.current();
        // 无外层观测上下文（离线 LLM 调用等）且未开启离线 tracing → 静默跳过
        if (obs == null && !props.isTraceOfflineCalls()) {
            log.info("[Langfuse] generation 跳过: 无观测上下文且 traceOfflineCalls=false, provider={}", provider);
            return;
        }
        Span span = tracer.spanBuilder("chat")
                .setSpanKind(SpanKind.CLIENT)
                .setParent(Context.current())
                .startSpan();
        if (obs != null) {
            agentObservability.applyTraceAttrs(span, obs);
        }
        span.setAttribute(LangfuseAttributeKeys.OBSERVATION_TYPE, LangfuseAttributeKeys.TYPE_GENERATION);
        span.setAttribute(LangfuseAttributeKeys.GEN_AI_OPERATION_NAME, "chat");
        span.setAttribute(LangfuseAttributeKeys.GEN_AI_PROVIDER_NAME, provider);

        ChatRequest chatRequest = requestContext.chatRequest();
        if (chatRequest != null) {
            String model = chatRequest.modelName();
            if (model != null) {
                span.setAttribute(LangfuseAttributeKeys.OBSERVATION_MODEL_NAME, model);
                span.setAttribute(LangfuseAttributeKeys.GEN_AI_REQUEST_MODEL, model);
            }
            span.setAttribute(LangfuseAttributeKeys.OBSERVATION_INPUT,
                    serializer.serializeMessages(chatRequest.messages()));
            String params = serializer.modelParameters(
                    chatRequest.temperature(), chatRequest.maxOutputTokens(), chatRequest.topP());
            if (!"{}".equals(params)) {
                span.setAttribute(LangfuseAttributeKeys.OBSERVATION_MODEL_PARAMETERS, params);
            }
            if (chatRequest.temperature() != null) {
                span.setAttribute(LangfuseAttributeKeys.METADATA_TEMPERATURE, chatRequest.temperature());
            }
        }
        Integer stepNumber = ObservableContext.getCurrentStep();
        if (stepNumber != null) {
            span.setAttribute(LangfuseAttributeKeys.METADATA_STEP_NUMBER, stepNumber);
        }
        requestContext.attributes().put(SPAN_KEY, span);
        log.info("[Langfuse] generation span 创建: traceId={}, spanId={}, provider={}, model={}",
                span.getSpanContext().getTraceId(), span.getSpanContext().getSpanId(), provider,
                requestContext.chatRequest() != null ? requestContext.chatRequest().modelName() : "?");
    }

    @Override
    public void onResponse(ChatModelResponseContext responseContext) {
        Span span = spanOf(responseContext.attributes().get(SPAN_KEY));
        if (span == null) {
            return;
        }
        ChatResponse response = responseContext.chatResponse();
        if (response != null) {
            span.setAttribute(LangfuseAttributeKeys.OBSERVATION_OUTPUT, serializer.serializeResponse(response));
            if (response.aiMessage() != null && response.aiMessage().thinking() != null) {
                span.setAttribute(LangfuseAttributeKeys.METADATA_THINKING_TOKENS,
                        response.aiMessage().thinking().length());
            }
            String model = response.modelName();
            if (model != null) {
                span.setAttribute(LangfuseAttributeKeys.GEN_AI_RESPONSE_MODEL, model);
            }
            TokenUsage usage = response.tokenUsage();
            if (usage != null) {
                if (usage.inputTokenCount() != null) {
                    span.setAttribute(LangfuseAttributeKeys.GEN_AI_USAGE_INPUT_TOKENS,
                            usage.inputTokenCount().longValue());
                }
                if (usage.outputTokenCount() != null) {
                    span.setAttribute(LangfuseAttributeKeys.GEN_AI_USAGE_OUTPUT_TOKENS,
                            usage.outputTokenCount().longValue());
                }
                span.setAttribute(LangfuseAttributeKeys.OBSERVATION_USAGE_DETAILS, serializer.usageDetails(usage));
            }
        }
        span.setStatus(StatusCode.OK);
        log.info("[Langfuse] generation span 结束: traceId={}, spanId={}, status=OK",
                span.getSpanContext().getTraceId(), span.getSpanContext().getSpanId());
        span.end();
    }

    @Override
    public void onError(ChatModelErrorContext errorContext) {
        Span span = spanOf(errorContext.attributes().get(SPAN_KEY));
        if (span == null) {
            return;
        }
        span.setStatus(StatusCode.ERROR);
        span.setAttribute(LangfuseAttributeKeys.OBSERVATION_LEVEL, "ERROR");
        Throwable error = errorContext.error();
        if (error != null) {
            span.recordException(error);
            if (error.getMessage() != null) {
                span.setAttribute(LangfuseAttributeKeys.OBSERVATION_STATUS_MESSAGE, error.getMessage());
            }
        }
        log.warn("[Langfuse] generation span 异常结束: traceId={}, spanId={}, error={}",
                span.getSpanContext().getTraceId(), span.getSpanContext().getSpanId(),
                error != null ? error.getMessage() : "未知");
        span.end();
    }

    private Span spanOf(Object value) {
        return value instanceof Span span ? span : null;
    }
}