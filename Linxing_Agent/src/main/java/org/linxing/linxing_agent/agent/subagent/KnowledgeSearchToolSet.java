package org.linxing.linxing_agent.agent.subagent;

import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import dev.langchain4j.web.search.WebSearchEngine;
import dev.langchain4j.web.search.WebSearchRequest;
import dev.langchain4j.web.search.WebSearchResults;
import dev.langchain4j.web.search.tavily.TavilyWebSearchEngine;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.linxing.linxing_agent.agent.tool.impl.RagSearchTool;
import org.linxing.linxing_agent.agent.tool.impl.WebSearchTool;
import org.linxing.linxing_agent.agent.tool.service.WebSearchService;
import org.linxing.linxing_agent.rag.dto.SearchResult;
import org.linxing.linxing_agent.rag.service.ISearchService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.stream.Collectors;

/**
 * 知识搜索工具集（使用@Tool 注解，供 KnowledgeCollectorAgent 内部 tool-calling 使用）。
 * <p>
 * <strong>已废弃</strong>：联网搜索与知识库搜索已分别迁移到
 * {@link WebSearchTool} 与 {@link RagSearchTool} 的 @Tool 方法，
 * 二者通过 {@link WebSearchService} 与 {@link ISearchService} 复用同一套核心服务，
 * 避免重复实现与配置。请直接使用 {@link WebSearchTool} / {@link RagSearchTool}。
 *
 * @deprecated 使用 {@link WebSearchTool} 与 {@link RagSearchTool} 替代
 */
@Slf4j
@Component
@Deprecated(since = "2026-06-21", forRemoval = false)
public class KnowledgeSearchToolSet {

    private final ISearchService searchService;

    @Value("${agent.web-search.tavily.api-key:}")
    private String tavilyApiKey;

    @Value("${agent.web-search.tavily.max-results:5}")
    private int maxResults;

    private WebSearchEngine webSearchEngine;

    public KnowledgeSearchToolSet(ISearchService searchService) {
        this.searchService = searchService;
    }

    @PostConstruct
    public void init() {
        if (tavilyApiKey != null && !tavilyApiKey.isBlank()
                && !tavilyApiKey.startsWith("YOUR_")) {
            webSearchEngine = TavilyWebSearchEngine.builder()
                    .apiKey(tavilyApiKey)
                    .build();
            log.info("[KnowledgeSearchTools] Tavily 搜索引擎初始化成功，maxResults={}", maxResults);
        } else {
            log.warn("[KnowledgeSearchTools] Tavily API Key 未配置，联网搜索功能不可用");
        }
    }

    /**
     * 搜索用户个人知识库中的笔记和文档。
     *
     * @deprecated 使用 {@link RagSearchTool#searchKnowledgeBase(String, int)} 替代
     */
    @Deprecated(since = "2026-06-21", forRemoval = false)
    @Tool("搜索用户个人知识库中的笔记和文档，返回相关的文本片段及其来源信息。"
            + "当需要查找用户自己记录的笔记、文档内容时使用此工具。")
    public String searchKnowledgeBase(
            @P("搜索查询关键词，支持自然语言描述") String query,
            @P("当前用户 ID") Integer userId) {
        if (userId == null) {
            return "用户未登录，无法搜索知识库";
        }
        if (query == null || query.isBlank()) {
            return "查询关键词不能为空";
        }
        try {
            log.info("[KnowledgeSearchTools] 知识库搜索: userId={}, query={}", userId, query);
            List<SearchResult> results = searchService.search(userId, query, 5, true);
            if (results.isEmpty()) {
                return "未找到相关笔记内容";
            }
            return results.stream()
                    .map(r -> {
                        String file = r.getFileName() != null ? r.getFileName() : "未知来源";
                        String text = r.getChunkText() != null ? r.getChunkText() : "";
                        return "[来源: " + file + "]\n" + text;
                    })
                    .collect(Collectors.joining("\n---\n"));
        } catch (Exception e) {
            log.error("[KnowledgeSearchTools] 知识库搜索异常: {}", e.getMessage(), e);
            return "知识库搜索失败: " + e.getMessage();
        }
    }

    /**
     * 搜索互联网获取最新信息。
     *
     * @deprecated 使用 {@link WebSearchTool#webSearch(String, int)} 替代
     */
    @Deprecated(since = "2026-06-21", forRemoval = false)
    @Tool("搜索互联网获取最新信息，返回相关的网页片段及其来源链接。"
            + "当主题涉及最新资讯、外部知识、或用户笔记中未涵盖的内容时使用此工具。")
    public String webSearch(@P("搜索查询关键词，使用自然语言描述") String query) {
        if (webSearchEngine == null) {
            return "联网搜索功能未启用：Tavily API Key 未配置";
        }
        if (query == null || query.isBlank()) {
            return "查询关键词不能为空";
        }
        try {
            log.info("[KnowledgeSearchTools] 联网搜索: query={}", query);
            WebSearchRequest searchRequest = WebSearchRequest.builder()
                    .searchTerms(query)
                    .maxResults(maxResults)
                    .build();
            WebSearchResults searchResult = webSearchEngine.search(searchRequest);
            List<String> hits = searchResult.results().stream()
                    .map(hit -> {
                        String title = hit.title() != null ? hit.title() : "";
                        String url = hit.url() != null ? hit.url().toString() : "";
                        String snippet = hit.snippet() != null ? hit.snippet() : "";
                        return "[标题: " + title + "]\n[链接: " + url + "]\n" + snippet;
                    })
                    .collect(Collectors.toList());
            if (hits.isEmpty()) {
                return "未找到相关网页内容";
            }
            return String.join("\n---\n", hits);
        } catch (Exception e) {
            log.error("[KnowledgeSearchTools] 联网搜索异常: {}", e.getMessage(), e);
            return "联网搜索失败: " + e.getMessage();
        }
    }
}
