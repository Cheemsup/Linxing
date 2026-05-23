package org.linxing.linxing_agent.rag.service.impl;

import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.model.embedding.EmbeddingModel;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.linxing.linxing_agent.rag.config.RagProperties;
import org.linxing.linxing_agent.rag.dto.SearchResult;
import org.linxing.linxing_agent.rag.entity.Bm25SearchResult;
import org.linxing.linxing_agent.rag.entity.Chunk;
import org.linxing.linxing_agent.rag.entity.VectorSearchResult;
import org.linxing.linxing_agent.rag.mapper.ChunkMapper;
import org.linxing.linxing_agent.rag.mapper.EmbeddingMapper;
import org.linxing.linxing_agent.rag.service.ISearchService;
import org.linxing.linxing_agent.rag.utils.KeywordExtractor;
import org.linxing.linxing_agent.rag.utils.ReciprocalRankFusion;
import org.linxing.linxing_agent.rag.utils.Reranker;
import org.linxing.linxing_agent.rag.utils.VectorUtils;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class SearchServiceImpl implements ISearchService {

    private final EmbeddingModel embeddingModel;
    private final EmbeddingMapper embeddingMapper;
    private final ChunkMapper chunkMapper;
    private final RagProperties ragProperties;
    private final Reranker reranker;

    @Override
    public List<SearchResult> search(Integer userId, String query, int topK, boolean hybrid) {
        int effectiveTopK = topK > 0 ? topK : ragProperties.getSearch().getDefaultTopK();
        int recallSize = ragProperties.getSearch().getRecallSize();

        Embedding queryEmbedding = embeddingModel.embed(query).content();
        String queryVectorString = VectorUtils.toArray(queryEmbedding.vector());

        List<VectorSearchResult> vectorResults =
                embeddingMapper.vectorSearch(userId, queryVectorString, recallSize);

        List<VectorSearchResult> candidates;

        if (hybrid && ragProperties.getSearch().isHybridEnabled()) {
            String tsquery = KeywordExtractor.extractToTsquery(query);
            if (!tsquery.isEmpty()) {
                int bm25RecallSize = ragProperties.getSearch().getBm25RecallSize();
                List<Bm25SearchResult> bm25Results =
                        chunkMapper.bm25Search(userId, tsquery, bm25RecallSize);
                log.debug("[搜索] 用户{} 混合检索: 向量候选={}, BM25候选={}",
                        userId, vectorResults.size(), bm25Results.size());

                candidates = ReciprocalRankFusion.fuse(
                        vectorResults,
                        bm25Results,
                        ragProperties.getSearch().getVectorWeight(),
                        ragProperties.getSearch().getBm25Weight()
                );
                log.debug("[搜索] 用户{} RRF融合后候选数: {}", userId, candidates.size());
            } else {
                log.debug("[搜索] 用户{} 未提取到有效关键词，仅使用向量检索", userId);
                candidates = vectorResults;
            }
        } else {
            candidates = vectorResults;
        }

        if (candidates.isEmpty()) {
            return List.of();
        }

        List<VectorSearchResult> results;
        if (reranker.isAvailable()) {
            log.debug("[搜索] 用户{} 开始Cross-Encoder重排序，候选数: {}, 目标TopK: {}",
                    userId, candidates.size(), effectiveTopK);
            results = reranker.rerank(query, candidates, effectiveTopK);
        } else {
            results = candidates.stream().limit(effectiveTopK).collect(Collectors.toList());
        }

        results = expandToParentChunks(results, userId);

        return results.stream()
                .map(r -> SearchResult.builder()
                        .chunkId(r.chunkId())
                        .documentId(r.documentId())
                        .fileName(r.fileName())
                        .titlePath(r.titlePath())
                        .chunkType(r.chunkType())
                        .chunkText(r.chunkText())
                        .score(r.score() != null ? r.score() : 0.0)
                        .build())
                .sorted(Comparator.comparingDouble(SearchResult::getScore).reversed())
                .collect(Collectors.toList());
    }

    private List<VectorSearchResult> expandToParentChunks(List<VectorSearchResult> results, Integer userId) {
        List<Integer> parentIds = results.stream()
                .map(VectorSearchResult::parentChunkId)
                .filter(pid -> pid != null)
                .distinct()
                .toList();

        if (parentIds.isEmpty()) {
            return results;
        }

        Map<Integer, Chunk> parentMap = chunkMapper.findByIds(parentIds).stream()
                .collect(Collectors.toMap(Chunk::getId, c -> c));

        log.debug("[搜索] 用户{} Small-to-Big替换: {}个小块，涉及{}个大块",
                userId, results.size(), parentIds.size());

        LinkedHashMap<Integer, VectorSearchResult> expanded = new LinkedHashMap<>();
        for (VectorSearchResult r : results) {
            Integer parentId = r.parentChunkId();
            if (parentId != null && parentMap.containsKey(parentId)) {
                if (!expanded.containsKey(parentId)) {
                    Chunk parent = parentMap.get(parentId);
                    expanded.put(parentId, new VectorSearchResult(
                            r.id(),
                            r.score(),
                            r.text(),
                            r.metadata(),
                            parent.getId(),
                            parent.getDocumentId(),
                            r.fileName(),
                            parent.getChunkType() != null ? parent.getChunkType() : r.chunkType(),
                            parent.getTitlePath() != null ? parent.getTitlePath() : r.titlePath(),
                            parent.getChunkText(),
                            null
                    ));
                }
            } else {
                expanded.put(r.chunkId(), r);
            }
        }

        log.debug("[搜索] 用户{} Small-to-Big替换完成: {}条结果 → {}条（去重后）",
                userId, results.size(), expanded.size());
        return new ArrayList<>(expanded.values());
    }
}
