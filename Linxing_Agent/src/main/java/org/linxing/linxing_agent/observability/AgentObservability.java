package org.linxing.linxing_agent.observability;

import dev.langchain4j.agent.tool.ToolExecutionRequest;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.Scope;
import lombok.extern.slf4j.Slf4j;
import org.linxing.linxing_agent.agent.tool.ToolCallResult;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Agent 观测门面：在既有主对话链路上创建/结束 root span 与 tool span，
 * 并把 trace 级属性冗余注入每个子 span（满足 Langfuse「每 span 传播」要求，见 3.2）。
 * <p>enabled=false 或非主链路调用时返回 no-op 句柄（span 为 OTel no-op），调用方无需判空。
 * 结构定义见 reference/TODOS/langfuse/0816LangfuseObservability.md 3.1/3.2/3.4。
 */
@Component
@Slf4j
public class AgentObservability {

    private static final String TRACE_NAME = "agent-run";
    private static final List<String> DEFAULT_TAGS = List.of("agent", "chat");
    private static final int MAX_INPUT_LENGTH = 4_000;
    /**
     * output 截断阈值。0816 Phase2 改进2 由 4000 上调至 20_000，与 generation（{@link MessageSerializer#MAX_RESPONSE_LENGTH}）对齐，
     * 使子 Agent span 能回放全量 plan/exam 产出。
     */
    private static final int MAX_OUTPUT_LENGTH = 20_000;

    private final Tracer tracer;
    private final LangfuseProperties props;
    private final MessageSerializer serializer;

    public AgentObservability(Tracer tracer, LangfuseProperties props, MessageSerializer serializer) {
        this.tracer = tracer;
        this.props = props;
        this.serializer = serializer;
    }

    /** trace 根句柄：非空；内部 span 可能为 no-op（enabled=false） */
    public static final class TraceHandle {
        private final Span span;
        private final Scope scope;
        private final ObservableContext context;

        TraceHandle(Span span, Scope scope, ObservableContext context) {
            this.span = span;
            this.scope = scope;
            this.context = context;
        }

        public ObservableContext getContext() {
            return context;
        }
    }

    /** tool span 句柄：非空；无观测上下文时 span/context 为 null（no-op） */
    public static final class ToolHandle {
        private final Span span;
        private final ObservableContext context;

        ToolHandle(Span span, ObservableContext context) {
            this.span = span;
            this.context = context;
        }

        public ObservableContext getContext() {
            return context;
        }
    }

    /** retrieval span 句柄：非空；无观测上下文时 span 为 null（no-op） */
    public static final class RetrievalHandle {
        private final Span span;

        RetrievalHandle(Span span) {
            this.span = span;
        }
    }

    /**
     * RAG 检索诊断统计（0816 Phase2 改进3）：SearchServiceImpl 在 search 内部逐步采集，
     * {@link #endRetrieval} 写入 retrieval span 的 metadata.*。
     */
    public record RetrievalStats(
            String vectorStore,
            String similarity,
            String reranker,
            int recallSize,
            int vectorCandidates,
            int bm25Candidates,
            boolean hybrid,
            double threshold,
            int beforeFilter,
            int afterFilter,
            List<Double> scores
    ) {
        public boolean isHit() {
            return afterFilter > 0;
        }
    }

    /** 子 Agent span 句柄：非空；无观测上下文时 span/scope 为 null（no-op） */
    public static final class SubAgentHandle {
        private final Span span;
        private final Scope scope;

        SubAgentHandle(Span span, Scope scope) {
            this.span = span;
            this.scope = scope;
        }
    }

    /**
     * 建 trace 根（ChatServiceImpl 入口）：root span 承载 trace 级全部字段（3.2），
     * 并置为当前上下文，使后续 generation/tool span 自动挂到其下。
     */
    public TraceHandle beginTraceRoot(Integer userId, Integer sessionId, String requestId, String question) {
        Span root = tracer.spanBuilder(TRACE_NAME)
                .setSpanKind(SpanKind.SERVER)
                .startSpan();
        ObservableContext.TraceAttrs attrs = new ObservableContext.TraceAttrs(
                sessionId != null ? String.valueOf(sessionId) : null,
                userId != null ? String.valueOf(userId) : null,
                requestId, question, null);
        ObservableContext ctx = ObservableContext.of(root, attrs);
        Scope scope = ctx.makeCurrent();

        applyTraceAttrs(root, ctx);
        root.setAttribute(LangfuseAttributeKeys.OBSERVATION_TYPE, LangfuseAttributeKeys.TYPE_SPAN);
        if (question != null) {
            root.setAttribute(LangfuseAttributeKeys.OBSERVATION_INPUT, serializer.truncate(question, MAX_INPUT_LENGTH));
        }
        log.info("[Langfuse] trace 根开始: traceId={}, sessionId={}, userId={}, requestId={}",
                root.getSpanContext().getTraceId(), attrs.sessionId, attrs.userId, attrs.requestId);
        return new TraceHandle(root, scope, ctx);
    }

    /**
     * 闭 trace 根（ChatServiceImpl 出口/finally）：写 answer/output 与状态，先恢复上下文再 end span。
     */
    public void endTraceRoot(TraceHandle handle, String answer, Throwable error) {
        if (handle == null) {
            return;
        }
        if (handle.scope != null) {
            handle.scope.close();
        }
        Span span = handle.span;
        if (error != null) {
            span.setStatus(StatusCode.ERROR);
            span.recordException(error);
            span.setAttribute(LangfuseAttributeKeys.OBSERVATION_LEVEL, "ERROR");
            if (error.getMessage() != null) {
                span.setAttribute(LangfuseAttributeKeys.OBSERVATION_STATUS_MESSAGE, error.getMessage());
            }
        } else {
            span.setStatus(StatusCode.OK);
            if (answer != null) {
                span.setAttribute(LangfuseAttributeKeys.OBSERVATION_OUTPUT, serializer.truncate(answer, MAX_OUTPUT_LENGTH));
            }
        }
        log.info("[Langfuse] trace 根结束: traceId={}, status={}, outputLen={}",
                span.getSpanContext().getTraceId(),
                error != null ? "ERROR" : "OK",
                answer != null ? answer.length() : 0);
        span.end();
    }

    /**
     * 建 tool span（主循环每次工具调用，挂当前上下文下）。返回句柄，其 context 交给工具线程 makeCurrent，
     * 使工具内 LLM 调用（子 Agent）的 generation 挂到 tool span 下。
     */
    public ToolHandle beginTool(ToolExecutionRequest req, String toolKind) {
        ObservableContext parent = ObservableContext.current();
        if (parent == null) {
            return new ToolHandle(null, null);
        }
        Span span = tracer.spanBuilder(LangfuseAttributeKeys.TOOL_NAME_PREFIX + req.name())
                .setParent(Context.current())
                .setSpanKind(SpanKind.INTERNAL)
                .startSpan();
        ObservableContext ctx = ObservableContext.childOf(span, parent);
        applyTraceAttrs(span, ctx);
        span.setAttribute(LangfuseAttributeKeys.OBSERVATION_TYPE, LangfuseAttributeKeys.TYPE_SPAN);
        span.setAttribute(LangfuseAttributeKeys.METADATA_KIND, "tool");
        if (toolKind != null) {
            span.setAttribute(LangfuseAttributeKeys.METADATA_TOOL_KIND, toolKind);
        }
        if (req.arguments() != null) {
            span.setAttribute(LangfuseAttributeKeys.OBSERVATION_INPUT, serializer.truncate(req.arguments(), MAX_INPUT_LENGTH));
        }
        log.debug("[Langfuse] tool span 开始: traceId={}, tool={}", span.getSpanContext().getTraceId(), req.name());
        return new ToolHandle(span, ctx);
    }

    /** 闭 tool span：写 output/duration/success，失败标 ERROR */
    public void endTool(ToolHandle handle, ToolCallResult result, long durationMs) {
        if (handle == null || handle.span == null) {
            return;
        }
        Span span = handle.span;
        span.setAttribute(LangfuseAttributeKeys.METADATA_DURATION_MS, durationMs);
        boolean success = result != null && result.isSuccess();
        span.setAttribute(LangfuseAttributeKeys.METADATA_SUCCESS, success);
        if (success) {
            span.setStatus(StatusCode.OK);
            if (result != null && result.getResult() != null) {
                span.setAttribute(LangfuseAttributeKeys.OBSERVATION_OUTPUT, serializer.truncate(result.getResult(), MAX_OUTPUT_LENGTH));
            }
        } else {
            span.setStatus(StatusCode.ERROR);
            span.setAttribute(LangfuseAttributeKeys.OBSERVATION_LEVEL, "ERROR");
            String err = result != null && result.getError() != null ? result.getError() : "tool failed";
            span.setAttribute(LangfuseAttributeKeys.OBSERVATION_STATUS_MESSAGE, serializer.truncate(err, MAX_OUTPUT_LENGTH));
        }
        log.debug("[Langfuse] tool span 结束: traceId={}, success={}, durationMs={}",
                span.getSpanContext().getTraceId(), success, durationMs);
        span.end();
    }

    /**
     * 建 retrieval span（RAG 检索观测，0816 Phase2 改进3）。
     * 覆盖主循环 / 子 Agent / HTTP 三入口：有观测上下文时挂当前 span 下（主循环为 {@code Tool: search_knowledge_base}
     * 的子 span，子 Agent 为 {@code Agent: xxx} 的子 span）；HTTP 直连 service 无观测上下文时返回 no-op，静默跳过。
     */
    public RetrievalHandle beginRetrieval(String toolName, String query, int topK, boolean hybrid) {
        ObservableContext parent = ObservableContext.current();
        if (parent == null) {
            return new RetrievalHandle(null);
        }
        Span span = tracer.spanBuilder(LangfuseAttributeKeys.RETRIEVER_NAME_PREFIX + toolName)
                .setParent(Context.current())
                .setSpanKind(SpanKind.INTERNAL)
                .startSpan();
        applyTraceAttrs(span, parent);
        span.setAttribute(LangfuseAttributeKeys.OBSERVATION_TYPE, LangfuseAttributeKeys.TYPE_SPAN);
        span.setAttribute(LangfuseAttributeKeys.METADATA_KIND, "retrieval");
        Map<String, Object> input = new LinkedHashMap<>();
        input.put("query", query);
        input.put("topK", topK);
        input.put("hybrid", hybrid);
        span.setAttribute(LangfuseAttributeKeys.OBSERVATION_INPUT,
                serializer.truncate(serializer.toJson(input), MAX_INPUT_LENGTH));
        log.debug("[Langfuse] retrieval span 开始: traceId={}, tool={}, topK={}, hybrid={}",
                span.getSpanContext().getTraceId(), toolName, topK, hybrid);
        return new RetrievalHandle(span);
    }

    /**
     * 闭 retrieval span：写结果摘要（chunkId/fileName/titlePath/score，不含 chunkText）与诊断 metadata。
     * 空结果（afterFilter=0）为正常业务结果，不标 ERROR，仅以 {@code metadata.hit=false} 表达。
     */
    public void endRetrieval(RetrievalHandle handle, List<Map<String, Object>> resultSummaries, RetrievalStats stats) {
        if (handle == null || handle.span == null) {
            return;
        }
        Span span = handle.span;
        List<Map<String, Object>> summaries = resultSummaries != null ? resultSummaries : List.of();
        span.setAttribute(LangfuseAttributeKeys.OBSERVATION_OUTPUT,
                serializer.truncate(serializer.toJson(summaries), MAX_OUTPUT_LENGTH));
        if (stats != null) {
            span.setAttribute(LangfuseAttributeKeys.METADATA_VECTOR_STORE, stats.vectorStore());
            span.setAttribute(LangfuseAttributeKeys.METADATA_SIMILARITY, stats.similarity());
            span.setAttribute(LangfuseAttributeKeys.METADATA_RERANKER, stats.reranker());
            span.setAttribute(LangfuseAttributeKeys.METADATA_RECALL_SIZE, stats.recallSize());
            span.setAttribute(LangfuseAttributeKeys.METADATA_VECTOR_CANDIDATES, stats.vectorCandidates());
            span.setAttribute(LangfuseAttributeKeys.METADATA_BM25_CANDIDATES, stats.bm25Candidates());
            span.setAttribute(LangfuseAttributeKeys.METADATA_HYBRID, stats.hybrid());
            span.setAttribute(LangfuseAttributeKeys.METADATA_SCORE_THRESHOLD, stats.threshold());
            span.setAttribute(LangfuseAttributeKeys.METADATA_BEFORE_FILTER, stats.beforeFilter());
            span.setAttribute(LangfuseAttributeKeys.METADATA_AFTER_FILTER, stats.afterFilter());
            span.setAttribute(LangfuseAttributeKeys.METADATA_HIT, stats.isHit());
            if (stats.scores() != null && !stats.scores().isEmpty()) {
                span.setAttribute(LangfuseAttributeKeys.METADATA_SCORES, serializer.toJson(stats.scores()));
            }
        }
        span.setStatus(StatusCode.OK);
        log.debug("[Langfuse] retrieval span 结束: traceId={}, hit={}, afterFilter={}",
                span.getSpanContext().getTraceId(), stats != null && stats.isHit(), stats != null ? stats.afterFilter() : 0);
        span.end();
    }

    /**
     * 检索异常时闭 retrieval span（标 ERROR + status_message），避免 span 泄漏。
     */
    public void endRetrievalError(RetrievalHandle handle, Throwable error) {
        if (handle == null || handle.span == null) {
            return;
        }
        Span span = handle.span;
        span.setStatus(StatusCode.ERROR);
        span.setAttribute(LangfuseAttributeKeys.OBSERVATION_LEVEL, "ERROR");
        if (error != null) {
            span.recordException(error);
            if (error.getMessage() != null) {
                span.setAttribute(LangfuseAttributeKeys.OBSERVATION_STATUS_MESSAGE, error.getMessage());
            }
        }
        log.warn("[Langfuse] retrieval span 异常结束: traceId={}, error={}",
                span.getSpanContext().getTraceId(), error != null ? error.getMessage() : "未知");
        span.end();
    }

    /**
     * 建子 Agent span（工作流内 SubAgentStepListener 钩子，运行在 tool-exec 线程）。
     * 置为当前上下文，使子 Agent 内部 LLM 调用（generation）自动挂到其下。
     * input/output 语义见 3.6；output 用 {@link #endSubAgent} 写。
     */
    public SubAgentHandle beginSubAgent(String agentName, String agentRole, String input) {
        ObservableContext parent = ObservableContext.current();
        if (parent == null) {
            return new SubAgentHandle(null, null);
        }
        Span span = tracer.spanBuilder(LangfuseAttributeKeys.AGENT_NAME_PREFIX + agentName)
                .setParent(Context.current())
                .setSpanKind(SpanKind.INTERNAL)
                .startSpan();
        ObservableContext ctx = ObservableContext.childOf(span, parent);
        applyTraceAttrs(span, ctx);
        span.setAttribute(LangfuseAttributeKeys.OBSERVATION_TYPE, LangfuseAttributeKeys.TYPE_SPAN);
        span.setAttribute(LangfuseAttributeKeys.METADATA_KIND, "agent");
        if (agentRole != null) {
            span.setAttribute(LangfuseAttributeKeys.METADATA_ROLE, agentRole);
        }
        if (input != null) {
            span.setAttribute(LangfuseAttributeKeys.OBSERVATION_INPUT, serializer.truncate(input, MAX_INPUT_LENGTH));
        }
        Scope scope = ctx.makeCurrent();//子 Agent 执行期间当前上下文 = 子 Agent span
        log.debug("[Langfuse] sub-agent span 开始: traceId={}, agent={}", span.getSpanContext().getTraceId(), agentName);
        return new SubAgentHandle(span, scope);
    }

    /** 闭子 Agent span：写 output/状态，先恢复上下文再 end span */
    public void endSubAgent(SubAgentHandle handle, String output, Throwable error) {
        if (handle == null || handle.span == null) {
            return;
        }
        if (handle.scope != null) {
            handle.scope.close();
        }
        Span span = handle.span;
        if (error != null) {
            span.setStatus(StatusCode.ERROR);
            span.recordException(error);
            span.setAttribute(LangfuseAttributeKeys.OBSERVATION_LEVEL, "ERROR");
            if (error.getMessage() != null) {
                span.setAttribute(LangfuseAttributeKeys.OBSERVATION_STATUS_MESSAGE, error.getMessage());
            }
        } else {
            span.setStatus(StatusCode.OK);
            if (output != null) {
                span.setAttribute(LangfuseAttributeKeys.OBSERVATION_OUTPUT, serializer.truncate(output, MAX_OUTPUT_LENGTH));
            }
        }
        log.debug("[Langfuse] sub-agent span 结束: traceId={}, status={}, outputLen={}",
                span.getSpanContext().getTraceId(), error != null ? "ERROR" : "OK", output != null ? output.length() : 0);
        span.end();
    }

    /**
     * 把 trace 级属性冗余注入 span（官方要求传播到每个 span，否则按 user/session/version 过滤缺数据）。
     * 常量部分（name/version/release/environment/tags/public）直接取自配置；请求相关字段取自 ctx.attrs。
     */
    public void applyTraceAttrs(Span span, ObservableContext ctx) {
        span.setAttribute(LangfuseAttributeKeys.TRACE_NAME, TRACE_NAME);
        span.setAttribute(LangfuseAttributeKeys.ENVIRONMENT, props.getEnvironment());
        span.setAttribute(LangfuseAttributeKeys.VERSION, props.getVersion());
        span.setAttribute(LangfuseAttributeKeys.RELEASE, props.getVersion());
        span.setAttribute(LangfuseAttributeKeys.TRACE_PUBLIC, false);
        span.setAttribute(AttributeKey.stringArrayKey(LangfuseAttributeKeys.TRACE_TAGS), DEFAULT_TAGS);

        if (ctx == null) {
            return;
        }
        ObservableContext.TraceAttrs attrs = ctx.getAttrs();
        if (attrs == null) {
            return;
        }
        if (attrs.sessionId != null) {
            span.setAttribute(LangfuseAttributeKeys.SESSION_ID, attrs.sessionId);
        }
        if (attrs.userId != null) {
            span.setAttribute(LangfuseAttributeKeys.USER_ID, attrs.userId);
        }
        if (attrs.requestId != null) {
            span.setAttribute(LangfuseAttributeKeys.TRACE_METADATA_REQUEST_ID, attrs.requestId);
        }
        if (attrs.question != null) {
            span.setAttribute(LangfuseAttributeKeys.TRACE_METADATA_QUESTION, attrs.question);
        }
    }
}
