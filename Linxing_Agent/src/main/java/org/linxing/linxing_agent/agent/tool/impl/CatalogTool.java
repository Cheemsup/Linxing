package org.linxing.linxing_agent.agent.tool.impl;

import dev.langchain4j.model.chat.request.json.JsonObjectSchema;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.linxing.linxing_agent.agent.core.AgentContext;
import org.linxing.linxing_agent.agent.catalog.Catalog;
import org.linxing.linxing_agent.agent.catalog.CatalogEntry;
import org.linxing.linxing_agent.agent.catalog.CatalogProvider;
import org.linxing.linxing_agent.agent.tool.Tool;
import org.linxing.linxing_agent.agent.tool.ToolCallRequest;
import org.linxing.linxing_agent.agent.tool.ToolCallResult;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 统一目录工具，聚合 ToolRegistry 和 SkillRegistry 的目录信息
 *
 * @deprecated 目录信息已在 {@code AgentExecutor.buildSystemPrompt()} 中注入 System Prompt，
 * LLM 第一轮即可直接阅读目录内容，无需通过工具调用获取。
 * 保留此类仅作为兜底（如 LLM 误调用时返回提示），后续版本将移除。
 */
@Deprecated
@Slf4j
@Component
@RequiredArgsConstructor
public class CatalogTool implements Tool {

    private static final String NAME = "catalog";
    private static final String DESCRIPTION = "查看所有可用工具和技能的目录，返回每个条目的名称、简介和适用场景。"
            + "当你不确定有哪些工具或技能可用时使用此工具。"
            + "从目录中选出需要的条目后，请调用 resolve 获取其完整定义。";
    private static final String BRIEF = "查看所有可用工具和技能的目录";
    private static final String WHEN_TO_USE = "当你不确定有哪些工具或技能可用时使用";

    private final List<CatalogProvider> providers;

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
        return JsonObjectSchema.builder().build();
    }

    @Override
    public ToolCallResult execute(ToolCallRequest request, AgentContext context) {
        List<CatalogEntry> allEntries = new ArrayList<>();
        for (CatalogProvider provider : providers) {
            allEntries.addAll(provider.catalogEntries());
        }

        //过滤掉所有元工具，避免目录中出现元工具
        List<CatalogEntry> filtered = allEntries.stream()
                .filter(e -> !Catalog.META_TOOLS.contains(e.getName()))
                .collect(Collectors.toList());

        Catalog catalog = new Catalog(filtered);
        return ToolCallResult.success(request.getToolCallId(), NAME, catalog.toPromptText());
    }
}
