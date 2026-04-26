package org.linxing.linxing_agent.entity;

import java.util.UUID;

public record FullEmbeddingRecord(
        UUID id,
        Integer userId,
        Integer documentId,
        Integer chunkId,
        String embeddingVector,
        String text,
        String metadata
) {
}
