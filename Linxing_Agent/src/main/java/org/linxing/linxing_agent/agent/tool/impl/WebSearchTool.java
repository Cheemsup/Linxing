package org.linxing.linxing_agent.agent.tool.impl;

import dev.langchain4j.model.chat.request.json.JsonIntegerSchema;
import dev.langchain4j.model.chat.request.json.JsonObjectSchema;
import dev.langchain4j.model.chat.request.json.JsonStringSchema;
import dev.langchain4j.web.search.WebSearchEngine;
import dev.langchain4j.web.search.WebSearchOrganicResult;
import dev.langchain4j.web.search.WebSearchRequest;
import dev.langchain4j.web.search.WebSearchResults;
import dev.langchain4j.web.search.tavily.TavilyWebSearchEngine;
import tools.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.linxing.linxing_agent.agent.core.AgentContext;
import org.linxing.linxing_agent.agent.tool.Tool;
import org.linxing.linxing_agent.agent.tool.ToolCallRequest;
import org.linxing.linxing_agent.agent.tool.ToolCallResult;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;
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

    @Value("${agent.web-search.tavily.api-key:}")
    private String tavilyApiKey;

    @Value("${agent.web-search.tavily.max-results:5}")
    private int maxResults;

    private WebSearchEngine searchEngine;

    private final ObjectMapper objectMapper;

    public WebSearchTool(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @PostConstruct
    public void init() {
        if (tavilyApiKey != null && !tavilyApiKey.isBlank()
                && !tavilyApiKey.startsWith("YOUR_")) {
            searchEngine = TavilyWebSearchEngine.builder()
                    .apiKey(tavilyApiKey)
                    .build();
            log.info("[WebSearchTool] Tavily 搜索引擎初始化成功，maxResults={}", maxResults);
        } else {
            log.warn("[WebSearchTool] Tavily API Key 未配置，联网搜索功能不可用");
        }
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
        if (searchEngine == null) {
            return ToolCallResult.failure(request.getToolCallId(), NAME,
                    "联网搜索功能未启用：Tavily API Key 未配置");
        }

        String arguments = request.getArguments();
        String query;
        int resultCount;

        try {
            WebSearchArgs args = objectMapper.readValue(arguments, WebSearchArgs.class);
            query = args.getQuery();
            resultCount = args.getMaxResults() > 0 ? Math.min(args.getMaxResults(), 10) : maxResults;
        } catch (Exception e) {
            log.warn("[WebSearchTool] 参数解析失败: {}", arguments, e);
            return ToolCallResult.failure(request.getToolCallId(), NAME,
                    "参数解析失败: " + e.getMessage());
        }

        if (query == null || query.isBlank()) {
            return ToolCallResult.failure(request.getToolCallId(), NAME, "查询关键词不能为空");
        }

        try {
            log.info("[WebSearchTool] 搜索: query={}, maxResults={}", query, resultCount);
            WebSearchRequest searchRequest = WebSearchRequest.builder()
                    .searchTerms(query)
                    .maxResults(resultCount)
                    .build();

            WebSearchResults searchResult = searchEngine.search(searchRequest);

            List<SearchHit> hits = searchResult.results().stream()
                    .map(hit -> new SearchHit(
                            hit.title(),
                            hit.url() != null ? hit.url().toString() : null,
                            hit.snippet()
                    ))
                    .collect(Collectors.toList());

            String resultJson = objectMapper.writeValueAsString(hits);
            return ToolCallResult.success(request.getToolCallId(), NAME, resultJson);
        } catch (Exception e) {
            log.error("[WebSearchTool] 搜索异常: {}", e.getMessage(), e);
            return ToolCallResult.failure(request.getToolCallId(), NAME,
                    "搜索执行失败: " + e.getMessage());
        }
    }

    @lombok.Data
    @lombok.NoArgsConstructor
    public static class WebSearchArgs {
        private String query;
        private int maxResults;
    }

    @lombok.Data
    @lombok.NoArgsConstructor
    @lombok.AllArgsConstructor
    public static class SearchHit {
        private String title;
        private String url;
        private String snippet;
    }
}
