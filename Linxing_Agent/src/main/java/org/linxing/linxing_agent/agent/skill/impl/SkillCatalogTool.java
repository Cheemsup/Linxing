package org.linxing.linxing_agent.agent.skill.impl;

import dev.langchain4j.model.chat.request.json.JsonObjectSchema;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.linxing.linxing_agent.agent.skill.SkillCatalog;
import org.linxing.linxing_agent.agent.skill.SkillRegistry;
import org.linxing.linxing_agent.agent.tool.Tool;
import org.linxing.linxing_agent.agent.tool.ToolCallRequest;
import org.linxing.linxing_agent.agent.tool.ToolCallResult;
import org.springframework.stereotype.Component;

/**
 * 技能目录工具
 * TODO：同tool包下的目录工具类，考虑直接初始request就发送给LLM而不是LLM发起请求才给目录信息
 * 返回所有已注册技能的 Metadata（name + description）。
 * 数据全量在内存中，零磁盘 I/O。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SkillCatalogTool implements Tool {

    private static final String NAME = "skill_catalog";
    private static final String DESCRIPTION = "查看所有可用技能的目录，返回每个技能的名称和描述。"
            + "当你需要完成一个复杂任务，想了解有哪些预定义的技能可用时使用此工具。"
            + "从目录中选出需要的技能后，请调用 skill_resolve 获取其完整定义。";
    private static final String BRIEF = "查看所有可用技能的目录";
    private static final String WHEN_TO_USE = "当你需要完成一个复杂任务，想了解有哪些预定义的技能可用时使用";

    private final SkillRegistry registry;

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
    public ToolCallResult execute(ToolCallRequest request) {
        SkillCatalog catalog = registry.catalog();
        return ToolCallResult.success(request.getToolCallId(), NAME, catalog.toPromptText());
    }
}
