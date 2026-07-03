package org.linxing.linxing_agent.rag.node;

import lombok.extern.slf4j.Slf4j;
import org.linxing.linxing_agent.rag.constant.RagParameters;
import org.linxing.linxing_agent.rag.entity.ChunkResult;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 基于 Node 序列的 Chunk 构建器，得到最终的经由了Node组合的chunk列表。
 *
 * 核心原则：
 * - Node 永不可拆分：图片、代码、表格作为原子单位
 * - Chunk 是 Node 的组合
 * - 按 semanticText 的 Token 估算累加，达到阈值切出 Chunk
 *
 * 父子装配（阶段三）：Python 侧对超长 section/段落/方法做二次切分时，拆出的子 Node 标 parentId 指向同源
 * Level1 父 Node 的 id。本构建器按 parentId 聚合：
 * - 有子 Node 的父 Node → 镜像为 Level1 父块（不参与检索，isSearchable=false）+ Level2 子块（子 Node 装箱）
 * - 无 parentId 的普通 Node → 走原有 token 装箱，产出 Level2 块
 * parentChunkId 引用机制复用 ChunkPipelineCoordinator 的 resultIndex→dbId 映射，此处用结果列表索引表达父子关系。
 */
@Slf4j
@Component
public class NodeBasedChunkBuilder {

    /**
     * 默认 Token 估算系数（1 中文字符 ≈ 2 Token）
     */
    private static final double TOKEN_RATIO = 2.0;

    /**
     * Node 分隔符预估 Token 数（\n\n）
     */
    private static final int SEPARATOR_TOKENS = 2;

    /**
     * 从顺序的Node中构建 ChunkResult 序列。
     *
     * @param nodes      Node 序列（按阅读顺序）
     * @param maxTokens  单个 Chunk 的最大 Token 数
     * @return ChunkResult 序列
     */
    public List<ChunkResult> build(List<DocumentNode> nodes, int maxTokens) {
        if (nodes == null || nodes.isEmpty()) {
            return List.of();
        }

        // 1. 按 parentId 聚合：parentId → 子 Node 列表（保持原顺序）
        //    有 parentId 的 Node 视为"子 Node"，其 parentId 指向同源父 Node 的 id
        Map<String, List<DocumentNode>> childrenByParentId = new LinkedHashMap<>();
        for (DocumentNode node : nodes) {
            String parentId = node.getParentId();
            if (parentId != null && !parentId.isEmpty()) {
                childrenByParentId
                        .computeIfAbsent(parentId, k -> new ArrayList<>())
                        .add(node);
            }
        }

        List<ChunkResult> results = new ArrayList<>();
        List<DocumentNode> currentNodes = new ArrayList<>();
        int currentTokens = 0;

        for (DocumentNode node : nodes) {
            // 子 Node 不参与普通装箱（由父 Node 的父子装配路径统一处理）
            if (node.getParentId() != null && !node.getParentId().isEmpty()) {
                continue;
            }

            // 父 Node（有子 Node 挂靠）：先 flush 当前累积的普通 Chunk，再做父子装配
            if (childrenByParentId.containsKey(node.getId())) {
                if (!currentNodes.isEmpty()) {
                    results.add(buildChunkFromNodes(currentNodes));
                    currentNodes = new ArrayList<>();
                    currentTokens = 0;
                }
                assembleParentWithChildren(node, childrenByParentId.get(node.getId()),
                        maxTokens, results);
                continue;
            }

            int nodeTokens = estimateTokens(node);

            // 单个 Node 超过阈值：独立成块
            if (nodeTokens > maxTokens) {
                // 先输出当前累积的 Chunk
                if (!currentNodes.isEmpty()) {
                    results.add(buildChunkFromNodes(currentNodes));
                    currentNodes = new ArrayList<>();
                    currentTokens = 0;
                }
                // 超大 Node 独立成块（无父子关系，作为 Level2）
                results.add(buildChunkFromNodes(List.of(node)));
                continue;
            }

            // 累加后超阈值：切出当前 Chunk
            if (currentTokens + nodeTokens + SEPARATOR_TOKENS > maxTokens && !currentNodes.isEmpty()) {
                results.add(buildChunkFromNodes(currentNodes));
                currentNodes = new ArrayList<>();
                currentTokens = 0;
            }

            currentNodes.add(node);
            currentTokens += nodeTokens + (currentNodes.size() > 1 ? SEPARATOR_TOKENS : 0);
        }

        // 输出剩余 Node
        if (!currentNodes.isEmpty()) {
            results.add(buildChunkFromNodes(currentNodes));
        }

        log.debug("NodeBasedChunkBuilder: {} nodes → {} chunks", nodes.size(), results.size());
        return results;
    }

    /**
     * 父子装配：超长单元镜像为 Level1 父块（不参与检索）+ Level2 子块（子 Node token 装箱）。
     * parentChunkId 用结果列表索引表达，由 ChunkPipelineCoordinator 的两 pass 插入解析为 DB id。
     *
     * @param parent    父 Node（超长单元整体）
     * @param children  子 Node 序列（Python 二次切分产物，带 parentId 指向 parent）
     * @param maxTokens 子块的最大 Token 数
     * @param results   结果列表（父块追加在此，level1Index 为其在列表中的索引）
     */
    private void assembleParentWithChildren(DocumentNode parent, List<DocumentNode> children,
                                            int maxTokens, List<ChunkResult> results) {
        // Level1 父块：镜像整块内容，不参与检索（isSearchable 由 buildChunk 按 chunkLevel 判定为 false）
        ChunkResult level1 = buildChunkFromNodes(List.of(parent));
        level1.setChunkLevel(RagParameters.CHUNK_LEVEL_1);
        level1.setTitlePath(parent.getTitlePath());
        int level1Index = results.size();
        results.add(level1);

        // Level2 子块：子 Node 按 token 装箱，parentChunkId 指向 level1Index
        List<DocumentNode> currentNodes = new ArrayList<>();
        int currentTokens = 0;
        for (DocumentNode child : children) {
            int nodeTokens = estimateTokens(child);

            if (currentTokens + nodeTokens + SEPARATOR_TOKENS > maxTokens && !currentNodes.isEmpty()) {
                ChunkResult childChunk = buildChunkFromNodes(currentNodes);
                childChunk.setChunkLevel(RagParameters.CHUNK_LEVEL_2);
                childChunk.setParentChunkId(level1Index);
                childChunk.setTitlePath(parent.getTitlePath());
                results.add(childChunk);
                currentNodes = new ArrayList<>();
                currentTokens = 0;
            }

            currentNodes.add(child);
            currentTokens += nodeTokens + (currentNodes.size() > 1 ? SEPARATOR_TOKENS : 0);
        }
        if (!currentNodes.isEmpty()) {
            ChunkResult childChunk = buildChunkFromNodes(currentNodes);
            childChunk.setChunkLevel(RagParameters.CHUNK_LEVEL_2);
            childChunk.setParentChunkId(level1Index);
            childChunk.setTitlePath(parent.getTitlePath());
            results.add(childChunk);
        }
    }

    /**
     * 估算 Node 的 Token 数。
     * 基于 semanticText 长度估算。
     *
     * @param node DocumentNode
     * @return 估算的 Token 数
     */
    private int estimateTokens(DocumentNode node) {
        String text = node.semanticText();
        if (text == null || text.isEmpty()) {
            return 0;
        }
        // 简单估算：中文字符 * 2，英文单词 * 1
        // 这里用字符数 * TOKEN_RATIO 作为近似
        return (int) (text.length() * TOKEN_RATIO);
    }

    /**
     * 从 Node 序列构建 ChunkResult（默认 Level2）。
     *
     * @param nodes Node 序列
     * @return ChunkResult
     */
    private ChunkResult buildChunkFromNodes(List<DocumentNode> nodes) {
        if (nodes.isEmpty()) {
            return ChunkResult.builder().build();
        }

        // 生成 chunkText（Display Render）：使用 originalContent，保留原文形态（图片/代码/表格为占位符）
        StringBuilder displayText = new StringBuilder();
        // 生成 indexText（Index Render）：使用 semanticText，含 VLM/LLM 语义增强结果，供 Embedding + BM25 使用
        StringBuilder indexText = new StringBuilder();
        for (int i = 0; i < nodes.size(); i++) {
            if (i > 0) {
                displayText.append("\n\n");
                indexText.append("\n\n");
            }
            displayText.append(nodes.get(i).originalContent());
            indexText.append(nodes.get(i).semanticText());
        }

        // titlePath：取首个 Node 的 titlePath（同源父子块共享同一标题路径）
        String titlePath = nodes.get(0).getTitlePath();

        return ChunkResult.builder()
                .chunkLevel(RagParameters.CHUNK_LEVEL_2)
                .chunkText(displayText.toString())
                .indexText(indexText.toString())
                .titlePath(titlePath)
                .nodes(new ArrayList<>(nodes))
                .chunkType("node_based")
                .sourceStrategy("NodeBasedChunkBuilder")
                .build();
    }

    /**
     * 生成用于向量化的文本（Index Render）。
     * 使用所有 Node 的 semanticText 拼接，所以得到的是“所有语义增强后的Node内容”的组合体
     *
     * @param nodes Node 序列
     * @return 向量化文本
     */
    public String renderForIndex(List<DocumentNode> nodes) {
        if (nodes == null || nodes.isEmpty()) {
            return "";
        }

        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < nodes.size(); i++) {
            if (i > 0) {
                sb.append("\n\n");
            }
            sb.append(nodes.get(i).semanticText());
        }
        return sb.toString();
    }
}