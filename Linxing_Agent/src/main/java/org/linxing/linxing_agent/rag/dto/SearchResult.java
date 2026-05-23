package org.linxing.linxing_agent.rag.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SearchResult {

    private Integer chunkId;

    private Integer documentId;

    private String fileName;

    private String titlePath;

    private String chunkType;

    private String chunkText;

    private double score;
}
