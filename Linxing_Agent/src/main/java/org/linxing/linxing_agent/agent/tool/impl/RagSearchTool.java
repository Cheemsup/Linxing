package org.linxing.linxing_agent.agent.tool.impl;

import dev.langchain4j.model.chat.request.json.JsonIntegerSchema;
import dev.langchain4j.model.chat.request.json.JsonObjectSchema;
import dev.langchain4j.model.chat.request.json.JsonStringSchema;
import tools.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.linxing.linxing_agent.agent.tool.Tool;
import org.linxing.linxing_agent.agent.tool.ToolCallRequest;
import org.linxing.linxing_agent.agent.tool.ToolCallResult;
import org.linxing.linxing_agent.common.userInfoMaintainer.BaseContext;
import org.linxing.linxing_agent.rag.dto.SearchResult;
import org.linxing.linxing_agent.rag.service.ISearchService;
import org.springframework.stereotype.Component;

import java.util.List;

@Slf4j
@Component
@RequiredArgsConstructor
public class RagSearchTool implements Tool {

    private static final String NAME = "search_knowledge_base";
    private static final String DESCRIPTION = "搜索用户个人知识库中的笔记和文档，返回相关的文本片段及其来源信息。"
            + "当需要查找用户笔记中存储的信息、知识点、或任何用户自己记录的内容时使用此工具。";
    private static final String BRIEF = "搜索用户个人知识库，返回相关笔记片段";
    private static final String WHEN_TO_USE = "当用户的问题涉及自己记录的笔记、文档内容时使用；"
            + "通用知识问题不需要使用此工具";

    private final ISearchService searchService;
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

    /**
     * 工具的JSON Schema
     * @return
     */
    @Override
    public JsonObjectSchema spec() {
        return JsonObjectSchema.builder()
                .addProperty("query",
                        JsonStringSchema.builder()
                                .description("搜索查询关键词，支持自然语言描述")
                                .build())
                .addProperty("topK",
                        JsonIntegerSchema.builder()
                                .description("返回结果数量，默认为5，最大不超过10")
                                .build())
                .required("query")
                .build();
    }

    @Override
    public ToolCallResult execute(ToolCallRequest request) {
        Long userIdLong = BaseContext.getCurrentId();
        if (userIdLong == null) {
            return ToolCallResult.failure(request.getToolCallId(), NAME, "用户未登录");
        }

        String arguments = request.getArguments();
        String query;
        int topK;

        try {
            RagSearchArgs args = objectMapper.readValue(arguments, RagSearchArgs.class);
            query = args.getQuery();
            topK = args.getTopK() > 0 ? args.getTopK() : 5;
        } catch (Exception e) {
            log.warn("[RagSearchTool] 参数解析失败: {}", arguments, e);
            return ToolCallResult.failure(request.getToolCallId(), NAME,
                    "参数解析失败: " + e.getMessage());
        }

        if (query == null || query.isBlank()) {
            return ToolCallResult.failure(request.getToolCallId(), NAME, "查询关键词不能为空");
        }

        try {
            int userId = userIdLong.intValue();
            log.info("[RagSearchTool] 用户{} 搜索: query={}, topK={}", userId, query, topK);
            List<SearchResult> results = searchService.search(userId, query, topK, true);

            String resultJson = objectMapper.writeValueAsString(results);
            return ToolCallResult.success(request.getToolCallId(), NAME, resultJson);
        } catch (Exception e) {
            log.error("[RagSearchTool] 搜索异常: {}", e.getMessage(), e);
            return ToolCallResult.failure(request.getToolCallId(), NAME,
                    "搜索执行失败: " + e.getMessage());
        }
    }

    @lombok.Data
    @lombok.NoArgsConstructor
    public static class RagSearchArgs {
        private String query;
        private int topK;
    }
}
