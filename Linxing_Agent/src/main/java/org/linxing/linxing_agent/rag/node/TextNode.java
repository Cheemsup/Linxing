package org.linxing.linxing_agent.rag.node;

import lombok.Builder;
import lombok.Data;

import java.util.HashMap;
import java.util.Map;

/**
 * 文本节点实现类，表示普通文本段落。
 *
 * originalContent: 文本原文
 * semanticText: 文本本身（可选 LLM 总结，待后续增强）
 */
@Data
@Builder
public class TextNode implements DocumentNode {

    /** 文本原文 */
    private String text;

    /** 元数据 */
    @Builder.Default
    private Map<String, Object> metadata = new HashMap<>();

    @Override
    public NodeType type() {
        return NodeType.TEXT;
    }

    @Override
    public String originalContent() {
        return text;
    }

    @Override
    public String semanticText() {
        // 未增强时返回原文；增强后可替换为 LLM 总结
        return text;
    }

    @Override
    public Map<String, Object> metadata() {
        return metadata;
    }
}