package org.linxing.linxing_agent.entity;

import java.util.UUID;

public record VectorSearchResult(
        UUID id,
        Double score,
        String text,
        String metadata,
        Integer chunkId,
        Integer documentId,
        String fileName,
        Integer pageNumber,
        String chunkText
) {
}
