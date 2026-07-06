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
public class ChunkContextVO {

    private Integer chunkId;

    private String chunkText;

    /**
     * Node 元信息（与 chunkText 中的 [[LINXING:TYPE:nodeId]] 占位符协同）。
     * 前端用 nodeId 关联此处的 imagePath/code/html/formula 等还原图片/代码/表格/公式的原文形态。
     * 仅 IMAGE/CODE/TABLE/FORMULA 类 Node 入列；纯文本 chunk 为空列表。
     */
    private List<Map<String, Object>> nodeMetadata;

    private ParentChunkInfo parentChunk;

    private List<SiblingChunkInfo> siblingChunks;

    private Integer documentId;

    private String fileName;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ParentChunkInfo {

        private Integer chunkId;

        private String titlePath;

        private String chunkText;

        /**
         * 父块的 Node 元信息，作用同外层 nodeMetadata，用于还原父级内容中的图片/代码/表格占位符。
         */
        private List<Map<String, Object>> nodeMetadata;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class SiblingChunkInfo {

        private Integer chunkId;

        private String textPreview;
    }
}
