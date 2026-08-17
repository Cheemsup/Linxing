package org.linxing.linxing_agent.agent.core;

import dev.langchain4j.agentic.observability.AgentListener;
import dev.langchain4j.agentic.observability.AgentRequest;
import dev.langchain4j.agentic.observability.AgentResponse;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.linxing.linxing_agent.agent.subagent.SubAgentContext;
import org.linxing.linxing_agent.observability.AgentObservability;
import org.linxing.linxing_agent.observability.CollectingSpanExporter;
import org.linxing.linxing_agent.observability.LangfuseAttributeKeys;
import org.linxing.linxing_agent.observability.LangfuseProperties;
import org.linxing.linxing_agent.observability.MessageSerializer;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import tools.jackson.databind.ObjectMapper;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * {@link SubAgentStepListener} 单测：子 Agent span 输出取值优先级。
 * <p>0816 Phase2 改进2：关闭子 Agent span 时优先读 {@link SubAgentContext#ATTR_OBSERVATION_OUTPUT}
 * （Save 工具组装的全量 JSON），回退 {@code AgentResponse.output()}。
 * 用 {@link CollectingSpanExporter} + SimpleSpanProcessor 同步导出，end 后直接断言 span 属性。
 */
@ExtendWith(MockitoExtension.class)
class SubAgentStepListenerTest {

    private CollectingSpanExporter exporter;
    private AgentObservability agentObservability;

    @Mock
    private StepRecorder recorder;

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
        agentObservability = new AgentObservability(tracer, props, new MessageSerializer(new ObjectMapper()));
    }

    @Test
    @DisplayName("SubAgentContext 存在观测输出 → 子 Agent span output 取全量 JSON，而非 container_id")
    void afterInvocation_shouldPreferObservationOutput() {
        SubAgentContext.bind(42, 10086);
        try {
            String fullPlanJson = "{\"title\":\"Rust 学习计划\",\"goal\":\"掌握基础\","
                    + "\"phases\":[{\"title\":\"第1阶段\"},{\"title\":\"第2阶段\"}]}";
            SubAgentContext.current().setAttribute(SubAgentContext.ATTR_OBSERVATION_OUTPUT, fullPlanJson);

            AgentListener listener = newListener();
            AgentRequest request = mock(AgentRequest.class);
            when(request.inputs()).thenReturn(Map.of("goal", "学Java"));
            AgentResponse response = mock(AgentResponse.class);
            // 不设 output() 桩：验证观测属性优先时根本不走回退路径

            AgentObservability.TraceHandle trace = agentObservability.beginTraceRoot(1, 42, "req-x", "制定计划");
            listener.beforeAgentInvocation(request);
            listener.afterAgentInvocation(response);
            agentObservability.endTraceRoot(trace, "完成", null);

            SpanData agentSpan = agentSpan();
            String output = agentSpan.getAttributes().get(
                    AttributeKey.stringKey(LangfuseAttributeKeys.OBSERVATION_OUTPUT));
            assertThat(output).contains("Rust 学习计划").contains("\"phases\"").contains("第1阶段");
            assertThat(output).doesNotContain("study_plan_abc123");
        } finally {
            SubAgentContext.clear();
        }
    }

    @Test
    @DisplayName("SubAgentContext 无观测输出 → 回退 AgentResponse.output()")
    void afterInvocation_shouldFallbackToAgentResponseOutput() {
        SubAgentContext.bind(42, 10086);
        try {
            AgentListener listener = newListener();
            AgentRequest request = mock(AgentRequest.class);
            when(request.inputs()).thenReturn(Map.of("goal", "学Java"));
            AgentResponse response = mock(AgentResponse.class);
            when(response.output()).thenReturn("study_plan_abc123");

            AgentObservability.TraceHandle trace = agentObservability.beginTraceRoot(1, 42, "req-y", "制定计划");
            listener.beforeAgentInvocation(request);
            listener.afterAgentInvocation(response);
            agentObservability.endTraceRoot(trace, "完成", null);

            SpanData agentSpan = agentSpan();
            assertThat(agentSpan.getAttributes().get(
                    AttributeKey.stringKey(LangfuseAttributeKeys.OBSERVATION_OUTPUT)))
                    .isEqualTo("study_plan_abc123");
        } finally {
            SubAgentContext.clear();
        }
    }

    @Test
    @DisplayName("未绑定 SubAgentContext 时子 Agent span output 无观测输出，走回退逻辑")
    void afterInvocation_withoutSubAgentContext() {
        AgentListener listener = newListener();
        AgentRequest request = mock(AgentRequest.class);
        when(request.inputs()).thenReturn(Map.of());
        AgentResponse response = mock(AgentResponse.class);
        when(response.output()).thenReturn("container_id_only");

        AgentObservability.TraceHandle trace = agentObservability.beginTraceRoot(1, 42, "req-z", "制定计划");
        listener.beforeAgentInvocation(request);
        listener.afterAgentInvocation(response);
        agentObservability.endTraceRoot(trace, "完成", null);

        SpanData agentSpan = agentSpan();
        assertThat(agentSpan.getAttributes().get(
                AttributeKey.stringKey(LangfuseAttributeKeys.OBSERVATION_OUTPUT)))
                .isEqualTo("container_id_only");
    }

    private AgentListener newListener() {
        return SubAgentStepListener.create("plan_generator", "plan",
                "生成学习计划", "plan_container_id", recorder,
                AgentStepTypes.PHASE_STUDY_PLAN, agentObservability);
    }

    private SpanData agentSpan() {
        return exporter.getSpans().stream()
                .filter(s -> s.getName().startsWith("Agent: "))
                .findFirst()
                .orElseThrow(() -> new AssertionError("未找到子 Agent span"));
    }
}
