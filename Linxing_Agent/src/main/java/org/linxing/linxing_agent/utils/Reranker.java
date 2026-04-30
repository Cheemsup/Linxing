package org.linxing.linxing_agent.utils;

import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.output.Response;
import dev.langchain4j.model.scoring.onnx.OnnxScoringModel;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.linxing.linxing_agent.config.RagProperties;
import org.linxing.linxing_agent.entity.VectorSearchResult;
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

        if (candidates.size() <= topK) {
            log.debug("候选结果数量({})不超过topK({})，跳过重排序", candidates.size(), topK);
            return candidates;
        }

        if (!isAvailable()) {
            log.warn("Cross-Encoder模型不可用，返回原始排序的前{}条结果", topK);
            return candidates.stream().limit(topK).collect(Collectors.toList());
        }

        try {
            log.debug("开始ONNX Cross-Encoder重排序，查询: {}, 候选数: {}, 目标TopK: {}", truncateText(query, 60), candidates.size(), topK);

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

            List<VectorSearchResult> reranked = scoredResults.stream()
                    .sorted(Comparator.comparingDouble((ScoredResult sr) -> sr.score()).reversed())
                    .limit(topK)
                    .map((ScoredResult sr) -> sr.result())
                    .collect(Collectors.toList());

            log.debug("ONNX Cross-Encoder重排序完成，从 {} 条候选中精选出 {} 条最优结果", candidates.size(), reranked.size());
            return reranked;

        } catch (Exception e) {
            log.error("ONNX Cross-Encoder重排序失败，返回原始排序结果: {}", e.getMessage(), e);
            return candidates.stream().limit(topK).collect(Collectors.toList());
        }
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

    private record ScoredResult(VectorSearchResult result, double score) {
    }
}
