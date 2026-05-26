package org.linxing.linxing_agent.agent.tool.impl;

import dev.langchain4j.model.chat.request.json.JsonArraySchema;
import dev.langchain4j.model.chat.request.json.JsonObjectSchema;
import dev.langchain4j.model.chat.request.json.JsonStringSchema;
import tools.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.linxing.linxing_agent.agent.core.AgentContext;
import org.linxing.linxing_agent.agent.catalog.CatalogProvider;
import org.linxing.linxing_agent.agent.tool.Tool;
import org.linxing.linxing_agent.agent.tool.ToolCallRequest;
import org.linxing.linxing_agent.agent.tool.ToolCallResult;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 统一解析工具，聚合 ToolRegistry 和 SkillRegistry 的解析能力
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ResolveTool implements Tool {

    private static final String NAME = "resolve";
    private static final String DESCRIPTION = "获取指定工具或技能的完整定义。"
            + "当你已从目录中确定了需要使用的工具或技能，需要查看其完整定义时使用此工具。"
            + "支持一次请求获取多个条目的定义。";
    private static final String BRIEF = "获取指定工具或技能的完整定义";
    private static final String WHEN_TO_USE = "当你已从目录中确定了需要使用的工具或技能，需要查看其完整定义时使用";

    private final List<CatalogProvider> providers;
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
    public JsonObjectSchema spec() {
        return JsonObjectSchema.builder()
                .addProperty("names",
                        JsonArraySchema.builder()
                                .description("需要获取完整定义的工具或技能名称列表")
                                .items(JsonStringSchema.builder().build())
                                .build())
                .required("names")
                .build();
    }

    @Override
    public ToolCallResult execute(ToolCallRequest request, AgentContext context) {
        ResolveArgs args;
        try {
            args = objectMapper.readValue(request.getArguments(), ResolveArgs.class);
        } catch (Exception e) {
            return ToolCallResult.failure(request.getToolCallId(), NAME,
                    "参数解析失败: " + e.getMessage());
        }

        if (args.getNames() == null || args.getNames().isEmpty()) {
            return ToolCallResult.failure(request.getToolCallId(), NAME, "names 不能为空");
        }

        StringBuilder resultText = new StringBuilder();
        for (CatalogProvider provider : providers) {
            String resolved = provider.resolve(args.getNames());//最终使用的是ToolRegistry或者SkillRegistry的resolve方法
            if (resolved != null && !resolved.isBlank()
                    && !resolved.startsWith("未找到")) {
                if (resultText.length() > 0) {
                    resultText.append("\n\n---\n\n");
                }
                resultText.append(resolved);
            }
        }

        if (resultText.length() == 0) {
            resultText.append("未找到指定的工具或技能，请先调用 catalog 查看可用列表。");
        }

        return ToolCallResult.success(request.getToolCallId(), NAME, resultText.toString());
    }

    @lombok.Data
    @lombok.NoArgsConstructor
    public static class ResolveArgs {
        private List<String> names;
    }
}
