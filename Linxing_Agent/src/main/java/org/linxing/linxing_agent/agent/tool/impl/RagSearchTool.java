package org.linxing.linxing_agent.agent.tool.impl;

import dev.langchain4j.agent.tool.P;
import dev.langchain4j.model.chat.request.json.JsonIntegerSchema;
import dev.langchain4j.model.chat.request.json.JsonObjectSchema;
import dev.langchain4j.model.chat.request.json.JsonStringSchema;
import tools.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.linxing.linxing_agent.agent.core.AgentContext;
import org.linxing.linxing_agent.agent.subagent.SubAgentContext;
import org.linxing.linxing_agent.agent.tool.Tool;
import org.linxing.linxing_agent.agent.tool.ToolCallRequest;
import org.linxing.linxing_agent.agent.tool.ToolCallResult;
import org.linxing.linxing_agent.rag.dto.SearchResult;
import org.linxing.linxing_agent.rag.service.ISearchService;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.stream.Collectors;

@Slf4j
@Component
@RequiredArgsConstructor
public class RagSearchTool implements Tool {

    private static final String NAME = "search_knowledge_base";
    private static final String DESCRIPTION = "搜索用户个人知识库中的笔记和文档，返回相关的文本片段及其来源信息。"
            + "当需要查找用户笔记中存储的信息、知识点、或任何用户自己记录的内容时使用此工具。";
    private static final String BRIEF = "搜索用户个人知识库，返回相关笔记片段";
    private static final String DISPLAY_LABEL = "搜索知识库";
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

    @Override
    public String displayLabel() {
        return DISPLAY_LABEL;
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
    public ToolCallResult execute(ToolCallRequest request, AgentContext context) {
        Integer userId = context.getUserId();
        if (userId == null) {
            return ToolCallResult.failure(request.getToolCallId(), NAME, "用户未登录");
        }

        String arguments = request.getArguments();
        String query;
        int topK;

        try {
            RagSearchArgs args = objectMapper.readValue(arguments, RagSearchArgs.class);
            query = args.getQuery();
            topK = args.getTopK();
        } catch (Exception e) {
            log.warn("[RagSearchTool] 参数解析失败: {}", arguments, e);
            return ToolCallResult.failure(request.getToolCallId(), NAME,
                    "参数解析失败: " + e.getMessage());
        }

        try {
            String resultJson = doSearch(userId, query, topK);
            return ToolCallResult.success(request.getToolCallId(), NAME, resultJson);
        } catch (IllegalArgumentException e) {
            return ToolCallResult.failure(request.getToolCallId(), NAME, e.getMessage());
        } catch (Exception e) {
            log.error("[RagSearchTool] 搜索异常: {}", e.getMessage(), e);
            return ToolCallResult.failure(request.getToolCallId(), NAME,
                    "搜索执行失败: " + e.getMessage());
        }
    }

    /**
     * 供 subagent 体系使用的 @Tool 入口。
     * 与 {@link #execute(ToolCallRequest, AgentContext)} 共享 {@link ISearchService}，
     * userId 从 {@link SubAgentContext} 读取，避免作为 LLM 可控参数暴露。
     */
    @dev.langchain4j.agent.tool.Tool("搜索用户个人知识库中的笔记和文档，返回相关的文本片段及其来源信息。"
            + "当需要查找用户自己记录的笔记、文档内容时使用此工具。")
    public String searchKnowledgeBase(
            @P("搜索查询关键词，支持自然语言描述") String query,
            @P("返回结果数量，默认为5，最大不超过10") int topK) {
        Integer userId = SubAgentContext.currentUserId();
        if (userId == null) {
            return "用户未登录，无法搜索知识库";
        }
        if (query == null || query.isBlank()) {
            return "查询关键词不能为空";
        }
        try {
            return doSearch(userId, query, topK);
        } catch (IllegalArgumentException e) {
            return e.getMessage();
        } catch (Exception e) {
            log.error("[RagSearchTool] @Tool 知识库搜索异常: {}", e.getMessage(), e);
            return "知识库搜索失败: " + e.getMessage();
        }
    }

    /**
     * 核心搜索逻辑，两个入口共用。
     */
    private String doSearch(Integer userId, String query, int topK) throws Exception {
        if (query == null || query.isBlank()) {
            throw new IllegalArgumentException("查询关键词不能为空");
        }
        int resultCount = topK > 0 ? Math.min(topK, 10) : 5;
        log.info("[RagSearchTool] 用户{} 搜索: query={}, topK={}", userId, query, resultCount);
        List<SearchResult> results = searchService.search(userId, query, resultCount, true);
        return objectMapper.writeValueAsString(results);
    }

    //TODO:实体类移到另外的包下
    @lombok.Data
    @lombok.NoArgsConstructor
    public static class RagSearchArgs {
        private String query;
        private int topK;
    }
}
