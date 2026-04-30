package org.linxing.linxing_agent.constant;

/**
 * RAG（检索增强生成）系统相关常量定义类
 * 包含系统提示词模板、搜索参数等配置常量
 */
public final class RagParameters {

    public static final String SYSTEM_PROMPT = "你是一个智能知识库助手。请基于以下参考内容回答用户问题。\n规则：\n1. 如果参考内容中没有相关信息，请明确告知用户\"知识库中未找到相关信息\"\n2. 绝对不可编造信息，仅依据提供的参考内容作答\n3. 回答时使用中文\n4. 在回答末尾标注信息来源（文件名和页码）\n\n参考内容：\n{{context}}\n\n用户问题：{{question}}\n";

    public static final short CHUNK_LEVEL_1 = 1;

    public static final short CHUNK_LEVEL_2 = 2;

    public static final String EMBEDDING_MODEL = "bge-small-zh";

    public static final int CHUNK_SIZE = 800;

    public static final int CHUNK_OVERLAP = 200;

    public static final int SEARCH_DEFAULT_TOP_K = 5;

    public static final int SEARCH_RECALL_SIZE = 20;

    /**
     * 目标类型：文档
     */
    public static final String TARGET_TYPE_DOCUMENT = "document";

    /**
     * 查询优化提示词模板 - 用于将用户原始查询转换为更适合向量检索的表述
     * TODO:后续能够找到快速的模型则可以考虑启用
     */
    @Deprecated
    public static final String QUERY_REWRITE_PROMPT = "你是一个查询优化专家。请将用户的自然语言问题改写为更适合语义检索的标准化查询语句。\n\n要求：\n1. 保持原意不变，使表述更精确、完整\n2. 补充隐含的关键词和上下文信息\n3. 使用专业术语替代口语化表达\n4. 输出仅包含优化后的查询语句，不要添加任何解释\n\n用户原始问题：{{query}}\n\n优化后的查询：";
}
