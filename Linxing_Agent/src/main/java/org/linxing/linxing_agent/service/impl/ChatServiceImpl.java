package org.linxing.linxing_agent.service.impl;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import org.linxing.linxing_agent.entity.ChatMessage;
import org.linxing.linxing_agent.entity.ChatSession;
import org.linxing.linxing_agent.entity.Chunk;
import org.linxing.linxing_agent.entity.VectorSearchResult;
import org.linxing.linxing_agent.mapper.ActivityLogMapper;
import org.linxing.linxing_agent.mapper.ChatMessageMapper;
import org.linxing.linxing_agent.mapper.ChatSessionMapper;
import org.linxing.linxing_agent.mapper.ChunkMapper;
import org.linxing.linxing_agent.mapper.EmbeddingMapper;
import org.linxing.linxing_agent.service.IChatService;
import org.linxing.linxing_agent.utils.KeywordExtractor;
import org.linxing.linxing_agent.utils.ReciprocalRankFusion;
import org.linxing.linxing_agent.utils.Reranker;
import org.linxing.linxing_agent.vo.ChatMessageVO;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class ChatServiceImpl implements IChatService {
    private static final int MAX_HISTORY_ROUNDS = 10;
    private static final int MAX_TOKENS_ESTIMATE = 8000;
    private static final ObjectMapper objectMapper = new ObjectMapper();

    private final EmbeddingModel embeddingModel;
    private final LlmManager llmManager;
    private final EmbeddingMapper embeddingMapper;
    private final ChunkMapper chunkMapper;
    private final ActivityLogMapper activityLogMapper;
    private final ChatSessionMapper chatSessionMapper;
    private final ChatMessageMapper chatMessageMapper;
    private final RagProperties ragProperties;
    private final Reranker reranker;
    private final ChatMessageCacheService chatMessageCacheService;

    @Override
    public ChatResponse chat(ChatRequest request) {
        Integer userId = resolveUserId(request);
        log.info("收到来自 [用户{}] 的问题: {}", userId, truncate(request.getQuestion(), 80));

        try {
            String originalQuery = request.getQuestion();

            Integer sessionId = resolveSession(userId, request.getSessionId());

            Integer parentId = request.getParentMessageId();

            ChatMessage userMsg = ChatMessage.builder()
                    .userId(userId)
                    .sessionId(sessionId)
                    .parentId(parentId)
                    .role("user")
                    .content(originalQuery)
                    .sources("[]")
                    .build();
            chatMessageMapper.insert(userMsg);
            log.debug("[用户{}] 保存用户消息 id={}, sessionId={}, parentId={}",
                    userId, userMsg.getId(), userMsg.getSessionId(), userMsg.getParentId());

            List<ChatMessage> history = backtrackHistory(userMsg.getId());
            String historyText = buildHistoryContext(history);

            Embedding queryEmbedding = embeddingModel.embed(originalQuery).content();
            String queryVectorString = VectorUtils.toArray(queryEmbedding.vector());

            int recallSize = ragProperties.getSearch().getRecallSize();

            List<VectorSearchResult> vectorResults = embeddingMapper.vectorSearch(userId, queryVectorString, recallSize);

            List<VectorSearchResult> candidates;

            if (ragProperties.getSearch().isHybridEnabled()) {
                String tsquery = KeywordExtractor.extractToTsquery(originalQuery);
                if (!tsquery.isEmpty()) {
                    int bm25RecallSize = ragProperties.getSearch().getBm25RecallSize();
                    List<Bm25SearchResult> bm25Results = chunkMapper.bm25Search(userId, tsquery, bm25RecallSize);
                    log.debug("[用户{}] 混合检索: 向量候选={}, BM25候选={}", userId, vectorResults.size(), bm25Results.size());

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

            if (candidates.isEmpty()) {
                return buildEmptyResponse(sessionId, "抱歉，在您的知识库中未找到与该问题相关的信息。");
            }

            int finalTopK = ragProperties.getSearch().getDefaultTopK();

            List<VectorSearchResult> results;
            if (reranker.isAvailable()) {
                log.debug("[用户{}] 开始Cross-Encoder细粒度重排序，候选数: {}, 目标TopK: {}", userId, candidates.size(), finalTopK);
                results = reranker.rerank(originalQuery, candidates, finalTopK);
            } else {
                results = candidates.stream().limit(finalTopK).collect(Collectors.toList());
            }

            results = expandToParentChunks(results, userId);

            String context = buildContext(results);
            List<String> sources = extractSources(results);
            List<ChatResponse.SourceDetail> sourceDetails = extractSourceDetails(results);

            Prompt prompt = buildPrompt(historyText, context, originalQuery);

            log.debug("[用户{}] 发送LLM请求，Prompt长度: {}字符", userId, prompt.text().length());
            long startTime = System.currentTimeMillis();

            OpenAiChatModel chatModel = llmManager.getModel(LlmType.CHAT_MODEL);

            String answer = chatModel.chat(prompt.text());

            long duration = System.currentTimeMillis() - startTime;
            log.debug("[用户{}] LLM响应完成，耗时: {}ms，引用 {} 个来源", userId, duration, sources.size());

            String sourcesJson = toSourcesJson(sourceDetails);

            ChatMessage assistantMsg = ChatMessage.builder()
                    .userId(userId)
                    .sessionId(sessionId)
                    .parentId(userMsg.getId())
                    .role("assistant")
                    .content(answer)
                    .sources(sourcesJson)
                    .build();
            chatMessageMapper.insert(assistantMsg);

            chatMessageCacheService.appendMessages(sessionId, List.of(
                    toMessageVO(userMsg),
                    toMessageVO(assistantMsg)
            ));

            chatSessionMapper.updateUpdatedAt(sessionId);

            recordActivityLog(userId, sources.size());

            return ChatResponse.builder()
                    .answer(answer)
                    .sources(sources)
                    .sourceDetails(sourceDetails)
                    .sessionId(sessionId)
                    .messageId(assistantMsg.getId())
                    .build();

        } catch (Exception e) {
            log.error("[用户{}] 处理请求时发生异常: {}", userId, e.getMessage(), e);
            return buildEmptyResponse(request.getSessionId(),
                    "抱歉，处理您的问题时出现了错误，请稍后重试。如果问题持续存在，请联系管理员。");
        }
    }

    private ChatResponse buildEmptyResponse(Integer sessionId, String answer) {
        return ChatResponse.builder()
                .answer(answer)
                .sources(List.of())
                .sourceDetails(List.of())
                .sessionId(sessionId)
                .build();
    }

    private Integer resolveSession(Integer userId, Integer sessionId) {
        if (sessionId != null) {
            ChatSession session = chatSessionMapper.selectById(sessionId);
            if (session != null) {
                return sessionId;
            }
            log.debug("[用户{}] 会话 {} 不存在，将创建新会话", userId, sessionId);
        }
        ChatSession session = ChatSession.builder()
                .userId(userId)
                .title("新对话")
                .build();
        chatSessionMapper.insert(session);
        log.info("[用户{}] 自动创建新会话 id={}", userId, session.getId());
        return session.getId();
    }

    private List<ChatMessage> backtrackHistory(Integer currentUserMsgId) {
        ChatMessage currentMsg = chatMessageMapper.selectById(currentUserMsgId);
        if (currentMsg == null) {
            return List.of();
        }
        List<ChatMessage> allMessages = chatMessageMapper.selectBySessionId(currentMsg.getSessionId());
        List<ChatMessage> before = new ArrayList<>();
        for (ChatMessage msg : allMessages) {
            if (msg.getCreatedAt().isBefore(currentMsg.getCreatedAt())) {
                before.add(msg);
            }
        }
        int start = Math.max(0, before.size() - MAX_HISTORY_ROUNDS * 2);
        return before.subList(start, before.size());
    }

    private String buildHistoryContext(List<ChatMessage> history) {
        if (history.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder("历史对话：\n");
        int estimatedChars = 0;
        List<ChatMessage> truncated = new ArrayList<>();
        for (int i = history.size() - 1; i >= 0; i--) {
            ChatMessage msg = history.get(i);
            estimatedChars += msg.getContent().length();
            if (estimatedChars / 2 > MAX_TOKENS_ESTIMATE / 2) break;
            truncated.add(0, msg);
        }
        for (ChatMessage msg : truncated) {
            String prefix = "user".equals(msg.getRole()) ? "用户：" : "助手：";
            sb.append(prefix).append(msg.getContent()).append("\n\n");
        }
        return sb.toString();
    }

    private String toSourcesJson(List<ChatResponse.SourceDetail> sourceDetails) {
        try {
            return objectMapper.writeValueAsString(sourceDetails);
        } catch (JsonProcessingException e) {
            log.warn("序列化 sources 失败: {}", e.getMessage());
            return "[]";
        }
    }

    private List<VectorSearchResult> expandToParentChunks(List<VectorSearchResult> results, Integer userId) {
        List<Integer> parentIds = results.stream()
                .map(VectorSearchResult::parentChunkId)
                .filter(pid -> pid != null)
                .distinct()
                .toList();

        if (parentIds.isEmpty()) {
            log.debug("[用户{}] 无小块需要替换为大块", userId);
            return results;
        }

        Map<Integer, Chunk> parentMap = chunkMapper.findByIds(parentIds).stream()
                .collect(Collectors.toMap(Chunk::getId, c -> c));

        log.debug("[用户{}] Small-to-Big替换: 检索到{}个小块，涉及{}个大块", userId, results.size(), parentIds.size());

        LinkedHashMap<Integer, VectorSearchResult> expanded = new LinkedHashMap<>();
        for (VectorSearchResult r : results) {
            Integer parentId = r.parentChunkId();
            if (parentId != null && parentMap.containsKey(parentId)) {
                if (!expanded.containsKey(parentId)) {
                    Chunk parent = parentMap.get(parentId);
                    expanded.put(parentId, new VectorSearchResult(
                            r.id(),
                            r.score(),
                            r.text(),
                            r.metadata(),
                            parent.getId(),
                            parent.getDocumentId(),
                            r.fileName(),
                            parent.getChunkType() != null ? parent.getChunkType() : r.chunkType(),
                            parent.getTitlePath() != null ? parent.getTitlePath() : r.titlePath(),
                            parent.getChunkText(),
                            null
                    ));
                }
            } else {
                expanded.put(r.chunkId(), r);
            }
        }

        log.debug("[用户{}] Small-to-Big替换完成: {}条结果 → {}条（去重后）", userId, results.size(), expanded.size());
        return new ArrayList<>(expanded.values());
    }

    private String buildContext(List<VectorSearchResult> results) {
        return results.stream()
                .map(r -> "【来源:" + r.fileName() +
                        (r.titlePath() != null ? " > " + r.titlePath() : "") +
                        "】\n" + r.chunkText())
                .collect(Collectors.joining("\n\n---\n\n"));
    }

    private List<String> extractSources(List<VectorSearchResult> results) {
        return results.stream()
                .map(r -> r.fileName() +
                        (r.titlePath() != null ? " > " + r.titlePath() : ""))
                .distinct()
                .collect(Collectors.toList());
    }

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

    private Prompt buildPrompt(String historyText, String context, String question) {
        PromptTemplate promptTemplate = PromptTemplate.from(RagParameters.SYSTEM_PROMPT);

        Map<String, Object> variables = new HashMap<>();
        variables.put("history", historyText);
        variables.put("context", context);
        variables.put("question", question);

        return promptTemplate.apply(variables);
    }

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

    private static String truncate(String text, int maxLen) {
        if (text == null) return "";
        return text.length() <= maxLen ? text : text.substring(0, maxLen) + "...";
    }

    private ChatMessageVO toMessageVO(ChatMessage msg) {
        return ChatMessageVO.builder()
                .id(msg.getId())
                .userId(msg.getUserId())
                .sessionId(msg.getSessionId())
                .parentId(msg.getParentId())
                .role(msg.getRole())
                .content(msg.getContent())
                .sources(msg.getSources())
                .createdAt(msg.getCreatedAt())
                .build();
    }
}
