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
import org.linxing.linxing_agent.agent.entity.ChatMessage;
import org.linxing.linxing_agent.agent.memory.window.runtime.AgentMemory;
import org.linxing.linxing_agent.agent.memory.window.runtime.AgentMemoryFactory;
import org.linxing.linxing_agent.agent.memory.window.SummaryService;
import org.linxing.linxing_agent.agent.memory.window.projection.ProjectionPolicy;
import org.linxing.linxing_agent.agent.memory.window.recovery.HistoryRecoveryService;
import org.linxing.linxing_agent.agent.memory.window.recovery.RecoveredHistory;
import org.linxing.linxing_agent.agent.memory.window.builder.ContextAssembly;
import org.linxing.linxing_agent.agent.memory.window.builder.ContextBuilder;
import org.linxing.linxing_agent.agent.utils.SourceExtractor;
import org.linxing.linxing_agent.rag.constant.OperationType;
import org.linxing.linxing_agent.common.userInfoMaintainer.BaseContext;
import org.linxing.linxing_agent.common.constant.LlmType;
import org.linxing.linxing_agent.common.constant.MessageType;
import org.linxing.linxing_agent.common.config.LlmManager;
import org.linxing.linxing_agent.agent.dto.ChatRequest;
import org.linxing.linxing_agent.agent.dto.ChatResponse;
import org.linxing.linxing_agent.rag.entity.ActivityLog;
import org.linxing.linxing_agent.rag.mapper.ActivityLogMapper;
import org.linxing.linxing_agent.agent.mapper.AgentStepMapper;
import org.linxing.linxing_agent.agent.service.IRuntimeMirrorService;
import org.linxing.linxing_agent.agent.service.IChatMessageService;
import org.linxing.linxing_agent.agent.service.IChatService;
import org.linxing.linxing_agent.observability.AgentObservability;
import org.springframework.stereotype.Service;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class ChatServiceImpl implements IChatService {

    private final LlmManager llmManager;
    private final ActivityLogMapper activityLogMapper;
    private final IChatMessageService chatMessageService;
    private final IRuntimeMirrorService runtimeMirrorService;
    private final SourceExtractor sourceExtractor;
    private final AgentExecutor agentExecutor;
    private final ContextBuilder contextBuilder;
    private final AgentMemoryFactory memoryFactory;
    private final AgentStepMapper agentStepMapper;
    private final SummaryService summaryService;
    private final HistoryRecoveryService historyRecoveryService;
    private final AgentObservability agentObservability;

    /**
     * 核心对话入口：解析会话→保存用户消息→溯源历史→Agent循环→记录日志
     * @param request
     * @param listener
     * @return
     */
    @Override
    public ChatResponse chat(ChatRequest request, AgentStepListener listener) {
        Integer userId = resolveUserId(request);
        // 0816 起注释：请求级噪音日志
        // log.info("收到来自 [用户{}] 的问题: {}", userId, truncate(request.getQuestion(), 80));

        //0816 Langfuse：trace 根，声明在 try 外供 catch 收尾（endTraceRoot 对 null 安全）
        AgentObservability.TraceHandle trace = null;
        try {
            String originalQuery = request.getQuestion();

            Integer sessionId = chatMessageService.resolveSession(userId, request.getSessionId());//解析或创建会话

            trace = agentObservability.beginTraceRoot(userId, sessionId, request.getRequestId(), originalQuery);

            // 统一步骤记录器：主循环与工作流共享同一实例，保证 session 级 step_order 单调递增
            //TODO：考虑将StepRecorder改为单例模式使用
            StepRecorder recorder = new StepRecorder(listener, agentStepMapper, sessionId, runtimeMirrorService);

            RecoveredHistory recovered = historyRecoveryService.recoverHistory(request.getParentMessageId(), sessionId);
            if (recovered.getMessages().isEmpty() && request.getParentMessageId() != null) {
                // 溯源失败兜底：加载最近消息（旧路径，不含 tool 回放，仅回放文本）
                List<ChatMessage> fallback = historyRecoveryService.loadRecentMessages(sessionId);
                List<dev.langchain4j.data.message.ChatMessage> fallbackMsgs = new ArrayList<>();
                for (ChatMessage m : fallback) {
                    if (MessageType.USER.equals(m.getType())) {
                        fallbackMsgs.add(UserMessage.from(m.getContent()));
                    } else if (MessageType.ASSISTANT.equals(m.getType())) {
                        fallbackMsgs.add(AiMessage.from(m.getContent()));
                    }
                }
                recovered = RecoveredHistory.builder()
                        .messages(fallbackMsgs)
                        .summaryEntity(null)
                        .pathEndMessageId(null)
                        .turnBoundaries(List.of())// fallback 无 Turn 结构，Builder 退化为零投影
                        .build();
            }

            // 回答前主动 summary 判定：历史超 SUMMARY 阈值时压缩"上一个 Summary 挂点（或 Root）→路径末端"的历史，挂到路径末端。
            boolean summaryTriggered = false;// SUMMARY 是否实际落盘：触发则跳过本轮异步 Projection（旧历史已被摘要丢弃，再跑 rewrite/snip 产出的 rule 下一轮必失配，纯属白做）
            Integer userMsgParentId = request.getParentMessageId();// userMsg 的 parent，默认指向上一条消息；判定 SUMMARY 后改指 summary

            // 一次性 build：Builder 内部闭环完成 装配 + token 估算+ 策略判定 + 同步重建触发（MISS+投影区间）+ 异步 Projection 触发（CAS）
            ContextAssembly asm = contextBuilder.build(sessionId, recovered, userId, originalQuery);

            if (recovered.getPathEndMessageId() != null
                    && !recovered.getMessages().isEmpty()
                    && asm.getPolicy() == ProjectionPolicy.SUMMARY) {
                // SUMMARY 落盘（DB + LLM 双重副作用，留外部——Builder 守纯装配边界不染此）
                // 压缩范围："上一个 Summary 挂点（或 Root）→路径末端"的历史
                // 不变式：recovered.summaryEntity != null 时 messages[0] 为旧 summary 摘要
                ChatMessage summaryMsg = summaryService.summarizeAndPersist(
                        userId, sessionId, recovered.getPathEndMessageId(),
                        recovered.getMessages());
                if (summaryMsg != null) {
                    // userMsg 待落盘时 parent 指向新 summary；resolveNearestSummary(summaryId) 会识别 parent 为 summary
                    // 并预填 nearestSummaryMessageId=summaryId，一次写对，无需事后刷新/改挂
                    userMsgParentId = summaryMsg.getId();
                    summaryTriggered = true;// 标记本轮已 SUMMARY 压缩（日志用）
                    // SUMMARY 落盘后构造精简 recovered（仅 summary 摘要 + 零 Turn），二次 build 得最终装配产物
                    // 二次 build 时 policy 已非 SUMMARY（精简后 token 大降），异步 Projection 不会误触发
                    RecoveredHistory simplified = buildSimplifiedFromSummary(recovered, summaryMsg);
                    asm = contextBuilder.build(sessionId, simplified, userId, originalQuery);
                }
            }

            // userMsg 在 summary 落盘后持久化：parentId 指向 summary
            ChatMessage userMsg = chatMessageService.saveUserMessage(
                    userId, sessionId, userMsgParentId, originalQuery);

            if (summaryTriggered) {
                log.info("[Projection] sessionId={} skipped: SUMMARY 已压缩历史，本轮 Projection 无需触发", sessionId);
            }

            ChatResponse agentResponse = runAgentLoop(userId, sessionId, userMsg, asm.getMessages(),
                    originalQuery, listener, recorder);//执行ReAct Agent循环

            chatMessageService.touchSession(sessionId);//更新会话最近修改时间

            int sourceCount = agentResponse.getSourceDetails() != null
                    ? agentResponse.getSourceDetails().size() : 0;
            recordActivityLog(userId, sourceCount);//记录活动日志

            agentObservability.endTraceRoot(trace, agentResponse.getAnswer(), null);
            return agentResponse;

        } catch (Exception e) {
            agentObservability.endTraceRoot(trace, null, e);
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
     * @param initialMessages 已由 ContextBuilder.build() 装配的最终 messages（SystemMessage 首位 + 历史投影 + 当前用户问），
     *                         直接写入运行容器，循环内 Executor 只读不再回调 Builder 装配
     * @param originalQuery
     * @return
     */
    private ChatResponse runAgentLoop(Integer userId, Integer sessionId,
                                       ChatMessage userMsg,
                                       List<dev.langchain4j.data.message.ChatMessage> initialMessages,
                                       String originalQuery, AgentStepListener listener,
                                       StepRecorder recorder) {
        AgentMemory memory = memoryFactory.create();//运行容器初始为空

        //装配结果作为运行容器初始内容；Executor 循环内 add 的 aiMessage/resultMsg 自然追加在此容器末尾
        memory.addAll(initialMessages);

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

        // Runtime Mirror 写回：userMsg/assistantMsg 进 mirror:msgs；
        runtimeMirrorService.appendMessage(sessionId, userMsg);
        runtimeMirrorService.appendMessage(sessionId, assistantMsg);
        patchMirrorStepChatMessageIds(sessionId, assistantMsg.getId());

        // 长期记忆改写不再对话后自动触发（决策 7）：仅用户主动 HTTP 编辑 /
        // 用户在对话里显式让 Agent 调 write_memory 两条路径。cron 负责历史合并。

        return ChatResponse.builder()
                .answer(result.getAnswer())
                .sources(sourceExtractor.parseSourceList(sourcesJson))
                .sourceDetails(sourceExtractor.parseSourceDetails(sourcesJson))
                .sessionId(sessionId)
                .messageId(assistantMsg.getId())
                .build();
    }

    /**
     * SUMMARY 落盘后构造精简版 {@link RecoveredHistory}：仅含 summary 摘要（带压缩提示前缀）+ 零 Turn。
     * <p>Builder 的 {@link ContextBuilder#build} 收到零 Turn 的 recovered 会走零投影路径
     * （SystemMessage + 当前用户问），实现"压缩旧历史丢弃，仅 summary 摘要进 prompt"。
     * @param origin     原始完整 recovered（提供 pathEndMessageId 等）
     * @param summaryMsg 已落盘的 summary 消息（取其 content 作摘要正文）
     * @return 精简版 recovered，供二次 build
     */
    private RecoveredHistory buildSimplifiedFromSummary(RecoveredHistory origin, ChatMessage summaryMsg) {
        List<dev.langchain4j.data.message.ChatMessage> messagesForMemory = new ArrayList<>();
        messagesForMemory.add(UserMessage.from(
                "【历史内容经过压缩精简，如下是摘要】\n" + summaryMsg.getContent()));
        return RecoveredHistory.builder()
                .messages(messagesForMemory)
                .summaryEntity(summaryMsg)
                .pathEndMessageId(origin.getPathEndMessageId())
                .turnBoundaries(List.of())
                .build();
    }

    /**
     * 补丁重写 mirror:steps 中本 assistant 消息所属 step 的 chatMessageId
     * <p>
     * StepRecorder 插入时 step.chatMessageId 为 null（尚未回填），镜像字段也是 null。
     * {@code updateChatMessageId} 回填 DB 后，按 assistantMsgId 过滤 session 全量 steps，
     * 逐条 HPUT 重写镜像字段（幂等覆盖）。补丁在 runAgentLoop 同步、SSE final 前完成。
     * 降级：失败仅 log.warn，不影响主流程（下次 Recovery 会 cache-aside 重建镜像）。
     */
    private void patchMirrorStepChatMessageIds(Integer sessionId, Integer assistantMsgId) {
        try {
            List<org.linxing.linxing_agent.agent.entity.AgentStep> sessionSteps =
                    agentStepMapper.selectBySessionId(sessionId);
            for (org.linxing.linxing_agent.agent.entity.AgentStep s : sessionSteps) {
                if (assistantMsgId.equals(s.getChatMessageId())) {
                    runtimeMirrorService.appendStep(sessionId, s);
                }
            }
        } catch (Exception e) {
            log.warn("[Mirror] step chatMessageId 补丁失败, sessionId={}, assistantMsgId={}: {}",
                    sessionId, assistantMsgId, e.getMessage());
        }
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
