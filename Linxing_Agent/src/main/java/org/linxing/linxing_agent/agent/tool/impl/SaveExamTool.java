package org.linxing.linxing_agent.agent.tool.impl;

import dev.langchain4j.model.chat.request.json.JsonArraySchema;
import dev.langchain4j.model.chat.request.json.JsonObjectSchema;
import dev.langchain4j.model.chat.request.json.JsonStringSchema;
import tools.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.linxing.linxing_agent.agent.core.AgentContext;
import org.linxing.linxing_agent.agent.service.impl.ExamService;
import org.linxing.linxing_agent.agent.tool.Tool;
import org.linxing.linxing_agent.agent.tool.ToolCallRequest;
import org.linxing.linxing_agent.agent.tool.ToolCallResult;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

@Slf4j
@Component
@RequiredArgsConstructor
public class SaveExamTool implements Tool {

    private static final String NAME = "save_exam";
    private static final String DESCRIPTION = "将生成的测验题目保存到数据库，返回测验ID。"
            + "当生成了知识测验题目后，必须调用此工具将测验持久化。";
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
                .addProperty("answer", JsonStringSchema.builder()
                        .description("正确答案。单选/判断/填空/简答为字符串如\"B\"；多选为JSON数组字符串如\"[\\\"A\\\",\\\"C\\\"]\"").build())
                .addProperty("explanation", JsonStringSchema.builder()
                        .description("答案解析，可选").build())
                .addProperty("difficulty", JsonStringSchema.builder()
                        .description("难度：easy/medium/hard，可选，默认medium").build())
                .required("type", "stem", "answer")
                .build();

        return JsonObjectSchema.builder()
                .addProperty("title", JsonStringSchema.builder()
                        .description("测验标题").build())
                .addProperty("source_type", JsonStringSchema.builder()
                        .description("素材来源：notes / web_search / mixed").build())
                .addProperty("questions", JsonArraySchema.builder()
                        .description("题目数组")
                        .items(questionItemSchema)
                        .build())
                .addProperty("source_refs", JsonArraySchema.builder()
                        .description("素材来源引用列表，如笔记文档名或搜索结果URL。例如: [\"RAG搭建笔记.md\", \"https://example.com/rag-guide\"]")
                        .build())
                .required("title", "questions")
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
            Integer examId = examService.parseAndSave(userId, arguments);

            Map<String, Object> result = new LinkedHashMap<>();
            result.put("examId", examId);
            String resultJson = objectMapper.writeValueAsString(result);

            log.info("[SaveExamTool] 用户 {} 保存测验成功，examId={}", userId, examId);
            return ToolCallResult.success(request.getToolCallId(), NAME, resultJson);
        } catch (ExamService.ExamParseException e) {
            log.warn("[SaveExamTool] 测验JSON解析失败: {}", e.getMessage());
            return ToolCallResult.failure(request.getToolCallId(), NAME,
                    "测验保存失败: " + e.getMessage());
        } catch (Exception e) {
            log.error("[SaveExamTool] 保存测验异常: {}", e.getMessage(), e);
            return ToolCallResult.failure(request.getToolCallId(), NAME,
                    "测验保存异常: " + e.getMessage());
        }
    }
}
