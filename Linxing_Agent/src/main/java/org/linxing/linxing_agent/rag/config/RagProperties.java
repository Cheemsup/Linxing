package org.linxing.linxing_agent.rag.config;

import lombok.Data;
import org.linxing.linxing_agent.rag.constant.RagParameters;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

import java.util.Set;

/**
 * TODO：后续考虑将这些所有的配置项都移入application.yaml（以及dev）
 */
@Data
@Configuration
@ConfigurationProperties(prefix = "rag")
public class RagProperties {

    private String storePath;//上传文档/被解析出的图片的存储位置
    private Embedding embedding = new Embedding();
    private VectorStore vectorStore = new VectorStore();
    private Search search = new Search();
    private Api api = new Api();
    private Cache cache = new Cache();
    private PythonService pythonService = new PythonService();
    private SemanticEnhancement semanticEnhancement = new SemanticEnhancement();

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
        /** embedding 输出向量维度，写入 DB cast 用；必须与 {@link Api.Embedding#model} 实际输出一致（bge-m3=1024） */
        private int dimension = 1024;
    }

    @Data
    public static class Search {
        private int defaultTopK = RagParameters.SEARCH_DEFAULT_TOP_K;
        private int recallSize = RagParameters.SEARCH_RECALL_SIZE;
        private boolean hybridEnabled = true;
        private double vectorWeight = 0.7;
        private double bm25Weight = 0.3;
        private int bm25RecallSize = 20;
        /**
         * Rerank API relevance_score（[0,1] 已归一化）的相关性阈值。
         * <p>低于此阈值的结果视为不相关并舍弃，可能导致 RAG 检索返回空。
         * 设为 0 表示关闭阈值过滤（保留全部 topK 结果，向后兼容）。
         * 默认取 {@link RagParameters#SCORE_THRESHOLD}，可经 rag.search.score-threshold 覆盖。
         */
        private double scoreThreshold = RagParameters.SCORE_THRESHOLD;
    }

    @Data
    public static class Api {
        private Embedding embedding = new Embedding();
        private Reranker reranker = new Reranker();

        @Data
        public static class Embedding {
            /** 是否启用 API 向量化；false 或 api-key 为空时 embeddingModel bean 不构建，RAG 向量化不可用 */
            private boolean enabled;
            /** 硅基流动 OpenAI 兼容端点，如 https://api.siliconflow.cn/v1 */
            private String baseUrl;
            /** 硅基流动 API Key（对应 Authorization: Bearer <key>） */
            private String apiKey;
            /** 向量化模型，如 BAAI/bge-m3（固定输出 1024 维） */
            private String model;
            /** API 调用超时秒数 */
            private int timeoutSeconds = 120;
            /** API 调用失败重试次数 */
            private int maxRetries = 2;
        }

        @Data
        public static class Reranker {
            /** 是否启用 API 重排序；false 或配置不全时降级为按候选已有分数排序 */
            private boolean enabled;
            private String baseUrl;
            private String apiKey;
            /** 重排序模型，如 BAAI/bge-reranker-v2-m3 */
            private String model;
            /** 单次 API 请求的文档批量大小 */
            private int batchSize = 8;
            private int timeoutSeconds = 120;
            private int maxRetries = 2;
        }
    }

    @Data
    public static class Cache {
        // 以下 TTL 单位均为秒
        private int docPreviewTtl;
        /** @deprecated P3 Runtime Mirror 落地后 session:msgs 停写，保留仅供旧键自然过期观察 */
        @Deprecated
        private int sessionMessagesTtl;
        /** @deprecated P3 Runtime Mirror 落地后 agent:steps:{messageId} 停写，保留仅供旧键自然过期观察 */
        @Deprecated
        private int agentStepsTtl;
        /** P3 Runtime Mirror 统一 TTL（mirror:msgs / mirror:steps 共用），默认 12h。每次写都 expire 续期 */
        private int mirrorTtl = 43200;
        /** chat:response:{requestId} 幂等缓存 TTL（秒），默认 35 分钟，略大于 SSE 超时(30 分钟)以覆盖空闲 reset 窗口 */
        private int chatResponseTtl = 2100;
    }

    /**
     * Python 文档解析服务配置，对应 document_analysis_service（Docling）
     */
    @Data
    public static class PythonService {
        /** 服务 URL。本机默认 18000（8000 被 Hyper-V/WSL 保留段占用，bind 报 Errno 13），Python 侧 config.py 默认一致 */
        private String url = "http://localhost:18000";
        /** 请求超时秒数（大文件解析耗时较长；MinerU 云端异步轮询大头在等待，默认 600s） */
        private int timeoutSeconds = 600;
        /** 是否启用 Python 服务 */
        private boolean enabled = true;
        /** 图片存储根目录（应与 Python 侧 IMAGE_STORE_DIR 一致，默认使用 storePath 下的 chunk_images） */
        private String imageStoreDir;
        /** Python 解释器路径（可选，用于指定python运行环境） */
        private String pythonPath;
    }

    /**
     * SemanticEnhancementService语义增强配置
     */
    @Data
    public static class SemanticEnhancement {
        private Context context = new Context();

        /**
         * 邻居上下文配置
         */
        @Data
        public static class Context {
            /** 前置邻居数量（默认 2） */
            private int previousNodes = 2;
            /** 后置邻居数量（默认 2） */
            private int nextNodes = 2;
            /** 单个邻居节点文本渲染的字符上限（超出截断，0 表示不截断） */
            //TODO：该参数未被使用，后续可作为增强参数，可选
            private int maxNeighborChars = 200;
            /**
             * 走"全篇原文"上下文注入的文件类型集合。
             * 命中的文件类型在语义增强时整体注入全篇原文作为背景
             */
            private Set<String> fullContextFileTypes = Set.of(
                    "java", "c", "cpp", "cc", "cxx", "h", "hpp", "hxx",
                    "cs", "py", "js", "mjs", "cjs", "ts", "tsx", "jsx",
                    "go", "rs", "rb", "php", "kt", "kts", "swift", "scala",
                    "sh", "bash", "zsh", "bat", "cmd", "ps1",
                    "sql", "html", "htm");
        }
    }
}
