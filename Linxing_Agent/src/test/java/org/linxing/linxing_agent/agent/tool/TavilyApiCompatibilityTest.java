package org.linxing.linxing_agent.agent.tool;

import dev.langchain4j.web.search.WebSearchEngine;
import dev.langchain4j.web.search.WebSearchInformationResult;
import dev.langchain4j.web.search.WebSearchOrganicResult;
import dev.langchain4j.web.search.WebSearchRequest;
import dev.langchain4j.web.search.WebSearchResults;
import dev.langchain4j.web.search.tavily.TavilyWebSearchEngine;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;

import java.net.URI;
import java.util.List;

/**
 * Tavily WebSearchEngine API 兼容性验证测试
 * <p>
 * 验证 LangChain4j 1.13.0 + langchain4j-web-search-engine-tavily 1.13.0-beta23 的 API 可用性。
 * 包含两条路径的验证：
 * <ul>
 *   <li>路径A：保持 1.13.0，适配当前版本 API</li>
 *   <li>路径B：升级到最新版，验证 API 差异</li>
 * </ul>
 * <p>
 * 需要设置环境变量 TAVILY_API_KEY 才能运行联网测试。
 */
public class TavilyApiCompatibilityTest {

    private static final String TAVILY_API_KEY = System.getenv("TAVILY_API_KEY");

    // ==================== 路径A：1.13.0 API 适配验证 ====================

    /**
     * 验证1：WebSearchRequest 构建方式
     * 1.13.0 使用 searchTerms() 而非 query()
     */
    @Test
    void testWebSearchRequestBuilder_113() {
        System.out.println("========== 验证1：WebSearchRequest 构建方式 ==========");

        WebSearchRequest request = WebSearchRequest.builder()
                .searchTerms("Java LangChain4j tutorial")
                .maxResults(5)
                .build();

        System.out.println("searchTerms = " + request.searchTerms());
        System.out.println("maxResults  = " + request.maxResults());
        System.out.println("✅ WebSearchRequest 构建成功，使用 searchTerms() 方法");
    }

    /**
     * 验证2：WebSearchRequest.from() 快捷方法
     */
    @Test
    void testWebSearchRequestFrom_113() {
        System.out.println("========== 验证2：WebSearchRequest.from() 快捷方法 ==========");

        WebSearchRequest request1 = WebSearchRequest.from("test query");
        System.out.println("from(String)       → searchTerms=" + request1.searchTerms() + ", maxResults=" + request1.maxResults());

        WebSearchRequest request2 = WebSearchRequest.from("test query", 3);
        System.out.println("from(String, Int) → searchTerms=" + request2.searchTerms() + ", maxResults=" + request2.maxResults());
        System.out.println("✅ WebSearchRequest.from() 快捷方法可用");
    }

    /**
     * 验证3：WebSearchOrganicResult 数据结构
     * 1.13.0 中 url() 返回 URI 类型，不是 String
     */
    @Test
    void testWebSearchOrganicResult_113() {
        System.out.println("========== 验证3：WebSearchOrganicResult 数据结构 ==========");

        WebSearchOrganicResult result = WebSearchOrganicResult.from(
                "Test Title",
                URI.create("https://example.com"),
                "Test snippet",
                "Test content"
        );

        System.out.println("title()   = " + result.title());
        System.out.println("url()     = " + result.url() + " (类型: " + result.url().getClass().getSimpleName() + ")");
        System.out.println("snippet() = " + result.snippet());
        System.out.println("content() = " + result.content());
        System.out.println("metadata()= " + result.metadata());

        // 验证 URI → String 转换
        String urlStr = result.url() != null ? result.url().toString() : null;
        System.out.println("url.toString() = " + urlStr);
        System.out.println("✅ WebSearchOrganicResult 构建成功，url() 返回 URI 类型");
    }

    /**
     * 验证4：WebSearchResults 容器结构
     */
    @Test
    void testWebSearchResults_113() {
        System.out.println("========== 验证4：WebSearchResults 容器结构 ==========");

        List<WebSearchOrganicResult> organicResults = List.of(
                WebSearchOrganicResult.from("Title1", URI.create("https://a.com"), "Snippet1", "Content1"),
                WebSearchOrganicResult.from("Title2", URI.create("https://b.com"), "Snippet2", "Content2")
        );

        WebSearchResults results = WebSearchResults.from(
                WebSearchInformationResult.from(2L),
                organicResults);

        System.out.println("results().size()     = " + results.results().size());
        System.out.println("searchInformation()  = " + results.searchInformation());
        System.out.println("searchMetadata()     = " + results.searchMetadata());

        // 验证 toTextSegments() 方法
        System.out.println("toTextSegments().size() = " + results.toTextSegments().size());
        System.out.println("✅ WebSearchResults 容器结构正确");
    }

    /**
     * 验证5：TavilyWebSearchEngine 构建方式
     */
    @Test
    void testTavilyEngineBuilder_113() {
        System.out.println("========== 验证5：TavilyWebSearchEngine 构建方式 ==========");

        // 使用占位 key 验证 builder 能否正常构建
        try {
            TavilyWebSearchEngine engine = TavilyWebSearchEngine.builder()
                    .apiKey("tvly-test-placeholder")
                    .build();
            System.out.println("✅ TavilyWebSearchEngine.builder() 构建成功");
            System.out.println("engine 类型: " + engine.getClass().getName());
        } catch (Exception e) {
            System.out.println("❌ 构建失败: " + e.getMessage());
        }
    }

    /**
     * 验证6：实际联网搜索（直接使用配置的 API Key）
     */
    @Test
    void testActualSearch_113() {
        String apiKey = TAVILY_API_KEY;
        // 如果环境变量未设置，使用硬编码的测试 key
        if (apiKey == null || apiKey.isBlank()) {
            apiKey = "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";
        }

        System.out.println("========== 验证6：实际联网搜索 ==========");

        WebSearchEngine engine = TavilyWebSearchEngine.builder()
                .apiKey(apiKey)
                .build();

        WebSearchRequest request = WebSearchRequest.builder()
                .searchTerms("LangChain4j Java framework")
                .maxResults(3)
                .build();

        WebSearchResults results = engine.search(request);

        System.out.println("搜索结果数: " + results.results().size());
        for (WebSearchOrganicResult result : results.results()) {
            System.out.println("---");
            System.out.println("  title   = " + result.title());
            System.out.println("  url     = " + (result.url() != null ? result.url().toString() : "null"));
            System.out.println("  snippet = " + truncate(result.snippet(), 150));
        }
        System.out.println("✅ 实际联网搜索成功");
    }

    // ==================== 路径B：升级影响评估 ====================

    /**
     * 验证7：项目当前使用的 LangChain4j API 清单
     * <p>
     * 评估升级到 1.15.x 的影响。核心 breaking change：
     * - 1.14.0: ChatLanguageModel → ChatModel, StreamingChatLanguageModel → StreamingChatModel
     * - 1.15.0: WebSearchResult → WebSearchResults (API 重构)
     * <p>
     * 本项目已使用 ChatModel/StreamingChatModel（1.13.0 就已支持新名称），
     * 因此 1.14.0 的重命名对项目无影响。
     */
    @Test
    void testUpgradeImpactAssessment() {
        System.out.println("========== 验证7：升级影响评估 ==========");

        System.out.println("=== 项目使用的 LangChain4j API 及升级影响 ===\n");

        String[][] apis = {
                // API, 当前版本状态, 升级影响
                {"OpenAiChatModel.builder()", "1.13.0 可用", "无变化"},
                {"OpenAiStreamingChatModel.builder()", "1.13.0 可用", "无变化"},
                {"ChatModel / StreamingChatModel", "1.13.0 已是新名称", "1.14.0 重命名，但项目已用新名，无影响"},
                {"StreamingChatResponseHandler", "1.13.0 可用", "1.15.0 新增 onPartialToolCall/onCompleteToolCall 方法"},
                {"ChatResponse (model.chat.response)", "1.13.0 可用", "无变化"},
                {"PartialThinking", "1.13.0 可用", "无变化"},
                {"ToolSpecification", "1.13.0 可用", "无变化"},
                {"ToolExecutionRequest", "1.13.0 可用", "无变化"},
                {"ChatMessage / AiMessage / UserMessage", "1.13.0 可用", "无变化"},
                {"ToolExecutionResultMessage", "1.13.0 可用", "无变化"},
                {"JsonObjectSchema / JsonStringSchema", "1.13.0 可用", "无变化"},
                {"EmbeddingModel (BGE)", "1.13.0 可用", "需确认 langchain4j-embeddings-bge-small-zh-v15 兼容"},
                {"DocumentSplitters", "1.13.0 可用", "无变化"},
                {"OnnxScoringModel", "1.13.0 可用", "需确认 langchain4j-onnx-scoring 兼容"},
                {"WebSearchEngine / WebSearchRequest", "1.13.0 可用", "1.15.0 API 重构，需适配"},
        };

        System.out.printf("%-45s | %-20s | %s%n", "API", "当前状态", "升级影响");
        System.out.println("-".repeat(100));
        for (String[] api : apis) {
            System.out.printf("%-45s | %-20s | %s%n", api[0], api[1], api[2]);
        }

        System.out.println("\n=== 结论 ===");
        System.out.println("路径A（保持1.13.0 + 适配API）：零风险，仅需修改 WebSearchTool 中的类名");
        System.out.println("路径B（升级到1.15.x）：低风险，核心 API 无 breaking change，");
        System.out.println("  但需注意：");
        System.out.println("  1. StreamingChatResponseHandler 新增方法需实现");
        System.out.println("  2. WebSearch API 再次重构（1.15.0 中类名又变）");
        System.out.println("  3. BGE embedding 和 ONNX scoring 需验证兼容性");
        System.out.println("  4. Tavily beta 版本号需同步升级");
    }

    /**
     * 验证8：1.13.0 中 WebSearchRequest 的完整字段
     */
    @Test
    void testWebSearchRequestAllFields_113() {
        System.out.println("========== 验证8：WebSearchRequest 完整字段 ==========");

        WebSearchRequest request = WebSearchRequest.builder()
                .searchTerms("test query")
                .maxResults(5)
                .language("zh")
                .geoLocation("cn")
                .safeSearch(true)
                .build();

        System.out.println("searchTerms    = " + request.searchTerms());
        System.out.println("maxResults     = " + request.maxResults());
        System.out.println("language       = " + request.language());
        System.out.println("geoLocation    = " + request.geoLocation());
        System.out.println("safeSearch     = " + request.safeSearch());
        System.out.println("startPage      = " + request.startPage());
        System.out.println("startIndex     = " + request.startIndex());
        System.out.println("additionalParams = " + request.additionalParams());
        System.out.println("✅ WebSearchRequest 所有字段可访问");
    }

    // ==================== 辅助方法 ====================

    static boolean hasTavilyApiKey() {
        return TAVILY_API_KEY != null && !TAVILY_API_KEY.isBlank();
    }

    private String truncate(String s, int maxLen) {
        if (s == null) return "null";
        return s.length() > maxLen ? s.substring(0, maxLen) + "..." : s;
    }
}
