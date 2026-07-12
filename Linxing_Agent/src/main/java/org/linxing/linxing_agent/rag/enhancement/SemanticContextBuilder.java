package org.linxing.linxing_agent.rag.enhancement;

import lombok.RequiredArgsConstructor;
import org.linxing.linxing_agent.rag.config.RagProperties;
import org.linxing.linxing_agent.rag.node.DocumentNode;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 根据 Ordered Node List，为每个需要增强的 Node 构造上下文。
 *
 * 两条路径：
 * - 邻居路径：取前后各 N 个邻居 Node（N 由配置决定），用于消解指代、补全省略主语
 * - 全文路径：注入 nodes 全体 backgroundContent 拼接的全篇原文背景，适用于 code/html 类文件
 *
 * 全文背景在单次 enhance 调用内复用：调用方传 useFullDocumentContext=true 时
 * buildFullDocumentBackground(nodes) 由本类按 nodes 引用 lazy 缓存，避免对同一批 nodes 反复拼接。
 */
@Component
@RequiredArgsConstructor
public class SemanticContextBuilder {

    private final RagProperties ragProperties;

    /** 全文背景缓存：单次 enhance 调用内复用，nodes 列表引用 → 拼接结果 */
    private List<DocumentNode> cachedFullBackgroundNodes = null;
    private String cachedFullDocumentBackground = null;

    /**
     * 为指定位置的 Node 构造上下文（邻居路径）。
     *
     * @param nodes Node 序列（按阅读顺序）
     * @param index 当前 Node 在序列中的位置
     * @return 包含前后邻居的 SemanticContext（fullDocumentBackground 为 null）
     */
    public SemanticContext build(List<DocumentNode> nodes, int index) {
        return build(nodes, index, false);
    }

    /**
     * 为指定位置的 Node 构造上下文。
     *
     * @param nodes                   Node 序列（按阅读顺序）
     * @param index                   当前 Node 在序列中的位置
     * @param useFullDocumentContext  是否走全文路径：true 注入全篇原文背景、邻居置空；false 走邻居路径
     * @return SemanticContext：全文路径携带 fullDocumentBackground，邻居路径携带前后邻居
     */
    public SemanticContext build(List<DocumentNode> nodes, int index, boolean useFullDocumentContext) {
        if (nodes == null || nodes.isEmpty() || index < 0 || index >= nodes.size()) {
            throw new IllegalArgumentException("无效的Node序列或者index");
        }

        DocumentNode target = nodes.get(index);

        // 全文路径：注入全篇原文背景，邻居置空
        if (useFullDocumentContext) {
            String fullBackground = buildFullDocumentBackground(nodes);
            return new SemanticContext(target, fullBackground);
        }

        // 邻居路径：取前后各 N 个邻居
        int prevCount = getPreviousNodesCount();
        int nextCount = getNextNodesCount();
        int from = Math.max(0, index - prevCount);
        int to = Math.min(nodes.size(), index + nextCount + 1);

        List<DocumentNode> previousNodes = index > 0
                ? nodes.subList(from, index)
                : Collections.emptyList();
        List<DocumentNode> nextNodes = index < nodes.size() - 1
                ? nodes.subList(index + 1, to)
                : Collections.emptyList();

        return new SemanticContext(target, previousNodes, nextNodes);
    }

    /**
     * 构建"全篇原文"背景：nodes 全体 backgroundContent 用 "\n\n" 拼接。
     * 结果按本次 enhance 调用缓存（按 nodes 引用匹配，同一批 nodes 不重复拼接）。
     *
     * @param nodes Node 序列
     * @return 全篇原文拼接结果；空序列返回空串
     */
    public String buildFullDocumentBackground(List<DocumentNode> nodes) {
        if (nodes == null || nodes.isEmpty()) {
            return "";
        }
        // 缓存命中：同一批 nodes 引用直接复用，避免对 N 个 Node 反复拼接
        if (nodes == cachedFullBackgroundNodes && cachedFullDocumentBackground != null) {
            return cachedFullDocumentBackground;
        }
        String result = nodes.stream()
                .map(DocumentNode::backgroundContent)
                .collect(Collectors.joining("\n\n"));
        cachedFullBackgroundNodes = nodes;
        cachedFullDocumentBackground = result;
        return result;
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
