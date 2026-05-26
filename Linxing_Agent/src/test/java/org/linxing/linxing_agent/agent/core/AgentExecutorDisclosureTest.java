package org.linxing.linxing_agent.agent.core;

import dev.langchain4j.agent.tool.ToolSpecification;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.linxing.linxing_agent.agent.catalog.Catalog;
import org.linxing.linxing_agent.agent.catalog.CatalogEntry;
import org.linxing.linxing_agent.agent.catalog.CatalogProvider;
import org.linxing.linxing_agent.agent.skill.SkillRegistry;
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
    private AgentExecutor agentExecutor;

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
    @DisplayName("全量注入模式：buildSystemPrompt(false) 应包含目录和技能正文（如有），不含渐进式引导")
    void testBuildSystemPromptFullInjectionMode() throws Exception {
        int totalCount = toolRegistry.size() + skillRegistry.size();
        System.out.println("当前注册: tool=" + toolRegistry.size()
                + ", skill=" + skillRegistry.size()
                + ", total=" + totalCount
                + ", threshold=" + disclosureThreshold);

        String systemPrompt = invokeBuildSystemPrompt(false);
        assertNotNull(systemPrompt);
        assertFalse(systemPrompt.isBlank());

        assertTrue(systemPrompt.contains("你是一个智能知识库助手"),
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
    }

    @Test
    @DisplayName("渐进披露模式：buildSystemPrompt(true) 应包含目录但含渐进式引导文本")
    void testBuildSystemPromptProgressiveMode() throws Exception {
        String systemPrompt = invokeBuildSystemPrompt(true);
        assertNotNull(systemPrompt);
        assertFalse(systemPrompt.isBlank());

        assertTrue(systemPrompt.contains("你是一个智能知识库助手"),
                "应包含基础角色定义");

        assertTrue(systemPrompt.contains("由于可用工具和技能较多"),
                "渐进模式应包含引导文本");
        assertTrue(systemPrompt.contains("resolve"),
                "渐进模式引导文本应提及 resolve");

        assertFalse(systemPrompt.contains("可用技能完整说明"),
                "渐进模式不应包含完整技能正文");
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
        List<ToolSpecification> initialSpecs = invokeBuildInitialToolSpecs(false);
        assertNotNull(initialSpecs);
        assertEquals(toolRegistry.size(), initialSpecs.size(),
                "全量模式初始 toolSpec 数量应等于已注册工具总数");
    }

    @Test
    @DisplayName("渐进披露模式：初始 toolSpec 仅包含 resolve 元工具")
    void testProgressiveModeInitialSpecsOnlyResolve() throws Exception {
        List<ToolSpecification> initialSpecs = invokeBuildInitialToolSpecs(true);
        assertNotNull(initialSpecs);
        assertFalse(initialSpecs.isEmpty(), "至少应有 resolve 工具");

        List<String> names = initialSpecs.stream()
                .map(ToolSpecification::name)
                .collect(Collectors.toList());
        assertTrue(names.contains("resolve"),
                "渐进模式初始 toolSpec 必须包含 resolve");
    }

    // ===== 反射辅助 =====

    private String invokeBuildSystemPrompt(boolean progressiveMode) throws Exception {
        return invokePrivateMethod("buildSystemPrompt",
                new Class[]{boolean.class}, progressiveMode);
    }

    @SuppressWarnings("unchecked")
    private List<ToolSpecification> invokeBuildInitialToolSpecs(boolean progressiveMode) throws Exception {
        return (List<ToolSpecification>) invokePrivateMethod("buildInitialToolSpecs",
                new Class[]{boolean.class}, progressiveMode);
    }

    @SuppressWarnings("unchecked")
    private <T> T invokePrivateMethod(String methodName, Class<?>[] paramTypes, Object... args)
            throws Exception {
        Method method = agentExecutor.getClass().getDeclaredMethod(methodName, paramTypes);
        method.setAccessible(true);
        return (T) method.invoke(agentExecutor, args);
    }
}
