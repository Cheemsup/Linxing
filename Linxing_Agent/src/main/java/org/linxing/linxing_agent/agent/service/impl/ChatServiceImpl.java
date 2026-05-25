package org.linxing.linxing_agent.agent.service.impl;

import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.openai.OpenAiChatModel;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.linxing.linxing_agent.agent.core.AgentContext;
import org.linxing.linxing_agent.agent.core.AgentExecutor;
import org.linxing.linxing_agent.agent.core.AgentResult;
import org.linxing.linxing_agent.agent.memory.WindowMemory;
import org.linxing.linxing_agent.rag.constant.OperationType;
import org.linxing.linxing_agent.common.userInfoMaintainer.BaseContext;
import org.linxing.linxing_agent.common.constant.LlmType;
import org.linxing.linxing_agent.common.config.LlmManager;
import org.linxing.linxing_agent.agent.dto.ChatRequest;
import org.linxing.linxing_agent.agent.dto.ChatResponse;
import org.linxing.linxing_agent.agent.entity.ChatMessage;
import org.linxing.linxing_agent.rag.entity.ActivityLog;
import org.linxing.linxing_agent.rag.mapper.ActivityLogMapper;
import org.linxing.linxing_agent.agent.service.IChatService;
import org.springframework.stereotype.Service;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class ChatServiceImpl implements IChatService {
    private static final int MAX_HISTORY_ROUNDS = 10;

    private final EmbeddingModel embeddingModel;
    private final LlmManager llmManager;
    private final ActivityLogMapper activityLogMapper;
    private final ChatMessageService chatMessageService;
    private final ChatMessageCacheService chatMessageCacheService;
    private final SemanticCacheService semanticCacheService;
    private final SourceExtractor sourceExtractor;
    private final AgentExecutor agentExecutor;

    @Override
    public ChatResponse chat(ChatRequest request) {
        Integer userId = resolveUserId(request);//获取用户ID
        log.info("收到来自 [用户{}] 的问题: {}", userId, truncate(request.getQuestion(), 80));

        try {
            String originalQuery = request.getQuestion();

            //TODO：分析由此而下的代码逻辑能否正确、优雅处理新建对话与继续对话的情况
            Integer sessionId = chatMessageService.resolveSession(userId, request.getSessionId());//解析或创建会话

            ChatMessage userMsg = chatMessageService.saveUserMessage(
                    userId, sessionId, request.getParentMessageId(), originalQuery);//持久化用户消息

            List<ChatMessage> history = chatMessageService.backtrackHistory(userMsg.getId());//沿parentId链路回溯对话历史

            //向量化query，用于语义缓存匹配
            Embedding queryEmbedding = embeddingModel.embed(originalQuery).content();

            SemanticCacheService.CacheResult cacheResult =
                    semanticCacheService.lookup(userId, queryEmbedding.vector());//查询语义缓存

            if (cacheResult.isHit()) {//语义缓存命中，直接返回不经由LLM
                return buildCachedResponse(userId, sessionId, userMsg, cacheResult);
            }

            ChatResponse agentResponse = runAgentLoop(userId, sessionId, userMsg, history, originalQuery);//ReAct Agent循环

            //Redis写入对话消息缓存
            chatMessageCacheService.appendMessages(sessionId, List.of(
                    chatMessageService.toMessageVO(userMsg),
                    chatMessageService.toMessageVO(ChatMessage.builder()
                            .id(agentResponse.getMessageId())
                            .userId(userId)
                            .sessionId(sessionId)
                            .role("assistant")
                            .content(agentResponse.getAnswer())
                            .sources(agentResponse.getSources() != null
                                    ? agentResponse.getSources().toString() : "[]")
                            .build())
            ));

            chatMessageService.touchSession(sessionId);//更新会话的最近更新时间

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

    /**
     * 执行ReAct Agent循环，驱动LLM与工具调用直到获得最终回答
     * @param userId
     * @param sessionId
     * @param userMsg
     * @param history
     * @param originalQuery
     * @return
     */
    private ChatResponse runAgentLoop(Integer userId, Integer sessionId,
                                       ChatMessage userMsg, List<ChatMessage> history,
                                       String originalQuery) {
        WindowMemory memory = new WindowMemory(MAX_HISTORY_ROUNDS * 2 + 20);

        //将历史消息转为LangChain4j消息格式注入记忆
        for (ChatMessage msg : history) {
            if ("user".equals(msg.getRole())) {
                memory.add(UserMessage.from(msg.getContent()));
            } else if ("assistant".equals(msg.getRole())) {
                memory.add(AiMessage.from(msg.getContent()));
            }
        }

        memory.add(UserMessage.from(originalQuery));//注入当前用户问题

        AgentContext context = new AgentContext(userId, sessionId, memory, originalQuery);//构建Agent上下文

        OpenAiChatModel chatModel = llmManager.getModel(LlmType.CHAT_MODEL);//获取chat类型的LLM对象

        AgentResult result = agentExecutor.execute(context, chatModel);//执行Agent循环

        String sourcesJson = sourceExtractor.extractSourcesFromSteps(result.getSteps());//从Agent执行步骤中提取来源信息

        ChatMessage assistantMsg = chatMessageService.saveAssistantMessage(
                userId, sessionId, userMsg.getId(), result.getAnswer(), sourcesJson);//持久化助手消息

        return ChatResponse.builder()
                .answer(result.getAnswer())
                .sources(sourceExtractor.parseSourceList(sourcesJson))
                .sourceDetails(sourceExtractor.parseSourceDetails(sourcesJson))
                .sessionId(sessionId)
                .messageId(assistantMsg.getId())
                .build();
    }

    /**
     * 从语义缓存中构建响应
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

        List<ChatResponse.SourceDetail> cachedSourceDetails =
                sourceExtractor.parseSourceDetails(cached.getSources());//反序列化缓存的来源详情

        List<String> cachedSources = cachedSourceDetails.stream()
                .map(sd -> sd.getFileName()
                        + (sd.getTitlePath() != null ? " > " + sd.getTitlePath() : ""))
                .distinct()
                .collect(Collectors.toList());//拼接来源路径

        ChatMessage assistantMsg = chatMessageService.saveAssistantMessage(
                userId, sessionId, userMsg.getId(), cached.getAnswer(), cached.getSources());//持久化缓存中的助手消息

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

    private ChatResponse buildEmptyResponse(Integer sessionId, String answer) {
        return ChatResponse.builder()
                .answer(answer)
                .sources(List.of())
                .sourceDetails(List.of())
                .sessionId(sessionId)
                .build();
    }

    /**
     * 获取用户ID，优先从请求中取，否则从ThreadLocal上下文中取
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

    private String truncate(String text, int maxLen) {
        if (text == null) return "";
        return text.length() <= maxLen ? text : text.substring(0, maxLen) + "...";
    }
}
