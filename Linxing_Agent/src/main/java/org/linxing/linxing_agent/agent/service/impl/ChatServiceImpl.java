package org.linxing.linxing_agent.agent.service.impl;

import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.input.Prompt;
import dev.langchain4j.model.input.PromptTemplate;
import dev.langchain4j.model.openai.OpenAiChatModel;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.linxing.linxing_agent.agent.core.AgentContext;
import org.linxing.linxing_agent.agent.core.AgentExecutor;
import org.linxing.linxing_agent.agent.core.AgentResult;
import org.linxing.linxing_agent.agent.memory.WindowMemory;
import org.linxing.linxing_agent.agent.tool.ToolRegistry;
import org.linxing.linxing_agent.rag.constant.OperationType;
import org.linxing.linxing_agent.common.userInfoMaintainer.BaseContext;
import org.linxing.linxing_agent.common.constant.LlmType;
import org.linxing.linxing_agent.rag.constant.RagParameters;
import org.linxing.linxing_agent.common.config.LlmManager;
import org.linxing.linxing_agent.agent.dto.ChatRequest;
import org.linxing.linxing_agent.agent.dto.ChatResponse;
import org.linxing.linxing_agent.rag.dto.SearchResult;
import org.linxing.linxing_agent.rag.entity.ActivityLog;
import org.linxing.linxing_agent.agent.entity.ChatMessage;
import org.linxing.linxing_agent.agent.entity.ChatSession;
import org.linxing.linxing_agent.rag.mapper.ActivityLogMapper;
import org.linxing.linxing_agent.agent.mapper.ChatMessageMapper;
import org.linxing.linxing_agent.agent.mapper.ChatSessionMapper;
import org.linxing.linxing_agent.agent.service.IChatService;
import org.linxing.linxing_agent.rag.service.ISearchService;
import org.linxing.linxing_agent.agent.vo.ChatMessageVO;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class ChatServiceImpl implements IChatService {
    private static final int MAX_HISTORY_ROUNDS = 10;
    private static final int MAX_TOKENS_ESTIMATE = 8000;//TODO：搞清楚这个变量的含义和作用，决定如何处置
    private static final ObjectMapper objectMapper = new ObjectMapper();

    private final EmbeddingModel embeddingModel;
    private final LlmManager llmManager;
    private final ISearchService searchService;
    private final ActivityLogMapper activityLogMapper;
    private final ChatSessionMapper chatSessionMapper;
    private final ChatMessageMapper chatMessageMapper;
    private final ChatMessageCacheService chatMessageCacheService;
    private final SemanticCacheService semanticCacheService;
    private final AgentExecutor agentExecutor;
    private final ToolRegistry toolRegistry;

    @Override
    public ChatResponse chat(ChatRequest request) {
        Integer userId = resolveUserId(request);//获取用户ID
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

            List<ChatMessage> history = backtrackHistory(userMsg.getId());//溯源对话历史，得到List
            String historyText = buildHistoryContext(history);//将List内容拼接为string，后续向LLM发送

            //向量化query
            Embedding queryEmbedding = embeddingModel.embed(originalQuery).content();

            SemanticCacheService.CacheResult cacheResult =
                    semanticCacheService.lookup(userId, queryEmbedding.vector());

            if (cacheResult.isHit()) {//命中缓存，直接返回，不经由LLM
                return buildCachedResponse(userId, sessionId, userMsg, cacheResult);
            }

            ChatResponse agentResponse;
            if (!toolRegistry.isEmpty()) {//已有工具注册，走ReAct路径
                agentResponse = runAgentLoop(userId, sessionId, userMsg, history, originalQuery);
            } else {//无工具注册，走单次RAG搜索的chat路径
                agentResponse = runLegacyRagChat(userId, sessionId, userMsg,
                        historyText, originalQuery, queryEmbedding);
            }

            //redis写入对话消息缓存
            chatMessageCacheService.appendMessages(sessionId, List.of(
                    toMessageVO(userMsg),
                    toMessageVO(ChatMessage.builder()
                            .id(agentResponse.getMessageId())
                            .userId(userId)
                            .sessionId(sessionId)
                            .role("assistant")
                            .content(agentResponse.getAnswer())
                            .sources(agentResponse.getSources() != null
                                    ? agentResponse.getSources().toString() : "[]")
                            .build())
            ));

            chatSessionMapper.updateUpdatedAt(sessionId);//更新这个session的“最近被更新时间”

            int sourceCount = agentResponse.getSourceDetails() != null
                    ? agentResponse.getSourceDetails().size() : 0;
            recordActivityLog(userId, sourceCount);//记录活动日志

            return agentResponse;

        } catch (Exception e) {
            log.error("[用户{}] 处理请求时发生异常: {}", userId, e.getMessage(), e);
            return buildEmptyResponse(request.getSessionId(),
                    "抱歉，处理您的问题时出现了错误，请稍后重试。如果问题持续存在，请联系管理员。");
        }
    }

    private ChatResponse runAgentLoop(Integer userId, Integer sessionId,
                                       ChatMessage userMsg, List<ChatMessage> history,
                                       String originalQuery) {
        WindowMemory memory = new WindowMemory(MAX_HISTORY_ROUNDS * 2 + 20);

        for (ChatMessage msg : history) {
            if ("user".equals(msg.getRole())) {
                memory.add(UserMessage.from(msg.getContent()));
            } else if ("assistant".equals(msg.getRole())) {
                memory.add(AiMessage.from(msg.getContent()));
            }
        }

        memory.add(UserMessage.from(originalQuery));

        AgentContext context = new AgentContext(userId, sessionId, memory);

        OpenAiChatModel chatModel = llmManager.getModel(LlmType.CHAT_MODEL);//获取chat类型的LLM对象

        AgentResult result = agentExecutor.execute(context, chatModel);

        String sourcesJson = extractSourcesFromSteps(result);//获取循环结束后结果中tool调用的来源并序列化为JSON

        ChatMessage assistantMsg = ChatMessage.builder()
                .userId(userId)
                .sessionId(sessionId)
                .parentId(userMsg.getId())
                .role("assistant")
                .content(result.getAnswer())
                .sources(sourcesJson)
                .build();
        chatMessageMapper.insert(assistantMsg);

        return ChatResponse.builder()
                .answer(result.getAnswer())
                .sources(parseSourceList(sourcesJson))
                .sourceDetails(parseSourceDetails(sourcesJson))
                .sessionId(sessionId)
                .messageId(assistantMsg.getId())
                .build();
    }

    private String extractSourcesFromSteps(AgentResult result) {
        if (result.getSteps() == null || result.getSteps().isEmpty()) {
            return "[]";
        }
        for (int i = result.getSteps().size() - 1; i >= 0; i--) {
            var step = result.getSteps().get(i);
            if ("tool_result".equals(step.getStepType()) && step.getContent() != null) {
                try {
                    List<SearchResult> searchResults = objectMapper.readValue(
                            step.getContent(),
                            objectMapper.getTypeFactory().constructCollectionType(
                                    List.class, SearchResult.class));
                    return toSourcesJson(extractSourceDetails(searchResults));
                } catch (Exception e) {
                    log.debug("解析工具结果中的sources失败: {}", e.getMessage());
                }
            }
        }
        return "[]";
    }

    /**
     * 从Redis缓存的对象中反序列化内容、拼接参考内容路径等，返回最终结果
     * @param userId
     * @param sessionId
     * @param userMsg
     * @param cacheResult
     * @return
     */
    private ChatResponse buildCachedResponse(Integer userId, Integer sessionId,
                                              ChatMessage userMsg,
                                              SemanticCacheService.CacheResult cacheResult) {
        SemanticCacheService.CacheEntry cached = cacheResult.getEntry();

        List<ChatResponse.SourceDetail> cachedSourceDetails = List.of();
        try {
            cachedSourceDetails = objectMapper.readValue(
                    cached.getSources(),
                    objectMapper.getTypeFactory().constructCollectionType(
                            List.class, ChatResponse.SourceDetail.class));
        } catch (Exception e) {
            log.warn("[语义缓存] 反序列化sources失败: {}", e.getMessage());
        }

        List<String> cachedSources = cachedSourceDetails.stream()
                .map(sd -> sd.getFileName()
                        + (sd.getTitlePath() != null ? " > " + sd.getTitlePath() : ""))
                .distinct()
                .collect(Collectors.toList());

        ChatMessage assistantMsg = ChatMessage.builder()
                .userId(userId)
                .sessionId(sessionId)
                .parentId(userMsg.getId())
                .role("assistant")
                .content(cached.getAnswer())
                .sources(cached.getSources())
                .build();
        chatMessageMapper.insert(assistantMsg);

        recordActivityLog(userId, cachedSourceDetails.size());

        log.info("[用户{}] 语义缓存命中，跳过Agent流程，score={}", userId, cacheResult.getScore());

        return ChatResponse.builder()
                .answer(cached.getAnswer())
                .sources(cachedSources)
                .sourceDetails(cachedSourceDetails)
                .sessionId(sessionId)
                .messageId(assistantMsg.getId())
                .build();
    }

    /**
     * 发起包含RAG查询的单词chat请求，返回结果
     * @param userId
     * @param sessionId
     * @param userMsg
     * @param historyText
     * @param originalQuery
     * @param queryEmbedding
     * @return
     */
    private ChatResponse runLegacyRagChat(Integer userId, Integer sessionId,
                                           ChatMessage userMsg, String historyText,
                                           String originalQuery,
                                           Embedding queryEmbedding) {
        List<SearchResult> results = searchService.search(userId, originalQuery, 0, true);

        if (results.isEmpty()) {
            return buildEmptyResponse(sessionId,
                    "抱歉，在您的知识库中未找到与该问题相关的信息。");
        }

        String context = buildContext(results);//构建RAG搜索结果来源
        List<String> sources = extractSources(results);//构建chunk在文件中的分级路径
        List<ChatResponse.SourceDetail> sourceDetails = extractSourceDetails(results);

        Prompt prompt = buildPrompt(historyText, context, originalQuery);

        log.debug("[用户{}] 发送LLM请求，Prompt长度: {}字符", userId, prompt.text().length());
        long startTime = System.currentTimeMillis();

        OpenAiChatModel chatModel = llmManager.getModel(LlmType.CHAT_MODEL);

        String answer = chatModel.chat(prompt.text());//发起chat

        long duration = System.currentTimeMillis() - startTime;
        log.debug("[用户{}] LLM响应完成，耗时: {}ms，引用 {} 个来源",
                userId, duration, sources.size());

        String sourcesJson = toSourcesJson(sourceDetails);//序列化detail信息为JSON格式

        ChatMessage assistantMsg = ChatMessage.builder()
                .userId(userId)
                .sessionId(sessionId)
                .parentId(userMsg.getId())
                .role("assistant")
                .content(answer)
                .sources(sourcesJson)
                .build();
        chatMessageMapper.insert(assistantMsg);

        //写入缓存
        semanticCacheService.store(userId, queryEmbedding.vector(),
                originalQuery, answer, sourcesJson);

        return ChatResponse.builder()
                .answer(answer)
                .sources(sources)
                .sourceDetails(sourceDetails)
                .sessionId(sessionId)
                .messageId(assistantMsg.getId())
                .build();
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

    /**
     * 溯源对话历史
     * TODO：验证溯源逻辑的正确性，只应该溯源这一条对话链路上的历史而非整个session ID树的历史
     * @param currentUserMsgId
     * @return
     */
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

    /**
     * 将对话历史的内容由List变为String
     * TODO：考虑将这部分代码与backtrackHistory()代码合并为一个函数
     * @param history
     * @return
     */
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

    /**
     * 序列化sources
     * @param sourceDetails
     * @return
     */
    private String toSourcesJson(List<ChatResponse.SourceDetail> sourceDetails) {
        try {
            return objectMapper.writeValueAsString(sourceDetails);
        } catch (Exception e) {
            log.warn("序列化 sources 失败: {}", e.getMessage());
            return "[]";
        }
    }

    /**
     * 将RAG搜索结果与chunk的文件来源、具体路径来源拼接
     * @param results
     * @return
     */
    private String buildContext(List<SearchResult> results) {
        return results.stream()
                .map(r -> "【来源:" + r.getFileName()
                        + (r.getTitlePath() != null ? " > " + r.getTitlePath() : "")
                        + "】\n" + r.getChunkText())
                .collect(Collectors.joining("\n\n---\n\n"));
    }

    /**
     * 获取chunk的来源路径
     * TODO：考虑是否和buildContext()函数发生了功能重复，是否需要优化
     * @param results
     * @return
     */
    private List<String> extractSources(List<SearchResult> results) {
        return results.stream()
                .map(r -> r.getFileName()
                        + (r.getTitlePath() != null ? " > " + r.getTitlePath() : ""))
                .distinct()
                .collect(Collectors.toList());
    }

    /**
     * 提取这个chunk的一些详细信息
     * @param results
     * @return
     */
    private List<ChatResponse.SourceDetail> extractSourceDetails(List<SearchResult> results) {
        return results.stream()
                .map(r -> ChatResponse.SourceDetail.builder()
                        .chunkId(r.getChunkId())
                        .documentId(r.getDocumentId())
                        .fileName(r.getFileName())
                        .titlePath(r.getTitlePath())
                        .chunkType(r.getChunkType())
                        .build())
                .collect(Collectors.toList());
    }

    private List<String> parseSourceList(String sourcesJson) {
        try {
            List<ChatResponse.SourceDetail> details = objectMapper.readValue(
                    sourcesJson,
                    objectMapper.getTypeFactory().constructCollectionType(
                            List.class, ChatResponse.SourceDetail.class));
            return details.stream()
                    .map(sd -> sd.getFileName()
                            + (sd.getTitlePath() != null ? " > " + sd.getTitlePath() : ""))
                    .distinct()
                    .collect(Collectors.toList());
        } catch (Exception e) {
            return List.of();
        }
    }

    private List<ChatResponse.SourceDetail> parseSourceDetails(String sourcesJson) {
        try {
            return objectMapper.readValue(
                    sourcesJson,
                    objectMapper.getTypeFactory().constructCollectionType(
                            List.class, ChatResponse.SourceDetail.class));
        } catch (Exception e) {
            return List.of();
        }
    }

    private Prompt buildPrompt(String historyText, String context, String question) {
        PromptTemplate promptTemplate = PromptTemplate.from(RagParameters.SYSTEM_PROMPT);

        Map<String, Object> variables = new HashMap<>();
        variables.put("history", historyText);
        variables.put("context", context);
        variables.put("question", question);

        return promptTemplate.apply(variables);
    }

    /**
     * 获取用户ID
     * @param request
     * @return
     */
    private Integer resolveUserId(ChatRequest request) {
        if (request.getUserId() != null) {
            return request.getUserId();
        }
        Long userId = BaseContext.getCurrentId();
        if (userId == null) {
            throw new IllegalStateException("用户未登录");
        }
        return userId.intValue();
    }

    /**
     * 记录活动日志
     * @param userId
     * @param resultCount
     */
    private void recordActivityLog(Integer userId, int resultCount) {
        try {
            ActivityLog logEntry = new ActivityLog();
            logEntry.setUserId(userId);
            logEntry.setActionType(OperationType.ACTION_TYPE_QUERY);
            logEntry.setTargetType(null);
            logEntry.setDetails("{\"resultCount\": " + resultCount + "}");
            logEntry.setCreatedAt(OffsetDateTime.now());
            activityLogMapper.insert(logEntry);
        } catch (Exception e) {
            log.warn("记录活动日志失败: {}", e.getMessage());
        }
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

    private String truncate(String text, int maxLen) {
        if (text == null) return "";
        return text.length() <= maxLen ? text : text.substring(0, maxLen) + "...";
    }
}
