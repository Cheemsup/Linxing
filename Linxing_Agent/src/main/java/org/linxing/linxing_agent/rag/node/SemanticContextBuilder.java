package org.linxing.linxing_agent.rag.node;

import lombok.RequiredArgsConstructor;
import org.linxing.linxing_agent.rag.config.RagProperties;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.List;

/**
 * 根据 Ordered Node List，为每个需要增强的 Node 构造包含前后邻居节点的上下文。
 *
 * 邻居数量由配置决定（默认各 2 个），用于为 LLM 提供上下文以生成更准确的 semanticText。
 */
@Component
@RequiredArgsConstructor
public class SemanticContextBuilder {

    private final RagProperties ragProperties;

    /**
     * 为指定位置的 Node 构造上下文。
     *
     * @param nodes Node 序列（按阅读顺序）
     * @param index 当前 Node 在序列中的位置
     * @return 包含前后邻居的 SemanticContext
     */
    public SemanticContext build(List<DocumentNode> nodes, int index) {
        if (nodes == null || nodes.isEmpty() || index < 0 || index >= nodes.size()) {
            throw new IllegalArgumentException("无效的Node序列或者index");
        }

        // 从配置读取邻居数量
        int prevCount = getPreviousNodesCount();
        int nextCount = getNextNodesCount();

        // 计算前后邻居的范围
        int from = Math.max(0, index - prevCount);
        int to = Math.min(nodes.size(), index + nextCount + 1);

        DocumentNode target = nodes.get(index);
        List<DocumentNode> previousNodes = index > 0
                ? nodes.subList(from, index)
                : Collections.emptyList();
        List<DocumentNode> nextNodes = index < nodes.size() - 1
                ? nodes.subList(index + 1, to)
                : Collections.emptyList();

        return new SemanticContext(target, previousNodes, nextNodes);
    }

    /**
     * 获取前置邻居数量（从配置读取，默认 2）。
     */
    private int getPreviousNodesCount() {
        RagProperties.SemanticEnhancement.Context context = getSemanticEnhancementContext();
        return context != null ? context.getPreviousNodes() : 2;
    }

    /**
     * 获取后置邻居数量（从配置读取，默认 2）。
     */
    private int getNextNodesCount() {
        RagProperties.SemanticEnhancement.Context context = getSemanticEnhancementContext();
        return context != null ? context.getNextNodes() : 2;
    }

    /**
     * 从 RagProperties 获取 SemanticEnhancement.Context 配置。
     */
    private RagProperties.SemanticEnhancement.Context getSemanticEnhancementContext() {
        RagProperties.SemanticEnhancement enhancement = ragProperties.getSemanticEnhancement();
        return enhancement != null ? enhancement.getContext() : null;
    }
}