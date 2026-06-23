package org.linxing.linxing_agent.agent.tool.impl;

import dev.langchain4j.agent.tool.P;
import dev.langchain4j.model.chat.request.json.JsonIntegerSchema;
import dev.langchain4j.model.chat.request.json.JsonObjectSchema;
import dev.langchain4j.model.chat.request.json.JsonStringSchema;
import tools.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.linxing.linxing_agent.agent.core.AgentContext;
import org.linxing.linxing_agent.agent.tool.Tool;
import org.linxing.linxing_agent.agent.tool.ToolCallRequest;
import org.linxing.linxing_agent.agent.tool.ToolCallResult;
import org.linxing.linxing_agent.agent.tool.service.WebSearchService;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.stream.Collectors;

@Slf4j
@Component
public class WebSearchTool implements Tool {

    private static final String NAME = "web_search";
    private static final String DESCRIPTION = "搜索互联网获取最新信息，返回相关的网页片段及其来源链接。"
            + "当用户的问题涉及最新资讯、外部知识、或用户笔记中未涵盖的内容时使用此工具。";
    private static final String BRIEF = "搜索互联网，返回相关网页片段";
    private static final String WHEN_TO_USE = "当用户的问题涉及笔记以外的外部知识、最新资讯、或需要联网查询的内容时使用；"
            + "用户笔记中已有的内容应优先使用 search_knowledge_base";

    private final WebSearchService webSearchService;
    private final ObjectMapper objectMapper;

    public WebSearchTool(WebSearchService webSearchService, ObjectMapper objectMapper) {
        this.webSearchService = webSearchService;
        this.objectMapper = objectMapper;
    }

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
                .addProperty("query",
                        JsonStringSchema.builder()
                                .description("搜索查询关键词，使用自然语言描述")
                                .build())
                .addProperty("maxResults",
                        JsonIntegerSchema.builder()
                                .description("返回结果数量，默认为5，最大不超过10")
                                .build())
                .required("query")
                .build();
    }

    @Override
    public ToolCallResult execute(ToolCallRequest request, AgentContext context) {
        String arguments = request.getArguments();
        String query;
        int resultCount;

        try {
            WebSearchArgs args = objectMapper.readValue(arguments, WebSearchArgs.class);
            query = args.getQuery();
            resultCount = args.getMaxResults();
        } catch (Exception e) {
            log.warn("[WebSearchTool] 参数解析失败: {}", arguments, e);
            return ToolCallResult.failure(request.getToolCallId(), NAME,
                    "参数解析失败: " + e.getMessage());
        }

        try {
            List<WebSearchService.SearchHit> hits = webSearchService.search(query, resultCount);
            String resultJson = objectMapper.writeValueAsString(hits);
            return ToolCallResult.success(request.getToolCallId(), NAME, resultJson);
        } catch (IllegalStateException e) {
            log.warn("[WebSearchTool] {}", e.getMessage());
            return ToolCallResult.failure(request.getToolCallId(), NAME, e.getMessage());
        } catch (Exception e) {
            log.error("[WebSearchTool] 搜索异常: {}", e.getMessage(), e);
            return ToolCallResult.failure(request.getToolCallId(), NAME,
                    "搜索执行失败: " + e.getMessage());
        }
    }

    /**
     * 供 subagent 体系使用的 @Tool 入口。
     * 与 {@link #execute(ToolCallRequest, AgentContext)} 共享 {@link WebSearchService} 核心服务。
     */
    @dev.langchain4j.agent.tool.Tool("搜索互联网获取最新信息，返回相关的网页片段及其来源链接。"
            + "当主题涉及最新资讯、外部知识、或用户笔记中未涵盖的内容时使用此工具。")
    public String webSearch(
            @P("搜索查询关键词，使用自然语言描述") String query,
            @P("返回结果数量，默认为5，最大不超过10") int maxResults) {
        try {
            List<WebSearchService.SearchHit> hits = webSearchService.search(query, maxResults);
            if (hits.isEmpty()) {
                return "未找到相关网页内容";
            }
            return hits.stream()
                    .map(hit -> {
                        String title = hit.getTitle() != null ? hit.getTitle() : "";
                        String url = hit.getUrl() != null ? hit.getUrl() : "";
                        String snippet = hit.getSnippet() != null ? hit.getSnippet() : "";
                        return "[标题: " + title + "]\n[链接: " + url + "]\n" + snippet;
                    })
                    .collect(Collectors.joining("\n---\n"));
        } catch (IllegalStateException e) {
            return e.getMessage();
        } catch (Exception e) {
            log.error("[WebSearchTool] @Tool 联网搜索异常: {}", e.getMessage(), e);
            return "联网搜索失败: " + e.getMessage();
        }
    }

    //TODO:实体类移到另外的包下
    @lombok.Data
    @lombok.NoArgsConstructor
    public static class WebSearchArgs {
        private String query;
        private int maxResults;
    }
}
