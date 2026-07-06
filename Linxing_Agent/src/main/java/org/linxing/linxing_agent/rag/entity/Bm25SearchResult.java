package org.linxing.linxing_agent.rag.entity;

/**
 * BM25 全文检索结果。nodeMetadata 为 JSONB 列返回的原始文本，由上层解析为 List&lt;Map&lt;String, Object&gt;&gt;。
 */
public record Bm25SearchResult(
        Integer chunkId,
        Integer documentId,
        String chunkText,
        String titlePath,
        String chunkType,
        String fileName,
        Double bm25Score,
        Integer parentChunkId,
        String nodeMetadata
) {
}
