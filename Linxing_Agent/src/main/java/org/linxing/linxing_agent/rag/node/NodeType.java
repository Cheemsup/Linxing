package org.linxing.linxing_agent.rag.node;

/**
 * Node 类型枚举，定义 DocumentNode 的所有合法类型。
 * Parser 输出的 Node 序列中的每个元素都有明确的类型标识。
 */
public enum NodeType {

    /** 普通文本段落 */
    TEXT,

    /** 标题（带层级信息） */
    HEADING,

    /** 图片（带路径、可选 caption） */
    IMAGE,

    /** 代码块（带语言标识） */
    CODE,

    /** 表格（HTML 或结构化数据） */
    TABLE,

    /** 数学公式（LaTeX 原文） */
    FORMULA
}