package org.linxing.linxing_agent.rag.utils;

import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.output.Response;
import dev.langchain4j.model.scoring.onnx.OnnxScoringModel;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.linxing.linxing_agent.rag.config.RagProperties;
import org.linxing.linxing_agent.rag.entity.VectorSearchResult;
import org.springframework.core.io.DefaultResourceLoader;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;

@Slf4j
@Component
public class Reranker {

    private final RagProperties ragProperties;

    private volatile OnnxScoringModel scoringModel;
    private volatile boolean initialized = false;
    private final Object initLock = new Object();

    public Reranker(RagProperties ragProperties) {
        this.ragProperties = ragProperties;
    }

    @PostConstruct
    public void init() {
        if (!ragProperties.getReranker().isEnabled()) {
            log.info("Cross-Encoder重排序已禁用");
            return;
        }

        try {
            String modelPath = ragProperties.getReranker().getModelPath();
            String tokenizerPath = ragProperties.getReranker().getTokenizerPath();

            if (modelPath == null || modelPath.isBlank()) {
                log.warn("未配置reranker.model-path，Cross-Encoder重排序不可用");
                return;
            }
            if (tokenizerPath == null || tokenizerPath.isBlank()) {
                log.warn("未配置reranker.tokenizer-path，Cross-Encoder重排序不可用");
                return;
            }

            Path modelFile = resolvePath(modelPath, "model.onnx");
            Path tokenizerFile = resolvePath(tokenizerPath, "tokenizer.json");

            if (modelFile == null || !Files.exists(modelFile)) {
                log.error("ONNX模型文件不存在: {}，请先下载模型文件", modelPath);
                return;
            }
            if (tokenizerFile == null || !Files.exists(tokenizerFile)) {
                log.error("Tokenizer文件不存在: {}，请先下载tokenizer文件", tokenizerPath);
                return;
            }

            synchronized (initLock) {
                if (!initialized) {
                    log.info("正在加载Cross-Encoder ONNX模型: {}", modelFile);
                    long startTime = System.currentTimeMillis();

                    scoringModel = new OnnxScoringModel(modelFile.toString(), tokenizerFile.toString());

                    long elapsed = System.currentTimeMillis() - startTime;
                    initialized = true;
                    log.info("Cross-Encoder ONNX模型加载完成，耗时: {}ms", elapsed);
                }
            }
        } catch (Exception e) {
            log.error("Cross-Encoder ONNX模型加载失败，重排序功能将不可用: {}", e.getMessage(), e);
        }
    }

    private Path resolvePath(String path, String defaultFileName) throws IOException {
        if (path == null || path.isBlank()) {
            return null;
        }

        if (path.startsWith("classpath:")) {
            DefaultResourceLoader resourceLoader = new DefaultResourceLoader();
            Resource resource = resourceLoader.getResource(path);
            if (!resource.exists()) {
                log.error("classpath资源不存在: {}", path);
                return null;
            }
            Path tempFile = Files.createTempFile("reranker_", "_" + defaultFileName);
            try (InputStream is = resource.getInputStream()) {
                Files.copy(is, tempFile, StandardCopyOption.REPLACE_EXISTING);
            }
            tempFile.toFile().deleteOnExit();
            log.debug("classpath资源已复制到临时文件: {} → {}", path, tempFile);
            return tempFile;
        }

        return Paths.get(path);
    }

    @PreDestroy
    public void destroy() {
        if (scoringModel != null) {
            synchronized (initLock) {
                scoringModel = null;
                initialized = false;
                log.info("Cross-Encoder ONNX模型资源已释放");
            }
        }
    }

    public List<VectorSearchResult> rerank(String query, List<VectorSearchResult> candidates, int topK) {
        if (candidates == null || candidates.isEmpty()) {
            return candidates;
        }

        //稳定兜底：模型不可用 / 出现异常时，统一按候选已有分数稳定降序后截断
        //——保证不同 topK 下前几名一致（不依赖 topK 走不同排序路径）
        if (!isAvailable()) {
            log.warn("Cross-Encoder模型不可用，返回稳定排序后的前{}条结果", topK);
            return stableLimit(candidates, topK);
        }

        //对全部候选统一打分后稳定排序截断——不依赖 topK 走不同打分路径
        return pickTopK(scoreAll(query, candidates), topK);
    }

    /**
     * 对全部候选统一 Cross-Encoder 打分，返回带分结果（不排序、不截断）。
     * 供调用方在父块去重后再做 limit(topK)，避免 topK 截断发生在父块去重之前
     * 破坏不同 topK 下的前缀子集关系。
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
            log.debug("开始ONNX Cross-Encoder打分，查询: {}, 候选数: {}", truncateText(query, 60), candidates.size());

            int batchSize = ragProperties.getReranker().getBatchSize();
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
            log.error("ONNX Cross-Encoder打分失败，退化为候选已有分数: {}", e.getMessage(), e);
            return candidates.stream()
                    .map(c -> new ScoredResult(c, c.score() != null ? c.score() : 0.0))
                    .collect(Collectors.toList());
        }
    }

    /**
     * 对已打分结果按 Cross-Encoder 分数降序、同分按 chunkId 升序稳定排序后截断 topK。
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

    public record ScoredResult(VectorSearchResult result, double score) {
    }
}
