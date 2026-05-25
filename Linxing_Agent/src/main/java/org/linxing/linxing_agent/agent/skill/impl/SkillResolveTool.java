package org.linxing.linxing_agent.agent.skill.impl;

import dev.langchain4j.model.chat.request.json.JsonArraySchema;
import dev.langchain4j.model.chat.request.json.JsonObjectSchema;
import dev.langchain4j.model.chat.request.json.JsonStringSchema;
import tools.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.linxing.linxing_agent.agent.skill.SkillInstructions;
import org.linxing.linxing_agent.agent.skill.SkillRegistry;
import org.linxing.linxing_agent.agent.tool.Tool;
import org.linxing.linxing_agent.agent.tool.ToolCallRequest;
import org.linxing.linxing_agent.agent.tool.ToolCallResult;
import org.linxing.linxing_agent.agent.tool.ToolRegistry;
import org.linxing.linxing_agent.agent.tool.ToolSpec;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 技能详情获取工具
 * 批量获取技能的完整指令（SKILL.md 正文）。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SkillResolveTool implements Tool {

    private static final String NAME = "skill_resolve";
    private static final String DESCRIPTION = "获取指定技能的完整定义（含所需工具的参数定义）。"
            + "当你已从目录中确定了需要使用的技能，需要查看其完整定义和所需工具时使用此工具。"
            + "支持一次请求获取多个技能的定义。";
    private static final String BRIEF = "获取指定技能的完整定义（含所需工具的参数定义）";
    private static final String WHEN_TO_USE = "当你已从目录中确定了需要使用的技能，需要查看其完整定义和所需工具时使用";

    private final SkillRegistry skillRegistry;
    private final ToolRegistry toolRegistry;
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
                .addProperty("skill_names",
                        JsonArraySchema.builder()
                                .description("需要获取完整定义的技能名称列表")
                                .items(JsonStringSchema.builder().build())
                                .build())
                .required("skill_names")
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

        if (args.getSkillNames() == null || args.getSkillNames().isEmpty()) {
            return ToolCallResult.failure(request.getToolCallId(), NAME, "skill_names 不能为空");
        }

        //取技能完整指令
        List<SkillInstructions> instructions = skillRegistry.resolve(args.getSkillNames());

        // 自动收集技能所需的所有工具，一并返回 Schema
        Set<String> toolNames = instructions.stream()
                .flatMap(i -> i.getToolNames().stream())
                .collect(Collectors.toSet());
        List<ToolSpec> toolSpecs = toolNames.isEmpty()
                ? List.of()
                : toolRegistry.resolve(new ArrayList<>(toolNames));

        String resultText = formatSkillAndToolSpecs(instructions, toolSpecs);//格式化所有被使用的skills和tools的信息

        if (resultText.isBlank()) {
            resultText = "未找到指定的技能，请先调用 skill_catalog 查看可用技能列表。";
        }

        return ToolCallResult.success(request.getToolCallId(), NAME, resultText);
    }

    /**
     * 格式化技能定义和工具 Schema 为 LLM 可读文本
     */
    private String formatSkillAndToolSpecs(List<SkillInstructions> instructions, List<ToolSpec> toolSpecs) {
        StringBuilder sb = new StringBuilder();

        for (SkillInstructions instr : instructions) {
            sb.append("## 技能: ").append(instr.getName()).append("\n\n");
            sb.append(instr.getInstructions()).append("\n\n");
            if (instr.getResourcePaths() != null && !instr.getResourcePaths().isEmpty()) {
                sb.append("可用参考资源: ").append(String.join(", ", instr.getResourcePaths())).append("\n\n");
            }
        }

        if (!toolSpecs.isEmpty()) {
            sb.append("---\n\n## 所需工具参数定义\n\n");
            for (ToolSpec spec : toolSpecs) {
                sb.append("### 工具: ").append(spec.getName()).append("\n");
                sb.append("描述: ").append(spec.getDescription()).append("\n");
                sb.append("参数定义: ").append(spec.getParameters()).append("\n\n");
            }
        }

        return sb.toString();
    }

    @lombok.Data
    @lombok.NoArgsConstructor
    public static class ResolveArgs {
        private List<String> skillNames;
    }
}
