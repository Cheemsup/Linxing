package org.linxing.linxing_agent.observability;

import tools.jackson.databind.ObjectMapper;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.ModelProvider;
import dev.langchain4j.model.chat.listener.ChatModelErrorContext;
import dev.langchain4j.model.chat.listener.ChatModelRequestContext;
import dev.langchain4j.model.chat.listener.ChatModelResponseContext;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.output.TokenUsage;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Scope;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link LangfuseChatModelListener} 单测：generation span 的 gen_ai/input/output/usage 属性、
 * 挂载到当前上下文、onError 标 ERROR。attributes map 跨 onRequest/onResponse 取 span 的机制也在此验证。
 */
class LangfuseChatModelListenerTest {

    private CollectingSpanExporter exporter;
    private LangfuseChatModelListener listener;

    @BeforeEach
    void setUp() {
        exporter = new CollectingSpanExporter();
        SdkTracerProvider provider = SdkTracerProvider.builder()
                .addSpanProcessor(SimpleSpanProcessor.create(exporter))
                .build();
        Tracer tracer = provider.get("test");

        LangfuseProperties props = new LangfuseProperties();
        props.setEnabled(true);
        props.setEnvironment("test");
        props.setVersion("1.0.0");
        MessageSerializer serializer = new MessageSerializer(new ObjectMapper());
        AgentObservability agentObservability = new AgentObservability(tracer, props, serializer);
        listener = new LangfuseChatModelListener(tracer, serializer, props, agentObservability, "deepseek");
    }

    @Test
    @DisplayName("onRequest→onResponse：generation span 属性齐全且挂当前上下文下")
    void generationSpan() {
        Span root = tracer().spanBuilder("agent-run").startSpan();
        ObservableContext rootCtx = ObservableContext.of(root,
                new ObservableContext.TraceAttrs("s1", "u1", "req-1", "你好", null));
        try (Scope rootScope = rootCtx.makeCurrent()) {
            ObservableContext.setCurrentStep(2);
            try {
                Map<Object, Object> attrs = new HashMap<>();
                ChatRequest chatRequest = ChatRequest.builder()
                        .messages(List.of(UserMessage.from("你好")))
                        .modelName("deepseek-chat")
                        .temperature(0.7)
                        .build();
                listener.onRequest(new ChatModelRequestContext(chatRequest, ModelProvider.OTHER, attrs));

                AiMessage ai = AiMessage.builder().text("你好！有什么可以帮你？").thinking("先理解问题").build();
                ChatResponse response = ChatResponse.builder()
                        .aiMessage(ai)
                        .modelName("deepseek-chat")
                        .tokenUsage(new TokenUsage(10, 5))
                        .build();
                listener.onResponse(new ChatModelResponseContext(response, chatRequest, ModelProvider.OTHER, attrs));
            } finally {
                ObservableContext.clearCurrentStep();
            }
        }

        SpanData span = exporter.getSpans().stream()
                .filter(s -> "chat".equals(s.getName())).findFirst().orElseThrow();
        Map<String, Object> a = attributes(span);

        assertEquals(LangfuseAttributeKeys.TYPE_GENERATION, a.get(LangfuseAttributeKeys.OBSERVATION_TYPE));
        assertEquals("chat", a.get(LangfuseAttributeKeys.GEN_AI_OPERATION_NAME));
        assertEquals("deepseek", a.get(LangfuseAttributeKeys.GEN_AI_PROVIDER_NAME));
        assertEquals("deepseek-chat", a.get(LangfuseAttributeKeys.GEN_AI_REQUEST_MODEL));
        assertEquals("deepseek-chat", a.get(LangfuseAttributeKeys.GEN_AI_RESPONSE_MODEL));
        assertEquals(10L, a.get(LangfuseAttributeKeys.GEN_AI_USAGE_INPUT_TOKENS));
        assertEquals(5L, a.get(LangfuseAttributeKeys.GEN_AI_USAGE_OUTPUT_TOKENS));
        assertEquals(2L, a.get(LangfuseAttributeKeys.METADATA_STEP_NUMBER));
        // trace 级属性传播（applyTraceAttrs）
        assertEquals("s1", a.get(LangfuseAttributeKeys.SESSION_ID));
        assertEquals("u1", a.get(LangfuseAttributeKeys.USER_ID));
        // input/output
        assertTrue(String.valueOf(a.get(LangfuseAttributeKeys.OBSERVATION_INPUT)).contains("你好"));
        assertTrue(String.valueOf(a.get(LangfuseAttributeKeys.OBSERVATION_OUTPUT)).contains("你好！"));
        // 父子：generation 挂 root 下
        assertEquals(root.getSpanContext().getSpanId(), span.getParentSpanId());
        assertEquals(root.getSpanContext().getTraceId(), span.getTraceId());
    }

    @Test
    @DisplayName("onError：generation span 标 ERROR + status_message + exception 记录")
    void generationError() {
        Span root = tracer().spanBuilder("agent-run").startSpan();
        ObservableContext rootCtx = ObservableContext.of(root,
                new ObservableContext.TraceAttrs("s1", "u1", "req-1", null, null));
        try (Scope ignored = rootCtx.makeCurrent()) {
            Map<Object, Object> attrs = new HashMap<>();
            ChatRequest chatRequest = ChatRequest.builder()
                    .messages(List.of(UserMessage.from("x")))
                    .build();
            listener.onRequest(new ChatModelRequestContext(chatRequest, ModelProvider.OTHER, attrs));
            listener.onError(new ChatModelErrorContext(
                    new RuntimeException("上游 429"), chatRequest, ModelProvider.OTHER, attrs));
        }
        SpanData span = exporter.getSpans().get(0);
        assertEquals(StatusCode.ERROR, span.getStatus().getStatusCode());
        assertEquals("ERROR", attributes(span).get(LangfuseAttributeKeys.OBSERVATION_LEVEL));
        assertTrue(String.valueOf(attributes(span).get(LangfuseAttributeKeys.OBSERVATION_STATUS_MESSAGE)).contains("429"));
    }

    @Test
    @DisplayName("无观测上下文且未开离线 tracing → onRequest 静默跳过，不产生 span")
    void skipWithoutContext() {
        Map<Object, Object> attrs = new HashMap<>();
        ChatRequest chatRequest = ChatRequest.builder().messages(List.of(UserMessage.from("x"))).build();
        listener.onRequest(new ChatModelRequestContext(chatRequest, ModelProvider.OTHER, attrs));
        assertTrue(exporter.getSpans().isEmpty(), "离线调用不应产生 span");
    }

    private Tracer tracer() {
        // 复用 setUp 里构建的 provider——为避免多 provider 实例，这里从 exporter 归属的 provider 取不到，
        // 直接再取同 scope 名称（noop 亦可，root 仅作父引用）
        return SdkTracerProvider.builder().addSpanProcessor(SimpleSpanProcessor.create(exporter)).build().get("test");
    }

    /** 把 SpanData 属性转成易断言的 Map（统一读成 Object） */
    private Map<String, Object> attributes(SpanData span) {
        Map<String, Object> map = new HashMap<>();
        span.getAttributes().forEach((key, value) -> map.put(key.getKey(), value));
        return map;
    }
}
