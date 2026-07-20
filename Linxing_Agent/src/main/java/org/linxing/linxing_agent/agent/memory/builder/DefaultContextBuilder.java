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
 * {@link ContextBuilder} 默认实现：装配 Agent 每轮发送给 LLM 的三类上下文素材。
 *
 * <p>三段职责：
 * <ul>
 *   <li>A 系统段 — {@link #buildSystemMessage} / {@link #buildSystemPrompt}：依据 progressiveMode 动态拼装系统提示词</li>
 *   <li>B 历史段 — {@link #buildMessages} 两个重载：SystemMessage 幂等首位 + memory 累加消息，可选叠加 Rule Set 投影</li>
 *   <li>C 工具规格段 — {@link #buildInitialToolSpecs} / {@link #buildRoundToolSpecs}：按渐进披露策略注入 ToolSpecification</li>
 * </ul>
 *
 * <p>关键不变量：SystemMessage 永不进 memory，每轮装配时由本类重新置于首位，
 * 以满足 langchain4j "SystemMessage 幂等首位" 硬约束
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DefaultContextBuilder implements ContextBuilder {

    private static final String SYSTEM_PROMPT_TEMPLATE_FULL = AgentPrompts.SYSTEM_PROMPT_TEMPLATE_FULL;
    private static final String SYSTEM_PROMPT_TEMPLATE_PROGRESSIVE = AgentPrompts.SYSTEM_PROMPT_TEMPLATE_PROGRESSIVE;

    /**
     * 渐进式披露阈值：工具数 + 技能数超过此值即进入 progressiveMode（仅注入 resolve 工具，其余按需披露）。
     * <p>与 AgentExecutor 同源配置，保证本类内部判定的 progressiveMode 与外部执行路径一致。
     */
    @Value("${agent.disclosure.threshold:5}")
    private int disclosureThreshold;

    private final ToolRegistry toolRegistry;
    private final SkillRegistry skillRegistry;
    private final List<CatalogProvider> catalogProviders;
    private final RuleSetStore ruleSetStore;

    //此方法貌似不必要存在了
    @Override
    public SystemMessage buildSystemMessage(boolean progressiveMode) {
        return SystemMessage.from(buildSystemPrompt(progressiveMode));
    }

    /**
     * 历史段读路径（Rule Set 投影版）：消费 {@link RecoveredHistory} 与 {@link RuleSet}，
     * 对 history 段应用 SkipTurnRule（整 Turn 跳过）与 RewriteToolRule（tool 结果占位），当前轮消息原样保留。
     *
     * <p>装配顺序：SystemMessage 首位 → history 段投影 → 当前轮追加消息。
     *
     * <p><b>history 段定位</b>：SystemMessage 不进 memory，故 memory.messages() 的前
     * {@code historySize} 条（= recovered.getMessages().size()）恰好是 Recovery 产出的 history，
     * 与 {@code recovered.turnBoundaries} 的下标一一对齐——这是投影能正确分 Turn 的前提。
     *
     * <p><b>两道兜底</b>：无 Recovery/无 turnBoundaries 时退化为零投影版本；
     * memory 实际条数少于 historySize（memory 被清空等异常）时退化为不投影、全量输出。
     */
    @Override
    public List<ChatMessage> buildMessages(AgentContext context, RecoveredHistory recovered) {
        // 无 Recovery 或无 Turn 结构 → 退化为零投影版本
        if (recovered == null || recovered.getTurnBoundaries() == null
                || recovered.getTurnBoundaries().isEmpty()) {
            return buildMessages(context);
        }

        int totalCount = toolRegistry.size() + skillRegistry.size();
        boolean progressiveMode = totalCount > disclosureThreshold;
        SystemMessage systemMessage = buildSystemMessage(progressiveMode);

        List<ChatMessage> memoryMessages = context.getMemory().messages();
        int historySize = recovered.getMessages().size();
        // 防御：memory 条数不足（被清空等异常）→ 退化为不投影、全量输出
        if (memoryMessages.size() < historySize) {
            List<ChatMessage> fallback = new ArrayList<>(memoryMessages.size() + 1);
            fallback.add(systemMessage);
            fallback.addAll(memoryMessages);
            return fallback;
        }

        RuleSet ruleSet = ruleSetStore.get(context.getSessionId());//获取rewrite、snip阶段生成的rule

        List<ChatMessage> result = new ArrayList<>(memoryMessages.size() + 1);
        result.add(systemMessage);

        // history 段投影：按 TurnBoundary 逐段处理
        Set<Integer> skippedTurnStartIds = ruleSet.skippedTurnStartIds();
        for (TurnBoundary tb : recovered.getTurnBoundaries()) {
            if (skippedTurnStartIds.contains(tb.getTurnStartMessageId())) {
                continue; // SkipTurnRule 命中：整 Turn 跳过
            }
            for (int i = tb.getStartIdx(); i < tb.getEndIdx() && i < historySize; i++) {
                result.add(projectToolResult(memoryMessages.get(i), ruleSet));//过了snip的第一关，现在过rewrite第二关
            }
        }
        // 当前轮追加消息（history 之后），原样保留不投影
        for (int i = historySize; i < memoryMessages.size(); i++) {
            result.add(memoryMessages.get(i));
        }
        return result;
    }

    /**
     * 对单条消息应用 RewriteToolRule：若为 ToolExecutionResultMessage 且命中 rule，
     * 用占位符替换其 content
     * <p>占位符版保留原 tool_call_id 重建 ToolExecutionResultMessage，避免破坏与对应 ToolExecutionRequest 的配对。
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
        // 丢 content、留简要提示，便于 LLM 知道此处曾有工具调用
        String placeholder = "[此工具结果已被 Projection 精简：toolCallId=" + toolCallId
                + (rule.getReason() != null && !rule.getReason().isBlank()
                ? ", reason=" + rule.getReason() : "")
                + "]";
        // ToolExecutionResultMessage 需配对的 ToolExecutionRequest；用原 msg 的 name/id 重建
        return ToolExecutionResultMessage.from(
                dev.langchain4j.agent.tool.ToolExecutionRequest.builder()
                        .id(term.id())
                        .name(term.toolName())
                        .build(),
                placeholder);
    }

    /**
     * 历史段读路径（零投影版）：SystemMessage 首位 + memory 全量消息。
     *
     * <p>progressiveMode 在此重算（公式与 AgentExecutor.execute 一致），保证 systemMessage
     * 与 {@link #buildInitialToolSpecs} 使用的 progressiveMode 同源同步。
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
     * 动态构建系统提示词：聚合所有 CatalogProvider 的能力目录注入【可用能力】段，
     * 再按 progressiveMode 决定是全量展开技能说明还是仅提示"调 resolve 获取"。
     */
    private String buildSystemPrompt(boolean progressiveMode) {
        List<CatalogEntry> allEntries = new ArrayList<>();
        for (CatalogProvider provider : catalogProviders) {
            allEntries.addAll(provider.catalogEntries());
        }

        // 过滤掉元工具（META_TOOLS），这些不作为显式能力向 LLM 披露
        List<CatalogEntry> filtered = allEntries.stream()
                .filter(e -> !Catalog.META_TOOLS.contains(e.getName()))
                .collect(Collectors.toList());

        StringBuilder dynamicSection = new StringBuilder();

        if (!filtered.isEmpty()) {
            Catalog catalog = new Catalog(filtered);
            dynamicSection.append("【可用能力】\n").append(catalog.toPromptText()).append("\n\n");
        }

        if (!progressiveMode) {
            // 非渐进模式：完整技能说明直接铺进 system prompt
            List<String> allSkillNames = skillRegistry.getAllNames();
            if (!allSkillNames.isEmpty()) {
                String resolved = skillRegistry.resolve(allSkillNames);
                if (resolved != null && !resolved.isBlank() && !resolved.startsWith("未找到")) {
                    dynamicSection.append("【可用技能完整说明】\n").append(resolved).append("\n\n");
                }
            }
            dynamicSection.append("所有工具和技能的完整定义已在上方提供，请直接使用。");
        } else {
            // 渐进模式：能力数过多，引导 LLM 先看目录、按需调 resolve 取定义
            dynamicSection.append("由于可用工具和技能较多，请先查看上方目录了解可用能力。"
                    + "如需使用某个工具或技能，请调用 resolve 获取其完整定义。");
        }

        String template = progressiveMode ? SYSTEM_PROMPT_TEMPLATE_PROGRESSIVE : SYSTEM_PROMPT_TEMPLATE_FULL;
        return String.format(template, dynamicSection.toString());
    }

    @Override
    public List<ToolSpecification> buildInitialToolSpecs(boolean progressiveMode) {
        if (!progressiveMode) {
            return toolRegistry.getToolSpecifications(); // 非渐进：全量注入
        }
        // 渐进：初始只注入 resolve（"工具之工具"，可用于按需获取其他工具的完整定义）
        List<ToolSpecification> specs = new ArrayList<>();
        ToolSpecification resolveSpec = toolRegistry.getToolSpecification("resolve");
        if (resolveSpec != null) {
            specs.add(resolveSpec);
        }
        return specs;
    }

    /**
     * 每轮工具规格装配：渐进模式下把当前轮已激活的工具定义追加到 initialSpecs 上。
     * <p>非渐进模式或无激活工具时直接返回 initialSpecs，避免无谓复制。
     */
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
