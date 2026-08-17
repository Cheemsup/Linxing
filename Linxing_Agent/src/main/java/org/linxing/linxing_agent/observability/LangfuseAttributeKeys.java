package org.linxing.linxing_agent.observability;

/**
 * Langfuse v4 OTLP 属性名常量，收敛 0816 改造涉及的全部 span 属性键。
 * <p>字段结构依据参考文档第三节（trace 级 / observation 级 / gen_ai 级 + 命名约定），
 * 官方字段来源：langfuse-docs opentelemetry/index.mdx + example_data_migration cookbook。
 * <p>命名约定：tool / 子 Agent span 一律写 {@code type=span}（官方写端仅识别 span/generation/event），
 * 语义靠「{@code Tool: xxx} / {@code Agent: xxx}」前缀 + {@code metadata.kind} 表达。
 */
public final class LangfuseAttributeKeys {

    private LangfuseAttributeKeys() {
    }

    // ==================== Trace 级（root span 创建时写入，各子 span 冗余传播） ====================

    /** trace 名（根 span 名兜底） */
    public static final String TRACE_NAME = "langfuse.trace.name";
    public static final String SESSION_ID = "langfuse.session.id";
    public static final String USER_ID = "langfuse.user.id";
    public static final String TRACE_TAGS = "langfuse.trace.tags";
    public static final String TRACE_METADATA_REQUEST_ID = "langfuse.trace.metadata.request_id";
    public static final String TRACE_METADATA_QUESTION = "langfuse.trace.metadata.question";
    public static final String VERSION = "langfuse.version";
    public static final String RELEASE = "langfuse.release";
    public static final String ENVIRONMENT = "langfuse.environment";
    public static final String TRACE_PUBLIC = "langfuse.trace.public";

    // ==================== Observation 级 ====================

    public static final String OBSERVATION_TYPE = "langfuse.observation.type";
    public static final String OBSERVATION_INPUT = "langfuse.observation.input";
    public static final String OBSERVATION_OUTPUT = "langfuse.observation.output";
    public static final String OBSERVATION_MODEL_NAME = "langfuse.observation.model.name";
    public static final String OBSERVATION_MODEL_PARAMETERS = "langfuse.observation.model.parameters";
    public static final String OBSERVATION_USAGE_DETAILS = "langfuse.observation.usage_details";
    public static final String OBSERVATION_COST_DETAILS = "langfuse.observation.cost_details";
    /** 首 token 时刻 ISO8601（流式场景可选） */
    public static final String OBSERVATION_COMPLETION_START_TIME = "langfuse.observation.completion_start_time";
    /** 失败时写 ERROR（官方通用字段） */
    public static final String OBSERVATION_LEVEL = "langfuse.observation.level";
    public static final String OBSERVATION_STATUS_MESSAGE = "langfuse.observation.status_message";

    // ==================== Observation metadata.* ====================

    public static final String METADATA_STEP_NUMBER = "langfuse.observation.metadata.step_number";
    public static final String METADATA_THINKING_TOKENS = "langfuse.observation.metadata.thinking_tokens";
    public static final String METADATA_TEMPERATURE = "langfuse.observation.metadata.temperature";
    /** 语义标记：tool / agent（弥补 type=span 的粒度损失） */
    public static final String METADATA_KIND = "langfuse.observation.metadata.kind";
    public static final String METADATA_TOOL_KIND = "langfuse.observation.metadata.tool_kind";
    public static final String METADATA_SUCCESS = "langfuse.observation.metadata.success";
    public static final String METADATA_DURATION_MS = "langfuse.observation.metadata.duration_ms";
    public static final String METADATA_ROLE = "langfuse.observation.metadata.role";
    public static final String METADATA_TRIGGERED = "langfuse.observation.metadata.triggered";

    // ==================== Retrieval（RAG 检索观测，0816 Phase2 改进3 metadata.*） ====================

    public static final String METADATA_VECTOR_STORE = "langfuse.observation.metadata.vector_store";
    public static final String METADATA_SIMILARITY = "langfuse.observation.metadata.similarity";
    public static final String METADATA_RECALL_SIZE = "langfuse.observation.metadata.recall_size";
    public static final String METADATA_VECTOR_CANDIDATES = "langfuse.observation.metadata.vector_candidates";
    public static final String METADATA_BM25_CANDIDATES = "langfuse.observation.metadata.bm25_candidates";
    public static final String METADATA_HYBRID = "langfuse.observation.metadata.hybrid";
    public static final String METADATA_RERANKER = "langfuse.observation.metadata.reranker";
    public static final String METADATA_SCORE_THRESHOLD = "langfuse.observation.metadata.score_threshold";
    public static final String METADATA_BEFORE_FILTER = "langfuse.observation.metadata.before_filter";
    public static final String METADATA_AFTER_FILTER = "langfuse.observation.metadata.after_filter";
    public static final String METADATA_HIT = "langfuse.observation.metadata.hit";
    /** 前若干归一化分数（JSON 数组字符串，仅非空时写） */
    public static final String METADATA_SCORES = "langfuse.observation.metadata.scores";

    // ==================== Gen-AI 语义约定（generation 双保险） ====================

    public static final String GEN_AI_OPERATION_NAME = "gen_ai.operation.name";
    public static final String GEN_AI_PROVIDER_NAME = "gen_ai.provider.name";
    public static final String GEN_AI_REQUEST_MODEL = "gen_ai.request.model";
    public static final String GEN_AI_RESPONSE_MODEL = "gen_ai.response.model";
    public static final String GEN_AI_USAGE_INPUT_TOKENS = "gen_ai.usage.input_tokens";
    public static final String GEN_AI_USAGE_OUTPUT_TOKENS = "gen_ai.usage.output_tokens";

    // ==================== observation.type 取值（官方写端仅 span/generation/event） ====================

    public static final String TYPE_SPAN = "span";
    public static final String TYPE_GENERATION = "generation";
    public static final String TYPE_EVENT = "event";

    // ==================== 命名约定前缀（官方 UI 按名称渲染工具/Agent 形态） ====================

    public static final String TOOL_NAME_PREFIX = "Tool: ";
    public static final String AGENT_NAME_PREFIX = "Agent: ";
    public static final String RETRIEVER_NAME_PREFIX = "Retriever: ";
}
