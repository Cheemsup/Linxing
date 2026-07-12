package org.linxing.linxing_agent.rag.enhancement;

import org.linxing.linxing_agent.rag.node.DocumentNode;

import java.util.List;

/**
 * 语义增强服务接口。
 */
public interface SemanticEnhancementService {

    /**
     * 对特定类型（如IMG、CODE） Node 序列进行语义增强。
     *
     * 文件类型用于决定上下文构建方式：code/html 类文件走"全篇原文"背景注入，
     * 其余文件类型走"前后邻居"背景注入。决策在一次调用内对所有 Node 统一生效。
     *
     * @param nodes    Node 序列
     * @param fileType 当前文档的文件类型（按扩展名，如 "java"/"pdf"），用于选择上下文构建路径
     */
    void enhance(List<DocumentNode> nodes, String fileType);
}