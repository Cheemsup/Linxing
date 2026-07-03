package org.linxing.linxing_agent.rag.render;

import org.linxing.linxing_agent.rag.node.DocumentNode;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 展示渲染器（Display Render），用于前端展示。
 *
 * 输出格式：保留原文形态，图片/代码/表格以占位符表示（如 [IMG_1]、[CODE_2]、[TABLE_3]），
 * 前端通过 nodeMetadata 字段将占位符还原为实际图片/代码/表格。
 *
 * 各 Node 类型的 originalContent() 已实现差异化输出：
 * - TEXT/HEADING: 返回文本原文
 * - IMAGE/CODE/TABLE/FORMULA: 返回占位符 [ID]（ID 取自 metadata.id）
 *
 * 输出示例：
 * 介绍 Redis 主从复制架构...
 *
 * [IMG_1]
 *
 * 配置步骤如下...
 *
 * [CODE_1]
 */
@Component
public class DisplayRenderer implements ChunkRenderer {

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
            // 各 Node 类型的 originalContent() 已实现差异化输出，直接调用即可
            sb.append(node.originalContent());
        }
        return sb.toString();
    }
}