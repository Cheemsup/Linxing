package org.linxing.linxing_agent.rag.constant;

/**
 * RAG（检索增强生成）系统相关常量定义类
 * 包含系统提示词模板、搜索参数等配置常量
 */
public final class RagParameters {

    public static final String SYSTEM_PROMPT = "你是一个智能知识库助手。请基于以下参考内容回答用户问题。\n规则：\n1. 如果参考内容中没有相关信息，请明确告知用户\"知识库中未找到相关信息\"\n2. 绝对不可编造信息，仅依据提供的参考内容作答\n3. 回答时使用中文\n4. 在回答末尾标注信息来源（文件名和页码）\n\n{{history}}参考内容：\n{{context}}\n\n用户问题：{{question}}\n";

    public static final short CHUNK_LEVEL_1 = 1;

    public static final short CHUNK_LEVEL_2 = 2;

    public static final String EMBEDDING_MODEL = "bge-small-zh";

    public static final int CHUNK_SIZE = 800;

    public static final int CHUNK_OVERLAP = 200;

    /** 最小 chunk 大小：Level2 小块文本长度低于此值时，参与相邻同源小块合并。设为 0 关闭合并 */
    public static final int MIN_CHUNK_SIZE = 200;

    /** 标题区块拆分阈值：超长标题区块按句子拆分时的字符上限（如 1000）。仅对 > 此阈值的区块做拆分，最佳大小还需要经过测试来不断修正 */
    public static final int CHUNK_THRESHOLD = 600;

    /**
     * 单个 Chunk 进入 embedding 的最大 token 数上限。
     * <p>换算关系（用户约定）：token 计数 ≈ 1.5 × 中文字符数，故 450 token ≈ 300 中文字符。
     * <p>NodeBasedChunkBuilder 装箱以此为上限，超长内容在切块阶段即被约束（而非在 embedding
     * 阶段生硬截断）；Python 侧各解析器同步将 text Node 控制在 {@code MAX_NODE_CHARS≈300} 字符内。
     */
    public static final int MAX_EMBEDDING_TOKENS = 450;

    public static final int SEARCH_DEFAULT_TOP_K = 5;

    public static final int SEARCH_RECALL_SIZE = 20;

    /**
     * Rerank 重排序分数的相关性阈值。
     * <p>硅基流动 Rerank API（BAAI/bge-reranker-v2-m3）返回的 {@code relevance_score} 已归一化到 [0,1]，
     * 低于此阈值的结果视为不相关并舍弃（即使这可能导致 RAG 检索为空）。
     * <p>0.35 为经验保守分界，后续应根据真实语料校准调整（可通过 rag.search.score-threshold 配置覆盖）。
     */
    public static final double SCORE_THRESHOLD = 0.35;

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
