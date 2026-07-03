package org.linxing.linxing_agent.rag.node;

import java.util.Map;

/**
 * DocumentNode 统一接口，是 Node-Based RAG 架构的核心抽象。
 *
 * Parser 不再输出纯字符串，而是输出 DocumentNode 序列。
 * 每个 Node 都有明确的类型、原始内容、语义增强文本和元数据。
 *
 * 核心原则：
 * - Node 是 Pipeline 的一等公民
 * - 图片/代码/表格作为原子单位，永不被切断
 * - semanticText 用于检索（Embedding + BM25）
 * - originalContent 用于前端展示（Display Render）
 * 
 * TODO：考虑更名以示与实现类之间的区分
 */
public interface DocumentNode {

    /**
     * Node 类型标识
     *
     * @return Node 类型枚举值
     */
    NodeType type();

    /**
     * 原始内容（用于 Display Render）
     *
     * TEXT/HEADING/CODE: 返回文本原文
     * IMAGE: 返回图片 URL 或占位符
     * TABLE: 返回表格 HTML 或原文
     * FORMULA: 返回 LaTeX 原文
     *
     * @return 原始内容字符串
     */
    String originalContent();

    /**
     * 语义增强文本（用于 Index Render：Embedding + BM25）
     *
     * TEXT/HEADING: 返回文本本身（可选 LLM 总结）
     * IMAGE: 返回 VLM 生成的图片描述
     * CODE: 返回 LLM 生成的代码解释
     * TABLE: 返回 LLM 生成的表格总结
     * FORMULA: 返回公式解释文本
     *
     * 若未进行语义增强，返回原始内容。
     *
     * @return 语义增强文本
     */
    String semanticText();

    /**
     * 元数据（位置、格式、来源等）
     *
     * 常见字段：
     * - id: Node 唯一标识（如 "IMG_1", "CODE_2"）
     * - page: 页码（PDF）
     * - bbox: 边界框 [x, y, width, height]
     * - level: 标题级别（HEADING）
     * - language: 代码语言（CODE）
     * - imagePath: 图片路径（IMAGE）
     * - caption: 图片标题（IMAGE）
     * - rowCount, colCount: 表格行列数（TABLE）
     *
     * @return 元数据 Map
     */
    Map<String, Object> metadata();

    /**
     * 获取 Node 唯一标识
     *
     * @return Node ID，如 "n1", "IMG_1"
     */
    default String getId() {
        Object id = metadata().get("id");
        return id != null ? id.toString() : type().name() + "_" + hashCode();
    }

    /**
     * 标题路径（如 "第一章 > 第一节"）。
     * 由 Python 侧结构识别产出并经 NodeConverter 写入 metadata["titlePath"]；
     * 非标题块也带其所属标题路径；无标题上下文时返回 null。
     *
     * @return 标题路径，可能为 null
     */
    default String getTitlePath() {
        Object tp = metadata().get("titlePath");
        return tp != null ? tp.toString() : null;
    }

    /**
     * 超长单元的父 Node ID（用于父子 chunk）。
     * Python 侧对超长 section/段落/方法做二次切分时，拆出的子 Node 标 parentId 指向同源 Level1 父 Node 的 id；
     * 普通块为 null。
     *
     * @return 父 Node ID，可能为 null
     */
    default String getParentId() {
        Object pid = metadata().get("parentId");
        return pid != null ? pid.toString() : null;
    }
}