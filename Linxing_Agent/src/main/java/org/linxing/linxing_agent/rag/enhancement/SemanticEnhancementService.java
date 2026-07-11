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
     * @param nodes Node 序列
     */
    void enhance(List<DocumentNode> nodes);
}