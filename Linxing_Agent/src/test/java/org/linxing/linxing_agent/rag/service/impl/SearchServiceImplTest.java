package org.linxing.linxing_agent.rag.service.impl;

import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.output.Response;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.linxing.linxing_agent.observability.AgentObservability;
import org.linxing.linxing_agent.observability.CollectingSpanExporter;
import org.linxing.linxing_agent.observability.LangfuseAttributeKeys;
import org.linxing.linxing_agent.observability.LangfuseProperties;
import org.linxing.linxing_agent.observability.MessageSerializer;
import org.linxing.linxing_agent.rag.config.RagProperties;
import org.linxing.linxing_agent.rag.dto.SearchResult;
import org.linxing.linxing_agent.rag.entity.VectorSearchResult;
import org.linxing.linxing_agent.rag.mapper.ChunkMapper;
import org.linxing.linxing_agent.rag.mapper.EmbeddingMapper;
import org.linxing.linxing_agent.rag.utils.Reranker;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import tools.jackson.databind.ObjectMapper;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/**
 * {@link SearchServiceImpl} 单测：验证 0816 Phase2 改进3 的 retrieval span 接线——
 * 真实诊断（候选数/阈值/过滤前后数/分数）进 metadata、空候选与异常均正常闭合、无观测上下文不建 span。
 * 用 {@link CollectingSpanExporter} + SimpleSpanProcessor 同步导出，end 后直接断言。
 */
@ExtendWith(MockitoExtension.class)
class SearchServiceImplTest {

    private CollectingSpanExporter exporter;
    private AgentObservability agentObservability;
    private SearchServiceImpl searchService;

    @Mock
    private EmbeddingModel embeddingModel;
    @Mock
    private EmbeddingMapper embeddingMapper;
    @Mock
    private ChunkMapper chunkMapper;

    @BeforeEach
    void setUp() {
        exporter = new CollectingSpanExporter();
        SdkTracerProvider provider = SdkTracerProvider.builder()
                .addSpanProcessor(SimpleSpanProcessor.create(exporter))
                .build();
        Tracer tracer = provider.get("test");
        LangfuseProperties props = new LangfuseProperties();
        props.setEnabled(true);
        props.setEnvironment("test");
        props.setVersion("1.0.0");
        agentObservability = new AgentObservability(tracer, props, new MessageSerializer(new ObjectMapper()));

        RagProperties ragProperties = new RagProperties();
        ragProperties.getSearch().setDefaultTopK(5);
        ragProperties.getSearch().setRecallSize(20);
        ragProperties.getSearch().setHybridEnabled(true);
        ragProperties.getSearch().setScoreThreshold(0.35);

        // 未 init（@PostConstruct 仅 Spring 触发）：scoringModel=null → scoreAll 退化按候选已有分数包装
        Reranker reranker = new Reranker(ragProperties);

        searchService = new SearchServiceImpl(
                embeddingModel, embeddingMapper, chunkMapper, ragProperties, reranker, agentObservability);
    }

    @Test
    @DisplayName("有观测上下文 → 检索产出 retrieval span，诊断统计进 metadata，检索结果不变")
    void search_shouldCreateRetrievalSpan() {
        // Given：观测上下文（模拟主循环）+ 1 个向量候选（非 hybrid，避免依赖 KeywordExtractor/BM25）
        mockEmbedding();
        when(embeddingMapper.vectorSearch(any(), any(), anyInt()))
                .thenReturn(List.of(vectorResult(101, "Java.md", 0.8)));

        AgentObservability.TraceHandle trace = agentObservability.beginTraceRoot(1, 42, "req-s", "查询 Java");

        List<SearchResult> results = searchService.search(42, "Java 是什么", 5, false);

        agentObservability.endTraceRoot(trace, "回答", null);

        // 检索行为不变：返回 1 条，score 为 sigmoid(0.8)≈0.69
        assertThat(results).hasSize(1);
        assertThat(results.get(0).getScore()).isBetween(0.68, 0.70);
        assertThat(results.get(0).getChunkId()).isEqualTo(101);

        // retrieval span 结构与真实诊断
        SpanData ret = exporter.getSpans().stream()
                .filter(s -> s.getName().startsWith("Retriever: ")).findFirst().orElseThrow();
        SpanData root = exporter.getSpans().stream()
                .filter(s -> "agent-run".equals(s.getName())).findFirst().orElseThrow();
        assertThat(ret.getName()).isEqualTo("Retriever: search_knowledge_base");
        assertThat(ret.getParentSpanId()).isEqualTo(root.getSpanId());
        assertThat(ret.getAttributes().get(AttributeKey.stringKey(LangfuseAttributeKeys.METADATA_KIND))).isEqualTo("retrieval");
        assertThat(ret.getAttributes().get(AttributeKey.longKey(LangfuseAttributeKeys.METADATA_VECTOR_CANDIDATES))).isEqualTo(1L);
        assertThat(ret.getAttributes().get(AttributeKey.longKey(LangfuseAttributeKeys.METADATA_BM25_CANDIDATES))).isEqualTo(0L);
        assertThat(ret.getAttributes().get(AttributeKey.booleanKey(LangfuseAttributeKeys.METADATA_HYBRID))).isFalse();
        assertThat(ret.getAttributes().get(AttributeKey.longKey(LangfuseAttributeKeys.METADATA_BEFORE_FILTER))).isEqualTo(1L);
        assertThat(ret.getAttributes().get(AttributeKey.longKey(LangfuseAttributeKeys.METADATA_AFTER_FILTER))).isEqualTo(1L);
        assertThat(ret.getAttributes().get(AttributeKey.booleanKey(LangfuseAttributeKeys.METADATA_HIT))).isTrue();
        assertThat(ret.getAttributes().get(AttributeKey.doubleKey(LangfuseAttributeKeys.METADATA_SCORE_THRESHOLD))).isEqualTo(0.35);

        String output = ret.getAttributes().get(AttributeKey.stringKey(LangfuseAttributeKeys.OBSERVATION_OUTPUT));
        assertThat(output).contains("Java.md").contains("\"chunkId\":101");
        assertThat(output).doesNotContain("chunkText");
    }

    @Test
    @DisplayName("空候选 → 检索返回空且 retrieval span 正常闭合 hit=false")
    void search_emptyCandidates_shouldCloseRetrievalSpan() {
        mockEmbedding();
        when(embeddingMapper.vectorSearch(any(), any(), anyInt())).thenReturn(List.of());

        AgentObservability.TraceHandle trace = agentObservability.beginTraceRoot(1, 42, "req-e", "查询");

        List<SearchResult> results = searchService.search(42, "不存在的内容", 5, false);

        agentObservability.endTraceRoot(trace, "无结果", null);

        assertThat(results).isEmpty();
        SpanData ret = exporter.getSpans().stream()
                .filter(s -> s.getName().startsWith("Retriever: ")).findFirst().orElseThrow();
        assertThat(ret.getAttributes().get(AttributeKey.booleanKey(LangfuseAttributeKeys.METADATA_HIT))).isFalse();
        assertThat(ret.getAttributes().get(AttributeKey.longKey(LangfuseAttributeKeys.METADATA_AFTER_FILTER))).isEqualTo(0L);
        assertThat(ret.getAttributes().get(AttributeKey.longKey(LangfuseAttributeKeys.METADATA_VECTOR_CANDIDATES))).isEqualTo(0L);
    }

    @Test
    @DisplayName("无观测上下文（HTTP 直连）→ 检索不产生 retrieval span")
    void search_withoutObservabilityContext_shouldNotCreateSpan() {
        mockEmbedding();
        when(embeddingMapper.vectorSearch(any(), any(), anyInt())).thenReturn(List.of());

        List<SearchResult> results = searchService.search(42, "Java", 5, false);

        assertThat(results).isEmpty();
        assertThat(exporter.getSpans()).isEmpty();
    }

    private void mockEmbedding() {
        when(embeddingModel.embed(anyString()))
                .thenReturn(Response.from(new Embedding(new float[]{0.1f, 0.2f})));
    }

    private VectorSearchResult vectorResult(int chunkId, String fileName, double score) {
        return new VectorSearchResult(1, score, "text", "meta", chunkId, 1,
                fileName, "paragraph", "编程", "chunk text", null, null);
    }
}
