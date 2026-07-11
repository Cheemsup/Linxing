package org.linxing.linxing_agent.rag.render;

import org.linxing.linxing_agent.rag.node.DocumentNode;

import java.util.List;

/**
 * Chunk 渲染器接口（Node-Based 架构新增）。
 *
 * 职责：基于 Chunk 内的 Node 序列生成不同用途的文本，实现 Display 与 Index 解耦。
 * - Display Render：用于前端展示，保留原文形态（图片/代码/表格以占位符表示）
 * - Index Render：用于 Embedding + BM25，使用语义增强文本（VLM 描述、LLM 解释等）
 *
 * 本接口已废弃
 */
@Deprecated
public interface ChunkRenderer {

    /**
     * 渲染 Chunk 为指定用途的文本
     *
     * @param nodes Chunk 内的 Node 序列
     * @return 渲染后的文本
     */
    String render(List<DocumentNode> nodes);
}