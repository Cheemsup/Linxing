package org.linxing.linxing_agent.entity;

public record Bm25SearchResult(
        Integer chunkId,
        Integer documentId,
        String chunkText,
        String titlePath,
        String chunkType,
        String fileName,
        Double bm25Score
) {
}
