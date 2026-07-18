package org.linxing.linxing_agent.agent.memory.builder;

import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.linxing.linxing_agent.agent.catalog.Catalog;
import org.linxing.linxing_agent.agent.catalog.CatalogEntry;
import org.linxing.linxing_agent.agent.catalog.CatalogProvider;
import org.linxing.linxing_agent.agent.core.AgentContext;
import org.linxing.linxing_agent.agent.core.AgentPrompts;
import org.linxing.linxing_agent.agent.memory.recovery.RecoveredHistory;
import org.linxing.linxing_agent.agent.memory.recovery.TurnBoundary;
import org.linxing.linxing_agent.agent.memory.ruleset.RuleSet;
import org.linxing.linxing_agent.agent.memory.ruleset.RuleSetStore;
import org.linxing.linxing_agent.agent.memory.ruleset.RewriteToolRule;
import org.linxing.linxing_agent.agent.skill.SkillRegistry;
import org.linxing.linxing_agent.agent.tool.ToolRegistry;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * ContextBuilder 默认实现。
 *
 * <p>2-A：A 系统段 / C 工具规格段三方法原样搬迁自 AgentExecutor，行为零变化。
 * 2-B：新增 {@link #buildMessages(AgentContext)} 接管 B 历史段读路径，
 * SystemMessage 幂等置于首位后接 memory 累加消息；AgentMemory 退化为极简累加器。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DefaultContextBuilder implements ContextBuilder {

    private static final String SYSTEM_PROMPT_TEMPLATE_FULL = AgentPrompts.SYSTEM_PROMPT_TEMPLATE_FULL;
    private static final String SYSTEM_PROMPT_TEMPLATE_PROGRESSIVE = AgentPrompts.SYSTEM_PROMPT_TEMPLATE_PROGRESSIVE;

    /**
     * 渐进式披露阈值：与 {@code AgentExecutor.disclosureThreshold} 同源配置，
     * 使 {@link #buildMessages(AgentContext)} 内部判定 progressiveMode 与 AgentExecutor 一致。
     */
    @Value("${agent.disclosure.threshold:5}")
    private int disclosureThreshold;

    private final ToolRegistry toolRegistry;
    private final SkillRegistry skillRegistry;
    private final List<CatalogProvider> catalogProviders;
    private final RuleSetStore ruleSetStore;

    @Override
    public SystemMessage buildSystemMessage(boolean progressiveMode) {
        return SystemMessage.from(buildSystemPrompt(progressiveMode));
    }

    /**
     * B 历史段读路径：SystemMessage 幂等首位 + memory 累加消息。
     * <p>
     * SystemMessage 不进 memory（memory 只承载运行时对话流），每轮由本方法装配时重新置于首位，
     * 保证 langchain4j "SystemMessage 幂等首位" 硬约束。memory 退化为极简累加器后不再持有
     * systemMessage 字段，故 SystemMessage 唯一来源就是本方法。
     * <p>
     * progressiveMode 在此重算（与 AgentExecutor.execute 同公式同配置），保证 systemMessage
     * 与 buildInitialToolSpecs 使用的 progressiveMode 一致。
     */
    @Override
    public List<ChatMessage> buildMessages(AgentContext context) {
        int totalCount = toolRegistry.size() + skillRegistry.size();
        boolean progressiveMode = totalCount > disclosureThreshold;
        SystemMessage systemMessage = buildSystemMessage(progressiveMode);

        List<ChatMessage> messages = new ArrayList<>(context.getMemory().size() + 1);
        messages.add(systemMessage);
        messages.addAll(context.getMemory().messages());
        return messages;
    }

    /**
     * B 历史段读路径（2-D 起，消费 Rule Set 驱动 Projection）。
     * <p>
     * 流程：SystemMessage 幂等首位 → 对 history 段（memory 的前 N 条，N=recovered.messages.size()）
     * 应用 SkipTurnRule（整 Turn 跳过）与 RewriteToolRule（tool 结果占位）→ 当前轮追加消息原样保留。
     * <p>
     * <b>history 段定位</b>：memory 不在 history 之前插消息（SystemMessage 不进 memory），
     * 故 memory.messages() 的前 {@code historySize} 条即 Recovery 产出的 history，与
     * {@code recovered.turnBoundaries} 下标对齐。historySize = recovered.getMessages().size()。
     * 若 memory 实际条数少于 historySize（异常情况，如 memory 被清空），退化为不投影、全量输出。
     */
    @Override
    public List<ChatMessage> buildMessages(AgentContext context, RecoveredHistory recovered) {
        // 无 Recovery 或无 turnBoundaries → 退化为零投影版本
        if (recovered == null || recovered.getTurnBoundaries() == null
                || recovered.getTurnBoundaries().isEmpty()) {
            return buildMessages(context);
        }

        int totalCount = toolRegistry.size() + skillRegistry.size();
        boolean progressiveMode = totalCount > disclosureThreshold;
        SystemMessage systemMessage = buildSystemMessage(progressiveMode);

        List<ChatMessage> memoryMessages = context.getMemory().messages();
        int historySize = recovered.getMessages().size();
        // 防御：memory 条数不足（被清空等异常），退化为零投影
        if (memoryMessages.size() < historySize) {
            List<ChatMessage> fallback = new ArrayList<>(memoryMessages.size() + 1);
            fallback.add(systemMessage);
            fallback.addAll(memoryMessages);
            return fallback;
        }

        RuleSet ruleSet = ruleSetStore.get(context.getSessionId());

        List<ChatMessage> result = new ArrayList<>(memoryMessages.size() + 1);
        result.add(systemMessage);

        // history 段投影
        Set<Integer> skippedTurnStartIds = ruleSet.skippedTurnStartIds();
        for (TurnBoundary tb : recovered.getTurnBoundaries()) {
            if (skippedTurnStartIds.contains(tb.getTurnStartMessageId())) {
                continue; // SkipTurnRule 命中：整 Turn 跳过
            }
            for (int i = tb.getStartIdx(); i < tb.getEndIdx() && i < historySize; i++) {
                result.add(projectToolResult(memoryMessages.get(i), ruleSet));
            }
        }
        // 当前轮追加消息（history 之后），原样保留
        for (int i = historySize; i < memoryMessages.size(); i++) {
            result.add(memoryMessages.get(i));
        }
        return result;
    }

    /**
     * 对单条消息应用 RewriteToolRule：若为 ToolExecutionResultMessage 且命中 rule，
     * 把 content 替换为占位符（保留 tool_call_id 不破坏配对）；其余消息原样返回。
     */
    private ChatMessage projectToolResult(ChatMessage msg, RuleSet ruleSet) {
        if (!(msg instanceof ToolExecutionResultMessage term)) {
            return msg;
        }
        String toolCallId = term.id() != null ? term.id() : term.toolName();
        RewriteToolRule rule = ruleSet.rewriteRuleFor(toolCallId);
        if (rule == null) {
            return msg;
        }
        // 占位符版：保留 tool_call_id 配对，丢 content，留简要提示
        String placeholder = "[此工具结果已被 Projection 精简：toolCallId=" + toolCallId
                + (rule.getReason() != null && !rule.getReason().isBlank()
                ? ", reason=" + rule.getReason() : "")
                + "]";
        // ToolExecutionResultMessage 需配对的 ToolExecutionRequest；此处用原 msg 的 name/id 重建
        return ToolExecutionResultMessage.from(
                dev.langchain4j.agent.tool.ToolExecutionRequest.builder()
                        .id(term.id())
                        .name(term.toolName())
                        .build(),
                placeholder);
    }

    /**
     * 动态构建系统提示词，注入工具与技能目录信息。
     * 原样搬迁自 AgentExecutor.buildSystemPrompt，逻辑不变。
     */
    private String buildSystemPrompt(boolean progressiveMode) {
        List<CatalogEntry> allEntries = new ArrayList<>();
        for (CatalogProvider provider : catalogProviders) {
            allEntries.addAll(provider.catalogEntries());
        }

        List<CatalogEntry> filtered = allEntries.stream()
                .filter(e -> !Catalog.META_TOOLS.contains(e.getName()))
                .collect(Collectors.toList());

        StringBuilder dynamicSection = new StringBuilder();

        if (!filtered.isEmpty()) {
            Catalog catalog = new Catalog(filtered);
            dynamicSection.append("【可用能力】\n").append(catalog.toPromptText()).append("\n\n");
        }

        if (!progressiveMode) {
            List<String> allSkillNames = skillRegistry.getAllNames();
            if (!allSkillNames.isEmpty()) {
                String resolved = skillRegistry.resolve(allSkillNames);
                if (resolved != null && !resolved.isBlank() && !resolved.startsWith("未找到")) {
                    dynamicSection.append("【可用技能完整说明】\n").append(resolved).append("\n\n");
                }
            }
            dynamicSection.append("所有工具和技能的完整定义已在上方提供，请直接使用。");
        } else {
            dynamicSection.append("由于可用工具和技能较多，请先查看上方目录了解可用能力。"
                    + "如需使用某个工具或技能，请调用 resolve 获取其完整定义。");
        }

        String template = progressiveMode ? SYSTEM_PROMPT_TEMPLATE_PROGRESSIVE : SYSTEM_PROMPT_TEMPLATE_FULL;
        return String.format(template, dynamicSection.toString());
    }

    @Override
    public List<ToolSpecification> buildInitialToolSpecs(boolean progressiveMode) {
        if (!progressiveMode) {
            return toolRegistry.getToolSpecifications(); //全量注入
        }
        List<ToolSpecification> specs = new ArrayList<>();
        ToolSpecification resolveSpec = toolRegistry.getToolSpecification("resolve"); //渐进披露模式，这一步的初始化只传入"工具之工具"——可用于获取其他工具定义的工具
        if (resolveSpec != null) {
            specs.add(resolveSpec);
        }
        return specs;
    }

    @Override
    public List<ToolSpecification> buildRoundToolSpecs(List<ToolSpecification> initialSpecs,
                                                       Set<String> activatedToolNames,
                                                       boolean progressiveMode) {
        if (!progressiveMode || activatedToolNames.isEmpty()) {
            return initialSpecs;
        }
        List<ToolSpecification> roundSpecs = new ArrayList<>(initialSpecs);
        List<ToolSpecification> activated = toolRegistry.getToolSpecifications(new ArrayList<>(activatedToolNames));
        roundSpecs.addAll(activated);
        return roundSpecs;
    }
}
