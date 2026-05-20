package org.linxing.linxing_agent.rag.utils;

import org.linxing.linxing_agent.rag.entity.Bm25SearchResult;
import org.linxing.linxing_agent.rag.entity.VectorSearchResult;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * RRF（Reciprocal Rank Fusion），按排名倒数融合向量搜索与 BM25 搜索结果。
 * 核心公式：score = weight / (K + rank)，K 为平滑常数。
 */
public class ReciprocalRankFusion {

    // RRF 平滑常数，避免排名靠前的结果权重过大
    private static final int K = 60;

    /**
     * 融合两路检索结果：以 chunkId 去重，分别按排名计算 RRF 分数后累加，最终按总分降序返回。
     */
    public static List<VectorSearchResult> fuse(
            List<VectorSearchResult> vectorResults,
            List<Bm25SearchResult> bm25Results,
            double vectorWeight,
            double bm25Weight) {

        // 以 chunkId 为 key 聚合，同一 chunk 在两路中都出现则分数累加
        Map<Integer, FusedEntry> fusedMap = new LinkedHashMap<>();

        // 遍历向量检索结果，按排名计算 RRF 分数
        for (int i = 0; i < vectorResults.size(); i++) {
            VectorSearchResult r = vectorResults.get(i);
            Integer chunkId = r.chunkId();
            double score = vectorWeight / (K + i + 1);
            fusedMap.computeIfAbsent(chunkId, id -> new FusedEntry(r, 0.0))
                    .addScore(score);
        }

        // 遍历 BM25 检索结果，已有 chunk 累加分数，新 chunk 转换为 VectorSearchResult 后加入
        for (int i = 0; i < bm25Results.size(); i++) {
            Bm25SearchResult r = bm25Results.get(i);
            Integer chunkId = r.chunkId();
            double score = bm25Weight / (K + i + 1);

            if (fusedMap.containsKey(chunkId)) {
                fusedMap.get(chunkId).addScore(score);
            } else {
                VectorSearchResult vsr = new VectorSearchResult(
                        null, r.bm25Score(), null, null,
                        r.chunkId(), r.documentId(), r.fileName(),
                        r.chunkType(), r.titlePath(), r.chunkText(), r.parentChunkId()
                );
                fusedMap.put(chunkId, new FusedEntry(vsr, score));
            }
        }

        // 按融合总分降序排序
        List<Map.Entry<Integer, FusedEntry>> sorted = new ArrayList<>(fusedMap.entrySet());
        sorted.sort((a, b) -> Double.compare(b.getValue().totalScore, a.getValue().totalScore));

        List<VectorSearchResult> result = new ArrayList<>();
        for (Map.Entry<Integer, FusedEntry> entry : sorted) {
            result.add(entry.getValue().vectorResult);
        }

        return result;
    }

    // 融合条目：持有原始向量结果及累加的 RRF 总分
    private static class FusedEntry {
        final VectorSearchResult vectorResult;
        double totalScore;

        FusedEntry(VectorSearchResult vectorResult, double initialScore) {
            this.vectorResult = vectorResult;
            this.totalScore = initialScore;
        }

        void addScore(double score) {
            this.totalScore += score;
        }
    }
}
