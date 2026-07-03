package org.linxing.linxing_agent.common.constant;

public final class LlmType {

    public static final String CHAT_MODEL = "deepseek";
    
    public static final String SEMANTIC_CHUNK_MODEL = "glm";
    
    public static final String CONTEXT_ENRICH_MODEL = "deepseek";//补全短chunk上下文
    
    public static final String QUERY_REWRITE = "minimax";
    
    public static final String SUMMARY_MODEL = "deepseek";

    // 用于理解图片、补充 Node 语义
    public static final String VISION_MODEL = "other1";

    
    public static final String CODE_ENHANCE_MODEL = "deepseek"; // LLM 代码解释
    public static final String TABLE_ENHANCE_MODEL = "deepseek"; // LLM 表格总结


    private LlmType() {
    }
}
