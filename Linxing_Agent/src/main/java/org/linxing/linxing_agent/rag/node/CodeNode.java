package org.linxing.linxing_agent.rag.node;

import lombok.Builder;
import lombok.Data;

import java.util.HashMap;
import java.util.Map;

/**
 * 代码节点实现类，表示文档中的代码块。
 *
 * originalContent: 代码原文
 * semanticText: LLM 生成的代码解释（待后续增强）
 * metadata.language: 代码语言（java/python/sql 等）
 * metadata.lineCount: 代码行数（可选）
 */
@Data
@Builder
public class CodeNode implements DocumentNode {

    /** 代码原文 */
    private String code;

    /** 代码语言（java/python/sql 等） */
    private String language;

    /** LLM 生成的语义解释（增强后填充） */
    @Builder.Default
    private String semanticExplanation = null;

    /** 元数据 */
    @Builder.Default
    private Map<String, Object> metadata = new HashMap<>();

    @Override
    public NodeType type() {
        return NodeType.CODE;
    }

    @Override
    public String originalContent() {
        // 返回占位符格式，用于 Display Render
        String id = getId();
        return "[" + id + "]";
    }

    @Override
    public String semanticText() {
        // 增强后返回 LLM 解释；未增强时返回语言标识 + 占位信息
        if (semanticExplanation != null && !semanticExplanation.isBlank()) {
            return semanticExplanation;
        }
        String lang = language != null ? language : "未知语言";
        return "[代码块(" + lang + "): " + getId() + "]";
    }

    @Override
    public Map<String, Object> metadata() {
        // 确保 language 存在于 metadata 中
        if (language != null && !metadata.containsKey("language")) {
            metadata.put("language", language);
        }
        // 存储原始代码供前端展示
        if (code != null && !metadata.containsKey("code")) {
            metadata.put("code", code);
        }
        return metadata;
    }

    /**
     * 设置 LLM 生成的语义解释
     *
     * @param explanation LLM 生成的代码解释
     */
    public void setSemanticExplanation(String explanation) {
        this.semanticExplanation = explanation;
        // 同步更新 metadata
        metadata.put("semantic", explanation);
    }

    /**
     * 获取原始代码内容（供前端展示使用）
     *
     * @return 代码原文
     */
    public String getCodeContent() {
        return code;
    }
}