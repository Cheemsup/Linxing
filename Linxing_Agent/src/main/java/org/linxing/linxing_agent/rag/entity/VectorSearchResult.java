package org.linxing.linxing_agent.rag.entity;

/**
 * 向量检索结果。nodeMetadata 为 JSONB 列返回的原始文本，由上层解析为 List&lt;Map&lt;String, Object&gt;&gt;。
 */
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
        Integer parentChunkId,
        String nodeMetadata
) {
}
