package org.linxing.linxing_agent.rag.chunk;

import lombok.extern.slf4j.Slf4j;
import org.linxing.linxing_agent.rag.constant.RagParameters;
import org.linxing.linxing_agent.rag.entity.ChunkResult;
import org.linxing.linxing_agent.rag.node.DocumentNode;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * 基于 Node 序列的 Chunk 构建器，得到最终的经由了Node组合的chunk列表。
 *
 * 核心原则：
 * - Node 永不可拆分：图片、代码、表格作为原子单位（Python 侧已对超长文本做语义切分产生多个小 Node，Java 不再切分）
 * - Chunk 是 Node 的组合
 * - 按 semanticText 的 Token 估算累加，达到阈值切出 Chunk
 *
 * 父子装配（基于 groupId）：
 * Python 侧对超长 unit 不再返回整块超长 Node，而是在内部拆为多个小 Node，这些子 Node 共享同一个 groupId
 * （标识「同源整块」），普通 Node 的 groupId 为 null。本构建器按 groupId 聚相邻 Node：
 * - 有 groupId 的相邻 Node 同属一组 → 优先组装在一起：合成一个 Level1 父块（同组 Node 拼接≈原整块，不参与检索，
 *   isSearchable=false）+ 一个或多个 Level2 子块（同组 Node 按 token 装箱，可检索），子块经 parentChunkId 指向父块。
 * - 隔离性：有 groupId 的 Node 与无 groupId 的 Node 之间不拼接；不同 groupId 之间不拼接。
 *   即进入/离开组时先 flush 当前普通累加块；组装组前也 flush。
 * - 无 groupId 的普通 Node → 走 token 装箱，产出 Level2 块。
 *
 * parentChunkId 引用机制复用 ChunkIngestCoordinator 的 resultIndex→dbId 映射，此处用结果列表索引表达父子关系。
 */
@Slf4j
@Component
public class NodeBasedChunkBuilder {

    /**
     * 默认 Token 估算系数（用户约定换算关系：1 中文字符 ≈ 1.5 Token）
     */
    private static final double TOKEN_RATIO = 1.5;

    /**
     * Node 分隔符预估 Token 数（\n\n）
     */
    private static final int SEPARATOR_TOKENS = 2;

    /**
     * 从顺序的Node中构建 ChunkResult 序列。
     *
     * @param nodes      Node 序列（按阅读顺序，同 groupId 的 Node 在序列中相邻）
     * @param maxTokens  单个 Chunk 的最大 Token 数
     * @return ChunkResult 序列
     */
    public List<ChunkResult> build(List<DocumentNode> nodes, int maxTokens) {
        if (nodes == null || nodes.isEmpty()) {
            return List.of();
        }

        List<ChunkResult> results = new ArrayList<>();
        // 当前普通（无 groupId）累加块
        List<DocumentNode> currentNodes = new ArrayList<>();
        int currentTokens = 0;

        int i = 0;
        while (i < nodes.size()) {
            DocumentNode node = nodes.get(i);
            String groupId = node.getGroupId();

            if (groupId != null && !groupId.isEmpty()) {
                // 进入组：先 flush 当前普通累加块（隔离：有组与无组/上一组之间不拼接）
                if (!currentNodes.isEmpty()) {
                    results.add(buildChunkFromNodes(currentNodes));
                    currentNodes = new ArrayList<>();
                    currentTokens = 0;
                }
                // 收集连续、同 groupId 的子 Node（Python 保证同组相邻）
                List<DocumentNode> groupNodes = new ArrayList<>();
                while (i < nodes.size()
                        && Objects.equals(nodes.get(i).getGroupId(), groupId)) {
                    groupNodes.add(nodes.get(i));
                    i++;
                }
                assembleGroupIdMember(groupNodes, maxTokens, results);
                continue;
            }

            int nodeTokens = estimateTokens(node);

            // 单个 Node 超过阈值：独立成块（无父子关系，作为 Level2）
            if (nodeTokens > maxTokens) {
                if (!currentNodes.isEmpty()) {
                    results.add(buildChunkFromNodes(currentNodes));
                    currentNodes = new ArrayList<>();
                    currentTokens = 0;
                }
                results.add(buildChunkFromNodes(List.of(node)));
                i++;
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
            i++;
        }

        // 输出剩余普通 Node
        if (!currentNodes.isEmpty()) {
            results.add(buildChunkFromNodes(currentNodes));
        }

        log.debug("NodeBasedChunkBuilder: {} nodes → {} chunks", nodes.size(), results.size());
        return results;
    }

    /**
     * 组装同 groupId 的子 Node：合成 Level1 父块（同组 Node 拼接，不参与检索）
     * + 一个或多个 Level2 子块（同组 Node 按 token 装箱，可检索，parentChunkId 指向父块）。
     *
     * 同组总长通常 > maxTokens（这正是「超长 unit」拆分的来源）：
     * - 子块按 maxTokens 装箱切多个；
     * - 父块为同组所有 Node 拼接的整体（Level1，isSearchable=false），作为 Small-to-Big 检索锚与 parentChunkId 指向。
     * parentChunkId 用结果列表索引表达，由 ChunkIngestCoordinator 的两 pass 插入解析为 DB id。
     *
     * @param groupNodes 同 groupId 的子 Node 序列（非空、有序）
     * @param maxTokens  子块的最大 Token 数
     * @param results    结果列表（父块追加在此，level1Index 为其在列表中的索引）
     */
    private void assembleGroupIdMember(List<DocumentNode> groupNodes, int maxTokens,
                                      List<ChunkResult> results) {
        if (groupNodes.isEmpty()) {
            return;
        }

        // Level1 父块：同组所有 Node 拼接≈原整块，不参与检索（isSearchable 由 buildChunk 按 chunkLevel 判定为 false）
        ChunkResult level1 = buildChunkFromNodes(groupNodes);
        level1.setChunkLevel(RagParameters.CHUNK_LEVEL_1);
        // titlePath 取组内首 Node（同源父子块共享同一标题路径）
        level1.setTitlePath(groupNodes.get(0).getTitlePath());
        String parentTitlePath = groupNodes.get(0).getTitlePath();
        int level1Index = results.size();
        results.add(level1);

        // Level2 子块：同组 Node 按 token 装箱，parentChunkId 指向 level1Index
        List<DocumentNode> currentNodes = new ArrayList<>();
        int currentTokens = 0;
        for (DocumentNode child : groupNodes) {
            int nodeTokens = estimateTokens(child);

            if (currentTokens + nodeTokens + SEPARATOR_TOKENS > maxTokens && !currentNodes.isEmpty()) {
                ChunkResult childChunk = buildChunkFromNodes(currentNodes);
                childChunk.setChunkLevel(RagParameters.CHUNK_LEVEL_2);
                childChunk.setParentChunkId(level1Index);
                childChunk.setTitlePath(parentTitlePath);
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
            childChunk.setTitlePath(parentTitlePath);
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
        // 用户约定换算关系：token 计数 ≈ 1.5 × 中文字符数（英文按同一系数近似，偏保守）
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