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
import tools.jackson.databind.JavaType;
import tools.jackson.databind.ObjectMapper;

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

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final JavaType NODE_META_LIST_TYPE = OBJECT_MAPPER.getTypeFactory()
            .constructCollectionType(List.class, Map.class);

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

        //进行向量搜索，得到结果
        List<VectorSearchResult> vectorResults =
                embeddingMapper.vectorSearch(userId, queryVectorString, recallSize);

        List<VectorSearchResult> candidates;//最终结果

        //用户选择混合搜索 && 系统开放混合搜索功能，则进行混合搜索
        if (hybrid && ragProperties.getSearch().isHybridEnabled()) {
            String tsquery = KeywordExtractor.extractToTsquery(query);
            if (!tsquery.isEmpty()) {
                int bm25RecallSize = ragProperties.getSearch().getBm25RecallSize();
                //进行BM25搜索，得到结果
                List<Bm25SearchResult> bm25Results =
                        chunkMapper.bm25Search(userId, tsquery, bm25RecallSize);
                log.debug("[搜索] 用户{} 混合检索: 向量候选={}, BM25候选={}",
                        userId, vectorResults.size(), bm25Results.size());

                //根据两种结果分数权重进行融合排序，得到最终结果
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
            results = reranker.rerank(query, candidates, effectiveTopK);//重排
        } else {
            results = candidates.stream().limit(effectiveTopK).collect(Collectors.toList());
        }

        results = expandToParentChunks(results, userId);//small2big处理

        return results.stream()
                .map(r -> SearchResult.builder()
                        .chunkId(r.chunkId())
                        .documentId(r.documentId())
                        .fileName(r.fileName())
                        .titlePath(r.titlePath())
                        .chunkType(r.chunkType())
                        .chunkText(r.chunkText())
                        .nodeMetadata(parseNodeMetadata(r.nodeMetadata()))
                        .score(r.score() != null ? r.score() : 0.0)
                        .build())
                .sorted(Comparator.comparingDouble(SearchResult::getScore).reversed())
                .collect(Collectors.toList());
    }

    /**
     * 将 node_metadata JSONB 列返回的原始文本解析为 List&lt;Map&gt;。
     * 解析失败或为空时返回空列表（前端对孤儿占位符原样输出，不报错）。
     */
    private List<Map<String, Object>> parseNodeMetadata(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            List<Map<String, Object>> parsed = OBJECT_MAPPER.readValue(json, NODE_META_LIST_TYPE);
            return parsed != null ? parsed : List.of();
        } catch (Exception e) {
            log.warn("解析 nodeMetadata 失败，回退为空列表: {}", e.getMessage());
            return List.of();
        }
    }

    /**
     * 将父块 Chunk.nodeMetadata（List&lt;Map&gt;）序列化为字符串，统一走 VectorSearchResult.nodeMetadata(String) 通道，
     * 再由上层 parseNodeMetadata 还原。父块来自 findByIds（已用 JsonListTypeHandler 还原为 List）。
     */
    private String serializeNodeMetadata(List<Map<String, Object>> nodeMetadata) {
        if (nodeMetadata == null || nodeMetadata.isEmpty()) {
            return null;
        }
        try {
            return OBJECT_MAPPER.writeValueAsString(nodeMetadata);
        } catch (Exception e) {
            log.warn("序列化父块 nodeMetadata 失败: {}", e.getMessage());
            return null;
        }
    }

    //small2big检索，将存在parent块的chunk再进行一次父块检索，作为最终返回的chunk
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
                            null,
                            serializeNodeMetadata(parent.getNodeMetadata())
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
