package org.linxing.linxing_agent.rag.node;

import lombok.Builder;
import lombok.Data;

import java.util.HashMap;
import java.util.Map;

/**
 * 表格节点实现类，表示文档中的表格。
 *
 * originalContent: 表格 HTML 或占位符 [TABLE_ID]
 * semanticText: LLM 生成的表格总结（待后续增强）
 * metadata.html: 表格 HTML 内容
 * metadata.rowCount/colCount: 表格行列数（可选）
 */
@Data
@Builder
public class TableNode implements DocumentNode {

    /** 表格 HTML 内容 */
    private String html;

    /** LLM 生成的语义总结（增强后填充） */
    @Builder.Default
    private String semanticSummary = null;

    /** 元数据 */
    @Builder.Default
    private Map<String, Object> metadata = new HashMap<>();

    @Override
    public NodeType type() {
        return NodeType.TABLE;
    }

    @Override
    public String originalContent() {
        // 前端通过正则 [[LINXING:TABLE:id]] 定位表格插入位置，再用 nodeId 关联 nodeMetadata 中的 html 还原显示。
        return "[[LINXING:TABLE:" + getId() + "]]";
    }

    @Override
    public String semanticText() {
        // 增强后返回 LLM 总结；未增强时返回占位信息
        if (semanticSummary != null && !semanticSummary.isBlank()) {
            return semanticSummary;
        }
        return "[表格: " + getId() + "]";
    }

    @Override
    public Map<String, Object> metadata() {
        // 确保 html 存在于 metadata 中
        if (html != null && !metadata.containsKey("html")) {
            metadata.put("html", html);
        }
        return metadata;
    }

    /**
     * 设置 LLM 生成的语义总结
     *
     * @param summary LLM 生成的表格总结
     */
    public void setSemanticSummary(String summary) {
        this.semanticSummary = summary;
        // 同步更新 metadata
        metadata.put("semantic", summary);
    }

    /**
     * 获取表格 HTML 内容（供前端展示使用）
     *
     * @return 表格 HTML
     */
    public String getTableHtml() {
        return html;
    }
}