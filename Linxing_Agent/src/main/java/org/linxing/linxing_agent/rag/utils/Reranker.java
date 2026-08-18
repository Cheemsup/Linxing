package org.linxing.linxing_agent.rag.utils;

import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.output.Response;
import dev.langchain4j.model.scoring.ScoringModel;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.linxing.linxing_agent.rag.config.RagProperties;
import org.linxing.linxing_agent.rag.entity.VectorSearchResult;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 重排序组件：对检索候选做 Cross-Encoder 风格重排。
 * <p>旧本地 ONNX 方案（ms-marco-MiniLM-L-6-v2，纯英文模型无法处理中文）已停用并删除模型文件，
 * 改为调用硅基流动 Rerank API（{@link SiliconFlowScoringModel}）。分数语义为 API 返回的
 * {@code relevance_score}（已归一化 [0,1]，上层无需再做 sigmoid）。
 * <p>模型不可用 / 调用失败时降级为按候选已有（向量相似度）分数稳定排序，保证搜索仍可用。
 */
@Slf4j
@Component
public class Reranker {

    private final RagProperties ragProperties;

    private volatile ScoringModel scoringModel;
    private volatile boolean initialized = false;

    public Reranker(RagProperties ragProperties) {
        this.ragProperties = ragProperties;
    }

    @PostConstruct
    public void init() {
        RagProperties.Api.Reranker cfg = ragProperties.getApi().getReranker();
        if (!cfg.isEnabled()) {
            log.info("Rerank API 重排序未启用（rag.api.reranker.enabled=false），检索将按其已有分数排序");
            return;
        }
        if (isBlank(cfg.getBaseUrl()) || isBlank(cfg.getApiKey()) || isBlank(cfg.getModel())) {
            log.warn("Rerank API 配置不完整（base-url/api-key/model 缺一不可），重排序不可用，检索将按其已有分数排序");
            return;
        }
        scoringModel = new SiliconFlowScoringModel(cfg);
        initialized = true;
        log.info("已启用 Rerank API 重排序: model={}, batch-size={}, base-url={}",
                cfg.getModel(), cfg.getBatchSize(), cfg.getBaseUrl());
    }

    @PreDestroy
    public void destroy() {
        if (scoringModel != null) {
            scoringModel = null;
            initialized = false;
            log.info("Rerank API 重排序资源已释放");
        }
    }

    public List<VectorSearchResult> rerank(String query, List<VectorSearchResult> candidates, int topK) {
        if (candidates == null || candidates.isEmpty()) {
            return candidates;
        }

        //稳定兜底：模型不可用 / 出现异常时，统一按候选已有分数稳定降序后截断
        //——保证不同 topK 下前几名一致（不依赖 topK 走不同排序路径）
        if (!isAvailable()) {
            log.warn("Rerank 模型不可用，返回稳定排序后的前{}条结果", topK);
            return stableLimit(candidates, topK);
        }

        //对全部候选统一打分后稳定排序截断——不依赖 topK 走不同打分路径
        return pickTopK(scoreAll(query, candidates), topK);
    }

    /**
     * 对全部候选统一重排序打分，返回带分结果（不排序、不截断）。
     * 供调用方在父块去重后再做 limit(topK)，避免 topK 截断发生在父块去重之前
     * 破坏不同 topK 下的前缀子集关系。分数为 API relevance_score（[0,1]）。
     */
    public List<ScoredResult> scoreAll(String query, List<VectorSearchResult> candidates) {
        if (candidates == null || candidates.isEmpty()) {
            return List.of();
        }
        if (!isAvailable()) {
            //模型不可用时退化为按候选已有分数包装，保持 ScoredResult 结构一致
            return candidates.stream()
                    .map(c -> new ScoredResult(c, c.score() != null ? c.score() : 0.0))
                    .collect(Collectors.toList());
        }
        try {
            log.debug("开始 Rerank API 打分，查询: {}, 候选数: {}", truncateText(query, 60), candidates.size());

            int batchSize = ragProperties.getApi().getReranker().getBatchSize();
            List<ScoredResult> scoredResults = new ArrayList<>(candidates.size());

            for (int i = 0; i < candidates.size(); i += batchSize) {
                int end = Math.min(i + batchSize, candidates.size());
                List<VectorSearchResult> batch = candidates.subList(i, end);

                List<TextSegment> segments = batch.stream()
                        .map(c -> TextSegment.from(truncateText(c.chunkText(), 510)))
                        .collect(Collectors.toList());

                Response<List<Double>> response = scoringModel.scoreAll(segments, query);
                List<Double> scores = response.content();

                for (int j = 0; j < batch.size(); j++) {
                    double score = j < scores.size() ? scores.get(j) : 0.0;
                    scoredResults.add(new ScoredResult(batch.get(j), score));
                }
            }
            return scoredResults;
        } catch (Exception e) {
            log.error("Rerank API 打分失败，退化为候选已有分数: {}", e.getMessage(), e);
            return candidates.stream()
                    .map(c -> new ScoredResult(c, c.score() != null ? c.score() : 0.0))
                    .collect(Collectors.toList());
        }
    }

    /**
     * 对已打分结果按重排分数降序、同分按 chunkId 升序稳定排序后截断 topK。
     */
    public List<VectorSearchResult> pickTopK(List<ScoredResult> scored, int topK) {
        if (scored == null || scored.isEmpty()) {
            return List.of();
        }
        return scored.stream()
                .sorted(Comparator.comparingDouble((ScoredResult sr) -> sr.score()).reversed()
                        .thenComparingInt(sr -> sr.result().chunkId() != null ? sr.result().chunkId() : Integer.MAX_VALUE))
                .limit(topK)
                .map(ScoredResult::result)
                .collect(Collectors.toList());
    }

    /**
     * 与 {@link #pickTopK} 相同的稳定排序+截断逻辑，但保留重排分数（返回 {@link ScoredResult}）。
     * <p>供需要对分数做阈值过滤的调用方使用——
     * {@link #pickTopK} 在 {@code .map(ScoredResult::result)} 时丢弃了重排分，
     * 而 {@link VectorSearchResult#score()} 对未做父块展开的结果是原始向量相似度，并非重排分，
     * 二者语义不一致，故阈值过滤必须基于此处保留的 {@link ScoredResult#score()}。
     * 分数为 API relevance_score（[0,1]），上层直接与 threshold 比较，无需 sigmoid。
     */
    public List<ScoredResult> pickTopKScored(List<ScoredResult> scored, int topK) {
        if (scored == null || scored.isEmpty()) {
            return List.of();
        }
        return scored.stream()
                .sorted(Comparator.comparingDouble((ScoredResult sr) -> sr.score()).reversed()
                        .thenComparingInt(sr -> sr.result().chunkId() != null ? sr.result().chunkId() : Integer.MAX_VALUE))
                .limit(topK)
                .collect(Collectors.toList());
    }

    /**
     * 模型不可用/异常时的稳定兜底：按候选已有 score 降序、同分按 chunkId 升序，再 limit(topK)。
     * 避免不同 topK 走不同排序路径导致前几名漂移。
     */
    private List<VectorSearchResult> stableLimit(List<VectorSearchResult> candidates, int topK) {
        return candidates.stream()
                .sorted(Comparator.comparingDouble((VectorSearchResult r) -> r.score() != null ? r.score() : 0.0).reversed()
                        .thenComparingInt(r -> r.chunkId() != null ? r.chunkId() : Integer.MAX_VALUE))
                .limit(topK)
                .collect(Collectors.toList());
    }

    public boolean isAvailable() {
        return initialized && scoringModel != null;
    }

    private String truncateText(String text, int maxChars) {
        if (text == null) {
            return "";
        }
        return text.length() <= maxChars ? text : text.substring(0, maxChars) + "...";
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }

    public record ScoredResult(VectorSearchResult result, double score) {
    }
}