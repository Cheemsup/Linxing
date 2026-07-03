package org.linxing.linxing_agent.rag.render;

import org.linxing.linxing_agent.rag.node.DocumentNode;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 索引渲染器（Index Render），用于 Embedding + BM25 向量化。
 *
 * 输出格式：所有 Node 都使用 semanticText（语义增强文本），
 * 图片用 VLM 描述、代码用 LLM 解释、表格用 LLM 总结，确保可被语义检索。
 *
 * 输出示例：
 * 介绍 Redis 主从复制架构...
 *
 * Redis 主从复制架构图，展示 Master-Slave 结构，数据单向同步流程...
 *
 * 配置步骤如下...
 *
 * 该代码初始化 Redis 连接池，设置最大连接数和超时参数...
 */
@Component
public class IndexRenderer implements ChunkRenderer {

    private static final String NODE_SEPARATOR = "\n\n";

    @Override
    public String render(List<DocumentNode> nodes) {
        if (nodes == null || nodes.isEmpty()) {
            return "";
        }

        StringBuilder sb = new StringBuilder();
        for (DocumentNode node : nodes) {
            if (sb.length() > 0) {
                sb.append(NODE_SEPARATOR);
            }
            sb.append(node.semanticText());
        }
        return sb.toString();
    }
}