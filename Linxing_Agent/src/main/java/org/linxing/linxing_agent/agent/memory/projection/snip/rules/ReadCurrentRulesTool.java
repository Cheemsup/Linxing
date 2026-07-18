package org.linxing.linxing_agent.agent.memory.projection.snip.rules;

import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.model.chat.request.json.JsonObjectSchema;
import org.linxing.linxing_agent.agent.memory.projection.snip.SkipTurnReActContext;
import org.linxing.linxing_agent.agent.memory.ruleset.RuleSet;

/**
 * 内部只读 tool：返回当前会话的 RuleSet 供 LLM 决策（thePlan P2-2 / nowRefact §6-5）。
 *
 * <p>同 {@link UpdateSkipTurnRuleTool}：不实现主 {@code Tool} 接口、不进 ToolRegistry，
 * 仅提供静态 {@link #SPEC} 与 {@link #execute(SkipTurnReActContext)} 供小循环手工分派。
 *
 * <p>让 LLM 在增改 SkipTurnRule 前先查看现状，避免重复添加或误删有效条目
 *（增量操作前提：知晓当前规则集状态，nowRefact §6-5）。
 */
public final class ReadCurrentRulesTool {

    public static final String NAME = "read_current_rules";

    public static final ToolSpecification SPEC = ToolSpecification.builder()
            .name(NAME)
            .description("只读：返回当前会话已存在的 Rule Set（含所有 SkipTurnRule 与 RewriteToolRule）。"
                    + "在增删改 rule 前调用以避免重复或误删。无参数。")
            .parameters(JsonObjectSchema.builder().build())
            .build();

    private ReadCurrentRulesTool() {
    }

    /** 返回当前 RuleSet 的 JSON 文本；序列化失败返回简要文本摘要兜底。 */
    public static String execute(SkipTurnReActContext ctx) {
        RuleSet ruleSet = ctx.getRuleSetStore().get(ctx.getSessionId());
        try {
            return ctx.getObjectMapper().writeValueAsString(ruleSet);
        } catch (Exception e) {
            // 序列化失败不阻断小循环，返回摘要兜底
            return "skipRules=" + ruleSet.getSkipTurnRules().size()
                    + ", rewriteRules=" + ruleSet.getRewriteToolRules().size()
                    + " (serialize failed: " + e.getMessage() + ")";
        }
    }
}
