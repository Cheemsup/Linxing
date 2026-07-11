package org.linxing.linxing_agent.rag.enhancement;

import lombok.Getter;
import org.linxing.linxing_agent.rag.node.DocumentNode;

import java.util.Collections;
import java.util.List;

/**
 * 语义增强上下文，封装待增强 Node 及其前后邻居节点。
 *
 * 用于 SemanticEnhancementService，为 LLM 提供邻居上下文以生成更准确的 semanticText。
 * 邻居节点仅作为辅助理解，不参与总结，输出完全自包含。
 */
@Getter
public class SemanticContext {

    /** 当前待增强 Node */
    private final DocumentNode target;

    /** 前置邻居节点（按阅读顺序，数量由配置决定） */
    private final List<DocumentNode> previousNodes;

    /** 后置邻居节点（按阅读顺序，数量由配置决定） */
    private final List<DocumentNode> nextNodes;

    /**
     * 构造语义增强上下文。
     *
     * @param target        当前待增强 Node
     * @param previousNodes 前置邻居节点（可为空列表）
     * @param nextNodes     后置邻居节点（可为空列表）
     */
    public SemanticContext(DocumentNode target,
                           List<DocumentNode> previousNodes,
                           List<DocumentNode> nextNodes) {
        this.target = target;
        this.previousNodes = previousNodes != null ? previousNodes : Collections.emptyList();
        this.nextNodes = nextNodes != null ? nextNodes : Collections.emptyList();
    }

    /**
     * 判断是否有前置邻居。
     */
    public boolean hasPreviousNodes() {
        return !previousNodes.isEmpty();
    }

    /**
     * 判断是否有后置邻居。
     */
    public boolean hasNextNodes() {
        return !nextNodes.isEmpty();
    }

    /**
     * 获取 Node 在文档中的位置描述（用于日志）。
     */
    public String getLocationDescription() {
        return String.format("Node %s (prev=%d, next=%d)",
                target.getId(), previousNodes.size(), nextNodes.size());
    }
}