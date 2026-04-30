package org.linxing.linxing_agent.service.impl;

import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.input.Prompt;
import dev.langchain4j.model.input.PromptTemplate;
import dev.langchain4j.model.openai.OpenAiChatModel;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.linxing.linxing_agent.constant.OperationType;
import org.linxing.linxing_agent.context.BaseContext;
import org.linxing.linxing_agent.constant.LlmType;
import org.linxing.linxing_agent.constant.RagParameters;
import org.linxing.linxing_agent.config.LlmManager;
import org.linxing.linxing_agent.config.RagProperties;
import org.linxing.linxing_agent.utils.VectorUtils;
import org.linxing.linxing_agent.dto.ChatRequest;
import org.linxing.linxing_agent.dto.ChatResponse;
import org.linxing.linxing_agent.entity.ActivityLog;
import org.linxing.linxing_agent.entity.Bm25SearchResult;
import org.linxing.linxing_agent.entity.VectorSearchResult;
import org.linxing.linxing_agent.mapper.ActivityLogMapper;
import org.linxing.linxing_agent.mapper.ChunkMapper;
import org.linxing.linxing_agent.mapper.EmbeddingMapper;
import org.linxing.linxing_agent.service.IChatService;
import org.linxing.linxing_agent.utils.KeywordExtractor;
import org.linxing.linxing_agent.utils.ReciprocalRankFusion;
import org.linxing.linxing_agent.utils.Reranker;
import org.springframework.stereotype.Service;

import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 对话服务实现：完整的 RAG 流程 —— 向量检索 + BM25 混合检索 → RRF 融合 → 重排序 → LLM 生成回答。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ChatServiceImpl implements IChatService {

    private final EmbeddingModel embeddingModel;
    private final LlmManager llmManager;
    private final EmbeddingMapper embeddingMapper;
    private final ChunkMapper chunkMapper;
    private final ActivityLogMapper activityLogMapper;
    private final RagProperties ragProperties;
    private final Reranker reranker;

    /**
     * 主对话入口：接收用户问题，执行检索增强生成（RAG）全流程并返回回答。
     */
    @Override
    public ChatResponse chat(ChatRequest request) {
        Integer userId = resolveUserId(request);
        log.info("收到来自 [用户{}] 的问题: {}", userId, truncate(request.getQuestion(), 80));

        try {
            String originalQuery = request.getQuestion();

            // 将用户问题转为向量嵌入
            Embedding queryEmbedding = embeddingModel.embed(originalQuery).content();
            String queryVectorString = VectorUtils.toArray(queryEmbedding.vector());

            int recallSize = ragProperties.getSearch().getRecallSize();

            // 向量相似度检索
            List<VectorSearchResult> vectorResults = embeddingMapper.vectorSearch(userId, queryVectorString, recallSize);

            List<VectorSearchResult> candidates;

            // 混合检索策略：向量 + BM25关键词
            if (ragProperties.getSearch().isHybridEnabled()) {
                // 提取关键词用于BM25检索
                String tsquery = KeywordExtractor.extractToTsquery(originalQuery);
                if (!tsquery.isEmpty()) {
                    int bm25RecallSize = ragProperties.getSearch().getBm25RecallSize();
                    // 执行BM25关键词检索
                    List<Bm25SearchResult> bm25Results = chunkMapper.bm25Search(userId, tsquery, bm25RecallSize);
                    log.debug("[用户{}] 混合检索: 向量候选={}, BM25候选={}", userId, vectorResults.size(), bm25Results.size());

                    // RRF融合两种检索结果
                    candidates = ReciprocalRankFusion.fuse(
                            vectorResults,
                            bm25Results,
                            ragProperties.getSearch().getVectorWeight(),
                            ragProperties.getSearch().getBm25Weight()
                    );
                    log.debug("[用户{}] RRF融合后候选数: {}", userId, candidates.size());
                } else {
                    log.debug("[用户{}] 未提取到有效关键词，仅使用向量检索", userId);
                    candidates = vectorResults;
                }
            } else {
                candidates = vectorResults;
            }

            // 无检索结果时直接返回兜底回答
            if (candidates.isEmpty()) {
                return ChatResponse.builder()
                        .answer("抱歉，在您的知识库中未找到与该问题相关的信息。")
                        .sources(List.of())
                        .sourceDetails(List.of())
                        .sessionId(request.getSessionId())
                        .build();
            }

            int finalTopK = ragProperties.getSearch().getDefaultTopK();

            // Cross-Encoder 重排序：有 reranker 时精排取 TopK，否则直接截断
            List<VectorSearchResult> results;
            if (reranker.isAvailable()) {
                log.debug("[用户{}] 开始Cross-Encoder细粒度重排序，候选数: {}, 目标TopK: {}", userId, candidates.size(), finalTopK);
                results = reranker.rerank(originalQuery, candidates, finalTopK);
            } else {
                results = candidates.stream().limit(finalTopK).collect(Collectors.toList());
            }

            // 将检索结果拼接为上下文、提取来源信息
            String context = buildContext(results);
            List<String> sources = extractSources(results);
            List<ChatResponse.SourceDetail> sourceDetails = extractSourceDetails(results);

            // 将上下文和问题填入 Prompt 模板
            Prompt prompt = buildPrompt(context, originalQuery);

            log.debug("[用户{}] 发送LLM请求，Prompt长度: {}字符", userId, prompt.text().length());
            long startTime = System.currentTimeMillis();

            OpenAiChatModel chatModel = llmManager.getModel(LlmType.CHAT_MODEL);

            // 调用 LLM 生成最终回答
            String answer = chatModel.chat(prompt.text());

            long duration = System.currentTimeMillis() - startTime;
            log.debug("[用户{}] LLM响应完成，耗时: {}ms，引用 {} 个来源", userId, duration, sources.size());

            recordActivityLog(userId, sources.size());

            return ChatResponse.builder()
                    .answer(answer)
                    .sources(sources)
                    .sourceDetails(sourceDetails)
                    .sessionId(request.getSessionId())
                    .build();

        } catch (Exception e) {
            log.error("[用户{}] 处理请求时发生异常: {}", userId, e.getMessage(), e);
            return ChatResponse.builder()
                    .answer("抱歉，处理您的问题时出现了错误，请稍后重试。如果问题持续存在，请联系管理员。")
                    .sources(List.of())
                    .sourceDetails(List.of())
                    .sessionId(request.getSessionId())
                    .build();
        }
    }

    // 将检索结果格式化为带来源标注的上下文字符串
    private String buildContext(List<VectorSearchResult> results) {
        return results.stream()
                .map(r -> "【来源:" + r.fileName() +
                        (r.titlePath() != null ? " > " + r.titlePath() : "") +
                        "】\n" + r.chunkText())
                .collect(Collectors.joining("\n\n---\n\n"));
    }

    // 提取去重后的来源列表（文件名 > 标题路径）
    private List<String> extractSources(List<VectorSearchResult> results) {
        return results.stream()
                .map(r -> r.fileName() +
                        (r.titlePath() != null ? " > " + r.titlePath() : ""))
                .distinct()
                .collect(Collectors.toList());
    }

    // 提取来源详情（含 chunkId、documentId 等，供前端展示）
    private List<ChatResponse.SourceDetail> extractSourceDetails(List<VectorSearchResult> results) {
        return results.stream()
                .map(r -> ChatResponse.SourceDetail.builder()
                        .chunkId(r.chunkId())
                        .documentId(r.documentId())
                        .fileName(r.fileName())
                        .titlePath(r.titlePath())
                        .chunkType(r.chunkType())
                        .build())
                .collect(Collectors.toList());
    }

    // 将上下文和问题填入 RAG 系统 Prompt 模板
    private Prompt buildPrompt(String context, String question) {
        PromptTemplate promptTemplate = PromptTemplate.from(RagParameters.SYSTEM_PROMPT);

        Map<String, Object> variables = new HashMap<>();
        variables.put("context", context);
        variables.put("question", question);

        return promptTemplate.apply(variables);
    }

    // 记录用户查询活动日志，失败不影响主流程
    private void recordActivityLog(Integer userId, int sourceCount) {
        try {
            activityLogMapper.insert(ActivityLog.builder()
                    .userId(userId)
                    .actionType(OperationType.ACTION_TYPE_QUERY)
                    .targetType(RagParameters.TARGET_TYPE_DOCUMENT)
                    .details("{\"sources\":" + sourceCount + "}")
                    .createdAt(OffsetDateTime.now())
                    .build());
        } catch (Exception e) {
            log.warn("记录活动日志失败（不影响主流程）: {}", e.getMessage());
        }
    }

    private Integer resolveUserId(ChatRequest request) {
        if (request.getUserId() != null) {
            return request.getUserId();
        }
        Long currentId = BaseContext.getCurrentId();
        if (currentId == null) {
            throw new IllegalStateException("用户未登录");
        }
        return currentId.intValue();
    }

    // 截断长文本，用于日志输出
    private static String truncate(String text, int maxLen) {
        if (text == null) return "";
        return text.length() <= maxLen ? text : text.substring(0, maxLen) + "...";
    }
}
