package org.linxing.linxing_agent.rag.utils;

import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.output.Response;
import dev.langchain4j.model.scoring.ScoringModel;
import lombok.extern.slf4j.Slf4j;
import org.linxing.linxing_agent.rag.config.RagProperties;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 硅基流动 Rerank API 的 {@link ScoringModel} 实现（Cohere/Jina 兼容 {@code POST /v1/rerank}）。
 * <p>替代已停用的本地 ONNX Cross-Encoder（ms-marco-MiniLM-L-6-v2，纯英文模型无法处理中文）。
 * 请求返回 {@code relevance_score}，已是 [0,1] 归一化分数，无需调用方再做 sigmoid。
 * 响应按 {@code results[].index} 回填到原始文档顺序，缺失项补 0.0。
 * <p>HTTP 使用 Spring {@link RestClient}（与 {@code PythonDocumentAnalysisServiceImpl} 同款），
 * JSON 序列化/解析使用 tools.jackson（Jackson 3）。请求重试次数与超时取 {@code rag.api.reranker} 配置。
 */
@Slf4j
public class SiliconFlowScoringModel implements ScoringModel {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private final RagProperties.Api.Reranker config;
    private final RestClient restClient;

    public SiliconFlowScoringModel(RagProperties.Api.Reranker config) {
        this.config = config;
        // 超时配置：连接 10s，读取按配置（重排序大文档/批量较大时耗时）
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(10_000);
        factory.setReadTimeout(config.getTimeoutSeconds() * 1000);

        this.restClient = RestClient.builder()
                .baseUrl(stripTrailingSlash(config.getBaseUrl()))
                .requestFactory(factory)
                .build();
    }

    @Override
    public Response<List<Double>> scoreAll(List<TextSegment> segments, String query) {
        if (segments == null || segments.isEmpty()) {
            return Response.from(List.of());
        }

        List<String> documents = segments.stream()
                .map(TextSegment::text)
                .toList();

        Map<String, Object> body = new HashMap<>();
        body.put("model", config.getModel());
        body.put("query", query);
        body.put("documents", documents);
        body.put("top_n", documents.size());
        body.put("return_documents", false);

        String responseBody = postRerank(OBJECT_MAPPER.writeValueAsString(body));
        return Response.from(parseScores(responseBody, documents.size()));
    }

    /**
     * 发送 rerank 请求，失败时按配置重试（rerank 幂等，可安全重试）。
     */
    private String postRerank(String requestJson) {
        Exception lastError = null;
        for (int attempt = 0; attempt <= config.getMaxRetries(); attempt++) {
            try {
                return restClient.post()
                        .uri("/rerank")
                        .header("Authorization", "Bearer " + config.getApiKey())
                        .contentType(MediaType.APPLICATION_JSON)
                        .body(requestJson)
                        .retrieve()
                        .body(String.class);
            } catch (Exception e) {
                lastError = e;
                log.warn("Rerank API 调用失败（第 {}/{} 次）: {}", attempt + 1, config.getMaxRetries() + 1, e.getMessage());
                if (attempt < config.getMaxRetries()) {
                    sleepQuietly(200L * (attempt + 1));
                }
            }
        }
        if (lastError == null) {
            throw new IllegalStateException("Rerank API 调用失败（重试次数配置无效）");
        }
        throw new IllegalStateException("Rerank API 调用失败: " + lastError.getMessage(), lastError);
    }

    /**
     * 解析 rerank 响应，将 results[].relevance_score 按 index 回填到原顺序。
     */
    private List<Double> parseScores(String responseBody, int size) {
        if (responseBody == null || responseBody.isBlank()) {
            throw new IllegalStateException("Rerank API 返回空响应");
        }
        try {
            JsonNode root = OBJECT_MAPPER.readTree(responseBody);
            JsonNode results = root.path("results");
            if (!results.isArray()) {
                throw new IllegalStateException("Rerank API 响应缺少 results 数组: " + responseBody);
            }
            double[] scores = new double[size];
            for (JsonNode item : results) {
                int index = item.path("index").asInt(-1);
                double score = item.path("relevance_score").asDouble(0.0);
                if (index >= 0 && index < size) {
                    scores[index] = score;
                } else {
                    log.warn("Rerank API 返回越界 index={}（documents={}），已忽略该项", index, size);
                }
            }
            List<Double> result = new ArrayList<>(size);
            for (double s : scores) {
                result.add(s);
            }
            return result;
        } catch (Exception e) {
            throw new IllegalStateException("解析 Rerank API 响应失败: " + e.getMessage(), e);
        }
    }

    private static void sleepQuietly(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static String stripTrailingSlash(String url) {
        if (url != null && url.endsWith("/")) {
            return url.substring(0, url.length() - 1);
        }
        return url;
    }
}