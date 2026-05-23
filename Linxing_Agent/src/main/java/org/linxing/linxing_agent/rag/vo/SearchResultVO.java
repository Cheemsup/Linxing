package org.linxing.linxing_agent.rag.vo;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

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

    private double score;
}
