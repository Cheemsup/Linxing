package org.linxing.linxing_agent.rag.node;

import lombok.extern.slf4j.Slf4j;
import org.linxing.linxing_agent.rag.strategy.ChunkResult;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 基于 Node 序列的 Chunk 构建器，得到最终的经由了Node组合的chunk列表。
 *
 * 核心原则：
 * - Node 永不可拆分：图片、代码、表格作为原子单位
 * - Chunk 是 Node 的组合
 * - 按 semanticText 的 Token 估算累加，达到阈值切出 Chunk
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

        List<ChunkResult> results = new ArrayList<>();
        List<DocumentNode> currentNodes = new ArrayList<>();
        int currentTokens = 0;

        for (DocumentNode node : nodes) {
            int nodeTokens = estimateTokens(node);

            // 单个 Node 超过阈值：独立成块
            //TODO：此处后续可以考虑建立父子chunk的关系模式
            if (nodeTokens > maxTokens) {
                // 先输出当前累积的 Chunk
                if (!currentNodes.isEmpty()) {
                    results.add(buildChunkFromNodes(currentNodes));
                    currentNodes = new ArrayList<>();
                    currentTokens = 0;
                }
                // 超大 Node 独立成块
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
     * 从 Node 序列构建 ChunkResult。
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

        return ChunkResult.builder()
                .chunkText(displayText.toString())
                .indexText(indexText.toString())
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