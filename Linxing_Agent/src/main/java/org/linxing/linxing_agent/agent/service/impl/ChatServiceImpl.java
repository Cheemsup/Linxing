package org.linxing.linxing_agent.agent.service.impl;

import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.openai.OpenAiStreamingChatModel;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.linxing.linxing_agent.agent.core.AgentContext;
import org.linxing.linxing_agent.agent.core.AgentExecutor;
import org.linxing.linxing_agent.agent.core.AgentResult;
import org.linxing.linxing_agent.agent.core.AgentStepListener;
import org.linxing.linxing_agent.agent.core.StepRecorder;
import org.linxing.linxing_agent.agent.memory.AgentMemory;
import org.linxing.linxing_agent.agent.memory.AgentMemoryFactory;
import org.linxing.linxing_agent.agent.utils.SourceExtractor;
import org.linxing.linxing_agent.rag.constant.OperationType;
import org.linxing.linxing_agent.common.userInfoMaintainer.BaseContext;
import org.linxing.linxing_agent.common.constant.LlmType;
import org.linxing.linxing_agent.common.config.LlmManager;
import org.linxing.linxing_agent.agent.dto.ChatRequest;
import org.linxing.linxing_agent.agent.dto.ChatResponse;
import org.linxing.linxing_agent.agent.entity.ChatMessage;
import org.linxing.linxing_agent.rag.entity.ActivityLog;
import org.linxing.linxing_agent.rag.mapper.ActivityLogMapper;
import org.linxing.linxing_agent.agent.mapper.AgentStepMapper;
import org.linxing.linxing_agent.agent.service.IChatMessageCacheService;
import org.linxing.linxing_agent.agent.service.IChatMessageService;
import org.linxing.linxing_agent.agent.service.IChatService;
import org.springframework.stereotype.Service;

import java.time.OffsetDateTime;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class ChatServiceImpl implements IChatService {

    private final LlmManager llmManager;
    private final ActivityLogMapper activityLogMapper;
    private final IChatMessageService chatMessageService;
    private final IChatMessageCacheService chatMessageCacheService;
    private final SourceExtractor sourceExtractor;
    private final AgentExecutor agentExecutor;
    private final AgentMemoryFactory memoryFactory;
    private final AgentStepMapper agentStepMapper;

    /**
     * 核心对话入口：解析会话→保存用户消息→溯源历史→Agent循环→记录日志
     * @param request
     * @param listener
     * @return
     */
    @Override
    public ChatResponse chat(ChatRequest request, AgentStepListener listener) {
        Integer userId = resolveUserId(request);
        log.info("收到来自 [用户{}] 的问题: {}", userId, truncate(request.getQuestion(), 80));

        try {
            String originalQuery = request.getQuestion();

            Integer sessionId = chatMessageService.resolveSession(userId, request.getSessionId());//解析或创建会话

            // 统一步骤记录器：主循环与工作流共享同一实例，保证 session 级 step_order 单调递增
            StepRecorder recorder = new StepRecorder(listener, agentStepMapper, sessionId);

            ChatMessage userMsg = chatMessageService.saveUserMessage(
                    userId, sessionId, request.getParentMessageId(), originalQuery);//持久化用户消息

            List<ChatMessage> history = chatMessageService.backtrackHistory(userMsg.getId());//溯源对话历史
            if (history.isEmpty() && request.getParentMessageId() != null && sessionId != null) {
                history = chatMessageService.loadRecentMessages(sessionId);//溯源失败时加载最近消息作为兜底
            }

            ChatResponse agentResponse = runAgentLoop(userId, sessionId, userMsg, history, originalQuery, listener, recorder);//执行ReAct Agent循环

            chatMessageService.touchSession(sessionId);//更新会话最近修改时间

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
                                       String originalQuery, AgentStepListener listener,
                                       StepRecorder recorder) {
        AgentMemory memory = memoryFactory.create();

        //将历史消息填入Agent记忆
        for (ChatMessage msg : history) {
            if ("user".equals(msg.getRole())) {
                memory.add(UserMessage.from(msg.getContent()));
            } else if ("assistant".equals(msg.getRole())) {
                memory.add(AiMessage.from(msg.getContent()));
            }
        }

        memory.add(UserMessage.from(originalQuery));//加入当前用户问题

        AgentContext context = new AgentContext(userId, sessionId, memory, originalQuery);
        context.setStepListener(listener);
        context.setStepRecorder(recorder);//注入统一步骤记录器，主循环与工作流共享

        OpenAiStreamingChatModel chatModel = llmManager.getStreamingModel(LlmType.CHAT_MODEL);//获取流式LLM对象

        AgentResult result = agentExecutor.execute(context, chatModel, listener);//执行Agent循环

        String sourcesJson = sourceExtractor.extractSourcesFromSteps(result.getSteps());//提取工具调用来源并序列化为JSON

        ChatMessage assistantMsg = chatMessageService.saveAssistantMessage(
                userId, sessionId, userMsg.getId(), result.getAnswer(), sourcesJson);//持久化助手消息

        //回填 agent_steps 的 chat_message_id
        agentStepMapper.updateChatMessageId(sessionId, assistantMsg.getId());

        chatMessageCacheService.appendMessages(sessionId, List.of(
                chatMessageService.toMessageVO(userMsg),
                chatMessageService.toMessageVO(assistantMsg)
        ));//追加消息到Redis缓存

        return ChatResponse.builder()
                .answer(result.getAnswer())
                .sources(sourceExtractor.parseSourceList(sourcesJson))
                .sourceDetails(sourceExtractor.parseSourceDetails(sourcesJson))
                .sessionId(sessionId)
                .messageId(assistantMsg.getId())
                .build();
    }

    /**
     * 构建无来源的空响应，用于异常等场景
     * @param sessionId
     * @param answer
     * @return
     */
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
