package org.linxing.linxing_agent.agent.tool.impl;

import dev.langchain4j.agent.tool.P;
import dev.langchain4j.model.chat.request.json.JsonAnyOfSchema;
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
import org.linxing.linxing_agent.agent.exception.ExamParseException;
import org.linxing.linxing_agent.agent.exception.ExamValidationException;
import org.linxing.linxing_agent.agent.service.impl.ExamService;
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
 * 保存exam的工具
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SaveExamTool implements Tool {

    private static final String NAME = "save_exam";
    public static final String ATTR_SAVE_RESULT = "save_exam:last_result";
    private static final String DESCRIPTION = "将生成的测验题目保存到数据库，返回测验ID。"
            + "模式选择规则：题目数 ≤ 5 时使用一次性模式（直接传 title + questions）；"
            + "题目数 > 5 时必须使用分批模式（先 create_container 再 append_to_container 最后传 container_id）。"
            + "判断依据：用户明确要求超过5题，或你计划生成超过5题时，必须走分批模式。";
    private static final String BRIEF = "保存生成的测验题目到数据库";
    private static final String WHEN_TO_USE = "当已生成完整的测验题目JSON后，必须调用此工具保存；"
            + "仅在生成测验时使用，普通问答不需要。"
            + "重要：如果用户要求出超过5道题，必须先调用 create_container 创建容器，"
            + "再分批调用 append_to_container 追加题目（每批1-3题），最后调用本工具传入 container_id 保存。"
            + "不要尝试一次性生成超过5题的 JSON，极易导致格式错误。";

    private final ExamService examService;
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
        // 题目对象的 Schema 定义
        JsonObjectSchema questionItemSchema = JsonObjectSchema.builder()
                .addProperty("type", JsonStringSchema.builder()
                        .description("题型，仅限: single_choice / multi_choice / fill_blank / true_false / short_answer").build())
                .addProperty("stem", JsonStringSchema.builder()
                        .description("题目内容").build())
                .addProperty("options", JsonArraySchema.builder()
                        .description("选项数组，single_choice / multi_choice 必填。每个元素必须含字母前缀和完整选项文本，如 [\"A. 数组\",\"B. 单向链表\",\"C. 哈希表+双向链表\",\"D. 栈\"]；填空题/简答题/判断题不需要")
                        .build())
                .addProperty("answer", JsonAnyOfSchema.builder()
                        .description("正确答案。多选题必须传字符串数组，每个元素需与 options 中对应选项文本完全一致（含字母前缀），如 [\"A. 冒泡排序\",\"C. 归并排序\"]；单选题/判断题/简答题/填空题必须传字符串（单选题答案需与 options 中某项完全一致，如 \"C. 哈希表+双向链表\"；判断题为 \"正确\" 或 \"错误\"；填空题为单个答案字符串）")
                        .anyOf(
                                JsonStringSchema.builder().description("单选题/判断题/简答题/填空题的答案字符串").build(),
                                JsonArraySchema.builder().description("多选题答案数组").build()
                        )
                        .build())
                .addProperty("explanation", JsonStringSchema.builder()
                        .description("答案解析，可选").build())
                .addProperty("difficulty", JsonStringSchema.builder()
                        .description("难度：easy/medium/hard，可选，默认medium").build())
                .required("type", "stem", "answer")
                .build();

        return JsonObjectSchema.builder()
                .addProperty("container_id", JsonStringSchema.builder()
                        .description("容器ID（分批模式）。传入此参数时，从容器读取分批构建的数据，忽略 title/questions 等参数。"
                                + "不传时走一次性调用模式，需传入完整参数。").build())
                .addProperty("title", JsonStringSchema.builder()
                        .description("测验标题（一次性模式必填，分批模式忽略）").build())
                .addProperty("source_type", JsonStringSchema.builder()
                        .description("素材来源：notes / web_search / mixed（一次性模式使用）").build())
                .addProperty("questions", JsonArraySchema.builder()
                        .description("题目数组（一次性模式必填，分批模式忽略）")
                        .items(questionItemSchema)
                        .build())
                .addProperty("source_refs", JsonArraySchema.builder()
                        .description("素材来源引用列表，如笔记文档名或搜索结果URL。例如: [\"RAG搭建笔记.md\", \"https://example.com/rag-guide\"]")
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
        log.debug("[SaveExamTool] 收到参数: {}", arguments);

        try {
            var root = objectMapper.readTree(arguments);

            // 解析数据来源：分批模式从容器读取，一次性模式直接使用 arguments
            boolean isContainerMode = root.has("container_id") && !root.get("container_id").asText().isBlank();
            JsonNode examRoot;
            ExamService.ValidationStrategy strategy;

            if (isContainerMode) {
                // 分批模式：从容器解析，使用 COLLECT_ALL 策略
                ToolCallResult containerError = validateContainer(request, context, root.get("container_id").asText());
                if (containerError != null) {
                    return containerError;
                }
                JsonContainer container = context.getContainer(root.get("container_id").asText());
                examRoot = container.assemble(objectMapper);
                strategy = ExamService.ValidationStrategy.COLLECT_ALL;
            } else {
                // 一次性模式：直接使用 arguments，使用 FAIL_FAST 策略
                examRoot = root;
                strategy = ExamService.ValidationStrategy.FAIL_FAST;
            }

            // 统一调用 ExamService（校验 + 持久化）
            Integer examId = examService.parseAndSave(userId, examRoot, strategy);

            Map<String, Object> result = new LinkedHashMap<>();
            result.put("examId", examId);
            if (examRoot.has("questions") && examRoot.get("questions").isArray()) {
                result.put("questionCount", examRoot.get("questions").size());
            }
            String resultJson = objectMapper.writeValueAsString(result);

            log.info("[SaveExamTool] 用户 {} 保存测验成功（{}），examId={}",
                    userId, isContainerMode ? "分批模式" : "一次性模式", examId);
            return ToolCallResult.success(request.getToolCallId(), NAME, resultJson);

        } catch (ExamParseException e) {
            log.warn("[SaveExamTool] 测验JSON解析失败: {}", e.getMessage());
            return ToolCallResult.failure(request.getToolCallId(), NAME,
                    "测验保存失败: " + e.getMessage());
        } catch (ExamValidationException e) {
            return buildValidationErrorResponse(request, e);
        } catch (Exception e) {
            log.error("[SaveExamTool] 保存测验异常: {}", e.getMessage(), e);
            return ToolCallResult.failure(request.getToolCallId(), NAME,
                    "测验保存异常: " + e.getMessage());
        }
    }

    /**
     * 供 subagent 体系使用的 {@code @Tool} 入口。
     * 与 {@link #execute(ToolCallRequest, AgentContext)} 共用
     * {@link ExamService} 核心服务，userId 与容器均从 {@link SubAgentContext} 读取，
     * 避免作为 LLM 可控参数暴露。
     *
     * @param containerId   容器 ID，由 create_container 返回，容器类型必须为 exam
     * @param linkedPlanId  关联的学习计划 ID，可选；若传入则保存到 exam.linked_plan_id
     * @return 保存结果 JSON，包含 examId 与 questionCount；失败时返回错误信息
     */
    @dev.langchain4j.agent.tool.Tool(name = "save_exam",
            value = "将已分批构建完成的测验容器保存到数据库，返回测验ID。"
                    + "必须在所有 question 追加完毕后调用，传入 create_container 返回的容器ID。")
    public String saveExam(
            @P("容器ID，由 create_container 返回，容器类型必须为 exam") String containerId,
            @P("关联的学习计划ID，可选") String linkedPlanId) {
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
        if (!"exam".equals(container.getContainerType())) {
            return "错误：容器类型不匹配: 期望 exam，实际 " + container.getContainerType();
        }

        Integer planId = parseLinkedPlanId(linkedPlanId);

        try {
            JsonNode examRoot = container.assemble(objectMapper);
            Integer examId = examService.parseAndSave(userId, examRoot,
                    ExamService.ValidationStrategy.COLLECT_ALL, planId);

            Map<String, Object> result = new LinkedHashMap<>();
            result.put("examId", examId);
            int questionCount = 0;
            if (examRoot.has("questions") && examRoot.get("questions").isArray()) {
                questionCount = examRoot.get("questions").size();
                result.put("questionCount", questionCount);
            }

            SubAgentContext context = SubAgentContext.current();
            if (context != null) {
                context.setAttribute(ATTR_SAVE_RESULT, new SaveResult(examId, questionCount));
            }

            log.info("[SaveExamTool] @Tool 保存测验成功，userId={}, examId={}, linkedPlanId={}",
                    userId, examId, planId);
            return objectMapper.writeValueAsString(result);
        } catch (ExamParseException e) {
            log.warn("[SaveExamTool] @Tool 测验解析失败: {}", e.getMessage());
            return "测验保存失败: " + e.getMessage();
        } catch (ExamValidationException e) {
            return buildValidationErrorMessage(e);
        } catch (Exception e) {
            log.error("[SaveExamTool] @Tool 保存测验异常: {}", e.getMessage(), e);
            return "测验保存异常: " + e.getMessage();
        }
    }

    private Integer parseLinkedPlanId(String linkedPlanId) {
        if (linkedPlanId == null || linkedPlanId.isBlank()) {
            return null;
        }
        try {
            return Integer.valueOf(linkedPlanId.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /**
     * 校验容器是否存在且类型匹配。属于路由逻辑，保留在 Tool 层。
     *
     * @return null 表示校验通过；非 null 表示校验失败的 ToolCallResult
     */
    private ToolCallResult validateContainer(ToolCallRequest request, AgentContext context, String containerId) {
        JsonContainer container = context.getContainer(containerId);
        if (container == null) {
            return ToolCallResult.failure(request.getToolCallId(), NAME, "容器不存在: " + containerId);
        }
        if (!"exam".equals(container.getContainerType())) {
            return ToolCallResult.failure(request.getToolCallId(), NAME,
                    "容器类型不匹配: 期望 exam，实际 " + container.getContainerType());
        }
        return null;
    }

    /**
     * 构建校验失败的索引级错误响应（旧版 {@link Tool} 接口入口使用）
     */
    private ToolCallResult buildValidationErrorResponse(ToolCallRequest request,
                                                         ExamValidationException e) {
        return ToolCallResult.failure(request.getToolCallId(), NAME, buildValidationErrorMessage(e));
    }

    /**
     * 构建校验失败的索引级错误信息（两套入口共用）
     */
    private String buildValidationErrorMessage(ExamValidationException e) {
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
            log.warn("[SaveExamTool] 测验校验失败，返回 {} 个错误", errorList.size());
            return resultJson;
        } catch (Exception ex) {
            return "测验校验失败，但错误信息序列化异常";
        }
    }
}
