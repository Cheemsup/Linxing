package org.linxing.linxing_agent.agent.tool.impl;

import dev.langchain4j.model.chat.request.json.JsonArraySchema;
import dev.langchain4j.model.chat.request.json.JsonObjectSchema;
import dev.langchain4j.model.chat.request.json.JsonStringSchema;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.linxing.linxing_agent.agent.core.AgentContext;
import org.linxing.linxing_agent.agent.core.JsonContainer;
import org.linxing.linxing_agent.agent.service.impl.ExamService;
import org.linxing.linxing_agent.agent.tool.Tool;
import org.linxing.linxing_agent.agent.tool.ToolCallRequest;
import org.linxing.linxing_agent.agent.tool.ToolCallResult;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@Component
@RequiredArgsConstructor
public class SaveExamTool implements Tool {

    private static final String NAME = "save_exam";
    private static final String DESCRIPTION = "将生成的测验题目保存到数据库，返回测验ID。"
            + "支持两种模式：1) 一次性传入完整参数（简单场景，5题以内）；"
            + "2) 传入 container_id 从容器读取分批构建的数据（大批量场景，超过5题时推荐）。";
    private static final String BRIEF = "保存生成的测验题目到数据库";
    private static final String WHEN_TO_USE = "当已生成完整的测验题目JSON后，必须调用此工具保存；"
            + "仅在生成测验时使用，普通问答不需要";

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
                        .description("选项数组，选择题必填，如 [\"A.选项1\",\"B.选项2\",\"C.选项3\",\"D.选项4\"]；填空题/简答题/判断题不需要")
                        .build())
                .addProperty("answer", JsonArraySchema.builder()
                        .description("正确答案。多选题传数组如[\"A\",\"C\"]；其余题型传单元素数组如[\"B\"]或直接传字符串\"B\"均可").build())
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

            // 判断是否为分批模式
            if (root.has("container_id") && !root.get("container_id").asText().isBlank()) {
                return executeFromContainer(request, context, userId, root.get("container_id").asText());
            } else {
                return executeDirect(request, userId, arguments);
            }
        } catch (ExamService.ExamParseException e) {
            log.warn("[SaveExamTool] 测验JSON解析失败: {}", e.getMessage());
            return ToolCallResult.failure(request.getToolCallId(), NAME,
                    "测验保存失败: " + e.getMessage());
        } catch (ExamService.ExamValidationException e) {
            // 分批模式校验失败，返回索引级错误
            return buildValidationErrorResponse(request, e);
        } catch (Exception e) {
            log.error("[SaveExamTool] 保存测验异常: {}", e.getMessage(), e);
            return ToolCallResult.failure(request.getToolCallId(), NAME,
                    "测验保存异常: " + e.getMessage());
        }
    }

    /**
     * 一次性调用模式：从 arguments 直接解析
     */
    private ToolCallResult executeDirect(ToolCallRequest request, Integer userId, String arguments) throws Exception {
        Integer examId = examService.parseAndSave(userId, arguments);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("examId", examId);
        String resultJson = objectMapper.writeValueAsString(result);

        log.info("[SaveExamTool] 用户 {} 保存测验成功（直接模式），examId={}", userId, examId);
        return ToolCallResult.success(request.getToolCallId(), NAME, resultJson);
    }

    /**
     * 分批模式：从容器读取拼装的 JSON
     */
    private ToolCallResult executeFromContainer(ToolCallRequest request, AgentContext context,
                                                 Integer userId, String containerId) throws Exception {
        JsonContainer container = context.getContainer(containerId);
        if (container == null) {
            return ToolCallResult.failure(request.getToolCallId(), NAME,
                    "容器不存在: " + containerId);
        }

        if (!"exam".equals(container.getContainerType())) {
            return ToolCallResult.failure(request.getToolCallId(), NAME,
                    "容器类型不匹配: 期望 exam，实际 " + container.getContainerType());
        }

        // 拼装完整 JSON
        ObjectNode fullJson = container.assemble(objectMapper);

        // 校验并持久化
        Integer examId = examService.parseAndSaveFromContainer(userId, fullJson);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("examId", examId);
        result.put("questionCount", fullJson.get("questions").size());
        String resultJson = objectMapper.writeValueAsString(result);

        log.info("[SaveExamTool] 用户 {} 保存测验成功（分批模式），examId={}, 题数={}",
                userId, examId, fullJson.get("questions").size());
        return ToolCallResult.success(request.getToolCallId(), NAME, resultJson);
    }

    /**
     * 构建校验失败的索引级错误响应
     */
    private ToolCallResult buildValidationErrorResponse(ToolCallRequest request,
                                                         ExamService.ExamValidationException e) {
        List<Map<String, Object>> errorList = new ArrayList<>();
        for (ExamService.QuestionError err : e.getErrors()) {
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
            return ToolCallResult.failure(request.getToolCallId(), NAME, resultJson);
        } catch (Exception ex) {
            return ToolCallResult.failure(request.getToolCallId(), NAME,
                    "测验校验失败，但错误信息序列化异常");
        }
    }
}
