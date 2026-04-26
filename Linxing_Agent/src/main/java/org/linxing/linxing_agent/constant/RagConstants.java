package org.linxing.linxing_agent.constant;

/**
 * RAG（检索增强生成）系统相关常量定义类
 * 包含系统提示词模板、搜索参数等配置常量
 */
public final class RagConstants {

    public static final String SYSTEM_PROMPT = "你是一个智能知识库助手。请基于以下参考内容回答用户问题。\n规则：\n1. 如果参考内容中没有相关信息，请明确告知用户\"知识库中未找到相关信息\"\n2. 绝对不可编造信息，仅依据提供的参考内容作答\n3. 回答时使用中文\n4. 在回答末尾标注信息来源（文件名和页码）\n\n参考内容：\n{{context}}\n\n用户问题：{{question}}\n";

    public static final String ACTION_TYPE_UPLOAD = "upload";

    public static final String ACTION_TYPE_QUERY = "query";

    public static final String ACTION_TYPE_DELETE = "delete";

    /**
     * 目标类型：文档
     */
    public static final String TARGET_TYPE_DOCUMENT = "document";

    /**
     * 查询优化提示词模板 - 用于将用户原始查询转换为更适合向量检索的表述
     */
    public static final String QUERY_REWRITE_PROMPT = "你是一个查询优化专家。请将用户的自然语言问题改写为更适合语义检索的标准化查询语句。\n\n要求：\n1. 保持原意不变，使表述更精确、完整\n2. 补充隐含的关键词和上下文信息\n3. 使用专业术语替代口语化表达\n4. 输出仅包含优化后的查询语句，不要添加任何解释\n\n用户原始问题：{{query}}\n\n优化后的查询：";

    /**
     * Cross-Encoder重排序提示词模板 - 用于对检索结果进行精细相关性评分
     */
    public static final String RERANK_PROMPT = "你是一个文本相关性评估专家。请评估以下查询与文本片段的相关性程度。\n\n查询内容：{{query}}\n\n待评估文本片段：{{chunk}}\n\n请根据上述内容和查询的语义相关性，给出一个0到1之间的相关性评分（保留2位小数）。\n其中0表示完全不相关，1表示高度相关。\n\n仅输出数字评分值，不要输出其他内容。\n\n相关性评分：";
}
