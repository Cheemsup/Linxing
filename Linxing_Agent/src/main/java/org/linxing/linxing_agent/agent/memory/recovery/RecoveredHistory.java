package org.linxing.linxing_agent.agent.memory.recovery;

import dev.langchain4j.data.message.ChatMessage;
import lombok.Builder;
import lombok.Data;

import java.util.List;

/**
 * Recovery 结果
 *
 * TODO：观察这个实体的消费链路，分析是否可以瘦身
 */
@Data
@Builder
public class RecoveredHistory {

    /** 已重建（含 tool 回放）的 langchain4j 消息列表，从旧到新。 */
    private List<ChatMessage> messages;

    /** 路径上每条消息的实体（含 id），从旧到新；供 Summary 判定 successorIds 与挂载点。 */
    private List<org.linxing.linxing_agent.agent.entity.ChatMessage> pathEntities;

    /** 路径上命中的最近 summary 实体（"之前"语义）；无则 null。 */
    private org.linxing.linxing_agent.agent.entity.ChatMessage summaryEntity;

    /** 当前路径末端 message id（用户消息的 parent 链末端，summary 的挂载点候选）。 */
    private Integer pathEndMessageId;

    /** history 段的 Turn 边界列表（2-D 起 buildMessages 应用 SkipTurnRule 用）；与 messages 下标对齐。 */
    private List<TurnBoundary> turnBoundaries;
}
