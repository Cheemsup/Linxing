package org.linxing.linxing_agent.rag.vo;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ChunkContextVO {

    private Integer chunkId;

    private String chunkText;

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
