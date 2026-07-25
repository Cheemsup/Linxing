package org.linxing.linxing_agent.agent.subagent;

import dev.langchain4j.agentic.AgenticServices;
import dev.langchain4j.agentic.UntypedAgent;
import dev.langchain4j.agentic.agent.ErrorRecoveryResult;
import dev.langchain4j.model.chat.ChatModel;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.linxing.linxing_agent.agent.core.AgentStepTypes;
import org.linxing.linxing_agent.agent.core.PendingClarificationRegistry;
import org.linxing.linxing_agent.agent.core.StepRecorder;
import org.linxing.linxing_agent.common.config.LlmManager;
import org.linxing.linxing_agent.common.constant.LlmType;
import org.springframework.stereotype.Service;

/**
 * study_plan 工作流顶层编排服务。学习计划的制定流程分为两阶段：资料收集和用户补充、plan（和可能的exam）生成，内部使用langchain4j的顺序工作流编排agent
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class StudyPlanner {

    private final LlmManager llmManager;
    private final KnowledgeCollectionStage knowledgeCollectionStage;
    private final ContentGenerationStage contentGenerationStage;
    private final PendingClarificationRegistry clarificationRegistry;

    /**
     * 启动 study_plan 工作流。
     *
     * @param topic                 学习主题
     * @param goal                  学习目标
     * @param duration              学习时长
     * @param sourceType            素材来源类型
     * @param materials             素材内容（可选，若为空则由知识收集阶段自主搜索）
     * @param generateExam          是否生成测验
     * @param needsClarification    是否需要澄清
     * @param clarificationQuestion 澄清问题
     * @param userId                用户 ID
     * @param sessionId             会话 ID（用于 HumanInTheLoop 回复路由）
     * @param recorder              统一步骤记录器（由主循环传入，共享 step_order 序列）
     * @return 工作流执行结果
     */
    public StudyPlanWorkflowResult startWorkflow(
            String topic, String goal, String duration, String sourceType,
            String materials, boolean generateExam, boolean needsClarification,
            String clarificationQuestion, Integer userId, Integer sessionId,
            StepRecorder recorder) {

        ChatModel chatModel = llmManager.getModel(LlmType.STUDY_PLAN_MODEL);//学习计划编排专用模型

        log.info("study_plan 工作流启动（两阶段编排）: userId={}, sessionId={}, topic='{}', generateExam={}, needsClarification={}",
                userId, sessionId, topic, generateExam, needsClarification);

        // 绑定 subagent 线程上下文：userId、sessionId、容器存储均不向 LLM 暴露
        SubAgentContext.bind(userId, sessionId);

        try {
            return doStartWorkflow(
                    topic, goal, duration, sourceType, materials,
                    generateExam, needsClarification, clarificationQuestion,
                    userId, sessionId, recorder, chatModel);
        } finally {
            SubAgentContext.clear();
            // 工作流结束时强制清理本会话的待澄清请求，避免网络中断/客户端重试时
            // 旧 pending future 被后续同 session 工作流复用或阻塞。
            if (sessionId != null) {
                clarificationRegistry.cancel(String.valueOf(sessionId));
            }
        }
    }

    private StudyPlanWorkflowResult doStartWorkflow(
            String topic, String goal, String duration, String sourceType,
            String materials, boolean generateExam, boolean needsClarification,
            String clarificationQuestion, Integer userId, Integer sessionId,
            StepRecorder recorder, ChatModel chatModel) {

        // 0724 改进一方案A：workflow_start 已删除——workflow 工具改由外层 AgentExecutor 的
        // tool_call(tool_kind=workflow, is_workflow=true) 承载状态，不再在此单独推送 start 事件。

        // ---- 知识收集（信息补充 + 自主搜索）----
        UntypedAgent knowledgeWorkflowAgent = knowledgeCollectionStage
                .build(recorder, chatModel, sessionId);

        // ---- 内容生成（plan + 条件 exam + 持久化）----
        UntypedAgent contentWorkflowAgent = contentGenerationStage
                .build(recorder, chatModel, userId, generateExam);

        // ---- 顶层顺序编排：知识收集 → 内容生成 ----
        StudyPlanAgent workflow = AgenticServices
                .sequenceBuilder(StudyPlanAgent.class)
                .subAgents(knowledgeWorkflowAgent, contentWorkflowAgent)
                .errorHandler(errorContext -> {
                    String agentName = errorContext.agentName();
                    Throwable ex = errorContext.exception();
                    String errMsg = ex != null ? ex.getMessage() : "unknown error";
                    log.error("Agent error in workflow: agent={}, error={}", agentName, errMsg, ex);
                    recorder.record(AgentStepTypes.SUB_AGENT, AgentStepTypes.PHASE_STUDY_PLAN,
                            StepRecorder.buildSubAgentData(agentName, "error",
                                    "生成失败", false, null, false, errMsg),
                            null, "Agent [" + agentName + "] 执行失败: " + errMsg, true);
                    // 返回包含错误信息的结果，而非 null，避免异常信息被吞掉
                    StudyPlanWorkflowResult errorResult = StudyPlanWorkflowResult.builder()
                            .planSaved(false)
                            .examSaved(false)
                            .examTriggered(generateExam)
                            .clarificationTriggered(needsClarification)
                            .error("Agent [" + agentName + "] 执行失败: " + errMsg)
                            .build();
                    SubAgentContext ctx = SubAgentContext.current();
                    if (ctx != null) {
                        ctx.setAttribute("study_plan_workflow_result", errorResult);
                    }
                    return ErrorRecoveryResult.result(errorResult);
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
                    .clarificationTriggered(needsClarification)
                    .error("工作流执行失败: " + e.getMessage())
                    .build();
        }

        // AgenticServices sequenceBuilder 在未指定 output() 时 execute() 可能返回 null，不过内容生成阶段已通过 SubAgentContext 保存了实际结果，优先从中读取。
        if (result == null) {
            SubAgentContext ctx = SubAgentContext.current();
            Object saved = ctx != null ? ctx.getAttribute("study_plan_workflow_result") : null;
            if (saved instanceof StudyPlanWorkflowResult) {
                result = (StudyPlanWorkflowResult) saved;
                log.info("Workflow execute() returned null, recovered result from SubAgentContext: planSaved={}, examSaved={}",
                        result.isPlanSaved(), result.isExamSaved());
            } else {
                log.error("Workflow returned null result and no result found in SubAgentContext");
                result = StudyPlanWorkflowResult.builder()
                        .planSaved(false)
                        .examSaved(false)
                        .examTriggered(generateExam)
                        .clarificationTriggered(needsClarification)
                        .error("工作流执行失败: 内部 Agent 异常被 errorHandler 吞掉")
                        .build();
            }
        }

        // 0724 改进一方案A：workflow_end 已删除——结果结构化数据（plan_id/exam_id 等）由
        // ContentGenerationStage.persistResults 内部已有的 save_plan/save_exam sub_agent 事件承载，
        // 外层 AgentExecutor 的 tool_result(tool_kind=workflow, is_workflow=true) 承载完成状态。
        // 仅当有错误时记录到日志，便于排查。
        if (result.getError() != null && !result.getError().isBlank()) {
            log.warn("study_plan 工作流执行有错误: session={}, error={}", sessionId, result.getError());
        }

        return result;
    }
}
