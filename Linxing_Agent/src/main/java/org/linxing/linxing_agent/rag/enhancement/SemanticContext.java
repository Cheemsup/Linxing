package org.linxing.linxing_agent.rag.enhancement;

import lombok.Getter;
import org.linxing.linxing_agent.rag.node.DocumentNode;

import java.util.Collections;
import java.util.List;

/**
 * 语义增强上下文，封装待增强 Node 及其背景信息。
 *
 * 两条上下文路径互斥：
 * - 邻居路径：previousNodes/nextNodes 非空，fullDocumentBackground 为 null
 * - 全文路径：fullDocumentBackground 非空，previousNodes/nextNodes 为空
 *
 * 用于 SemanticEnhancementService，为 LLM 提供上下文以生成更准确的 semanticText。
 * 背景信息仅作为辅助理解，不参与总结，输出完全自包含。
 */
@Getter
public class SemanticContext {

    /** 当前待增强 Node */
    private final DocumentNode target;

    /** 前置邻居节点（按阅读顺序，数量由配置决定）；全文路径下为空 */
    private final List<DocumentNode> previousNodes;

    /** 后置邻居节点（按阅读顺序，数量由配置决定）；全文路径下为空 */
    private final List<DocumentNode> nextNodes;

    /**
     * 全篇文档背景（nodes 全体 backgroundContent 拼接）。
     * 仅全文路径下非空；邻居路径下为 null。
     */
    private final String fullDocumentBackground;

    /**
     * 邻居路径构造器：注入前后邻居，fullDocumentBackground 置 null。
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
        this.fullDocumentBackground = null;
    }

    /**
     * 全文路径构造器：注入全篇文档背景，前后邻居置空。
     *
     * @param target                  当前待增强 Node
     * @param fullDocumentBackground  全篇原文（nodes 全体 backgroundContent 拼接，Rich Node 取真实载体原文），不可为空
     */
    public SemanticContext(DocumentNode target, String fullDocumentBackground) {
        this.target = target;
        this.previousNodes = Collections.emptyList();
        this.nextNodes = Collections.emptyList();
        this.fullDocumentBackground = fullDocumentBackground;
    }

    /**
     * 判断是否走全文路径。
     */
    public boolean useFullDocumentContext() {
        return fullDocumentBackground != null && !fullDocumentBackground.isBlank();
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
        return useFullDocumentContext()
                ? String.format("Node %s (fullDocumentBackground=%d chars)",
                        target.getId(), fullDocumentBackground.length())
                : String.format("Node %s (prev=%d, next=%d)",
                        target.getId(), previousNodes.size(), nextNodes.size());
    }
}