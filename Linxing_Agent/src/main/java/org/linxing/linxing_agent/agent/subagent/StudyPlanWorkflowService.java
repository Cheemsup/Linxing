package org.linxing.linxing_agent.agent.subagent;

import dev.langchain4j.agentic.AgenticServices;
import dev.langchain4j.agentic.UntypedAgent;
import dev.langchain4j.agentic.agent.ErrorContext;
import dev.langchain4j.agentic.agent.ErrorRecoveryResult;
import dev.langchain4j.agentic.observability.AgentInvocationError;
import dev.langchain4j.agentic.observability.AgentListener;
import dev.langchain4j.agentic.observability.AgentResponse;
import dev.langchain4j.agentic.scope.AgenticScope;
import dev.langchain4j.agentic.workflow.HumanInTheLoop;
import dev.langchain4j.model.chat.ChatModel;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.linxing.linxing_agent.agent.core.AgentStepEvent;
import org.linxing.linxing_agent.agent.core.AgentStepListener;
import org.linxing.linxing_agent.agent.core.AgentStepTypes;
import org.linxing.linxing_agent.agent.service.impl.ExamService;
import org.linxing.linxing_agent.agent.service.impl.StudyPlanService;
import org.linxing.linxing_agent.common.config.LlmManager;
import org.linxing.linxing_agent.common.constant.LlmType;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * study_plan 工作流编排服务
 * <p>
 * 使用 langchain4j-agentic 的 sequenceBuilder + conditionalBuilder + humanInTheLoopBuilder
 * 编排三个子 Agent：StudyPlanClarifyAgent（条件触发）→ PlanGeneratorAgent → ExamGeneratorAgent（条件触发）。
 * <p>
 * 工作流内部使用非流式 ChatModel，通过 AgentStepListener 以 step 事件向主循环 SSE 通道汇报进度。
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class StudyPlanWorkflowService {

    private final LlmManager llmManager;
    private final StudyPlanService studyPlanService;
    private final ExamService examService;
    private final PendingClarificationRegistry clarificationRegistry;
    private final ObjectMapper objectMapper;

    private static final String CLARIFY_AGENT_NAME = "StudyPlanClarifyAgent";
    private static final String PLAN_AGENT_NAME = "PlanGeneratorAgent";
    private static final String EXAM_AGENT_NAME = "ExamGeneratorAgent";
    private static final String CLARIFY_TIMEOUT_REPLY = "无补充信息";
    private static final long CLARIFY_TIMEOUT_SECONDS = 120;

    /**
     * 启动 study_plan 工作流
     *
     * @param topic               学习主题
     * @param goal                学习目标
     * @param duration            学习时长
     * @param sourceType          素材来源类型
     * @param materials           素材内容
     * @param generateExam        是否生成测验
     * @param needsClarification  是否需要澄清
     * @param clarificationQuestion 澄清问题
     * @param userId              用户 ID
     * @param sessionId           会话 ID（用于 HumanInTheLoop 回复路由）
     * @param listener            SSE step 监听器
     * @return 工作流执行结果
     */
    public StudyPlanWorkflowResult startWorkflow(
            String topic, String goal, String duration, String sourceType,
            String materials, boolean generateExam, boolean needsClarification,
            String clarificationQuestion, Integer userId, Integer sessionId,
            AgentStepListener listener) {

        ChatModel chatModel = llmManager.getModel(LlmType.CHAT_MODEL);

        // 推送 workflow_start 事件
        pushEvent(listener, AgentStepTypes.WORKFLOW_START, "study_plan",
                buildWorkflowStartData(topic, generateExam, needsClarification),
                null, null, false);

        // ---- 构建子 Agent ----

        // 计划生成 Agent
        PlanGeneratorAgent planAgent = AgenticServices
                .agentBuilder(PlanGeneratorAgent.class)
                .chatModel(chatModel)
                .outputKey("plan_json")
                .defaultKeyValue("clarification", CLARIFY_TIMEOUT_REPLY)
                .listener(createAgentListener(PLAN_AGENT_NAME, "plan", "plan_json", listener))
                .build();

        // 测验生成 Agent
        ExamGeneratorAgent examAgent = AgenticServices
                .agentBuilder(ExamGeneratorAgent.class)
                .chatModel(chatModel)
                .outputKey("exam_json")
                .listener(createAgentListener(EXAM_AGENT_NAME, "exam", "exam_json", listener))
                .build();

        // HumanInTheLoop 澄清 Agent
        HumanInTheLoop clarifyAgent = AgenticServices
                .humanInTheLoopBuilder()
                .description("An agent that asks the user for missing information about the study plan")
                .outputKey("clarification")
                .responseProvider(scope -> {
                    String question = scope.readState("clarification_question", "请补充您的学习信息");
                    // 推送 sub_agent 事件，携带澄清问题
                    pushEvent(listener, AgentStepTypes.SUB_AGENT, "study_plan",
                            buildSubAgentData(CLARIFY_AGENT_NAME, "clarify", true,
                                    "clarification", true, question),
                            null, null, false);
                    // 注册 pending future，等待用户回复
                    CompletableFuture<String> future = new CompletableFuture<>();
                    clarificationRegistry.register(String.valueOf(sessionId), question, future);
                    try {
                        return future.get(CLARIFY_TIMEOUT_SECONDS, TimeUnit.SECONDS);
                    } catch (Exception e) {
                        log.warn("Clarification timeout for session {}", sessionId, e);
                        return CLARIFY_TIMEOUT_REPLY;
                    }
                })
                .build();

        // 条件包装：needs_clarification → clarifyAgent
        UntypedAgent clarifyConditional = AgenticServices
                .conditionalBuilder()
                .subAgents(
                        scope -> scope.readState("needs_clarification", false),
                        clarifyAgent
                )
                .build();

        // 条件包装：generate_exam → examAgent
        UntypedAgent examConditional = AgenticServices
                .conditionalBuilder()
                .subAgents(
                        scope -> scope.readState("generate_exam", false),
                        examAgent
                )
                .build();

        // ---- 构建主工作流（顺序编排）----
        StudyPlanWorkflowAgent workflow = AgenticServices
                .sequenceBuilder(StudyPlanWorkflowAgent.class)
                .subAgents(clarifyConditional, planAgent, examConditional)
                .output(scope -> persistResults(scope, userId, generateExam, listener))
                .errorHandler(errorContext -> {
                    log.error("Agent error in workflow: {}", errorContext.agentName(),
                            errorContext.exception());
                    pushEvent(listener, AgentStepTypes.SUB_AGENT, "study_plan",
                            buildSubAgentData(errorContext.agentName(), "unknown",
                                    true, null, false, null),
                            null, getErrorMessage(errorContext), false);
                    return ErrorRecoveryResult.result(null);
                })
                .build();

        // ---- 执行工作流 ----
        StudyPlanWorkflowResult result;
        try {
            result = workflow.execute(
                    topic, goal, duration, materials, sourceType,
                    generateExam, needsClarification, clarificationQuestion
            );
        } catch (Exception e) {
            log.error("Workflow execution failed", e);
            result = StudyPlanWorkflowResult.builder()
                    .planSaved(false)
                    .examSaved(false)
                    .examTriggered(generateExam)
                    .error("工作流执行失败: " + e.getMessage())
                    .build();
        }

        // 推送 workflow_end 事件
        pushEvent(listener, AgentStepTypes.WORKFLOW_END, "study_plan",
                buildWorkflowEndData(result),
                null, result.getError(), true);

        return result;
    }

    /**
     * output() 函数：解析 plan_json / exam_json 并持久化
     */
    private StudyPlanWorkflowResult persistResults(AgenticScope scope, Integer userId,
                                                   boolean generateExam, AgentStepListener listener) {
        StudyPlanWorkflowResult.StudyPlanWorkflowResultBuilder builder = StudyPlanWorkflowResult.builder()
                .examTriggered(generateExam);

        // ---- 解析并保存计划 ----
        String planJson = scope.readState("plan_json", "");
        if (planJson != null && !planJson.isBlank()) {
            try {
                Integer planId = studyPlanService.parseAndSave(userId, planJson);
                builder.planId(planId).planSaved(true);
                // 统计阶段数
                try {
                    JsonNode planRoot = objectMapper.readTree(planJson);
                    if (planRoot.has("phases") && planRoot.get("phases").isArray()) {
                        builder.phaseCount(planRoot.get("phases").size());
                    }
                } catch (Exception ignored) {
                }
                log.info("Plan saved with id {} for user {}", planId, userId);

                // ---- 解析并保存测验（如果触发）----
                if (generateExam) {
                    String examJson = scope.readState("exam_json", "");
                    if (examJson != null && !examJson.isBlank()) {
                        try {
                            JsonNode examRoot = objectMapper.readTree(examJson);
                            Integer examId = examService.parseAndSave(userId, examRoot,
                                    ExamService.ValidationStrategy.FAIL_FAST, planId);
                            builder.examId(examId).examSaved(true);
                            // 统计题目数
                            if (examRoot.has("questions") && examRoot.get("questions").isArray()) {
                                builder.questionCount(examRoot.get("questions").size());
                            }
                            log.info("Exam saved with id {} for plan {}", examId, planId);
                        } catch (Exception e) {
                            log.error("Failed to save exam", e);
                            builder.examSaved(false).error("测验保存失败: " + e.getMessage());
                        }
                    } else {
                        builder.examSaved(false).error("测验生成结果为空");
                    }
                }
            } catch (Exception e) {
                log.error("Failed to save plan", e);
                builder.planSaved(false).error("计划保存失败: " + e.getMessage());
            }
        } else {
            builder.planSaved(false).error("计划生成结果为空");
        }

        return builder.build();
    }

    // ==================== Agent Listener 工厂 ====================

    /**
     * 为子 Agent 创建 AgentListener，在执行完成后推送 sub_agent 事件
     */
    private AgentListener createAgentListener(String agentName, String agentRole,
                                              String outputKey, AgentStepListener listener) {
        return new AgentListener() {
            @Override
            public void afterAgentInvocation(AgentResponse response) {
                pushEvent(listener, AgentStepTypes.SUB_AGENT, "study_plan",
                        buildSubAgentData(agentName, agentRole, true, outputKey, true, null),
                        null, null, false);
            }

            @Override
            public void onAgentInvocationError(AgentInvocationError error) {
                pushEvent(listener, AgentStepTypes.SUB_AGENT, "study_plan",
                        buildSubAgentData(agentName, agentRole, true, outputKey, false, null),
                        null, getErrorMessage(error), false);
            }
        };
    }

    // ==================== 事件推送辅助方法 ====================

    private void pushEvent(AgentStepListener listener, String eventType, String phase,
                           Map<String, Object> stepData, String answer, String error, boolean finalStep) {
        if (listener != null) {
            listener.onStep(AgentStepEvent.builder()
                    .eventType(eventType)
                    .stepNumber(0)
                    .phase(phase)
                    .stepData(stepData)
                    .answer(answer)
                    .error(error)
                    .finalStep(finalStep)
                    .build());
        }
    }

    private Map<String, Object> buildSubAgentData(String agentName, String agentRole,
                                                  boolean triggered, String outputKey,
                                                  boolean success, String question) {
        Map<String, Object> data = new HashMap<>();
        data.put(AgentStepTypes.KEY_AGENT_NAME, agentName);
        data.put(AgentStepTypes.KEY_AGENT_ROLE, agentRole);
        data.put(AgentStepTypes.KEY_TRIGGERED, triggered);
        if (outputKey != null) {
            data.put(AgentStepTypes.KEY_OUTPUT_KEY, outputKey);
        }
        data.put(AgentStepTypes.KEY_SUCCESS, success);
        if (question != null) {
            data.put(AgentStepTypes.KEY_QUESTION, question);
        }
        return data;
    }

    private Map<String, Object> buildWorkflowStartData(String topic, boolean generateExam,
                                                       boolean needsClarification) {
        Map<String, Object> data = new HashMap<>();
        data.put("topic", topic);
        data.put("generate_exam", generateExam);
        data.put("needs_clarification", needsClarification);
        return data;
    }

    private Map<String, Object> buildWorkflowEndData(StudyPlanWorkflowResult result) {
        Map<String, Object> data = new HashMap<>();
        data.put("plan_saved", result.isPlanSaved());
        data.put("exam_saved", result.isExamSaved());
        data.put("plan_id", result.getPlanId() != null ? result.getPlanId() : -1);
        data.put("exam_id", result.getExamId() != null ? result.getExamId() : -1);
        data.put("exam_triggered", result.isExamTriggered());
        return data;
    }

    private String getErrorMessage(ErrorContext errorContext) {
        Throwable ex = errorContext.exception();
        return ex != null ? ex.getMessage() : "unknown error";
    }

    private String getErrorMessage(AgentInvocationError error) {
        Throwable ex = error.error();
        return ex != null ? ex.getMessage() : "unknown error";
    }
}
