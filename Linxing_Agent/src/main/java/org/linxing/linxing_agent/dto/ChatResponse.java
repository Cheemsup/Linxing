package org.linxing.linxing_agent.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ChatResponse {

    private String answer;

    private List<String> sources;

    private List<SourceDetail> sourceDetails;

    private String sessionId;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class SourceDetail {

        private Integer chunkId;

        private Integer documentId;

        private String fileName;

        private String titlePath;

        private String chunkType;
    }
}
