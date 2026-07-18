package org.linxing.linxing_agent.agent.memory.recovery;

import lombok.Builder;
import lombok.Value;

/**
 * Conversation Turn 边界（2-D 起 buildMessages 应用 SkipTurnRule 用）。
 *
 * <p>一个 Turn = 起始 UserMessage（或 summary 摘要 UserMessage）→（可选 Assistant 中间回复）
 * → ToolCall → ToolResult →（可选 Skill/MCP）→ Assistant Final。
 * {@link #startIdx}/@{link #endIdx} 为该 Turn 在 Recovery 产出的 langchain4j 消息列表
 *（即填入 memory 的 history 段）中的下标区间，左闭右开。
 *
 * <p>{@link #turnStartMessageId} 为该 Turn 起始 UserMessage 的 DB chat_message id，
 * 与 {@code SkipTurnRule.turnStartMessageId} 对应，用于 rule 匹配。
 *
 * <p><b>下标对齐前提</b>：memory 不在 history 之前插入消息（SystemMessage 不进 memory，
 * 由 buildMessages 装配时置于首位），故 turnBoundaries 的下标与 memory.messages() 的
 * history 段前缀对齐。当前轮追加消息（用户问题 + 循环内 aiMessage/resultMsg）在 history 之后，
 * 不属于任何 TurnBoundary——它们尚未被 Snip 分析，不参与 SkipTurnRule。
 */
@Value
@Builder
public class TurnBoundary {

    /** 该 Turn 起始 UserMessage 的 chat_message id（summary 摘要 UserMessage 取原 summary 实体 id）。 */
    Integer turnStartMessageId;

    /** 该 Turn 在 history 消息列表中的起始下标（含）。 */
    int startIdx;

    /** 该 Turn 在 history 消息列表中的结束下标（不含）。 */
    int endIdx;
}
