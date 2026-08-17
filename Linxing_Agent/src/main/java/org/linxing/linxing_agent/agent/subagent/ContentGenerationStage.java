package org.linxing.linxing_agent.agent.subagent;

import dev.langchain4j.agentic.AgenticServices;
import dev.langchain4j.agentic.UntypedAgent;
import dev.langchain4j.agentic.scope.AgenticScope;
import dev.langchain4j.model.chat.ChatModel;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.linxing.linxing_agent.agent.core.AgentStepTypes;
import org.linxing.linxing_agent.agent.core.StepRecorder;
import org.linxing.linxing_agent.agent.core.SubAgentStepListener;
import org.linxing.linxing_agent.agent.service.impl.ExamServiceImpl;
import org.linxing.linxing_agent.agent.tool.impl.SaveExamTool;
import org.linxing.linxing_agent.agent.tool.impl.SaveStudyPlanTool;
import org.linxing.linxing_agent.agent.tool.impl.jsoncontainer.AppendToContainerTool;
import org.linxing.linxing_agent.agent.tool.impl.jsoncontainer.CreateContainerTool;
import org.linxing.linxing_agent.agent.tool.impl.jsoncontainer.RemoveFromContainerTool;
import org.linxing.linxing_agent.agent.tool.impl.jsoncontainer.ReplaceContainerMetadataTool;
import org.linxing.linxing_agent.agent.tool.impl.jsoncontainer.ReplaceInContainerTool;
import org.linxing.linxing_agent.observability.AgentObservability;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * 内容生成阶段编排：计划生成 → （条件）测验生成 → 持久化结果汇总。
 * PlanGeneratorAgent / ExamGeneratorAgent 负责使用JSON容器工具分批构建 plan/exam，并自主调用 {@link SaveStudyPlanTool} / {@link SaveExamTool} 完成持久化。
 * 本 Service 通过 {@link SubAgentContext} 读取工具保存后的结果，不再依赖 LLM 最终文本输出。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ContentGenerationStage {

    private static final String PLAN_AGENT_NAME = "plan_generator";
    private static final String EXAM_AGENT_NAME = "exam_generator";
    private static final String PLAN_DISPLAY_LABEL = "生成学习计划";
    private static final String EXAM_DISPLAY_LABEL = "生成测验";
    private static final String SAVE_PLAN_DISPLAY_LABEL = "保存学习计划";
    private static final String SAVE_EXAM_DISPLAY_LABEL = "保存测验";
    private static final String PLAN_OUTPUT_KEY = "plan_container_id";
    private static final String EXAM_OUTPUT_KEY = "exam_container_id";

    private final ObjectMapper objectMapper;
    private final CreateContainerTool createContainerTool;
    private final AppendToContainerTool appendToContainerTool;
    private final RemoveFromContainerTool removeFromContainerTool;
    private final ReplaceInContainerTool replaceInContainerTool;
    private final ReplaceContainerMetadataTool replaceContainerMetadataTool;
    private final SaveStudyPlanTool saveStudyPlanTool;
    private final SaveExamTool saveExamTool;
    private final ExamServiceImpl examService;
    private final AgentObservability agentObservability;

    /**
     * 构建内容生成阶段工作流。
     *
     * @param recorder    步骤记录器
     * @param chatModel   非流式 ChatModel
     * @param userId      用户 ID
     * @param generateExam 是否生成测验
     * @return 内容生成阶段工作流 Agent
     */
    public UntypedAgent build(StepRecorder recorder,
                              ChatModel chatModel,
                              Integer userId,
                              boolean generateExam) {
        // 计划生成 Agent
        var planAgent = AgenticServices.agentBuilder(PlanGenerationAgent.class)
                .chatModel(chatModel)
                .tools(createContainerTool, appendToContainerTool,
                        removeFromContainerTool, replaceInContainerTool,
                        replaceContainerMetadataTool,
                        saveStudyPlanTool)
                .outputKey(PLAN_OUTPUT_KEY)
                .defaultKeyValue("clarification", "无补充信息")
                .listener(SubAgentStepListener.create(
                        PLAN_AGENT_NAME, "plan",
                        PLAN_DISPLAY_LABEL, PLAN_OUTPUT_KEY, recorder,
                        AgentStepTypes.PHASE_STUDY_PLAN, agentObservability))
                .build();

        // 测验生成 Agent
        var examAgent = AgenticServices.agentBuilder(ExamGenerationAgent.class)
                .chatModel(chatModel)
                .tools(createContainerTool, appendToContainerTool,
                        removeFromContainerTool, replaceInContainerTool,
                        replaceContainerMetadataTool,
                        saveExamTool)
                .outputKey(EXAM_OUTPUT_KEY)
                .listener(SubAgentStepListener.create(
                        EXAM_AGENT_NAME, "exam",
                        EXAM_DISPLAY_LABEL, EXAM_OUTPUT_KEY, recorder,
                        AgentStepTypes.PHASE_STUDY_PLAN, agentObservability))
                .build();

        // 根据 generate_exam 条件决定是否执行 examAgent
        //TODO：考虑使用langchain4j的自带条件辨析器取代这部分的代码
        var examConditional = AgenticServices
                .conditionalBuilder()
                .subAgents(
                        scope -> StepRecorder.readBooleanState(scope, "generate_exam", false),
                        examAgent
                )
                .build();

        // 顺序编排：先生成 plan，再条件生成 exam，最后汇总结果
        return AgenticServices.sequenceBuilder()
                .subAgents(planAgent, examConditional)
                .output(scope -> persistResults(scope, generateExam, recorder))
                .build();
    }

    /**
     * 对agent最终输出做处理：汇总和处理 plan/exam 保存结果。
     *
     * TODO：如下的代码考虑是否真的需要如此复杂的处理和兜底，它还连带如下的很多私有方法
     */
    private StudyPlanWorkflowResult persistResults(AgenticScope scope, boolean generateExam,
                                                   StepRecorder recorder) {
        var builder = StudyPlanWorkflowResult.builder();
        builder.examTriggered(generateExam);

        String planContainerId = extractContainerId(scope.readState(PLAN_OUTPUT_KEY, ""));
        SaveResult planResult = findPlanSaveResult(planContainerId);

        if (planResult != null) {
            builder.planSaved(true)
                    .planId(planResult.id())
                    .phaseCount(planResult.count());
            recorder.record(AgentStepTypes.SUB_AGENT, AgentStepTypes.PHASE_STUDY_PLAN,
                    StepRecorder.buildSubAgentData(PLAN_AGENT_NAME, "save_plan",
                            SAVE_PLAN_DISPLAY_LABEL, true, PLAN_OUTPUT_KEY, true,
                            "planId=" + planResult.id() + ", phases=" + planResult.count()),
                    null, "学习计划已保存（planId=" + planResult.id() + "）", true);
            log.info("Plan saved: id={}, phases={}", planResult.id(), planResult.count());
        } else {
            builder.planSaved(false)
                    .error("学习计划保存失败：未获取到保存结果");
            recorder.record(AgentStepTypes.SUB_AGENT, AgentStepTypes.PHASE_STUDY_PLAN,
                    StepRecorder.buildSubAgentData(PLAN_AGENT_NAME, "save_plan",
                            SAVE_PLAN_DISPLAY_LABEL, false, PLAN_OUTPUT_KEY, false, "no save result"),
                    null, "学习计划保存失败", true);
            log.warn("Plan save result missing, containerId={}", planContainerId);
        }

        if (generateExam && planResult != null) {
            String examContainerId = extractContainerId(scope.readState(EXAM_OUTPUT_KEY, ""));
            SaveResult examResult = findExamSaveResult(examContainerId);

            if (examResult != null) {
                builder.examSaved(true)
                        .examId(examResult.id())
                        .questionCount(examResult.count());
                // 统一关联建立点：编排层回填 exam.linked_plan_id。
                // exam 工具保持纯被动（LLM 不再传 linked_plan_id，落库时为 NULL），
                // 关联完全由编排层事后用 linkToPlan 回填，覆盖 LLM 自主保存与兜底保存两条路径。
                examService.linkToPlan(examResult.id(), planResult.id());
                recorder.record(AgentStepTypes.SUB_AGENT, AgentStepTypes.PHASE_STUDY_PLAN,
                        StepRecorder.buildSubAgentData(EXAM_AGENT_NAME, "save_exam",
                                SAVE_EXAM_DISPLAY_LABEL, true, EXAM_OUTPUT_KEY, true,
                                "examId=" + examResult.id()),
                        null, "测验已保存（examId=" + examResult.id() + "）", true);
                log.info("Exam saved: id={}, questions={}, linkedPlanId={}",
                        examResult.id(), examResult.count(), planResult.id());
            } else {
                builder.examSaved(false)
                        .examParseError("测验保存失败：未获取到保存结果");
                recorder.record(AgentStepTypes.SUB_AGENT, AgentStepTypes.PHASE_STUDY_PLAN,
                        StepRecorder.buildSubAgentData(EXAM_AGENT_NAME, "save_exam",
                                SAVE_EXAM_DISPLAY_LABEL, false, EXAM_OUTPUT_KEY, false, "no save result"),
                        null, "测验保存失败", true);
                log.warn("Exam save result missing, containerId={}", examContainerId);
            }
        } else if (generateExam) {
            builder.examSaved(false)
                    .examParseError("计划保存失败，测验未触发保存");
            recorder.record(AgentStepTypes.SUB_AGENT, AgentStepTypes.PHASE_STUDY_PLAN,
                    StepRecorder.buildSubAgentData(EXAM_AGENT_NAME, "save_exam",
                            SAVE_EXAM_DISPLAY_LABEL, false, EXAM_OUTPUT_KEY, false, "skipped: plan not saved"),
                    null, "保存测验（未触发：计划保存失败）", true);
        }

        StudyPlanWorkflowResult result = builder.build();
        SubAgentContext context = SubAgentContext.current();
        if (context != null) {
            context.setAttribute("study_plan_workflow_result", result);
        }
        return result;
    }

    /**
     * 查找计划保存结果：优先从 SubAgentContext 读取工具副作用，否则用 container_id 兜底保存。
     */
    private SaveResult findPlanSaveResult(String containerId) {
        SubAgentContext context = SubAgentContext.current();
        if (context != null) {
            SaveResult result = context.getAttribute(SaveStudyPlanTool.ATTR_SAVE_RESULT);
            if (result != null) {
                log.debug("Plan save result found in SubAgentContext: id={}", result.id());
                return result;
            }
        }

        if (containerId != null && !containerId.isBlank()) {
            log.info("Plan save result not in context, fallback save by containerId={}", containerId);
            String resultJson = saveStudyPlanTool.saveStudyPlan(containerId);
            return parseSaveResult(resultJson);
        }

        return null;
    }

    /**
     * 查找测验保存结果：优先从 SubAgentContext 读取工具副作用，否则用 container_id 兜底保存。
     * 兜底保存时 linked_plan_id 传空串——exam 工具保持纯被动，关联由 persistResults 统一调
     * {@link ExamServiceImpl#linkToPlan} 回填，不依赖 LLM 传参或兜底分支传 planId。
     */
    private SaveResult findExamSaveResult(String containerId) {
        SubAgentContext context = SubAgentContext.current();
        if (context != null) {
            SaveResult result = context.getAttribute(SaveExamTool.ATTR_SAVE_RESULT);
            if (result != null) {
                log.debug("Exam save result found in SubAgentContext: id={}", result.id());
                return result;
            }
        }

        if (containerId != null && !containerId.isBlank()) {
            log.info("Exam save result not in context, fallback save by containerId={}", containerId);
            String resultJson = saveExamTool.saveExam(containerId, "");
            return parseSaveResult(resultJson);
        }

        return null;
    }

    /**
     * 解析 save 工具返回的 JSON，兼容错误字符串。
     */
    private SaveResult parseSaveResult(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String trimmed = raw.trim();
        if (trimmed.startsWith("错误：")) {
            log.warn("Save tool returned error: {}", trimmed);
            return null;
        }
        try {
            if (trimmed.startsWith("{")) {
                JsonNode root = objectMapper.readTree(trimmed);
                Integer id = root.has("planId") ? root.get("planId").asInt()
                        : root.has("examId") ? root.get("examId").asInt() : null;
                int count = root.has("phaseCount") ? root.get("phaseCount").asInt()
                        : root.has("questionCount") ? root.get("questionCount").asInt() : 0;
                return id != null ? new SaveResult(id, count) : null;
            }
            return new SaveResult(Integer.parseInt(trimmed), 0);
        } catch (Exception e) {
            log.warn("Save result 解析失败: {}", trimmed);
            return null;
        }
    }

    /**
     * 从 LLM 输出中提取 container_id，去除 Markdown 代码块和前后引号。
     */
    private String extractContainerId(String raw) {
        if (raw == null || raw.isBlank()) {
            return "";
        }
        String text = JsonSanitizer.sanitize(raw).trim();
        if (text.startsWith("\"") && text.endsWith("\"") && text.length() >= 2) {
            text = text.substring(1, text.length() - 1);
        }
        return text.trim();
    }
}
