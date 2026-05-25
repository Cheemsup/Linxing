package org.linxing.linxing_agent.agent.tool.impl;

import dev.langchain4j.model.chat.request.json.JsonObjectSchema;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.linxing.linxing_agent.agent.tool.Catalog;
import org.linxing.linxing_agent.agent.tool.CatalogEntry;
import org.linxing.linxing_agent.agent.tool.Tool;
import org.linxing.linxing_agent.agent.tool.ToolCallRequest;
import org.linxing.linxing_agent.agent.tool.ToolCallResult;
import org.linxing.linxing_agent.agent.tool.ToolRegistry;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 工具目录工具
 * 作为元工具，它的JSON schema一开始就会被提供给LLM，当LLM需要调用其他工具时，会自动调用 tool_catalog 获取工具目录
 * TODO：也就是说，LLM一开始是完全不知道系统内有什么工具的，这并不好。后续应该优化为首次向LLM发送request时就已经将这个目录的执行结果提供给LLM，避免了“LLM一开始只知道能用工具、但是对于有什么工具一概不知的情况”
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ToolCatalogTool implements Tool {

    private static final String NAME = "tool_catalog";
    private static final String DESCRIPTION = "查看所有可用工具的目录，返回每个工具的名称、简介和适用场景。"
            + "当你不确定有哪些工具可用，或需要了解工具的适用场景时使用此工具。"
            + "从目录中选出需要的工具后，请调用 tool_resolve 获取其完整参数定义。";
    private static final String BRIEF = "查看所有可用工具的目录";
    private static final String WHEN_TO_USE = "当你不确定有哪些工具可用，或需要了解工具的适用场景时使用";

    private final ToolRegistry registry;

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
     * 目录工具无需参数
     * @return
     */
    @Override
    public JsonObjectSchema spec() {
        return JsonObjectSchema.builder().build();
    }

    private static final Set<String> META_TOOLS = Set.of(
            "tool_catalog", "tool_resolve", "skill_catalog", "skill_resolve"
    );

    @Override
    public ToolCallResult execute(ToolCallRequest request) {
        Catalog catalog = registry.catalog();
        //过滤掉所有元工具（catalog/resolve），避免目录中出现元工具
        List<CatalogEntry> filtered = catalog.getEntries().stream()
                .filter(e -> !META_TOOLS.contains(e.getName()))
                .collect(Collectors.toList());
        Catalog displayCatalog = new Catalog(filtered);
        return ToolCallResult.success(request.getToolCallId(), NAME, displayCatalog.toPromptText());
    }
}
