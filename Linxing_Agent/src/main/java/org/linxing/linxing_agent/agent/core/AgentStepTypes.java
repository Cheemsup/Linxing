package org.linxing.linxing_agent.agent.core;

/**
 * Agent步骤类型常量，统一 DB step_type 与 SSE eventType 的词汇表
 */
public final class AgentStepTypes {

    private AgentStepTypes() {}

    // ---- step_type / eventType ----
    public static final String THINKING = "thinking";
    public static final String TOOL_CALL = "tool_call";
    public static final String TOOL_RESULT = "tool_result";
    public static final String FINAL = "final";
    public static final String ERROR = "error";
    public static final String CACHE_HIT = "cache_hit";

    // ---- phase ----
    public static final String PHASE_THINKING = "thinking";
    public static final String PHASE_ANSWER = "answer";
    public static final String PHASE_CACHE = "cache";

    // ---- step_data keys ----
    public static final String KEY_TOOL_CALL_ID = "tool_call_id";
    public static final String KEY_TOOL_NAME = "tool_name";
    public static final String KEY_IS_SUCCESS = "is_success";
    public static final String KEY_ERROR_CODE = "error_code";
    public static final String KEY_STEP_COUNT = "step_count";

    // ---- error_code values ----
    public static final String ERR_LLM_CALL_FAILED = "llm_call_failed";
    public static final String ERR_MAX_STEPS_EXCEEDED = "max_steps_exceeded";
}
