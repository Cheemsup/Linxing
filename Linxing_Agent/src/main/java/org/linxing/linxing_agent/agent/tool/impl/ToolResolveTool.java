package org.linxing.linxing_agent.agent.tool.impl;

import dev.langchain4j.model.chat.request.json.JsonArraySchema;
import dev.langchain4j.model.chat.request.json.JsonObjectSchema;
import dev.langchain4j.model.chat.request.json.JsonStringSchema;
import tools.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.linxing.linxing_agent.agent.tool.Tool;
import org.linxing.linxing_agent.agent.tool.ToolCallRequest;
import org.linxing.linxing_agent.agent.tool.ToolCallResult;
import org.linxing.linxing_agent.agent.tool.ToolRegistry;
import org.linxing.linxing_agent.agent.tool.ToolSpec;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.stream.Collectors;

/**
 * 元工具，用于获取指定工具的完整参数定义（JSON Schema）。它一开始就会被提供给LLM
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ToolResolveTool implements Tool {

    private static final String NAME = "tool_resolve";
    private static final String DESCRIPTION = "获取指定工具的完整参数定义（JSON Schema）。"
            + "当你已从目录中确定了需要使用的工具，需要查看其完整参数定义时使用此工具。"
            + "支持一次请求获取多个工具的参数定义。";
    private static final String BRIEF = "获取指定工具的完整参数定义";
    private static final String WHEN_TO_USE = "当你已从目录中确定了需要使用的工具，需要查看其完整参数定义时使用";

    private final ToolRegistry registry;
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

    /**
     * 参数为工具名称列表
     * @return
     */
    @Override
    public JsonObjectSchema spec() {
        return JsonObjectSchema.builder()
                .addProperty("tool_names",
                        JsonArraySchema.builder()
                                .description("需要获取参数定义的工具名称列表")
                                .items(JsonStringSchema.builder().build())
                                .build())
                .required("tool_names")
                .build();
    }

    @Override
    public ToolCallResult execute(ToolCallRequest request) {
        ResolveArgs args;
        try {
            args = objectMapper.readValue(request.getArguments(), ResolveArgs.class);
        } catch (Exception e) {
            return ToolCallResult.failure(request.getToolCallId(), NAME,
                    "参数解析失败: " + e.getMessage());
        }

        if (args.getToolNames() == null || args.getToolNames().isEmpty()) {
            return ToolCallResult.failure(request.getToolCallId(), NAME, "tool_names 不能为空");
        }

        List<ToolSpec> specs = registry.resolve(args.getToolNames());//从注册中心批量获取工具完整规格

        //格式化为 LLM 可读的文本
        String resultText = specs.stream()
                .map(spec -> "工具: " + spec.getName() + "\n"
                        + "描述: " + spec.getDescription() + "\n"
                        + "参数定义: " + spec.getParameters())
                .collect(Collectors.joining("\n\n---\n\n"));

        if (resultText.isBlank()) {
            resultText = "未找到指定的工具，请先调用 tool_catalog 查看可用工具列表。";
        }

        return ToolCallResult.success(request.getToolCallId(), NAME, resultText);
    }

    @lombok.Data
    @lombok.NoArgsConstructor
    public static class ResolveArgs {
        private List<String> toolNames;
    }
}
