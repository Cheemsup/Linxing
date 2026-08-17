package org.linxing.linxing_agent.agent.core;

import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.exception.RetriableException;
import dev.langchain4j.exception.UnresolvedModelServerException;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.openai.OpenAiStreamingChatModel;
import lombok.extern.slf4j.Slf4j;
import org.linxing.linxing_agent.agent.memory.window.builder.ContextBuilder;
import org.linxing.linxing_agent.agent.tool.ToolCallRequest;
import org.linxing.linxing_agent.agent.tool.ToolCallResult;
import org.linxing.linxing_agent.agent.tool.ToolRegistry;
import org.linxing.linxing_agent.agent.tool.ToolSpec;
import org.linxing.linxing_agent.common.RetryMetrics;
import org.linxing.linxing_agent.common.config.LlmProperties;
import org.linxing.linxing_agent.observability.AgentObservability;
import org.linxing.linxing_agent.observability.ObservableContext;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.http.HttpTimeoutException;
import java.nio.channels.UnresolvedAddressException;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.TimeUnit;

@Slf4j
@Component
public class AgentExecutor {

    /**
     * 大模型最大调用轮次，注意并不等同于step
     */
    private static final int MAX_STEPS = 20;

    /**
     * 普通工具执行超时秒数，默认3分钟
     */
    @Value("${agent.tool.timeout-seconds:180}")
    private int toolTimeoutSeconds;

    /**
     * 工作流类工具执行超时秒数，默认10分钟
     * 工作流类工具内部编排多个子Agent，耗时较长，单独放宽
     */
    @Value("${agent.tool.workflow-timeout-seconds:600}")
    private int workflowToolTimeoutSeconds;

    /**
     * 工作流类工具名集合：这些工具内部编排多Agent工作流，耗时较长，使用更宽松的超时
     */
    private static final Set<String> WORKFLOW_TOOL_NAMES = Set.of("start_study_plan_workflow");

    private final ToolRegistry toolRegistry;
    private final ToolExecutionTimeout toolExecutionTimeout;
    private final ContextBuilder contextBuilder;
    private final LlmProperties llmProperties;
    private final RetryMetrics retryMetrics;
    private final AgentObservability agentObservability;

    public AgentExecutor(ToolRegistry toolRegistry,
                         ToolExecutionTimeout toolExecutionTimeout,
                         ContextBuilder contextBuilder,
                         LlmProperties llmProperties,
                         RetryMetrics retryMetrics,
                         AgentObservability agentObservability) {
        this.toolRegistry = toolRegistry;
        this.toolExecutionTimeout = toolExecutionTimeout;
        this.contextBuilder = contextBuilder;
        this.llmProperties = llmProperties;
        this.retryMetrics = retryMetrics;
        this.agentObservability = agentObservability;
    }

    /**
     * ReAct Agent核心执行循环：LLM推理→工具调用→结果注入→下一轮，直到获得最终回答或超过最大步数
     * @param context
     * @param chatModel
     * @param listener
     * @return
     */
    public AgentResult execute(AgentContext context, OpenAiStreamingChatModel chatModel, AgentStepListener listener) {
        // 从 context 取统一步骤记录器：主循环与工作流共享同一实例，step_order 单调递增
        StepRecorder recorder = context.getStepRecorder();
        int sessionId = context.getSessionId();

        //progressiveMode 判定、工具规格拼装与动态激活维护已全部内化到 contextBuilder
        int stepNumber = 0;

        //主循环：LLM推理 → 工具调用 → 结果注入 → 下一轮
        //注（0723 改造）：不再在 finally 调 clearSession——激活集跨同 session 多次 chat 复用，
        //循环结束清掉会破坏跨 chat 复用；改由 Builder 的 Caffeine TTL 兜底回收
        while (stepNumber < MAX_STEPS) {
            stepNumber++;

            //推送 thinking 空占位 SSE（前端"思考中"状态），不入库不消耗 order
            //thinking 的 DB 持久化在 LLM 完成后由 recorder.recordThinkingContent 处理
            listener.onStep(AgentStepEvent.builder()
                    .eventType(AgentStepTypes.THINKING)
                    .stepNumber(stepNumber)
                    .phase(AgentStepTypes.PHASE_THINKING)
                    .build());

            List<ToolSpecification> roundSpecs = contextBuilder.buildRoundToolSpecs(sessionId);//渐进模式下追加已激活的工具规格

            List<ChatMessage> currentMessages = context.getMemory().messages();//装配已在对话开始一次性完成（Builder→AgentContext.memory），循环内只读不再回调 Builder
            ChatRequest chatRequest = ChatRequest.builder()
                    .messages(currentMessages)
                    .toolSpecifications(roundSpecs)
                    .build();

            //调用流式LLM并等待完整响应（0814 重试机制：瞬态失败且未 emit 时循环内单轮重试）
            StreamingResponseFuture future;
            ObservableContext.setCurrentStep(stepNumber);//0816 Langfuse：标记主循环当前 step，listener 据此写 generation span 的 step_number
            try {
                future = invokeWithRetry(chatModel, chatRequest, listener, stepNumber);
            } catch (Exception e) {
                log.error("[AgentExecutor] LLM调用失败: {}", e.getMessage(), e);
                recorder.record(AgentStepEvent.builder()
                        .eventType(AgentStepTypes.ERROR)
                        .stepNumber(stepNumber)
                        .phase(AgentStepTypes.PHASE_THINKING)
                        .label("思考失败")
                        .error("LLM调用失败: " + e.getMessage())
                        .stepData(Map.of(AgentStepTypes.KEY_ERROR_CODE, AgentStepTypes.ERR_LLM_CALL_FAILED))
                        .finalStep(true)
                        .build());

                return AgentResult.builder()
                        .answer("抱歉，处理您的问题时出现了错误，请稍后重试。")
                        .sourcesJson("[]")
                        .steps(recorder.getRecordedSteps())
                        .totalSteps(stepNumber)
                        .build();
            } finally {
                ObservableContext.clearCurrentStep();
            }

            ChatResponse response = future.getResponse();

            AiMessage aiMessage = response.aiMessage();

            log.debug("[DEBUG] 步骤{} hasTool={}", stepNumber, aiMessage.hasToolExecutionRequests());

            //持久化推理/思考内容到agent_steps（仅当LLM产生了thinking token时）
            //thinking 步骤 SSE（循环开头空占位 + 流式token通道）与 DB（完整文本）语义分离，
            //用 recorder.recordThinkingContent 仅做 DB 持久化 + VO 累积，不推 SSE（避免重复）。
            if (future.hasThinkingContent()) {
                String thinkingText = future.getThinkingContent();
                recorder.recordThinkingContent(
                        truncate(thinkingText, 8000),
                        Map.of("thinking_tokens", thinkingText.length()),
                        "思考中");
            }

            if (aiMessage.hasToolExecutionRequests()) {
                List<ToolExecutionRequest> toolRequests = aiMessage.toolExecutionRequests();

                context.getMemory().add(aiMessage);//将LLM的工具调用消息加入记忆，供后续轮次参考

                for (ToolExecutionRequest toolReq : toolRequests) {

                    //查找并执行工具，未知工具返回失败
                    ToolSpec toolSpec = toolRegistry.getTool(toolReq.name());
                    String toolLabel = toolSpec != null ? toolSpec.getExecutor().displayLabel() : toolReq.name();

                    // 0724 改进三：tool_kind 分类——在统一 tool 入口区分 function_calling / skill / workflow / mcp
                    // resolve 是技能/工具解析入口→skill；workflow 工具→workflow；其余→function_calling；mcp 预留
                    boolean isWorkflowTool = WORKFLOW_TOOL_NAMES.contains(toolReq.name());
                    String toolKind = isWorkflowTool
                            ? AgentStepTypes.TOOL_KIND_WORKFLOW
                            : ("resolve".equals(toolReq.name())
                                ? AgentStepTypes.TOOL_KIND_SKILL
                                : AgentStepTypes.TOOL_KIND_FUNCTION);

                    // 推送 tool_call 事件（SSE + 入库）：content 为请求参数，执行前已知
                    // 0724 改进一方案A：workflow_start 已删，workflow 工具改由 tool_call 的 stepData
                    // (is_workflow=true + tool_kind=workflow) 承载，前端据此渲染为可折叠容器
                    Map<String, Object> toolCallData = new java.util.HashMap<>();
                    toolCallData.put(AgentStepTypes.KEY_TOOL_CALL_ID, toolReq.id());
                    toolCallData.put(AgentStepTypes.KEY_TOOL_NAME, toolReq.name());
                    toolCallData.put(AgentStepTypes.KEY_TOOL_KIND, toolKind);
                    toolCallData.put("arguments", toolReq.arguments());
                    if (isWorkflowTool) {
                        toolCallData.put(AgentStepTypes.KEY_IS_WORKFLOW, true);
                    }
                    // 0724 改造B：tool_call 落盘并拿到 step id 压入 parent 栈，
                    // 使工作流子 Agent 的 sub_agent start 能挂到该 tool_call 下（parent_step_id 不再为 NULL）。
                    // tool_result 落盘前弹栈，保证 tool_result 与 tool_call 平级（均根层）。
                    Integer toolCallStepId = recorder.record(AgentStepEvent.builder()
                            .eventType(AgentStepTypes.TOOL_CALL)
                            .stepNumber(stepNumber)
                            .phase(AgentStepTypes.PHASE_THINKING)
                            .label(toolLabel)
                            .answer(toolReq.arguments())
                            .stepData(toolCallData)
                            .build());
                    recorder.pushParentStepId(toolCallStepId);

                    ToolCallRequest toolCallRequest = ToolCallRequest.builder()
                            .toolCallId(toolReq.id())
                            .toolName(toolReq.name())
                            .arguments(toolReq.arguments())
                            .build();

                    ToolCallResult toolResult;
                    long toolStartNanos = System.nanoTime();//0816 Langfuse：tool span 墙钟耗时起点
                    AgentObservability.ToolHandle toolObs = agentObservability.beginTool(toolReq, toolKind);
                    try {
                        if (toolSpec == null) {
                            toolResult = ToolCallResult.failure(toolReq.id(), toolReq.name(),
                                    "未知工具: " + toolReq.name());
                        } else {
                            //工作流类工具使用更宽松的超时，普通工具使用默认超时
                            int timeout = isWorkflowTool
                                    ? workflowToolTimeoutSeconds
                                    : toolTimeoutSeconds;
                            toolResult = toolExecutionTimeout.executeWithTimeout(
                                    toolSpec, toolCallRequest, context, timeout, toolObs.getContext());//将tool传入带计时的执行类中执行
                        }
                    } finally {
                        // 0724 改造B：工具执行结束（含异常/未知工具）后弹栈——子 Agent 的 start/end 已在
                        // beforeAgentInvocation/afterAgentInvocation 自行压弹，这里弹的是外层 tool_call id。
                        // 必须在 tool_result 落盘前弹，使 tool_result 的 parent 取到 NULL（与 tool_call 平级）。
                        recorder.popParentStepId();
                    }
                    agentObservability.endTool(toolObs, toolResult,
                            TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - toolStartNanos));

                    //回调通知 builder：渐进披露模式下 resolve 成功会触发工具/技能动态激活（策略内化于 builder）
                    //0724 改进三：透传 recorder，激活技能时由 builder 推 skill_activated 事件
                    contextBuilder.onToolExecuted(sessionId, toolReq.name(), toolResult, toolReq.arguments(), recorder);

                    //构建工具执行结果文本
                    String resultText = toolResult.isSuccess()
                            ? toolResult.getResult()
                            : "Error: " + toolResult.getError();

                    // 推送 tool_result 事件（SSE + 入库）：成功用 answer 存结果，失败用 error 存错误
                    // 0724 改进一方案A：workflow_end 已删，workflow 工具结果由 tool_result 的 stepData 承载
                    Map<String, Object> toolResultData = new java.util.HashMap<>();
                    toolResultData.put(AgentStepTypes.KEY_TOOL_CALL_ID, toolReq.id());
                    toolResultData.put(AgentStepTypes.KEY_TOOL_NAME, toolReq.name());
                    toolResultData.put(AgentStepTypes.KEY_TOOL_KIND, toolKind);
                    toolResultData.put(AgentStepTypes.KEY_IS_SUCCESS, toolResult.isSuccess());
                    if (isWorkflowTool) {
                        toolResultData.put(AgentStepTypes.KEY_IS_WORKFLOW, true);
                    }
                    recorder.record(AgentStepEvent.builder()
                            .eventType(AgentStepTypes.TOOL_RESULT)
                            .stepNumber(stepNumber)
                            .phase(AgentStepTypes.PHASE_THINKING)
                            .label(toolLabel)
                            .answer(toolResult.isSuccess() ? toolResult.getResult() : null)
                            .error(toolResult.isSuccess() ? null : toolResult.getError())
                            .stepData(toolResultData)
                            .build());

                    //工具执行结果注入记忆，供LLM下一轮参考
                    ToolExecutionResultMessage resultMsg = ToolExecutionResultMessage.from(toolReq, resultText);
                    context.getMemory().add(resultMsg);
                }
            } else {
                //无工具调用 → LLM直接返回文本回答，循环结束
                String answer = aiMessage.text();
                if (answer == null || answer.isBlank()) {
                    answer = "抱歉，无法生成回答。";
                }

                recorder.record(AgentStepEvent.builder()
                        .eventType(AgentStepTypes.FINAL)
                        .stepNumber(stepNumber)
                        .phase(AgentStepTypes.PHASE_ANSWER)
                        .label("回答已就绪")
                        .answer(answer)
                        .finalStep(true)
                        .build());

                //final步骤不写DB，最终回答唯一存储在chat_messages中

                return AgentResult.builder()
                        .answer(answer)
                        .sourcesJson("[]")
                        .steps(recorder.getRecordedSteps())
                        .totalSteps(stepNumber)
                        .build();
            }
        }

        //超过最大步骤数，兜底返回
        log.warn("[AgentExecutor] 超过最大步骤数 {}!", MAX_STEPS);
        recorder.record(AgentStepEvent.builder()
                .eventType(AgentStepTypes.ERROR)
                .stepNumber(stepNumber)
                .phase(AgentStepTypes.PHASE_THINKING)
                .label("处理步骤过多")
                .error("超过最大步骤数 " + MAX_STEPS)
                .stepData(Map.of(AgentStepTypes.KEY_ERROR_CODE, AgentStepTypes.ERR_MAX_STEPS_EXCEEDED,
                        AgentStepTypes.KEY_STEP_COUNT, MAX_STEPS))
                .finalStep(true)
                .build());

        return AgentResult.builder()
                .answer("抱歉，回答该问题需要过多的处理步骤，请尝试简化问题。")
                .sourcesJson("[]")
                .steps(recorder.getRecordedSteps())
                .totalSteps(stepNumber)
                .exceededMaxSteps(true)
                .build();
    }

    private String truncate(String text, int maxLen) {
        if (text == null) return "";
        return text.length() <= maxLen ? text : text.substring(0, maxLen) + "...";
    }

    private static final Random RANDOM = new Random();

    /**
     * 调用流式LLM并等待完整响应；对"瞬态失败且尚未 emit 任何 token"的失败按指数退避重试（0814 改造）。
     * <p>重试安全性的三个前提：
     * <ul>
     *   <li>工具在本轮成功返回后才执行 → 重试不重复工具调用/落库；</li>
     *   <li>memory 在 await 成功后才变更 → 重试不污染运行容器；</li>
     *   <li>已 emit 内容（SSE 已推送 answer/thinking token）不重试 → 避免前端重复输出。</li>
     * </ul>
     * 参数来自 {@code llm.retry}（改造 B 落地），单次 180s 模型超时不变、不累计。
     * 重试行为同步上报到 {@link RetryMetrics}（改造 D，轻量计数 + 定时汇总）。
     *
     * @return 已完成（await 成功）的 future，调用方经 {@link StreamingResponseFuture#getResponse()} 取结果
     * @throws RuntimeException 重试耗尽、不可重试或已 emit 时抛原异常
     */
    private StreamingResponseFuture invokeWithRetry(OpenAiStreamingChatModel chatModel, ChatRequest chatRequest,
                                                    AgentStepListener listener, int stepNumber) {
        LlmProperties.Retry retry = llmProperties.getRetry();
        int maxRetries = retry.getMaxRetries();
        boolean retried = false;
        for (int attempt = 0; attempt <= maxRetries; attempt++) {
            StreamingResponseFuture future = new StreamingResponseFuture(listener, stepNumber);
            try {
                chatModel.chat(chatRequest, future);
                future.await(600, TimeUnit.SECONDS);
                if (retried) {
                    retryMetrics.onRetrySuccess();
                }
                return future;
            } catch (Exception e) {
                String type = RetryMetrics.classify(e);
                boolean retryable = isRetryable(e) && !future.hasEmitted();
                if (!retryable || attempt >= maxRetries) {
                    if (retried) {
                        retryMetrics.onRetryExhausted(type);
                    } else {
                        retryMetrics.onNonRetryable(type);
                    }
                    throw e;
                }
                retried = true;
                retryMetrics.onRetry(type);
                long backoff = backoffDelayMillis(retry, attempt);
                log.warn("[retry] LLM调用失败，第 {}/{} 次重试，{}ms 后重试: type={}, err={}",
                        attempt + 1, maxRetries, backoff, type, e.getMessage());
                sleepQuietly(backoff);
            }
        }
        // 防御：循环必然 return 或 throw
        throw new IllegalStateException("invokeWithRetry 不应到达此处");
    }

    /**
     * 可重试异常白名单：langchain4j {@link RetriableException} 系（5xx/429/408）+ 网络 IO + DNS 解析失败。
     * <p>复用异常类型而非解析 HTTP 状态码，与 langchain4j 内置分类一致；其中 DNS 解析
     * （{@link UnresolvedModelServerException}）虽属 NonRetriableException，但为瞬态，显式纳入白名单。
     * <p>沿 cause 链逐层判定：流式失败经 {@code await()} 包装为 RuntimeException，真正根因在链上，
     * 只查顶层会漏掉 429 限流等最该重试的异常。
     */
    private boolean isRetryable(Throwable t) {
        Throwable cause = t;
        while (cause != null) {
            if (cause instanceof RetriableException
                    || cause instanceof IOException
                    || cause instanceof HttpTimeoutException
                    || cause instanceof UnresolvedAddressException
                    || cause instanceof UnresolvedModelServerException) {
                return true;
            }
            cause = cause.getCause();
        }
        return false;
    }

    /**
     * 指数退避 + 抖动：initialBackoffMs × multiplier^attempt × (1 ± jitterRatio 随机)。
     */
    private long backoffDelayMillis(LlmProperties.Retry retry, int attempt) {
        double base = retry.getInitialBackoffMs() * Math.pow(retry.getBackoffMultiplier(), attempt);
        double factor = 1 + (RANDOM.nextDouble() * 2 - 1) * retry.getJitterRatio();
        return Math.max(1L, (long) (base * factor));
    }

    private void sleepQuietly(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("LLM重试等待被中断", ie);
        }
    }
}
