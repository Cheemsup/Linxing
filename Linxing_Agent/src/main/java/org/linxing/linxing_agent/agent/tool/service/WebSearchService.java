package org.linxing.linxing_agent.agent.tool.service;

import dev.langchain4j.web.search.WebSearchEngine;
import dev.langchain4j.web.search.WebSearchRequest;
import dev.langchain4j.web.search.WebSearchResults;
import dev.langchain4j.web.search.tavily.TavilyWebSearchEngine;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 联网搜索核心服务。
 * <p>
 * 封装 Tavily 引擎初始化、搜索请求构造与结果格式化，与 {@code AgentContext} 解耦，
 * 供主循环 {@link org.linxing.linxing_agent.agent.tool.impl.WebSearchTool} 与
 * subagent 体系共用。
 */
@Slf4j
@Service
public class WebSearchService {

    @Value("${agent.web-search.tavily.api-key:}")
    private String tavilyApiKey;

    @Value("${agent.web-search.tavily.max-results:5}")
    private int defaultMaxResults;

    private WebSearchEngine searchEngine;

    @PostConstruct
    public void init() {
        if (tavilyApiKey != null && !tavilyApiKey.isBlank()
                && !tavilyApiKey.startsWith("YOUR_")) {
            searchEngine = TavilyWebSearchEngine.builder()
                    .apiKey(tavilyApiKey)
                    .build();
            log.info("[WebSearchService] Tavily 搜索引擎初始化成功，defaultMaxResults={}", defaultMaxResults);
        } else {
            log.warn("[WebSearchService] Tavily API Key 未配置，联网搜索功能不可用");
        }
    }

    /**
     * 检查联网搜索是否已启用。
     */
    public boolean isEnabled() {
        return searchEngine != null;
    }

    /**
     * 执行联网搜索。
     *
     * @param query     搜索关键词，不能为空
     * @param maxResults 最大返回结果数，小于等于 0 时使用默认值
     * @return 搜索结果列表
     * @throws IllegalStateException    搜索未启用
     * @throws IllegalArgumentException query 为空
     */
    public List<SearchHit> search(String query, int maxResults) {
        if (!isEnabled()) {
            throw new IllegalStateException("联网搜索功能未启用：Tavily API Key 未配置");
        }
        if (query == null || query.isBlank()) {
            throw new IllegalArgumentException("查询关键词不能为空");
        }

        int resultCount = maxResults > 0 ? Math.min(maxResults, 10) : defaultMaxResults;
        log.info("[WebSearchService] 搜索: query={}, maxResults={}", query, resultCount);

        WebSearchRequest searchRequest = WebSearchRequest.builder()
                .searchTerms(query)
                .maxResults(resultCount)
                .build();

        WebSearchResults searchResult = searchEngine.search(searchRequest);

        return searchResult.results().stream()
                .map(hit -> new SearchHit(
                        hit.title(),
                        hit.url() != null ? hit.url().toString() : null,
                        hit.snippet()
                ))
                .collect(Collectors.toList());
    }

    /**
     * 联网搜索结果项。
     */
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class SearchHit {
        private String title;
        private String url;
        private String snippet;
    }
}
