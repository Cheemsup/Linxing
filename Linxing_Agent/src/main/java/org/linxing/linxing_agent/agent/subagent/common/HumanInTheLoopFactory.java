package org.linxing.linxing_agent.agent.subagent.common;

import dev.langchain4j.agentic.AgenticServices;
import dev.langchain4j.agentic.scope.AgenticScope;
import dev.langchain4j.agentic.workflow.HumanInTheLoop;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.linxing.linxing_agent.agent.core.AgentStepTypes;
import org.linxing.linxing_agent.agent.core.ToolExecutionTimeout;
import org.linxing.linxing_agent.agent.core.ToolTimeoutContext;
import org.linxing.linxing_agent.agent.subagent.PendingClarificationRegistry;
import org.springframework.stereotype.Component;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * HumanInTheLoop 公共工厂。
 * 将阻塞式澄清 Agent 的构建逻辑（humanInTheLoopBuilder + PendingClarificationRegistry 交互）封装为可复用组件，任意工作流均可调用。
 * 由调用方保证同一工作流只条件触发一次。
 *
 * TODO：考虑后续将其移动到主循环包下并做出适配（真正的“公用”），因为这样的打断循环并补充信息的操作在主循环中也是应该能够使用的
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class HumanInTheLoopFactory {

    private final PendingClarificationRegistry clarificationRegistry;

    /**
     * 创建一个阻塞式 HumanInTheLoop Agent。
     *
     * @param sessionId       会话 ID（作为 clarificationId）
     * @param outputKey       回复内容写入 AgenticScope 的 key
     * @param timeoutSeconds  超时秒数
     * @param defaultReply    超时默认回复
     * @param recorder        步骤记录器（用于 SSE 推送 + DB 持久化）
     * @param agentName       Agent 名称（用于 step 事件展示）
     * @param phase           阶段标识（用于 step 事件分组）
     * @return 阻塞式 HumanInTheLoop Agent
     */
    public HumanInTheLoop create(Integer sessionId, String outputKey,
                                 long timeoutSeconds, String defaultReply,
                                 StepRecorder recorder, String agentName, String phase) {
        return AgenticServices
                .humanInTheLoopBuilder()
                .description("An agent that asks the user for missing information")
                .outputKey(outputKey)
                .responseProvider(scope -> {
                    String question = scope.readState("clarification_question",
                            "请补充您的信息");
                    // 推送 sub_agent 事件，携带澄清问题
                    recorder.emit(AgentStepTypes.SUB_AGENT, phase,
                            StepRecorder.buildSubAgentData(agentName, "clarify", true,
                                    outputKey, true, question),
                            null, null, false);
                    // 注册 pending future，等待用户回复；registry 负责超时自清理与版本校验
                    CompletableFuture<String> future = new CompletableFuture<>();
                    AtomicBoolean timedOut = clarificationRegistry.register(
                            String.valueOf(sessionId), question, future,
                            timeoutSeconds, defaultReply);
                    // 暂停外层工具执行超时计时：HumanInTheLoop 等待期间不扣减工具预算
                    ToolTimeoutContext toolTimeoutCtx = ToolExecutionTimeout.getCurrentContext();
                    if (toolTimeoutCtx != null) {
                        toolTimeoutCtx.pause();
                    }
                    try {
                        // 阻塞等待：由用户回复（complete）或 registry 超时任务唤醒。
                        // 安全网超时比 registry 超时多 60s，仅在 scheduler 异常时兜底。
                        String answer = future.get(timeoutSeconds + 60, TimeUnit.SECONDS);
                        String statusLabel;
                        if (timedOut.get()) {
                            // 超时由 registry 完成 future，标记状态供后续结果语义使用
                            scope.writeState("clarification_timed_out", true);
                            statusLabel = "（超时，使用默认值）";
                            log.warn("澄清超时，使用默认值继续: session={}", sessionId);
                        } else {
                            statusLabel = "（已回复）";
                        }
                        // 推送用户已回复/超时的状态事件
                        recorder.emit(AgentStepTypes.SUB_AGENT, phase,
                                StepRecorder.buildSubAgentData(agentName, "clarify_answer", true,
                                        outputKey, true, null),
                                answer + statusLabel, null, false);
                        return answer;
                    } catch (Exception e) {
                        // InterruptedException / CancellationException / 安全网超时：工作流被中断
                        log.warn("澄清等待被中断或安全网超时: session={}", sessionId, e);
                        scope.writeState("clarification_timed_out", true);
                        clarificationRegistry.cancel(String.valueOf(sessionId));
                        recorder.emit(AgentStepTypes.SUB_AGENT, phase,
                                StepRecorder.buildSubAgentData(agentName, "clarify_answer", true,
                                        outputKey, false, null),
                                null, "澄清等待被中断：" + e.getMessage(), false);
                        return defaultReply;
                    } finally {
                        // 恢复外层工具执行超时计时
                        if (toolTimeoutCtx != null) {
                            toolTimeoutCtx.resume();
                        }
                    }
                })
                .build();
    }
}