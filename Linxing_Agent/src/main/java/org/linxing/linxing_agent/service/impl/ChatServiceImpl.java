package org.linxing.linxing_agent.service.impl;

import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.input.Prompt;
import dev.langchain4j.model.input.PromptTemplate;
import dev.langchain4j.model.openai.OpenAiChatModel;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.linxing.linxing_agent.constant.CommonConstants;
import org.linxing.linxing_agent.constant.RagConstants;
import org.linxing.linxing_agent.config.RagProperties;
import org.linxing.linxing_agent.utils.VectorUtils;
import org.linxing.linxing_agent.dto.ChatRequest;
import org.linxing.linxing_agent.dto.ChatResponse;
import org.linxing.linxing_agent.entity.ActivityLog;
import org.linxing.linxing_agent.entity.VectorSearchResult;
import org.linxing.linxing_agent.mapper.ActivityLogMapper;
import org.linxing.linxing_agent.mapper.EmbeddingMapper;
import org.linxing.linxing_agent.service.IChatService;
import org.linxing.linxing_agent.utils.QueryRewriter;
import org.linxing.linxing_agent.utils.Reranker;
import org.springframework.stereotype.Service;

import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class ChatServiceImpl implements IChatService {

    private final EmbeddingModel embeddingModel;
    private final OpenAiChatModel chatLanguageModel;
    private final EmbeddingMapper embeddingMapper;
    private final ActivityLogMapper activityLogMapper;
    private final RagProperties ragProperties;
    private final QueryRewriter queryRewriter;
    private final Reranker reranker;

    @Override
    public ChatResponse chat(ChatRequest request) {
        Integer userId = resolveUserId(request);
        log.info("收到来自 [用户{}] 的问题: {}", userId, truncate(request.getQuestion(), 80));

        try {
            String originalQuery = request.getQuestion();

//            String optimizedQuery = queryRewriter.rewriteQuery(originalQuery);
//            log.info("[用户{}] 查询优化完成: '{}' -> '{}'", userId, originalQuery, optimizedQuery);

            Embedding queryEmbedding = embeddingModel.embed(originalQuery).content();
            String queryVectorString = VectorUtils.toArray(queryEmbedding.vector());

            int recallSize = ragProperties.getSearch().getRecallSize();

            List<VectorSearchResult> candidates = embeddingMapper.vectorSearch(userId, queryVectorString, recallSize);

            if (candidates.isEmpty()) {
                return ChatResponse.builder()
                        .answer("抱歉，在您的知识库中未找到与该问题相关的信息。")
                        .sources(List.of())
                        .sessionId(request.getSessionId())
                        .build();
            }

            int finalTopK = ragProperties.getSearch().getDefaultTopK();

            List<VectorSearchResult> results;
            if (reranker.isAvailable()) {
                log.debug("[用户{}] 开始Cross-Encoder细粒度重排序，候选数: {}, 目标TopK: {}", userId, candidates.size(), finalTopK);
                results = reranker.rerank(originalQuery, candidates, finalTopK);
            } else {
                results = candidates.stream().limit(finalTopK).collect(Collectors.toList());
            }

            String context = buildContext(results);
            List<String> sources = extractSources(results);

            Prompt prompt = buildPrompt(context, originalQuery);

            log.debug("[用户{}] 发送LLM请求，Prompt长度: {}字符", userId, prompt.text().length());
            long startTime = System.currentTimeMillis();

            String answer = chatLanguageModel.chat(prompt.text());

            long duration = System.currentTimeMillis() - startTime;
            log.debug("[用户{}] LLM响应完成，耗时: {}ms，引用 {} 个来源", userId, duration, sources.size());

            recordActivityLog(userId, sources.size());

            return ChatResponse.builder()
                    .answer(answer)
                    .sources(sources)
                    .sessionId(request.getSessionId())
                    .build();

        } catch (Exception e) {
            log.error("[用户{}] 处理请求时发生异常: {}", userId, e.getMessage(), e);
            return ChatResponse.builder()
                    .answer("抱歉，处理您的问题时出现了错误，请稍后重试。如果问题持续存在，请联系管理员。")
                    .sources(List.of())
                    .sessionId(request.getSessionId())
                    .build();
        }
    }

    private String buildContext(List<VectorSearchResult> results) {
        return results.stream()
                .map(r -> "【来源:" + r.fileName() +
                        (r.pageNumber() != null && r.pageNumber() > 0 ? " 第" + r.pageNumber() + "页" : "") +
                        "】\n" + r.chunkText())
                .collect(Collectors.joining("\n\n---\n\n"));
    }

    private List<String> extractSources(List<VectorSearchResult> results) {
        return results.stream()
                .map(r -> r.fileName() +
                        (r.pageNumber() != null && r.pageNumber() > 0 ? " (第" + r.pageNumber() + "页)" : ""))
                .distinct()
                .collect(Collectors.toList());
    }

    private Prompt buildPrompt(String context, String question) {
        PromptTemplate promptTemplate = PromptTemplate.from(RagConstants.SYSTEM_PROMPT);

        Map<String, Object> variables = new HashMap<>();
        variables.put("context", context);
        variables.put("question", question);

        return promptTemplate.apply(variables);
    }

    private void recordActivityLog(Integer userId, int sourceCount) {
        try {
            activityLogMapper.insert(ActivityLog.builder()
                    .userId(userId)
                    .actionType(RagConstants.ACTION_TYPE_QUERY)
                    .targetType(RagConstants.TARGET_TYPE_DOCUMENT)
                    .details("{\"sources\":" + sourceCount + "}")
                    .createdAt(OffsetDateTime.now())
                    .build());
        } catch (Exception e) {
            log.warn("记录活动日志失败（不影响主流程）: {}", e.getMessage());
        }
    }

    private Integer resolveUserId(ChatRequest request) {
        return request.getUserId() != null ? request.getUserId() : CommonConstants.DEFAULT_USER_ID;
    }

    private static String truncate(String text, int maxLen) {
        if (text == null) return "";
        return text.length() <= maxLen ? text : text.substring(0, maxLen) + "...";
    }
}
