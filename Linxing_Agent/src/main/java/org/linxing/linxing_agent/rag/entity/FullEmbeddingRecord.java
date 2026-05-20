package org.linxing.linxing_agent.rag.entity;

import jakarta.validation.constraints.NotNull;

public record FullEmbeddingRecord(
        Integer id,
        Integer userId,
        @NotNull Integer documentId,
        @NotNull Integer chunkId,
        String embeddingVector,
        String text,
        String metadata
) {
}
