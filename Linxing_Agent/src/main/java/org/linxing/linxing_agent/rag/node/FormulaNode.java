package org.linxing.linxing_agent.rag.node;

import lombok.Builder;
import lombok.Data;

import java.util.HashMap;
import java.util.Map;

/**
 * 公式节点实现类，表示文档中的数学公式。
 *
 * originalContent: LaTeX 公式原文
 * semanticText: 公式解释文本（待后续增强）
 * metadata.formula: LaTeX 公式原文
 */
@Data
@Builder
public class FormulaNode implements DocumentNode {

    /** LaTeX 公式原文 */
    private String formula;

    /** 公式解释文本（增强后填充，可选） */
    @Builder.Default
    private String semanticExplanation = null;

    /** 元数据 */
    @Builder.Default
    private Map<String, Object> metadata = new HashMap<>();

    @Override
    public NodeType type() {
        return NodeType.FORMULA;
    }

    @Override
    public String originalContent() {
        // 返回占位符格式，用于 Display Render
        String id = getId();
        return "[" + id + "]";
    }

    @Override
    public String semanticText() {
        // 增强后返回解释；未增强时返回公式原文或占位信息
        if (semanticExplanation != null && !semanticExplanation.isBlank()) {
            return semanticExplanation;
        }
        if (formula != null && !formula.isBlank()) {
            return "[公式: " + formula + "]";
        }
        return "[公式: " + getId() + "]";
    }

    @Override
    public Map<String, Object> metadata() {
        // 确保 formula 存在于 metadata 中
        if (formula != null && !metadata.containsKey("formula")) {
            metadata.put("formula", formula);
        }
        return metadata;
    }

    /**
     * 设置公式解释文本
     *
     * @param explanation 公式解释
     */
    public void setSemanticExplanation(String explanation) {
        this.semanticExplanation = explanation;
        // 同步更新 metadata
        metadata.put("semantic", explanation);
    }

    /**
     * 获取 LaTeX 公式原文（供前端展示使用）
     *
     * @return LaTeX 公式
     */
    public String getFormulaContent() {
        return formula;
    }
}