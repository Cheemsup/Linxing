package org.linxing.linxing_agent.rag.node;

import lombok.Builder;
import lombok.Data;

import java.util.HashMap;
import java.util.Map;

/**
 * 标题节点实现类，表示文档中的标题（带层级信息）。
 *
 * originalContent: 标题文本原文
 * semanticText: 标题文本本身
 * metadata.level: 标题级别（1=一级标题，2=二级标题，...）
 */
@Data
@Builder
public class HeadingNode implements DocumentNode {

    /** 标题文本原文 */
    private String text;

    /** 标题级别（1=一级标题，2=二级标题，...） */
    private Integer level;

    /** 元数据 */
    @Builder.Default
    private Map<String, Object> metadata = new HashMap<>();

    @Override
    public NodeType type() {
        return NodeType.HEADING;
    }

    @Override
    public String originalContent() {
        return text;
    }

    @Override
    public String semanticText() {
        return text;
    }

    @Override
    public Map<String, Object> metadata() {
        // 确保 level 存在于 metadata 中
        if (level != null && !metadata.containsKey("level")) {
            metadata.put("level", level);
        }
        return metadata;
    }
}