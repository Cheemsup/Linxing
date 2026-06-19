package org.linxing.linxing_agent.agent.tool.impl;

import dev.langchain4j.model.chat.request.json.JsonBooleanSchema;
import dev.langchain4j.model.chat.request.json.JsonObjectSchema;
import dev.langchain4j.model.chat.request.json.JsonStringSchema;
import tools.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.linxing.linxing_agent.agent.core.AgentContext;
import org.linxing.linxing_agent.agent.subagent.StudyPlanWorkflowResult;
import org.linxing.linxing_agent.agent.subagent.StudyPlanWorkflowService;
import org.linxing.linxing_agent.agent.tool.Tool;
import org.linxing.linxing_agent.agent.tool.ToolCallRequest;
import org.linxing.linxing_agent.agent.tool.ToolCallResult;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 启动 study_plan 工作流的工具
 * <p>
 * 由主 ReAct 循环中的 LLM 调用，触发 study_plan 多 Agent 工作流。
 * 工作流内部编排 clarify → plan → exam 三个子 Agent，通过 SSE step 事件汇报进度。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class StartStudyPlanWorkflowTool implements Tool {

    private static final String NAME = "start_study_plan_workflow";
    private static final String DESCRIPTION = "启动学习计划生成工作流。工作流会依次执行：澄清提问（可选）→ 计划生成 → 测验生成（可选），"
            + "并自动持久化到数据库。调用后通过 SSE step 事件推送进度，无需再调用 save_study_plan。";
    private static final String BRIEF = "启动学习计划生成工作流（含可选测验）";
    private static final String WHEN_TO_USE = "当用户要求制定学习计划时调用此工具。"
            + "如果用户信息不足（如缺少目标、时长或素材），设置 needs_clarification=true 并提供 clarification_question，工作流会暂停等待用户回复。"
            + "如果用户同时要求生成测验/题目，设置 generate_exam=true。";

    private final StudyPlanWorkflowService workflowService;
    private final ObjectMapper objectMapper;

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public String description() {
        return DESCRIPTION;
    }

    @Override
    public String brief() {
        return BRIEF;
    }

    @Override
    public String whenToUse() {
        return WHEN_TO_USE;
    }

    @Override
    public JsonObjectSchema spec() {
        return JsonObjectSchema.builder()
                .addProperty("topic", JsonStringSchema.builder()
                        .description("学习主题，如\"Rust语言入门\"").build())
                .addProperty("goal", JsonStringSchema.builder()
                        .description("学习目标，如\"能独立写CLI项目\"。可选，若用户未明确可留空").build())
                .addProperty("duration", JsonStringSchema.builder()
                        .description("学习时长，如\"3个月\"。可选").build())
                .addProperty("materials", JsonStringSchema.builder()
                        .description("学习素材内容（笔记摘要、搜索结果等）。可选").build())
                .addProperty("source_type", JsonStringSchema.builder()
                        .description("素材来源类型：notes / web_search / mixed / none。默认 none").build())
                .addProperty("generate_exam", JsonBooleanSchema.builder()
                        .description("是否同时生成测验题目。默认 false").build())
                .addProperty("needs_clarification", JsonBooleanSchema.builder()
                        .description("是否需要向用户澄清。当 topic/goal/duration 等关键信息缺失时设为 true。默认 false").build())
                .addProperty("clarification_question", JsonStringSchema.builder()
                        .description("澄清问题文本。needs_clarification=true 时必填，如\"您希望学习时长是多少？有没有特定目标？\"").build())
                .required("topic")
                .build();
    }

    @Override
    public ToolCallResult execute(ToolCallRequest request, AgentContext context) {
        Integer userId = context.getUserId();
        if (userId == null) {
            return ToolCallResult.failure(request.getToolCallId(), NAME, "用户未登录");
        }

        Integer sessionId = context.getSessionId();
        String arguments = request.getArguments();
        log.debug("[StartStudyPlanWorkflowTool] 收到参数: {}", arguments);

        try {
            var root = objectMapper.readTree(arguments);

            // 解析参数
            String topic = getText(root, "topic", "");
            String goal = getText(root, "goal", "");
            String duration = getText(root, "duration", "");
            String materials = getText(root, "materials", "");
            String sourceType = getText(root, "source_type", "none");
            boolean generateExam = root.has("generate_exam") && root.get("generate_exam").asBoolean(false);
            boolean needsClarification = root.has("needs_clarification") && root.get("needs_clarification").asBoolean(false);
            String clarificationQuestion = getText(root, "clarification_question", "");

            if (topic.isBlank()) {
                return ToolCallResult.failure(request.getToolCallId(), NAME, "topic 参数不能为空");
            }

            // 启动工作流
            StudyPlanWorkflowResult result = workflowService.startWorkflow(
                    topic, goal, duration, sourceType, materials,
                    generateExam, needsClarification, clarificationQuestion,
                    userId, sessionId, context.getStepListener()
            );

            // 构建返回结果
            Map<String, Object> resultMap = new LinkedHashMap<>();
            resultMap.put("planSaved", result.isPlanSaved());
            resultMap.put("examSaved", result.isExamSaved());
            resultMap.put("examTriggered", result.isExamTriggered());
            if (result.getPlanId() != null) {
                resultMap.put("planId", result.getPlanId());
            }
            if (result.getExamId() != null) {
                resultMap.put("examId", result.getExamId());
            }
            if (result.getPhaseCount() > 0) {
                resultMap.put("phaseCount", result.getPhaseCount());
            }
            if (result.getQuestionCount() > 0) {
                resultMap.put("questionCount", result.getQuestionCount());
            }
            if (result.getError() != null) {
                resultMap.put("error", result.getError());
            }

            String resultJson = objectMapper.writeValueAsString(resultMap);
            log.info("[StartStudyPlanWorkflowTool] 用户 {} 工作流完成: planSaved={}, examSaved={}",
                    userId, result.isPlanSaved(), result.isExamSaved());

            return ToolCallResult.success(request.getToolCallId(), NAME, resultJson);

        } catch (Exception e) {
            log.error("[StartStudyPlanWorkflowTool] 工作流执行异常", e);
            return ToolCallResult.failure(request.getToolCallId(), NAME,
                    "工作流执行异常: " + e.getMessage());
        }
    }

    private String getText(tools.jackson.databind.JsonNode root, String field, String defaultValue) {
        if (root.has(field) && !root.get(field).isNull()) {
            String text = root.get(field).asText();
            return text != null ? text : defaultValue;
        }
        return defaultValue;
    }
}
