package org.linxing.linxing_agent.rag.entity;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

/**
 * 文档分块实体类
 * 支持分层存储：Level 1（大粒度结构块）和 Level 2（小粒度检索块）
 *
 * 文本双轨：
 * - chunkText（Display Render）：用于前端展示，保留原文形态（图片/代码/表格为占位符）
 * - indexText（Index Render）：用于 Embedding + BM25，语义增强文本；缺失时回退 chunkText
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Chunk {

    private Integer id;

    private Integer userId;

    private Integer documentId;

    private String chunkText;

    /**
     * 索引文本（Index Render）：用于 Embedding 向量化与 BM25 全文检索的语义增强文本。
     *
     * Node-Based 架构下由各 Node 的 semanticText 拼接而成，包含 VLM 生成的图片描述、
     * LLM 生成的代码解释/表格总结等语义内容；传统策略构建的 Chunk 该字段为 null。
     *
     * 非持久化字段：仅用于入库时构建 embedding 与 ts_content，未映射到 chunks 表列；
     * 下游（EmbeddingPersist/FullTextIndexer）缺失时回退使用 chunkText（Display Render）。
     */
    private String indexText;

    private Integer parentChunkId;

    private Short chunkLevel;

    private String chunkType;

    private String titlePath;

    private String contextPrefix;

    private String sourceStrategy;

    private Boolean isSearchable;

    private String tsContent;

    private Integer sortOrder;

    /**
     * Node 元信息（JSONB 数组），记录 Chunk 内所有 Node 的类型、位置、语义描述等。
     * Node-Based 架构下，前端通过此字段还原图片/代码/表格的原文形态。
     * 仅当 Chunk 由 Node 序列构建时填充；传统策略构建的 Chunk 为空数组。
     */
    private List<Map<String, Object>> nodeMetadata;

    /**
     * 组成该 Chunk 的 Node 序列。
     * 仅用于在责任链中生成 indexText/nodeMetadata，不映射到 chunks 表列；入库后不再保留。
     */
    private List<org.linxing.linxing_agent.rag.node.DocumentNode> nodes;

    private OffsetDateTime createdAt;
}
