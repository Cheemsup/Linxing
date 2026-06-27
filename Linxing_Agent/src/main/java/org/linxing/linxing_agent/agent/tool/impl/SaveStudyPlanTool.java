package org.linxing.linxing_agent.agent.tool.impl;

import dev.langchain4j.agent.tool.P;
import dev.langchain4j.model.chat.request.json.JsonArraySchema;
import dev.langchain4j.model.chat.request.json.JsonObjectSchema;
import dev.langchain4j.model.chat.request.json.JsonStringSchema;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.linxing.linxing_agent.agent.core.AgentContext;
import org.linxing.linxing_agent.agent.core.JsonContainer;
import org.linxing.linxing_agent.agent.dto.QuestionError;
import org.linxing.linxing_agent.agent.exception.StudyPlanParseException;
import org.linxing.linxing_agent.agent.exception.StudyPlanValidationException;
import org.linxing.linxing_agent.agent.service.impl.StudyPlanService;
import org.linxing.linxing_agent.agent.subagent.SaveResult;
import org.linxing.linxing_agent.agent.subagent.SubAgentContext;
import org.linxing.linxing_agent.agent.tool.Tool;
import org.linxing.linxing_agent.agent.tool.ToolCallRequest;
import org.linxing.linxing_agent.agent.tool.ToolCallResult;
import org.linxing.linxing_agent.agent.tool.impl.jsoncontainer.JsonContainerStore;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 保存学习计划的工具。
 * 同时支持两套调用体系：主循环的直接调用、langchain4j的@Agent系列调用
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SaveStudyPlanTool implements Tool {

    private static final String NAME = "save_study_plan";
    public static final String ATTR_SAVE_RESULT = "save_study_plan:last_result";
    private static final String DESCRIPTION = "将生成的学习计划保存到数据库，返回计划ID。"
            + "模式选择规则：阶段数 ≤ 5 时使用一次性模式（直接传 title + goal + phases）；"
            + "阶段数 > 5 时必须使用分批模式（先 create_container 再 append_to_container 最后传 container_id）。"
            + "判断依据：用户明确要求超过5个阶段，或你计划生成超过5个阶段时，必须走分批模式。";
    private static final String BRIEF = "保存生成的学习计划到数据库";
    private static final String DISPLAY_LABEL = "保存学习计划";
    private static final String WHEN_TO_USE = "当已生成完整的学习计划JSON后，必须调用此工具保存；"
            + "仅在制定学习计划时使用，普通问答不需要。"
            + "重要：如果用户要求生成超过5个阶段的学习计划，必须先调用 create_container 创建容器，"
            + "再分批调用 append_to_container 追加阶段（每批1-3个），最后调用本工具传入 container_id 保存。"
            + "不要尝试一次性生成超过5个阶段的 JSON，极易导致格式错误。";

    private final StudyPlanService studyPlanService;
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
    public String displayLabel() {
        return DISPLAY_LABEL;
    }

    @Override
    public JsonObjectSchema spec() {
        // 阶段对象的 Schema 定义
        JsonObjectSchema phaseItemSchema = JsonObjectSchema.builder()
                .addProperty("title", JsonStringSchema.builder()
                        .description("阶段标题，如\"第1月：基础语法\"").build())
                .addProperty("duration", JsonStringSchema.builder()
                        .description("阶段时长，如\"4周\"，可选").build())
                .addProperty("objective", JsonStringSchema.builder()
                        .description("阶段学习目标，可选但推荐").build())
                .addProperty("key_topics", JsonArraySchema.builder()
                        .description("关键知识点数组，如[\"变量与类型\",\"所有权机制\"]，可选")
                        .items(JsonStringSchema.builder().build())
                        .build())
                .addProperty("resources", JsonArraySchema.builder()
                        .description("学习资源数组，每项可为字符串或对象{name,url}，可选")
                        .build())
                .addProperty("practice_tasks", JsonArraySchema.builder()
                        .description("实践任务数组，如[\"实现一个CLI计算器\",\"完成Exercism前10题\"]，可选")
                        .items(JsonStringSchema.builder().build())
                        .build())
                .addProperty("milestones", JsonArraySchema.builder()
                        .description("阶段里程碑数组，如[\"能独立写出Hello World\",\"理解所有权规则\"]，可选")
                        .items(JsonStringSchema.builder().build())
                        .build())
                .required("title")
                .build();

        return JsonObjectSchema.builder()
                .addProperty("container_id", JsonStringSchema.builder()
                        .description("容器ID（分批模式）。传入此参数时，从容器读取分批构建的数据，忽略 title/goal/phases 等参数。"
                                + "不传时走一次性调用模式，需传入完整参数。").build())
                .addProperty("title", JsonStringSchema.builder()
                        .description("学习计划标题（一次性模式必填，分批模式忽略），如\"Rust 3个月学习计划\"").build())
                .addProperty("goal", JsonStringSchema.builder()
                        .description("学习目标（一次性模式必填，分批模式忽略），如\"从零到能写项目\"").build())
                .addProperty("description", JsonStringSchema.builder()
                        .description("计划描述或背景说明，可选").build())
                .addProperty("duration", JsonStringSchema.builder()
                        .description("计划总时长，如\"3个月\"，可选").build())
                .addProperty("source_type", JsonStringSchema.builder()
                        .description("素材来源：notes / web_search / mixed（一次性模式使用，默认 web_search）").build())
                .addProperty("phases", JsonArraySchema.builder()
                        .description("学习阶段数组（一次性模式必填，分批模式忽略）")
                        .items(phaseItemSchema)
                        .build())
                .addProperty("source_refs", JsonArraySchema.builder()
                        .description("素材来源引用列表，如笔记文档名或搜索结果URL。例如: [\"Rust笔记.md\", \"https://doc.rust-lang.org/book/\"]")
                        .build())
                .build();
    }

    @Override
    public ToolCallResult execute(ToolCallRequest request, AgentContext context) {
        Integer userId = context.getUserId();
        if (userId == null) {
            return ToolCallResult.failure(request.getToolCallId(), NAME, "用户未登录");
        }

        String arguments = request.getArguments();
        log.debug("[SaveStudyPlanTool] 收到参数: {}", arguments);

        try {
            var root = objectMapper.readTree(arguments);

            // 解析数据来源：分批模式从容器读取，一次性模式直接使用 arguments
            boolean isContainerMode = root.has("container_id") && !root.get("container_id").asText().isBlank();
            JsonNode planRoot;
            StudyPlanService.ValidationStrategy strategy;

            if (isContainerMode) {
                // 分批模式：从容器解析，使用 COLLECT_ALL 策略
                ToolCallResult containerError = validateContainer(request, context, root.get("container_id").asText());
                if (containerError != null) {
                    return containerError;
                }
                JsonContainer container = context.getContainer(root.get("container_id").asText());
                planRoot = container.assemble(objectMapper);
                strategy = StudyPlanService.ValidationStrategy.COLLECT_ALL;
            } else {
                // 一次性模式：直接使用 arguments，使用 FAIL_FAST 策略
                planRoot = root;
                strategy = StudyPlanService.ValidationStrategy.FAIL_FAST;
            }

            // 统一调用 StudyPlanService（校验 + 持久化）
            Integer planId = studyPlanService.parseAndSave(userId, planRoot, strategy);

            Map<String, Object> result = new LinkedHashMap<>();
            result.put("planId", planId);
            if (planRoot.has("phases") && planRoot.get("phases").isArray()) {
                result.put("phaseCount", planRoot.get("phases").size());
            }
            String resultJson = objectMapper.writeValueAsString(result);

            log.info("[SaveStudyPlanTool] 用户 {} 保存学习计划成功（{}），planId={}",
                    userId, isContainerMode ? "分批模式" : "一次性模式", planId);
            return ToolCallResult.success(request.getToolCallId(), NAME, resultJson);

        } catch (StudyPlanParseException e) {
            log.warn("[SaveStudyPlanTool] 学习计划JSON解析失败: {}", e.getMessage());
            return ToolCallResult.failure(request.getToolCallId(), NAME,
                    "学习计划保存失败: " + e.getMessage());
        } catch (StudyPlanValidationException e) {
            return buildValidationErrorResponse(request, e);
        } catch (Exception e) {
            log.error("[SaveStudyPlanTool] 保存学习计划异常: {}", e.getMessage(), e);
            return ToolCallResult.failure(request.getToolCallId(), NAME,
                    "学习计划保存异常: " + e.getMessage());
        }
    }

    /**
     * 供 subagent 体系使用的 {@code @Tool} 入口。
     * 与 {@link #execute(ToolCallRequest, AgentContext)} 共用
     * {@link StudyPlanService} 核心服务，userId 与容器均从 {@link SubAgentContext} 读取，
     * 避免作为 LLM 可控参数暴露。
     *
     * @param containerId 容器 ID，由 create_container 返回，容器类型必须为 study_plan
     * @return 保存结果 JSON，包含 planId 与 phaseCount；失败时返回错误信息
     */
    @dev.langchain4j.agent.tool.Tool(name = "save_study_plan",
            value = "将已分批构建完成的学习计划容器保存到数据库，返回计划ID。"
                    + "必须在所有 phase 追加完毕后调用，传入 create_container 返回的容器ID。")
    public String saveStudyPlan(
            @P("容器ID，由 create_container 返回，容器类型必须为 study_plan") String containerId) {
        Integer userId = SubAgentContext.currentUserId();
        if (userId == null) {
            return "错误：用户未登录";
        }

        JsonContainerStore store = SubAgentContext.currentStore();
        if (store == null) {
            return "错误：subagent 容器存储未绑定";
        }

        JsonContainer container = store.getContainer(containerId);
        if (container == null) {
            return "错误：容器不存在: " + containerId;
        }
        if (!"study_plan".equals(container.getContainerType())) {
            return "错误：容器类型不匹配: 期望 study_plan，实际 " + container.getContainerType();
        }

        try {
            JsonNode planRoot = container.assemble(objectMapper);
            Integer planId = studyPlanService.parseAndSave(userId, planRoot,
                    StudyPlanService.ValidationStrategy.COLLECT_ALL);

            Map<String, Object> result = new LinkedHashMap<>();
            result.put("planId", planId);
            int phaseCount = 0;
            if (planRoot.has("phases") && planRoot.get("phases").isArray()) {
                phaseCount = planRoot.get("phases").size();
                result.put("phaseCount", phaseCount);
            }

            SubAgentContext context = SubAgentContext.current();
            if (context != null) {
                context.setAttribute(ATTR_SAVE_RESULT, new SaveResult(planId, phaseCount));
            }

            log.info("[SaveStudyPlanTool] @Tool 保存学习计划成功，userId={}, planId={}", userId, planId);
            return objectMapper.writeValueAsString(result);
        } catch (StudyPlanParseException e) {
            log.warn("[SaveStudyPlanTool] @Tool 学习计划解析失败: {}", e.getMessage());
            return "学习计划保存失败: " + e.getMessage();
        } catch (StudyPlanValidationException e) {
            return buildValidationErrorMessage(e);
        } catch (Exception e) {
            log.error("[SaveStudyPlanTool] @Tool 保存学习计划异常: {}", e.getMessage(), e);
            return "学习计划保存异常: " + e.getMessage();
        }
    }

    /**
     * 校验容器是否存在且类型匹配
     *
     * @return null 表示校验通过；非 null 表示校验失败的 ToolCallResult
     */
    private ToolCallResult validateContainer(ToolCallRequest request, AgentContext context, String containerId) {
        JsonContainer container = context.getContainer(containerId);
        if (container == null) {
            return ToolCallResult.failure(request.getToolCallId(), NAME, "容器不存在: " + containerId);
        }
        if (!"study_plan".equals(container.getContainerType())) {
            return ToolCallResult.failure(request.getToolCallId(), NAME,
                    "容器类型不匹配: 期望 study_plan，实际 " + container.getContainerType());
        }
        return null;
    }

    /**
     * 构建校验失败的索引级错误响应（旧版 {@link Tool} 接口入口使用）
     */
    private ToolCallResult buildValidationErrorResponse(ToolCallRequest request,
                                                         StudyPlanValidationException e) {
        return ToolCallResult.failure(request.getToolCallId(), NAME, buildValidationErrorMessage(e));
    }

    /**
     * 构建校验失败的索引级错误信息（两套入口共用）
     */
    private String buildValidationErrorMessage(StudyPlanValidationException e) {
        List<Map<String, Object>> errorList = new ArrayList<>();
        for (QuestionError err : e.getErrors()) {
            Map<String, Object> errorItem = new LinkedHashMap<>();
            errorItem.put("index", err.getIndex());
            errorItem.put("field", err.getField());
            errorItem.put("message", err.getMessage());
            errorList.add(errorItem);
        }

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("success", false);
        response.put("errors", errorList);

        try {
            String resultJson = objectMapper.writeValueAsString(response);
            log.warn("[SaveStudyPlanTool] 学习计划校验失败，返回 {} 个错误", errorList.size());
            return resultJson;
        } catch (Exception ex) {
            return "学习计划校验失败，但错误信息序列化异常";
        }
    }
}
