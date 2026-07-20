package org.linxing.linxing_agent.agent.memory.ruleset;

import lombok.Value;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Rule Set
 *
 * <p>每会话一份，统一承载两类 rule：{@link SkipTurnRule}（哪些 Turn 跳过）与
 * {@link RewriteToolRule}（哪些 tool 结果精简）。Builder 每轮消费同一份 Rule Set
 * 构建 Projection。
 *
 * <p>Rule Set 不落库、不进 Redis Mirror（它是"投影规则"非"事实"）。
 */
@Value
public class RuleSet {

    /** 空 RuleSet（会话首次访问或无规则时的默认值）。 */
    public static final RuleSet EMPTY = new RuleSet(List.of(), List.of());

    List<SkipTurnRule> skipTurnRules;
    List<RewriteToolRule> rewriteToolRules;

    public RuleSet(List<SkipTurnRule> skipTurnRules, List<RewriteToolRule> rewriteToolRules) {
        this.skipTurnRules = List.copyOf(skipTurnRules);
        this.rewriteToolRules = List.copyOf(rewriteToolRules);
    }

    /** 该 RuleSet 是否命中某 Turn 起始消息（Builder 过滤时用）。 */
    public boolean shouldSkipTurn(Integer turnStartMessageId) {
        if (turnStartMessageId == null) {
            return false;
        }
        for (SkipTurnRule r : skipTurnRules) {
            if (turnStartMessageId.equals(r.getTurnStartMessageId())) {
                return true;
            }
        }
        return false;
    }

    /** 命中某 tool_call_id 的 RewriteToolRule；无则 null。 */
    public RewriteToolRule rewriteRuleFor(String toolCallId) {
        if (toolCallId == null) {
            return null;
        }
        for (RewriteToolRule r : rewriteToolRules) {
            if (toolCallId.equals(r.getToolCallId())) {
                return r;
            }
        }
        return null;
    }

    /** 当前所有 SkipTurnRule 涉及的 turnStartMessageId 集合（便于 Builder 快速过滤）。 */
    public Set<Integer> skippedTurnStartIds() {
        return skipTurnRules.stream()
                .map(SkipTurnRule::getTurnStartMessageId)
                .collect(Collectors.toUnmodifiableSet());
    }
}
