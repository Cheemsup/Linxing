package org.linxing.linxing_agent.rag.entity;

public record VectorSearchResult(
        Integer id,
        Double score,
        String text,
        String metadata,
        Integer chunkId,
        Integer documentId,
        String fileName,
        String chunkType,
        String titlePath,
        String chunkText,
        Integer parentChunkId
) {
}
