package org.linxing.linxing_agent.common.constant;

/**
 * 各功能所使用的大模型 provider 常量。
 */
public final class LlmType {

    // 对话聊天（流式主对话）
    public static final String CHAT_MODEL = "deepseek";

    // 学习计划编排（子智能体工作流对话）
    public static final String STUDY_PLAN_MODEL = "deepseek";

    // 语义分块（文档全文送入 LLM 做语义切分）
    public static final String SEMANTIC_CHUNK_MODEL = "deepseek";

    // 补全短 chunk 上下文（1-2 句背景/主题描述）
    public static final String CONTEXT_ENRICH_MODEL = "deepseek";

    // Agent 记忆摘要
    public static final String SUMMARY_MODEL = "deepseek";

    // 会话标题生成（根据首条对话生成简短中文标题）
    public static final String TITLE_GENERATION_MODEL = "deepseek";

    // 用于理解图片、补充 Node 语义（VLM 多模态）
    public static final String VISION_MODEL = "other1";

    // LLM 代码解释（CodeNode 增强）
    public static final String CODE_ENHANCE_MODEL = "deepseek";

    // LLM 表格总结（TableNode 增强）
    public static final String TABLE_ENHANCE_MODEL = "deepseek";

    // Query 改写（标准化用户自然语言问题以利于检索）
    public static final String QUERY_REWRITE = "deepseek";

    // Memory Worker（长期记忆异步判断/改写，非流式轻量调用）
    public static final String MEMORY_WORKER_MODEL = "deepseek";

    private LlmType() {
    }
}
