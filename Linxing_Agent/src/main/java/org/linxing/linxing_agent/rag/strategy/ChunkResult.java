package org.linxing.linxing_agent.rag.strategy;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.linxing.linxing_agent.rag.node.DocumentNode;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 分块结果数据类，表示一次分块策略执行后产出的单个文本块，包含层级、类型、标题路径等元信息
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ChunkResult {

    private Integer parentChunkId;

    private Short chunkLevel;

    private String chunkText;

    /**
     * 索引文本（Index Render），用于 Embedding + BM25。
     * Node-Based 架构下由各 Node 的 semanticText 拼接而成（含 VLM/LLM 语义增强结果）；
     * 传统策略构建的 Chunk 为 null，下游回退使用 chunkText。
     */
    private String indexText;

    private String titlePath;

    private String chunkType;

    private String sourceStrategy;

    @Builder.Default
    private Map<String, Object> metadata = new HashMap<>();

    /**
     * Node 序列（Node-Based 架构新增）。
     * 当 Chunk 由 Node 序列构建时，保存组成该 Chunk 的所有 DocumentNode。
     * 后续 Render 阶段基于此生成 chunkText（Display）和向量化文本（Index）。
     * 传统策略构建的 Chunk 该字段为空列表。
     */
    @Builder.Default
    private List<DocumentNode> nodes = new ArrayList<>();
}
