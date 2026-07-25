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

    /**
     * 工具执行保活/进度心跳：仅 SSE 推送，不入库、不进 recordedSteps、不分配 orderSeq。
     * 兼具"驱动前端动画 + 已 N 秒计时"与"重置中间件空闲超时保活"双重作用。
     */
    public static final String TOOL_PROGRESS = "tool_progress";

    /**
     * 技能激活事件：渐进披露模式下 resolve 成功激活技能时推送，
     * 携带 skill_name(displayName) 与 tool_names，告知前端"技能 X 已激活、关联工具已注入"。
     */
    public static final String SKILL_ACTIVATED = "skill_activated";

    // ---- 工作流相关 step_type（study_plan Agent 工作流）----
    /**
     * 子 Agent 步骤：工作流内部编排的各子 Agent（plan_generator/exam_generator 等），
     * 拆分为开始/结束两个事件配对，前端靠同 agent_id 配对成可折叠面板。
     * 0724 改造：workflow_start/workflow_end 已删（方案 A 合并），workflow 工具改由
     * 外层 tool_call/tool_result 的 stepData(is_workflow=true) 承载结构化数据。
     */
    public static final String SUB_AGENT = "sub_agent";

    // ---- 工作流 phase 标识 ----
    public static final String PHASE_STUDY_PLAN = "study_plan";
    public static final String PHASE_KNOWLEDGE_SEARCH = "knowledge_search";

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

    /**
     * 工具分类：在统一 tool 入口中区分 function_calling / skill / mcp / workflow。
     * 前端据此分类展示不同图标/文案。
     */
    public static final String KEY_TOOL_KIND = "tool_kind";

    /** 心跳累计执行秒数，前端显示"已 N 秒" */
    public static final String KEY_ELAPSED_SECONDS = "elapsed_seconds";

    /** skill_activated 事件携带的技能展示名（取自 SkillMetadata.displayName） */
    public static final String KEY_SKILL_NAME = "skill_name";

    /** skill_activated 事件携带的关联工具名列表 */
    public static final String KEY_TOOL_NAMES = "tool_names";

    /** workflow 工具标记：tool_call/tool_result 的 stepData 中 is_workflow=true */
    public static final String KEY_IS_WORKFLOW = "is_workflow";

    // ---- 工作流 sub_agent 步骤的 step_data keys ----
    public static final String KEY_AGENT_NAME = "agent_name";
    public static final String KEY_AGENT_ROLE = "agent_role";
    public static final String KEY_TRIGGERED = "triggered";
    public static final String KEY_OUTPUT_KEY = "output_key";
    public static final String KEY_SUCCESS = "success";
    public static final String KEY_QUESTION = "question";

    /**
     * sub_agent 事件拆分开始/结束配对的标记：true=开始事件，false=结束事件。
     * 前端靠同 agent_id 的开始/结束两个事件配对成可折叠面板。
     */
    public static final String KEY_IS_START = "is_start";

    // ---- tool_kind 取值 ----
    public static final String TOOL_KIND_FUNCTION = "function_calling";
    public static final String TOOL_KIND_SKILL = "skill";
    public static final String TOOL_KIND_MCP = "mcp";
    public static final String TOOL_KIND_WORKFLOW = "workflow";

    // ---- error_code values ----
    public static final String ERR_LLM_CALL_FAILED = "llm_call_failed";
    public static final String ERR_MAX_STEPS_EXCEEDED = "max_steps_exceeded";
    public static final String ERR_TOOL_TIMEOUT = "tool_timeout";
}
