package org.linxing.linxing_agent.config;

import lombok.Data;
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
        private String model;
        private int chunkSize;
        private int chunkOverlap;
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
        private int defaultTopK;
        private int recallSize;
    }

    @Data
    public static class Reranker {
        private boolean enabled = true;
        private String modelPath;
        private String tokenizerPath;
        private int batchSize = 8;
    }
}
