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
import org.linxing.linxing_agent.agent.memory.AgentMemory;
import org.linxing.linxing_agent.agent.memory.AgentMemoryFactory;
import org.linxing.linxing_agent.agent.memory.SummaryService;
import org.linxing.linxing_agent.agent.memory.TokenEstimator;
import org.linxing.linxing_agent.agent.memory.projection.ProjectionPolicy;
import org.linxing.linxing_agent.agent.memory.projection.ProjectionThresholds;
import org.linxing.linxing_agent.agent.memory.projection.snip.SnipLoopExecutor;
import org.linxing.linxing_agent.agent.memory.recovery.HistoryRecoveryService;
import org.linxing.linxing_agent.agent.memory.recovery.RecoveredHistory;
import org.linxing.linxing_agent.agent.utils.SourceExtractor;
import org.linxing.linxing_agent.rag.constant.OperationType;
import org.linxing.linxing_agent.common.userInfoMaintainer.BaseContext;
import org.linxing.linxing_agent.common.constant.LlmType;
import org.linxing.linxing_agent.common.config.LlmManager;
import org.linxing.linxing_agent.agent.dto.ChatRequest;
import org.linxing.linxing_agent.agent.dto.ChatResponse;
import org.linxing.linxing_agent.agent.mapper.ChatMessageMapper;
import org.linxing.linxing_agent.rag.entity.ActivityLog;
import org.linxing.linxing_agent.rag.mapper.ActivityLogMapper;
import org.linxing.linxing_agent.agent.mapper.AgentStepMapper;
import org.linxing.linxing_agent.agent.service.IRuntimeMirrorService;
import org.linxing.linxing_agent.agent.service.IChatMessageService;
import org.linxing.linxing_agent.agent.service.IChatService;
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
    private final AgentMemoryFactory memoryFactory;
    private final AgentStepMapper agentStepMapper;
    private final ChatMessageMapper chatMessageMapper;
    private final SummaryService summaryService;
    private final ProjectionThresholds projectionThresholds;
    private final TokenEstimator tokenEstimator;
    private final HistoryRecoveryService historyRecoveryService;
    private final SnipLoopExecutor snipLoopExecutor;

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
            StepRecorder recorder = new StepRecorder(listener, agentStepMapper, sessionId, runtimeMirrorService);

            ChatMessage userMsg = chatMessageService.saveUserMessage(
                    userId, sessionId, request.getParentMessageId(), originalQuery);//持久化用户消息

            // Recovery（thePlan P1-3，2-C 下沉至 HistoryRecoveryService）：含 summary 点查、tool 回放、token 预算截断
            // token 预算取 SUMMARY 阈值（90% max-context）以下，留缓冲；Recovery 内部还会按 tool 组保证不切断
            long recoverBudget = projectionThresholds.getMaxContextTokens()
                    - projectionThresholds.getSummaryMaxTokens();
            RecoveredHistory recovered = historyRecoveryService.recoverHistory(userMsg.getId(), recoverBudget);
            if (recovered.getMessages().isEmpty() && request.getParentMessageId() != null) {
                // 溯源失败兜底：加载最近消息（旧路径，不含 tool 回放，仅回放文本）
                List<ChatMessage> fallback = historyRecoveryService.loadRecentMessages(sessionId);
                List<dev.langchain4j.data.message.ChatMessage> fallbackMsgs = new ArrayList<>();
                for (ChatMessage m : fallback) {
                    if ("user".equals(m.getType())) {
                        fallbackMsgs.add(UserMessage.from(m.getContent()));
                    } else if ("assistant".equals(m.getType())) {
                        fallbackMsgs.add(AiMessage.from(m.getContent()));
                    }
                }
                recovered = RecoveredHistory.builder()
                        .messages(fallbackMsgs)
                        .pathEntities(fallback)
                        .summaryEntity(null)
                        .pathEndMessageId(null)
                        .turnBoundaries(List.of())// fallback 无 Turn 结构，Builder 退化为零投影
                        .build();
            }

            // 回答前主动 summary 判定（thePlan P1-2，含二次压缩）：历史超 SUMMARY 阈值时，
            // 压缩"上一个 Summary 挂点（或 Root）→路径末端"的历史，挂到路径末端，用户消息改挂 summary 节点。
            // 语义要点（用户 2026-07-17 定调）：
            //   - nearest_summary_message_id = "summary 之后的所有消息节点指向它之前的最近 summaryID"；
            //   - 二次压缩时无需修改任何已填好的 nearest_summary_message_id（旧指向不再被 Recovery 使用，
            //     Recovery 从当前用户消息回溯只会命中最新 summary）；
            //   - 故 successorIds 恒为 [userMsg.getId()]——仅本次新挂的用户消息需指向新 summary。
            // summary 生成后，被压缩的旧历史（含旧 summary 本身）不再进 memory，仅以新 summary 摘要作为前文上下文。
            RecoveredHistory recoveredForLoop = recovered;
            List<dev.langchain4j.data.message.ChatMessage> messagesForMemory = recovered.getMessages();
            long historyTokens = tokenEstimator.estimate(recovered.getMessages());
            if (recovered.getPathEndMessageId() != null
                    && !recovered.getMessages().isEmpty()) {
                if (projectionThresholds.policyFor(historyTokens) == ProjectionPolicy.SUMMARY) {
                    // 压缩范围：旧 summary 之后到路径末端（不含旧 summary 自身，它是前次压缩产物）；
                    // 首条 summary 时为 Root→路径末端 全量。
                    // 不变式：recovered.summaryEntity != null 时 messages[0] 为旧 summary 摘要（见 recoverHistory L193-205）
                    List<dev.langchain4j.data.message.ChatMessage> toSummarize =
                            recovered.getSummaryEntity() != null
                                    ? recovered.getMessages().subList(1, recovered.getMessages().size())
                                    : recovered.getMessages();
                    ChatMessage summaryMsg = summaryService.summarizeAndPersist(
                            userId, sessionId, recovered.getPathEndMessageId(),
                            toSummarize, List.of(userMsg.getId()));
                    if (summaryMsg != null) {
                        // 用户消息改挂 summary 节点；刷新其 nearest_summary_message_id 已由 summarizeAndPersist 完成
                        chatMessageMapper.updateParentId(userMsg.getId(), summaryMsg.getId());
                        // DB 已改 parentId，重查以同步内存对象，避免后续 toMessageVO 落 Redis 时 parentId 过时
                        ChatMessage refreshed = chatMessageMapper.selectById(userMsg.getId());
                        if (refreshed != null) {
                            userMsg = refreshed;
                            // P3 Mirror：updateParentId 只动 DB，mirror:msgs 的 userMsg 字段 parentId 已过时，重写补丁
                            runtimeMirrorService.appendMessage(sessionId, refreshed);
                        }
                        // memory 历史替换为仅 summary 摘要（被压缩旧历史丢弃），当前用户消息由 runAgentLoop 末尾追加；
                        // 原 Turn 结构已失效（被压缩），turnBoundaries 置空 → Builder 退化为零投影
                        messagesForMemory = new ArrayList<>();
                        messagesForMemory.add(UserMessage.from("【对话历史摘要】\n" + summaryMsg.getContent()));
                        recoveredForLoop = RecoveredHistory.builder()
                                .messages(messagesForMemory)
                                .pathEntities(recovered.getPathEntities())
                                .summaryEntity(summaryMsg)
                                .pathEndMessageId(recovered.getPathEndMessageId())
                                .turnBoundaries(List.of())
                                .build();
                    }
                }
            }

            // 2-E：Projection rule 异步产出（thePlan P2-2/P2-3）。历史进入 REWRITE_TOOL/SNIP_LOWVALUE 区间时，
            // 异步启动小循环产出 RewriteToolRule（纯规则）+ SkipTurnRule（LLM），攒进同一 batch 原子应用。
            // 主流程不等待、用上一版 RuleSet 继续（允许落后一轮）；SUMMARY 区间走上方同步 Summary，不触发。
            // per-session CAS 去重；小循环不落库、不推 SSE；异常丢弃 batch 不影响主流程。
            ProjectionPolicy policy = projectionThresholds.policyFor(historyTokens);
            if (snipLoopExecutor.shouldTrigger(policy) && snipLoopExecutor.tryStart(sessionId)) {
                snipLoopExecutor.executeAsync(sessionId, recovered);
            }

            ChatResponse agentResponse = runAgentLoop(userId, sessionId, userMsg, recoveredForLoop,
                    originalQuery, listener, recorder);//执行ReAct Agent循环

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
     * @param recovered 已由 Recovery 重建的历史（含 tool 回放 + turnBoundaries），直接填入 memory；
     *                  Builder 消费其 turnBoundaries 应用 Rule Set 投影（2-D 起）
     * @param originalQuery
     * @return
     */
    private ChatResponse runAgentLoop(Integer userId, Integer sessionId,
                                       ChatMessage userMsg,
                                       RecoveredHistory recovered,
                                       String originalQuery, AgentStepListener listener,
                                       StepRecorder recorder) {
        AgentMemory memory = memoryFactory.create();

        //将历史消息（含 tool 回放）填入Agent记忆——Recovery 已按"工具调用组"原子重建，无需再按 type 分支
        memory.addAll(recovered.getMessages());

        memory.add(UserMessage.from(originalQuery));//加入当前用户问题

        AgentContext context = new AgentContext(userId, sessionId, memory, originalQuery);
        context.setStepListener(listener);
        context.setStepRecorder(recorder);//注入统一步骤记录器，主循环与工作流共享
        context.setRecovered(recovered);//注入 Recovery 结果，供 ContextBuilder.buildMessages 应用 Rule Set 投影

        OpenAiStreamingChatModel chatModel = llmManager.getStreamingModel(LlmType.CHAT_MODEL);//获取流式LLM对象

        AgentResult result = agentExecutor.execute(context, chatModel, listener);//执行Agent循环

        String sourcesJson = sourceExtractor.extractSourcesFromSteps(result.getSteps());//提取工具调用来源并序列化为JSON

        ChatMessage assistantMsg = chatMessageService.saveAssistantMessage(
                userId, sessionId, userMsg.getId(), result.getAnswer(), sourcesJson);//持久化助手消息

        //回填 agent_steps 的 chat_message_id
        agentStepMapper.updateChatMessageId(sessionId, assistantMsg.getId());

        // P3 Runtime Mirror 写回：userMsg/assistantMsg 进 mirror:msgs；
        // 回填后的 steps（chatMessageId 已知）补丁重写进 mirror:steps（即时写时 chatMessageId 为 null）
        runtimeMirrorService.appendMessage(sessionId, userMsg);
        runtimeMirrorService.appendMessage(sessionId, assistantMsg);
        patchMirrorStepChatMessageIds(sessionId, assistantMsg.getId());

        return ChatResponse.builder()
                .answer(result.getAnswer())
                .sources(sourceExtractor.parseSourceList(sourcesJson))
                .sourceDetails(sourceExtractor.parseSourceDetails(sourcesJson))
                .sessionId(sessionId)
                .messageId(assistantMsg.getId())
                .build();
    }

    /**
     * 补丁重写 mirror:steps 中本 assistant 消息所属 step 的 chatMessageId（thePlan P3 决策：即时写 + 末尾补丁）。
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
