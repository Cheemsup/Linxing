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

    // ---- 工作流相关 step_type（study_plan Agent 工作流）----
    public static final String WORKFLOW_START = "workflow_start";
    public static final String SUB_AGENT = "sub_agent";
    public static final String WORKFLOW_END = "workflow_end";

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

    // ---- 工作流 sub_agent 步骤的 step_data keys ----
    public static final String KEY_AGENT_NAME = "agent_name";
    public static final String KEY_AGENT_ROLE = "agent_role";
    public static final String KEY_TRIGGERED = "triggered";
    public static final String KEY_OUTPUT_KEY = "output_key";
    public static final String KEY_SUCCESS = "success";
    public static final String KEY_QUESTION = "question";

    // ---- error_code values ----
    public static final String ERR_LLM_CALL_FAILED = "llm_call_failed";
    public static final String ERR_MAX_STEPS_EXCEEDED = "max_steps_exceeded";
}
