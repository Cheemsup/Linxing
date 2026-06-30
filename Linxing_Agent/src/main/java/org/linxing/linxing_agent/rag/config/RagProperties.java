package org.linxing.linxing_agent.rag.config;

import lombok.Data;
import org.linxing.linxing_agent.rag.constant.RagParameters;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

import java.util.HashMap;
import java.util.Map;

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
    private Cache cache = new Cache();

    @Data
    public static class Embedding {
        private String model = RagParameters.EMBEDDING_MODEL;
        private int chunkSize = RagParameters.CHUNK_SIZE;
        private int chunkOverlap = RagParameters.CHUNK_OVERLAP;
        /** 最小 chunk 大小，低于此值的相邻同源小块会被合并；设为 0 关闭合并 */
        //TODO：1、待确定该参数的真正用法；2、这个值太大了
        private int minChunkSize = RagParameters.MIN_CHUNK_SIZE;
        /** 标题区块拆分阈值：超长标题区块按句子拆分时的字符上限（如 1000）。仅对 > 此阈值的区块做拆分 */
        private int chunkThreshold = RagParameters.CHUNK_THRESHOLD;
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
        private String defaultProvider;
        private Map<String, LlmProviderConfig> providers = new HashMap<>();
        private Double temperature;
        private int timeoutSeconds;
        private int maxTokens;
    }

    @Data
    public static class LlmProviderConfig {
        private String apiKey;
        private String baseUrl;
        private String groupId;
        private String model;
        private Boolean returnThinking;
        private Boolean sendThinking;
    }

    @Data
    public static class Search {
        private int defaultTopK = RagParameters.SEARCH_DEFAULT_TOP_K;
        private int recallSize = RagParameters.SEARCH_RECALL_SIZE;
        private boolean hybridEnabled = true;
        private double vectorWeight = 0.7;
        private double bm25Weight = 0.3;
        private int bm25RecallSize = 20;
    }

    @Data
    public static class Reranker {
        private boolean enabled;
        private String modelPath;
        private String tokenizerPath;
        private int batchSize = 8;
    }

    @Data
    public static class Cache {
        private int docPreviewTtl;
        private int sessionMessagesTtl;
        private SemanticCache semanticCache = new SemanticCache();
    }

    @Data
    public static class SemanticCache {
        private boolean enabled;
        private double threshold;
        private int quotaPerUser;
        private String quantization;
    }
}
