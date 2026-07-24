package org.linxing.linxing_agent.agent.core;

import dev.langchain4j.agent.tool.ToolSpecification;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.linxing.linxing_agent.agent.catalog.Catalog;
import org.linxing.linxing_agent.agent.catalog.CatalogEntry;
import org.linxing.linxing_agent.agent.catalog.CatalogProvider;
import org.linxing.linxing_agent.agent.memory.window.builder.DefaultContextBuilder;
import org.linxing.linxing_agent.agent.skill.SkillRegistry;
import org.linxing.linxing_agent.agent.tool.ToolCallResult;
import org.linxing.linxing_agent.agent.tool.ToolRegistry;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.lang.reflect.Method;
import java.util.List;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@DisplayName("AgentExecutor 渐进式披露机制测试")
class AgentExecutorDisclosureTest {

    @Autowired
    private ToolRegistry toolRegistry;

    @Autowired
    private SkillRegistry skillRegistry;

    @Autowired
    private DefaultContextBuilder defaultContextBuilder;

    @Autowired
    private List<CatalogProvider> catalogProviders;

    @Autowired
    private ObjectMapper objectMapper;

    @Value("${agent.disclosure.threshold:5}")
    private int disclosureThreshold;

    @Test
    @DisplayName("目录内容：过滤元工具后应包含业务工具，不应泄露 catalog/resolve")
    void testCatalogFiltersMetaTools() {
        List<CatalogEntry> allEntries = catalogProviders.stream()
                .flatMap(p -> p.catalogEntries().stream())
                .collect(Collectors.toList());

        List<CatalogEntry> filtered = allEntries.stream()
                .filter(e -> !Catalog.META_TOOLS.contains(e.getName()))
                .collect(Collectors.toList());

        assertFalse(filtered.isEmpty(), "过滤后不应为空，至少应有 search_knowledge_base");

        for (CatalogEntry entry : filtered) {
            assertFalse(Catalog.META_TOOLS.contains(entry.getName()),
                    "目录不应包含元工具: " + entry.getName());
            assertNotNull(entry.getName(), "条目名称不能为 null");
            assertNotNull(entry.getBrief(), "条目简介不能为 null — " + entry.getName());
            assertFalse(entry.getBrief().isBlank(), "条目简介不能为空 — " + entry.getName());
        }
    }

    @Test
    @DisplayName("目录文本格式：Catalog.toPromptText() 应生成结构化的 LLM 可读文本")
    void testCatalogPromptTextFormat() {
        List<CatalogEntry> allEntries = catalogProviders.stream()
                .flatMap(p -> p.catalogEntries().stream())
                .filter(e -> !Catalog.META_TOOLS.contains(e.getName()))
                .collect(Collectors.toList());

        Catalog catalog = new Catalog(allEntries);
        String promptText = catalog.toPromptText();

        assertNotNull(promptText);
        assertFalse(promptText.isBlank(), "目录文本不应为空");
        assertTrue(promptText.contains("可用能力目录："),
                "目录文本应包含标题头");
        assertTrue(promptText.contains("简介："),
                "目录文本应包含简介字段");

        if (allEntries.size() > 1) {
            assertTrue(promptText.contains("1.") || promptText.contains("\n1"),
                    "多条目时应有序号");
        }

        assertTrue(promptText.contains("工具") || promptText.contains("技能"),
                "目录文本应标注条目类型");
    }

    @Test
    @DisplayName("全量 ToolSpecification：每个规格应包含 name、description、parameters（非空）")
    void testFullToolSpecificationsHaveCompleteSchema() {
        List<ToolSpecification> specs = toolRegistry.getToolSpecifications();

        assertFalse(specs.isEmpty(), "应至少注册了 1 个工具");

        for (ToolSpecification spec : specs) {
            assertNotNull(spec.name(), "name 不能为 null");
            assertFalse(spec.name().isBlank(), "name 不能为空 — spec: " + spec);

            assertNotNull(spec.description(), "description 不能为 null — " + spec.name());
            assertFalse(spec.description().isBlank(), "description 不能为空 — " + spec.name());

            assertNotNull(spec.parameters(), "parameters 不能为 null — " + spec.name());
        }
    }

    @Test
    @DisplayName("resolve() 返回严格 OpenAI function-calling JSON 格式")
    void testResolveReturnsFunctionCallingJson() throws Exception {
        String toolName = "search_knowledge_base";
        ToolSpecification spec = toolRegistry.getToolSpecification(toolName);
        assertNotNull(spec, toolName + " 应已注册");

        String resolved = toolRegistry.resolve(List.of(toolName));

        assertNotNull(resolved);
        assertFalse(resolved.isBlank(), "resolve 结果不能为空");
        assertFalse(resolved.startsWith("#"), "不应是 Markdown 格式");
        assertFalse(resolved.startsWith("未找到"), "应找到已注册工具");

        JsonNode root = objectMapper.readTree(resolved);
        assertEquals("function", root.get("type").asText(),
                "type 必须是 'function'");

        JsonNode function = root.get("function");
        assertNotNull(function, "必须包含 function 节点");
        assertEquals(toolName, function.get("name").asText(),
                "function.name 必须与工具名一致");

        assertNotNull(function.get("description"),
                "function.description 不能为 null");
        assertFalse(function.get("description").asText().isBlank(),
                "function.description 不能为空");

        JsonNode params = function.get("parameters");
        assertNotNull(params, "function.parameters 不能为 null — 必须包含 JSON Schema");
        assertTrue(params.isObject(), "parameters 应为 JSON 对象");
    }

    @Test
    @DisplayName("resolve() 批量解析多个工具，返回多个 function-calling JSON，换行分隔")
    void testResolveBatchReturnsMultipleFunctionCallingJsons() throws Exception {
        List<String> names = List.of("search_knowledge_base", "resolve");

        String resolved = toolRegistry.resolve(names);
        assertNotNull(resolved);
        assertFalse(resolved.contains("---"), "不应再有 Markdown 分隔符");

        String[] lines = resolved.split("\n");
        int functionCount = 0;
        for (String line : lines) {
            if (line.trim().isEmpty()) continue;
            JsonNode root = objectMapper.readTree(line);
            assertEquals("function", root.get("type").asText());
            assertNotNull(root.get("function"));
            functionCount++;
        }
        assertEquals(2, functionCount, "应返回 2 个 function-calling 定义");
    }

    @Test
    @DisplayName("resolve() 查询不存在的工具名，返回提示信息")
    void testResolveUnknownToolReturnsHint() {
        String resolved = toolRegistry.resolve(List.of("nonexistent_tool_xyz"));
        assertNotNull(resolved);
        assertTrue(resolved.contains("未找到"),
                "不存在的工具应返回'未找到'提示");
    }

    @Test
    @DisplayName("全量注入模式：buildSystemPrompt 应包含目录和技能正文（如有），不含渐进式引导")
    void testBuildSystemPromptFullInjectionMode() throws Exception {
        int totalCount = toolRegistry.size() + skillRegistry.size();
        System.out.println("当前注册: tool=" + toolRegistry.size()
                + ", skill=" + skillRegistry.size()
                + ", total=" + totalCount
                + ", threshold=" + disclosureThreshold);

        setProgressiveMode(false);
        try {
            String systemPrompt = invokeBuildSystemPrompt();
            assertNotNull(systemPrompt);
            assertFalse(systemPrompt.isBlank());

            assertTrue(systemPrompt.contains("你是一个智能学习助手"),
                    "应包含基础角色定义");

            if (toolRegistry.size() > 0) {
                assertTrue(systemPrompt.contains("可用能力"),
                        "全量模式应包含可用能力目录");
                assertFalse(systemPrompt.contains("由于可用工具和技能较多"),
                        "全量模式不应出现渐进式引导文本");
            }

            if (!skillRegistry.getAllNames().isEmpty()) {
                assertTrue(systemPrompt.contains("可用技能完整说明"),
                        "全量模式有技能时应包含完整技能说明");
            }
        } finally {
            setProgressiveMode(totalCount > disclosureThreshold);
        }
    }

    @Test
    @DisplayName("渐进披露模式：buildSystemPrompt 应包含目录但含渐进式引导文本")
    void testBuildSystemPromptProgressiveMode() throws Exception {
        setProgressiveMode(true);
        try {
            String systemPrompt = invokeBuildSystemPrompt();
            assertNotNull(systemPrompt);
            assertFalse(systemPrompt.isBlank());

            assertTrue(systemPrompt.contains("你是一个智能学习助手"),
                    "应包含基础角色定义");

            assertTrue(systemPrompt.contains("由于可用工具和技能较多"),
                    "渐进模式应包含引导文本");
            assertTrue(systemPrompt.contains("resolve"),
                    "渐进模式引导文本应提及 resolve");

            assertFalse(systemPrompt.contains("可用技能完整说明"),
                    "渐进模式不应包含完整技能正文");
        } finally {
            int totalCount = toolRegistry.size() + skillRegistry.size();
            setProgressiveMode(totalCount > disclosureThreshold);
        }
    }

    @Test
    @DisplayName("阈值配置：disclosureThreshold 被正确注入")
    void testDisclosureThresholdConfigured() {
        assertTrue(disclosureThreshold > 0,
                "disclosureThreshold 应为正整数，实际=" + disclosureThreshold);
    }

    @Test
    @DisplayName("全量注入模式：初始 toolSpec 应包含所有已注册工具（包括元工具）")
    void testFullInjectionInitialSpecsIncludeAllTools() throws Exception {
        setProgressiveMode(false);
        try {
            List<ToolSpecification> initialSpecs = invokeBuildInitialToolSpecs();
            assertNotNull(initialSpecs);
            assertEquals(toolRegistry.size(), initialSpecs.size(),
                    "全量模式初始 toolSpec 数量应等于已注册工具总数");
        } finally {
            int totalCount = toolRegistry.size() + skillRegistry.size();
            setProgressiveMode(totalCount > disclosureThreshold);
        }
    }

    @Test
    @DisplayName("渐进披露模式：初始 toolSpec 仅包含 resolve 元工具")
    void testProgressiveModeInitialSpecsOnlyResolve() throws Exception {
        setProgressiveMode(true);
        try {
            List<ToolSpecification> initialSpecs = invokeBuildInitialToolSpecs();
            assertNotNull(initialSpecs);
            assertFalse(initialSpecs.isEmpty(), "至少应有 resolve 工具");

            List<String> names = initialSpecs.stream()
                    .map(ToolSpecification::name)
                    .collect(Collectors.toList());
            assertTrue(names.contains("resolve"),
                    "渐进模式初始 toolSpec 必须包含 resolve");
        } finally {
            int totalCount = toolRegistry.size() + skillRegistry.size();
            setProgressiveMode(totalCount > disclosureThreshold);
        }
    }

    // ===== 反射辅助 =====
    // prompt 装配已从 AgentExecutor 下沉到 DefaultContextBuilder（@Component）。
    // buildInitialToolSpecs 是 ContextBuilder 接口 public 方法可直接调；
    // buildSystemPrompt 是 DefaultContextBuilder 私有方法，反射调用，签名 (Integer)。
    // progressiveMode 内化于 builder（由 disclosureThreshold vs 注册总数 判定），
    // 测试通过反射注入 disclosureThreshold 强制驱动全量/渐进分支（与 RewriteRuleTest 注入 resultTokenThreshold 同套路）。
    // userId 直接传入：longMemoryInjector 取 userId（非空时读 md，失败 safeRead 降级空串，不影响文案断言）。

    /**
     * 反射注入 disclosureThreshold，强制 builder 进入指定模式（true→渐进，false→全量）。
     * 调用后需 {@link #restoreThreshold(int)} 还原，避免污染后续测试。
     */
    private void setProgressiveMode(boolean progressive) throws Exception {
        java.lang.reflect.Field f = DefaultContextBuilder.class.getDeclaredField("disclosureThreshold");
        f.setAccessible(true);
        if (progressive) {
            f.set(defaultContextBuilder, 0); // threshold=0 → 任何注册数都 > 0，强制渐进
        } else {
            f.set(defaultContextBuilder, Integer.MAX_VALUE); // 极大阈值 → 永不超阈值，强制全量
        }
    }

    private String invokeBuildSystemPrompt() throws Exception {
        Method method = DefaultContextBuilder.class.getDeclaredMethod(
                "buildSystemPrompt", Integer.class);
        method.setAccessible(true);
        return (String) method.invoke(defaultContextBuilder, 1);
    }

    private List<ToolSpecification> invokeBuildInitialToolSpecs() {
        return defaultContextBuilder.buildInitialToolSpecs();
    }

    // ===== onToolExecuted 激活策略 =====

    /**
     * 构造一个 resolve 成功结果，arguments 指定 names。
     */
    private ToolCallResult resolveSuccess(String... names) throws Exception {
        StringBuilder json = new StringBuilder("{\"names\":[");
        for (int i = 0; i < names.length; i++) {
            if (i > 0) json.append(",");
            json.append("\"").append(names[i]).append("\"");
        }
        json.append("]}");
        return ToolCallResult.success("call-1", "resolve", json.toString());
    }

    private List<String> roundSpecNames(int sessionId) {
        return defaultContextBuilder.buildRoundToolSpecs(sessionId).stream()
                .map(ToolSpecification::name)
                .collect(Collectors.toList());
    }

    @Test
    @DisplayName("onToolExecuted：渐进模式下 resolve 成功激活被解析的工具")
    void testOnToolExecutedActivatesResolvedTools() throws Exception {
        setProgressiveMode(true);
        try {
            int sid = 99001;
            defaultContextBuilder.clearSession(sid);
            // resolve 前只有 resolve 元工具
            assertTrue(roundSpecNames(sid).contains("resolve"));
            assertTrue(roundSpecNames(sid).size() == 1,
                    "渐进模式初始应仅 resolve");

            // 解析一个真实存在的业务工具
            String bizTool = pickBizToolName();
            ToolCallResult ok = resolveSuccess(bizTool);
            defaultContextBuilder.onToolExecuted(sid, "resolve", ok, ok.getResult());

            List<String> names = roundSpecNames(sid);
            assertTrue(names.contains("resolve"), "resolve 仍在");
            assertTrue(names.contains(bizTool),
                    "被 resolve 解析的工具 " + bizTool + " 应被激活");
        } finally {
            int totalCount = toolRegistry.size() + skillRegistry.size();
            setProgressiveMode(totalCount > disclosureThreshold);
        }
    }

    @Test
    @DisplayName("onToolExecuted：非 resolve 工具或失败结果不激活")
    void testOnToolExecutedIgnoresNonResolveOrFailure() throws Exception {
        setProgressiveMode(true);
        try {
            int sid = 99002;
            defaultContextBuilder.clearSession(sid);

            // 非 resolve 工具成功 → 不激活
            String bizTool = pickBizToolName();
            ToolCallResult nonResolve = ToolCallResult.success("c2", bizTool, "{}");
            defaultContextBuilder.onToolExecuted(sid, bizTool, nonResolve, "{}");
            assertEquals(1, roundSpecNames(sid).size(), "非 resolve 工具不应触发激活");

            // resolve 失败 → 不激活
            ToolCallResult fail = ToolCallResult.failure("c3", "resolve", "boom");
            defaultContextBuilder.onToolExecuted(sid, "resolve", fail, fail.getError());
            assertEquals(1, roundSpecNames(sid).size(), "resolve 失败不应触发激活");
        } finally {
            int totalCount = toolRegistry.size() + skillRegistry.size();
            setProgressiveMode(totalCount > disclosureThreshold);
        }
    }

    @Test
    @DisplayName("onToolExecuted：per-session 隔离 + clearSession 清理")
    void testOnToolExecutedPerSessionIsolationAndClear() throws Exception {
        setProgressiveMode(true);
        try {
            int sidA = 99003, sidB = 99004;
            defaultContextBuilder.clearSession(sidA);
            defaultContextBuilder.clearSession(sidB);

            String bizTool = pickBizToolName();
            ToolCallResult ok = resolveSuccess(bizTool);
            defaultContextBuilder.onToolExecuted(sidA, "resolve", ok, ok.getResult());

            // A 已激活，B 未受影响
            assertTrue(roundSpecNames(sidA).contains(bizTool), "session A 应已激活");
            assertFalse(roundSpecNames(sidB).contains(bizTool), "session B 不应被 A 污染");

            // clearSession 后 A 恢复仅 resolve
            defaultContextBuilder.clearSession(sidA);
            assertEquals(1, roundSpecNames(sidA).size(), "clearSession 后激活集应清空");
        } finally {
            int totalCount = toolRegistry.size() + skillRegistry.size();
            setProgressiveMode(totalCount > disclosureThreshold);
        }
    }

    /**
     * 取一个真实存在的业务工具名（非 resolve 元工具），用于激活断言。
     */
    private String pickBizToolName() {
        return toolRegistry.getToolSpecifications().stream()
                .map(ToolSpecification::name)
                .filter(n -> !Catalog.META_TOOLS.contains(n))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("无可用业务工具用于测试"));
    }
}
