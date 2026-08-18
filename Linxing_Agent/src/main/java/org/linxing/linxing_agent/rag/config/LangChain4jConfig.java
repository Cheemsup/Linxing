package org.linxing.linxing_agent.rag.config;

import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.openai.OpenAiEmbeddingModel;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;

@Slf4j
@Configuration
public class LangChain4jConfig {

    /**
     * 向量化模型 Bean（硅基流动 API，OpenAI 兼容 /v1/embeddings）。
     * <p>本地部署方案（langchain4j 内置 ONNX bge-small-zh-v1.5）已停用并删除模型文件，
     * 改为调用硅基流动接口（默认 BAAI/bge-m3，1024 维）。
     * <p>未启用 / api-key 为空时返回 null：应用可正常启动，RAG 向量化/检索降级不可用（与旧行为一致）。
     * 配置见 {@code rag.api.embedding}（application.yaml + application-dev.yaml）。
     */
    @Bean
    public EmbeddingModel embeddingModel(RagProperties ragProperties) {
        RagProperties.Api.Embedding cfg = ragProperties.getApi().getEmbedding();

        if (!cfg.isEnabled()) {
            log.warn("RAG API 向量化未启用（rag.api.embedding.enabled=false），embeddingModel 不构建，向量检索不可用");
            return null;
        }
        if (isBlank(cfg.getBaseUrl()) || isBlank(cfg.getApiKey()) || isBlank(cfg.getModel())) {
            log.warn("RAG API 向量化配置不完整（base-url/api-key/model 缺一不可），embeddingModel 不构建，向量检索不可用");
            return null;
        }

        OpenAiEmbeddingModel model = OpenAiEmbeddingModel.builder()
                .baseUrl(cfg.getBaseUrl())
                .apiKey(cfg.getApiKey())
                .modelName(cfg.getModel())
                .timeout(Duration.ofSeconds(cfg.getTimeoutSeconds()))
                .maxRetries(cfg.getMaxRetries())
                .build();
        log.info("RAG 向量化已启用: model={}, base-url={}（输出维度须与 rag.vector-store.dimension 一致）",
                cfg.getModel(), cfg.getBaseUrl());
        return model;
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }
}