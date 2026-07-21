package org.linxing.linxing_agent.agent.memory.window.recovery;

import lombok.Builder;
import lombok.Value;

/**
 * 对话 Turn 边界。
 *
 * <p>一个 Turn = 起始 UserMessage →（中间 Assistant/Tool 往来）→ Assistant Final。
 * {@link #startIdx}/{@link #endIdx} 是该 Turn 在 history 消息列表中的下标区间，左闭右开；
 * {@link #turnStartMessageId} 是起始 UserMessage 的 DB id，与 SkipTurnRule 匹配用。
 *
 * <p><b>下标对齐前提</b>：SystemMessage 不进 memory，故 turnBoundaries 的下标与
 * memory.messages() 的 history 段前缀一一对齐；当前轮追加消息在 history 之后，不属于任何 Turn。
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
