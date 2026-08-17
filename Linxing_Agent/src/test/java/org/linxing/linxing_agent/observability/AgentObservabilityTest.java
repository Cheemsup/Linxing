package org.linxing.linxing_agent.observability;

import tools.jackson.databind.ObjectMapper;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.linxing.linxing_agent.agent.tool.ToolCallResult;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link AgentObservability} 单测：root + tool span 层级、trace 级属性每 span 传播、成功/失败状态。
 * 用 {@link CollectingSpanExporter} + SimpleSpanProcessor 同步导出，end 后直接断言。
 */
class AgentObservabilityTest {

    private CollectingSpanExporter exporter;
    private AgentObservability agentObservability;

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
        agentObservability = new AgentObservability(tracer, props, serializer);
    }

    @Test
    @DisplayName("root→tool 父子层级正确，trace 级属性传播到每个 span")
    void traceHierarchy() {
        AgentObservability.TraceHandle trace = agentObservability.beginTraceRoot(1, 42, "req-1", "什么是Langfuse?");
        ToolExecutionRequest req = ToolExecutionRequest.builder()
                .id("call_1")
                .name("search_knowledge_base")
                .arguments("{\"query\":\"langfuse\"}")
                .build();
        AgentObservability.ToolHandle tool = agentObservability.beginTool(req, "function");
        agentObservability.endTool(tool, ToolCallResult.success("call_1", "search_knowledge_base", "结果内容"), 123);
        agentObservability.endTraceRoot(trace, "最终回答", null);

        List<SpanData> spans = exporter.getSpans();
        assertEquals(2, spans.size());

        SpanData root = spans.stream().filter(s -> "agent-run".equals(s.getName())).findFirst().orElseThrow();
        SpanData toolSpan = spans.stream().filter(s -> s.getName().startsWith("Tool: ")).findFirst().orElseThrow();

        // 同 trace、父子关系
        assertEquals(root.getTraceId(), toolSpan.getTraceId());
        assertEquals(root.getSpanId(), toolSpan.getParentSpanId(), "tool span 应挂在 root 下");

        // tool 命名约定 + 元数据
        assertEquals("Tool: search_knowledge_base", toolSpan.getName());
        assertEquals("tool", toolSpan.getAttributes().get(AttributeKey.stringKey(LangfuseAttributeKeys.METADATA_KIND)));
        assertEquals("function", toolSpan.getAttributes().get(AttributeKey.stringKey(LangfuseAttributeKeys.METADATA_TOOL_KIND)));
        assertEquals(123L, toolSpan.getAttributes().get(AttributeKey.longKey(LangfuseAttributeKeys.METADATA_DURATION_MS)).longValue());
        assertEquals(Boolean.TRUE, toolSpan.getAttributes().get(AttributeKey.booleanKey(LangfuseAttributeKeys.METADATA_SUCCESS)));

        // trace 级属性传播到 tool span（3.2 官方要求每 span 传播）
        assertEquals("42", toolSpan.getAttributes().get(AttributeKey.stringKey(LangfuseAttributeKeys.SESSION_ID)));
        assertEquals("1", toolSpan.getAttributes().get(AttributeKey.stringKey(LangfuseAttributeKeys.USER_ID)));
        assertEquals("req-1", toolSpan.getAttributes().get(AttributeKey.stringKey(LangfuseAttributeKeys.TRACE_METADATA_REQUEST_ID)));
        assertEquals("什么是Langfuse?", toolSpan.getAttributes().get(AttributeKey.stringKey(LangfuseAttributeKeys.TRACE_METADATA_QUESTION)));
        assertEquals("test", toolSpan.getAttributes().get(AttributeKey.stringKey(LangfuseAttributeKeys.ENVIRONMENT)));
        assertEquals("1.0.0", toolSpan.getAttributes().get(AttributeKey.stringKey(LangfuseAttributeKeys.VERSION)));
        assertEquals(List.of("agent", "chat"),
                toolSpan.getAttributes().get(AttributeKey.stringArrayKey(LangfuseAttributeKeys.TRACE_TAGS)));

        // root 的 input/output
        assertEquals("什么是Langfuse?",
                root.getAttributes().get(AttributeKey.stringKey(LangfuseAttributeKeys.OBSERVATION_INPUT)));
        assertEquals("最终回答",
                root.getAttributes().get(AttributeKey.stringKey(LangfuseAttributeKeys.OBSERVATION_OUTPUT)));
    }

    @Test
    @DisplayName("工具失败 → tool span 标 ERROR + status_message，无观测上下文 → no-op 不产生 span")
    void toolFailureAndNoop() {
        AgentObservability.TraceHandle trace = agentObservability.beginTraceRoot(1, 42, "req-2", "查询");
        ToolExecutionRequest req = ToolExecutionRequest.builder()
                .id("call_2")
                .name("web_search")
                .arguments("{}")
                .build();
        AgentObservability.ToolHandle tool = agentObservability.beginTool(req, "function");
        agentObservability.endTool(tool, ToolCallResult.failure("call_2", "web_search", "超时"), 500);
        agentObservability.endTraceRoot(trace, null, null);

        SpanData toolSpan = exporter.getSpans().stream()
                .filter(s -> s.getName().startsWith("Tool: ")).findFirst().orElseThrow();
        assertEquals(io.opentelemetry.api.trace.StatusCode.ERROR, toolSpan.getStatus().getStatusCode());
        assertEquals("ERROR", toolSpan.getAttributes().get(AttributeKey.stringKey(LangfuseAttributeKeys.OBSERVATION_LEVEL)));
        assertTrue(toolSpan.getAttributes().get(AttributeKey.stringKey(LangfuseAttributeKeys.OBSERVATION_STATUS_MESSAGE)).contains("超时"));

        // 无观测上下文时 beginTool 返回 no-op，endTool 不产生 span
        AgentObservability.ToolHandle noop = agentObservability.beginTool(req, "function");
        agentObservability.endTool(noop, ToolCallResult.success("call_2", "web_search", "x"), 10);
        assertEquals(2, exporter.getSpans().size(), "无观测上下文不应新增 span");
    }

    @Test
    @DisplayName("root→tool→子Agent 三级层级，子Agent 挂 tool 下且携带 trace 属性")
    void subAgentHierarchy() {
        AgentObservability.TraceHandle trace = agentObservability.beginTraceRoot(1, 42, "req-3", "制定学习计划");
        ToolExecutionRequest req = ToolExecutionRequest.builder()
                .id("call_3")
                .name("start_study_plan_workflow")
                .arguments("{\"goal\":\"学Java\"}")
                .build();
        AgentObservability.ToolHandle tool = agentObservability.beginTool(req, "workflow");
        // 模拟工具线程：makeCurrent 恢复 tool 上下文，子 Agent 在其中执行（顺序兄弟，非嵌套）
        try (io.opentelemetry.context.Scope toolScope = tool.getContext().makeCurrent()) {
            AgentObservability.SubAgentHandle plan = agentObservability.beginSubAgent("plan_generator", "plan", "请生成学习计划");
            agentObservability.endSubAgent(plan, "planId=3", null);
            AgentObservability.SubAgentHandle exam = agentObservability.beginSubAgent("exam_generator", "exam", "请生成测验");
            agentObservability.endSubAgent(exam, "examId=5", null);
        }
        agentObservability.endTool(tool, ToolCallResult.success("call_3", "start_study_plan_workflow", "ok"), 5000);
        agentObservability.endTraceRoot(trace, "完成", null);

        List<SpanData> spans = exporter.getSpans();
        assertEquals(4, spans.size());

        SpanData toolSpan = spans.stream().filter(s -> s.getName().startsWith("Tool: ")).findFirst().orElseThrow();
        List<SpanData> agents = spans.stream().filter(s -> s.getName().startsWith("Agent: ")).toList();
        assertEquals(2, agents.size());
        for (SpanData a : agents) {
            assertEquals(toolSpan.getSpanId(), a.getParentSpanId(), "子Agent span 应挂 tool span 下");
            assertEquals(toolSpan.getTraceId(), a.getTraceId());
            assertEquals("agent", a.getAttributes().get(AttributeKey.stringKey(LangfuseAttributeKeys.METADATA_KIND)));
            assertEquals("42", a.getAttributes().get(AttributeKey.stringKey(LangfuseAttributeKeys.SESSION_ID)), "trace 属性应传播到子Agent");
        }
    }

    @Test
    @DisplayName("子Agent 失败 → span 标 ERROR + status_message")
    void subAgentError() {
        AgentObservability.TraceHandle trace = agentObservability.beginTraceRoot(1, 42, "req-4", "制定计划");
        ToolExecutionRequest req = ToolExecutionRequest.builder()
                .id("call_4").name("start_study_plan_workflow").arguments("{}").build();
        AgentObservability.ToolHandle tool = agentObservability.beginTool(req, "workflow");
        try (io.opentelemetry.context.Scope toolScope = tool.getContext().makeCurrent()) {
            AgentObservability.SubAgentHandle agent = agentObservability.beginSubAgent("plan_generator", "plan", "问题");
            agentObservability.endSubAgent(agent, null, new RuntimeException("模型超时"));
        }
        agentObservability.endTool(tool, ToolCallResult.success("call_4", "start_study_plan_workflow", "ok"), 100);
        agentObservability.endTraceRoot(trace, "完成", null);

        SpanData agentSpan = exporter.getSpans().stream()
                .filter(s -> s.getName().startsWith("Agent: ")).findFirst().orElseThrow();
        assertEquals(io.opentelemetry.api.trace.StatusCode.ERROR, agentSpan.getStatus().getStatusCode());
        assertEquals("ERROR", agentSpan.getAttributes().get(AttributeKey.stringKey(LangfuseAttributeKeys.OBSERVATION_LEVEL)));
        assertTrue(agentSpan.getAttributes().get(AttributeKey.stringKey(LangfuseAttributeKeys.OBSERVATION_STATUS_MESSAGE)).contains("模型超时"));
    }

    @Test
    @DisplayName("retrieval span 挂 tool span 下，input/output/metadata 结构化且 output 不含 chunkText")
    void retrievalHierarchyAndMetadata() {
        AgentObservability.TraceHandle trace = agentObservability.beginTraceRoot(1, 42, "req-5", "Java是什么?");
        ToolExecutionRequest req = ToolExecutionRequest.builder()
                .id("call_5").name("search_knowledge_base").arguments("{\"query\":\"Java\"}").build();
        AgentObservability.ToolHandle tool = agentObservability.beginTool(req, "function");
        try (io.opentelemetry.context.Scope toolScope = tool.getContext().makeCurrent()) {
            AgentObservability.RetrievalHandle retrieval = agentObservability.beginRetrieval(
                    "search_knowledge_base", "Java 是什么", 5, true);
            List<Map<String, Object>> summaries = new ArrayList<>();
            Map<String, Object> s1 = new LinkedHashMap<>();
            s1.put("chunkId", 101);
            s1.put("fileName", "Java.md");
            s1.put("titlePath", "编程");
            s1.put("score", 0.9842);
            summaries.add(s1);
            agentObservability.endRetrieval(retrieval, summaries, new AgentObservability.RetrievalStats(
                    "pgvector", "cosine", "ms-marco-MiniLM-L-6-v2", 20, 20, 5, true,
                    0.35, 5, 3, List.of(0.9842, 0.9231, 0.7104)));
        }
        agentObservability.endTool(tool, ToolCallResult.success("call_5", "search_knowledge_base", "ok"), 300);
        agentObservability.endTraceRoot(trace, "回答", null);

        SpanData toolSpan = exporter.getSpans().stream()
                .filter(s -> s.getName().startsWith("Tool: ")).findFirst().orElseThrow();
        SpanData ret = exporter.getSpans().stream()
                .filter(s -> s.getName().startsWith("Retriever: ")).findFirst().orElseThrow();

        // 命名与层级：retrieval 挂 tool span 下
        assertEquals("Retriever: search_knowledge_base", ret.getName());
        assertEquals(toolSpan.getSpanId(), ret.getParentSpanId(), "retrieval 应挂 tool span 下");
        assertEquals("retrieval", ret.getAttributes().get(AttributeKey.stringKey(LangfuseAttributeKeys.METADATA_KIND)));

        // input/output
        String input = ret.getAttributes().get(AttributeKey.stringKey(LangfuseAttributeKeys.OBSERVATION_INPUT));
        assertTrue(input.contains("Java 是什么"));
        assertTrue(input.contains("\"topK\":5"));
        String output = ret.getAttributes().get(AttributeKey.stringKey(LangfuseAttributeKeys.OBSERVATION_OUTPUT));
        assertTrue(output.contains("Java.md"));
        assertTrue(output.contains("\"chunkId\":101"));
        assertFalse(output.contains("chunkText"), "retrieval output 不应含 chunkText");

        // metadata
        assertEquals("pgvector", ret.getAttributes().get(AttributeKey.stringKey(LangfuseAttributeKeys.METADATA_VECTOR_STORE)));
        assertEquals("cosine", ret.getAttributes().get(AttributeKey.stringKey(LangfuseAttributeKeys.METADATA_SIMILARITY)));
        assertEquals("ms-marco-MiniLM-L-6-v2", ret.getAttributes().get(AttributeKey.stringKey(LangfuseAttributeKeys.METADATA_RERANKER)));
        assertEquals(20L, ret.getAttributes().get(AttributeKey.longKey(LangfuseAttributeKeys.METADATA_RECALL_SIZE)).longValue());
        assertEquals(20L, ret.getAttributes().get(AttributeKey.longKey(LangfuseAttributeKeys.METADATA_VECTOR_CANDIDATES)).longValue());
        assertEquals(5L, ret.getAttributes().get(AttributeKey.longKey(LangfuseAttributeKeys.METADATA_BM25_CANDIDATES)).longValue());
        assertEquals(Boolean.TRUE, ret.getAttributes().get(AttributeKey.booleanKey(LangfuseAttributeKeys.METADATA_HYBRID)));
        assertEquals(0.35, ret.getAttributes().get(AttributeKey.doubleKey(LangfuseAttributeKeys.METADATA_SCORE_THRESHOLD)).doubleValue(), 0.0001);
        assertEquals(5L, ret.getAttributes().get(AttributeKey.longKey(LangfuseAttributeKeys.METADATA_BEFORE_FILTER)).longValue());
        assertEquals(3L, ret.getAttributes().get(AttributeKey.longKey(LangfuseAttributeKeys.METADATA_AFTER_FILTER)).longValue());
        assertEquals(Boolean.TRUE, ret.getAttributes().get(AttributeKey.booleanKey(LangfuseAttributeKeys.METADATA_HIT)));
        assertTrue(ret.getAttributes().get(AttributeKey.stringKey(LangfuseAttributeKeys.METADATA_SCORES)).contains("0.9842"));

        // trace 级属性传播
        assertEquals("42", ret.getAttributes().get(AttributeKey.stringKey(LangfuseAttributeKeys.SESSION_ID)));
    }

    @Test
    @DisplayName("空结果 → retrieval span hit=false；无观测上下文 → no-op 不产生 span")
    void retrievalEmptyAndNoop() {
        AgentObservability.TraceHandle trace = agentObservability.beginTraceRoot(1, 42, "req-6", "查询");
        AgentObservability.RetrievalHandle retrieval = agentObservability.beginRetrieval(
                "search_knowledge_base", "不存在的内容", 5, true);
        agentObservability.endRetrieval(retrieval, List.of(), new AgentObservability.RetrievalStats(
                "pgvector", "cosine", "ms-marco-MiniLM-L-6-v2", 20, 3, 0, true, 0.35, 5, 0, List.of()));
        agentObservability.endTraceRoot(trace, "无结果", null);

        SpanData ret = exporter.getSpans().stream()
                .filter(s -> s.getName().startsWith("Retriever: ")).findFirst().orElseThrow();
        assertEquals(Boolean.FALSE, ret.getAttributes().get(AttributeKey.booleanKey(LangfuseAttributeKeys.METADATA_HIT)));
        assertEquals(0L, ret.getAttributes().get(AttributeKey.longKey(LangfuseAttributeKeys.METADATA_AFTER_FILTER)).longValue());

        // 无观测上下文 → beginRetrieval 返回 no-op，endRetrieval/endRetrievalError 均不产生 span
        AgentObservability.RetrievalHandle noop = agentObservability.beginRetrieval("search_knowledge_base", "x", 5, false);
        agentObservability.endRetrieval(noop, List.of(), new AgentObservability.RetrievalStats(
                "pgvector", "cosine", "ms-marco-MiniLM-L-6-v2", 20, 0, 0, false, 0.35, 0, 0, List.of()));
        agentObservability.endRetrievalError(noop, new RuntimeException("不应产生 span"));
        assertEquals(2, exporter.getSpans().size(), "无观测上下文不应新增 span");
    }

    @Test
    @DisplayName("检索异常 → retrieval span 标 ERROR + status_message")
    void retrievalError() {
        AgentObservability.TraceHandle trace = agentObservability.beginTraceRoot(1, 42, "req-7", "查询");
        AgentObservability.RetrievalHandle retrieval = agentObservability.beginRetrieval(
                "search_knowledge_base", "q", 5, false);
        agentObservability.endRetrievalError(retrieval, new RuntimeException("embedding 失败"));
        agentObservability.endTraceRoot(trace, "回答", null);

        SpanData ret = exporter.getSpans().stream()
                .filter(s -> s.getName().startsWith("Retriever: ")).findFirst().orElseThrow();
        assertEquals(io.opentelemetry.api.trace.StatusCode.ERROR, ret.getStatus().getStatusCode());
        assertEquals("ERROR", ret.getAttributes().get(AttributeKey.stringKey(LangfuseAttributeKeys.OBSERVATION_LEVEL)));
        assertTrue(ret.getAttributes().get(AttributeKey.stringKey(LangfuseAttributeKeys.OBSERVATION_STATUS_MESSAGE)).contains("embedding"));
    }
}
