package org.linxing.linxing_agent.config;

import lombok.Data;
import org.linxing.linxing_agent.constant.CommonConstants;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Data
@Configuration
@ConfigurationProperties(prefix = "rag")
public class RagProperties {

    private String storePath;
    private Embedding embedding = new Embedding();
    private VectorStore vectorStore = new VectorStore();
    private Llm llm = new Llm();
    private Search search = new Search();
    private Reranker reranker = new Reranker();

    @Data
    public static class Embedding {
        private String model = CommonConstants.EMBEDDING_MODEL;
        private int chunkSize = CommonConstants.CHUNK_SIZE;
        private int chunkOverlap = CommonConstants.CHUNK_OVERLAP;
    }

    @Data
    public static class VectorStore {
        private String type;
        private String host;
        private int port;
        private String database;
        private String user;
        private String password;
        private String table;
        private int dimension;
    }

    @Data
    public static class Llm {
        private String provider;
        private String apiKey;
        private String baseUrl;
        private String groupId;
        private String model;
        private Double temperature;
        private int timeoutSeconds;
        private int maxTokens;
    }

    @Data
    public static class Search {
        private int defaultTopK = CommonConstants.SEARCH_DEFAULT_TOP_K;
        private int recallSize = CommonConstants.SEARCH_RECALL_SIZE;
        private boolean hybridEnabled = true;
        private double vectorWeight = 0.7;
        private double bm25Weight = 0.3;
        private int bm25RecallSize = 20;
    }

    @Data
    public static class Reranker {
        private boolean enabled = true;
        private String modelPath;
        private String tokenizerPath;
        private int batchSize = 8;
    }
}
