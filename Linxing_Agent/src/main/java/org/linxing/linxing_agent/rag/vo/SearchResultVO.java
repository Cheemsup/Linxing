package org.linxing.linxing_agent.rag.vo;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.Map;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SearchResultVO {

    private Integer chunkId;

    private Integer documentId;

    private String fileName;

    private String titlePath;

    private String chunkType;

    private String chunkText;

    /**
     * Node 元信息（与 chunkText 中的 [[LINXING:TYPE:nodeId]] 占位符协同）。
     * 前端用 nodeId 关联此处的 imagePath/code/html/formula 等还原图片/代码/表格/公式的原文形态。
     * 仅 IMAGE/CODE/TABLE/FORMULA 类 Node 入列；纯文本 chunk 为空列表。
     */
    private List<Map<String, Object>> nodeMetadata;

    private double score;
}
