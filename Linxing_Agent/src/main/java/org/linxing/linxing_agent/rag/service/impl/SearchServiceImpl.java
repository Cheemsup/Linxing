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
import org.linxing.linxing_agent.rag.vo.SearchResultVO;
import org.linxing.linxing_agent.observability.AgentObservability;
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

    /** 0816 Phase2 改进3：retrieval span 语义常量（与 RagSearchTool 工具名一致） */
    private static final String RETRIEVAL_TOOL_NAME = "search_knowledge_base";
    private static final String RETRIEVAL_VECTOR_STORE = "pgvector";
    private static final String RETRIEVAL_SIMILARITY = "cosine";
    private static final String RETRIEVAL_RERANKER = "ms-marco-MiniLM-L-6-v2";
    /** retrieval span metadata.scores 保留的归一化分数个数上限 */
    private static final int MAX_SCORES_KEPT = 10;

    private final EmbeddingModel embeddingModel;
    private final EmbeddingMapper embeddingMapper;
    private final ChunkMapper chunkMapper;
    private final RagProperties ragProperties;
    private final Reranker reranker;
    private final AgentObservability agentObservability;

    @Override
    public List<SearchResult> search(Integer userId, String query, int topK, boolean hybrid) {
        int effectiveTopK = topK > 0 ? topK : ragProperties.getSearch().getDefaultTopK();
        int recallSize = ragProperties.getSearch().getRecallSize();
        double threshold = ragProperties.getSearch().getScoreThreshold();
        boolean hybridUsed = hybrid && ragProperties.getSearch().isHybridEnabled();

        // 0816 Phase2 改进3：RAG 检索观测。覆盖主循环（Tool: search_knowledge_base 子 span）/ 子 Agent
        // （Agent: xxx 子 span）两入口；HTTP 直连无观测上下文时返回 no-op，静默跳过，无额外开销。
        AgentObservability.RetrievalHandle retrieval = agentObservability.beginRetrieval(
                RETRIEVAL_TOOL_NAME, query, effectiveTopK, hybrid);

        // 诊断统计采集（沿查询路径逐步累计，span 关闭时写入 metadata.*）
        int vectorCandidates = 0;
        int bm25Candidates = 0;
        List<Double> topScores = new ArrayList<>(MAX_SCORES_KEPT);

        try {
            Embedding queryEmbedding = embeddingModel.embed(query).content();
            String queryVectorString = VectorUtils.toArray(queryEmbedding.vector());

            //进行向量搜索，得到结果
            List<VectorSearchResult> vectorResults =
                    embeddingMapper.vectorSearch(userId, queryVectorString, recallSize);
            vectorCandidates = vectorResults.size();

            List<VectorSearchResult> candidates;//最终结果

            //用户选择混合搜索 && 系统开放混合搜索功能，则进行混合搜索
            if (hybridUsed) {
                String tsquery = KeywordExtractor.extractToTsquery(query);
                if (!tsquery.isEmpty()) {
                    int bm25RecallSize = ragProperties.getSearch().getBm25RecallSize();
                    //进行BM25搜索，得到结果
                    List<Bm25SearchResult> bm25Results =
                            chunkMapper.bm25Search(userId, tsquery, bm25RecallSize);
                    bm25Candidates = bm25Results.size();
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
                // 空候选：正常业务结果（hit=false），闭合 span 避免泄漏
                agentObservability.endRetrieval(retrieval, List.of(), buildRetrievalStats(
                        recallSize, vectorCandidates, bm25Candidates, hybridUsed, threshold,
                        0, 0, topScores));
                return List.of();
            }

            //对全部候选统一 Cross-Encoder 打分（与 topK 无关，保证不同 topK 下打分对象一致）
            List<Reranker.ScoredResult> scored = reranker.scoreAll(query, candidates);

            //父块去重展开（small2big），用父块代表小块参与最终排序
            //   必须在 limit(topK) 之前做，否则不同 topK 截断后再父块去重会破坏前缀子集关系
            List<Reranker.ScoredResult> expanded = expandScoredToParent(scored, userId);

            //按 Cross-Encoder 分数稳定排序后截断 topK（保留分数，供归一化/阈值过滤使用）
            //   pickTopKScored 与原 pickTopK 排序逻辑一致，仅保留 ScoredResult.score（Cross-Encoder 原始 logits）
            List<Reranker.ScoredResult> topScored = reranker.pickTopKScored(expanded, effectiveTopK);

            //对 Cross-Encoder 原始 logits 做 sigmoid 归一化到 [0,1]，再按阈值舍弃低分结果
            //   即使导致结果为空也舍弃——空结果由消费侧（RagSearchTool）降级提示
            List<Reranker.ScoredResult> filtered = new ArrayList<>(topScored.size());
            for (Reranker.ScoredResult sr : topScored) {
                double normalized = sigmoid(sr.score());
                if (topScores.size() < MAX_SCORES_KEPT) {
                    topScores.add(normalized);
                }
                if (threshold <= 0.0 || normalized >= threshold) {
                    filtered.add(new Reranker.ScoredResult(sr.result(), normalized));
                }
            }
            if (filtered.size() < topScored.size()) {
                log.debug("[搜索] 用户{} 分数阈值过滤: topK候选={} 过滤后保留={} (threshold={})",
                        userId, topScored.size(), filtered.size(), threshold);
            }

            List<SearchResult> results = filtered.stream()
                    .map(sr -> {
                        VectorSearchResult r = sr.result();
                        return SearchResult.builder()
                                .chunkId(r.chunkId())
                                .documentId(r.documentId())
                                .fileName(r.fileName())
                                .titlePath(r.titlePath())
                                .chunkType(r.chunkType())
                                .chunkText(r.chunkText())
                                .nodeMetadata(parseNodeMetadata(r.nodeMetadata()))
                                //对外暴露 sigmoid 归一化后的 [0,1] 分数
                                .score(sr.score())
                                .build();
                    })
                    //按score降序，同分时按chunkId升序作稳定tie-breaker
                    //——避免不同topK下同分父块顺序漂移导致前几名不一致
                    .sorted(Comparator.comparingDouble((SearchResult r) -> r.getScore()).reversed()
                            .thenComparingInt(r -> r.getChunkId() != null ? r.getChunkId() : Integer.MAX_VALUE))
                    .collect(Collectors.toList());

            agentObservability.endRetrieval(retrieval, toRetrievalSummaries(results), buildRetrievalStats(
                    recallSize, vectorCandidates, bm25Candidates, hybridUsed, threshold,
                    topScored.size(), filtered.size(), topScores));
            return results;
        } catch (RuntimeException e) {
            // 检索异常：闭合 span 并标 ERROR，避免 span 泄漏；异常照常向上传播
            agentObservability.endRetrievalError(retrieval, e);
            throw e;
        }
    }

    /**
     * 构建 retrieval 诊断统计（0816 Phase2 改进3）。
     * threshold 为 0 表示阈值过滤关闭，但 metadata 仍如实记录配置值。
     */
    private AgentObservability.RetrievalStats buildRetrievalStats(int recallSize, int vectorCandidates,
                                                                  int bm25Candidates, boolean hybrid,
                                                                  double threshold, int beforeFilter,
                                                                  int afterFilter, List<Double> topScores) {
        return new AgentObservability.RetrievalStats(
                RETRIEVAL_VECTOR_STORE, RETRIEVAL_SIMILARITY, RETRIEVAL_RERANKER,
                recallSize, vectorCandidates, bm25Candidates, hybrid, threshold,
                beforeFilter, afterFilter, topScores);
    }

    /**
     * 结果 → retrieval span output 摘要（chunkId/fileName/titlePath/score，不含 chunkText 控体积）。
     */
    private List<Map<String, Object>> toRetrievalSummaries(List<SearchResult> results) {
        if (results == null || results.isEmpty()) {
            return List.of();
        }
        List<Map<String, Object>> summaries = new ArrayList<>(results.size());
        for (SearchResult r : results) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("chunkId", r.getChunkId());
            m.put("fileName", r.getFileName());
            m.put("titlePath", r.getTitlePath());
            m.put("score", Math.round(r.getScore() * 10000.0) / 10000.0);
            summaries.add(m);
        }
        return summaries;
    }

    /**
     * sigmoid 归一化：将 Cross-Encoder 原始 logits 映射到 (0,1) 区间。
     * 单调递增，不改变排序，仅赋予分数可解释的语义（0.5 ≈ 相关性中性点）。
     */
    private static double sigmoid(double x) {
        return 1.0 / (1.0 + Math.exp(-x));
    }

    /**
     * 将搜索结果DTO列表转换为VO列表，score保留四位小数
     * @param results 搜索结果DTO
     * @return VO列表
     */
    @Override
    public List<SearchResultVO> toVOList(List<SearchResult> results) {
        return results.stream()
                .map(this::toVO)
                .collect(Collectors.toList());
    }

    private SearchResultVO toVO(SearchResult r) {
        return SearchResultVO.builder()
                .chunkId(r.getChunkId())
                .documentId(r.getDocumentId())
                .fileName(r.getFileName())
                .titlePath(r.getTitlePath())
                .chunkType(r.getChunkType())
                .chunkText(r.getChunkText())
                .nodeMetadata(r.getNodeMetadata())
                .score(Math.round(r.getScore() * 10000.0) / 10000.0)
                .build();
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

    /**
     * 在 Cross-Encoder 打分之后、limit(topK) 之前做父块去重展开（small2big）。
     */
    private List<Reranker.ScoredResult> expandScoredToParent(List<Reranker.ScoredResult> scored, Integer userId) {
        List<Integer> parentIds = scored.stream()
                .map(sr -> sr.result().parentChunkId())
                .filter(pid -> pid != null)
                .distinct()
                .toList();

        if (parentIds.isEmpty()) {
            return scored;
        }

        Map<Integer, Chunk> parentMap = chunkMapper.findByIds(parentIds).stream()
                .collect(Collectors.toMap(Chunk::getId, c -> c));

        log.debug("[搜索] 用户{} Small-to-Big替换: {}个小块，涉及{}个大块",
                userId, scored.size(), parentIds.size());

        //key=父块id（无父块则用小块自身chunkId），值=代表该父块的最高分小块（按分数降序、同分按chunkId升序取首个）
        LinkedHashMap<Integer, Reranker.ScoredResult> expanded = new LinkedHashMap<>();
        for (Reranker.ScoredResult sr : scored) {
            Integer parentId = sr.result().parentChunkId();
            Integer key = (parentId != null && parentMap.containsKey(parentId)) ? parentId : sr.result().chunkId();

            Reranker.ScoredResult existing = expanded.get(key);
            if (existing == null) {
                //首次出现，若为父块则替换为父块文本但保留该小块最高分
                expanded.put(key, toParentScored(sr, parentId, parentMap));
            } else {
                //已有同父块条目：取较高分作为代表分（同分保留先出现者，由上层稳定排序兜底）
                if (sr.score() > existing.score()) {
                    expanded.put(key, toParentScored(sr, parentId, parentMap));
                }
            }
        }

        log.debug("[搜索] 用户{} Small-to-Big替换完成: {}条结果 → {}条（去重后）",
                userId, scored.size(), expanded.size());
        return new ArrayList<>(expanded.values());
    }

    /**
     * 将小块打分结果替换为父块文本，保留原 Cross-Encoder 分数与小块id作 tie-breaker。
     * parentId 为 null 或父块不存在时原样返回。
     */
    private Reranker.ScoredResult toParentScored(Reranker.ScoredResult sr, Integer parentId, Map<Integer, Chunk> parentMap) {
        if (parentId == null || !parentMap.containsKey(parentId)) {
            return sr;
        }
        Chunk parent = parentMap.get(parentId);
        VectorSearchResult r = sr.result();
        VectorSearchResult parentResult = new VectorSearchResult(
                r.id(),
                sr.score(),
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
        );
        return new Reranker.ScoredResult(parentResult, sr.score());
    }
}
