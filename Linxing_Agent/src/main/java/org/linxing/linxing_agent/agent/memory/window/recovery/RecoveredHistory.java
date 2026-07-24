package org.linxing.linxing_agent.agent.memory.window.recovery;

import dev.langchain4j.data.message.ChatMessage;
import lombok.Builder;
import lombok.Data;

import java.util.List;

/**
 * Recovery 结果
 *
 * <p>瘦身说明（0721）：原 {@code pathEntities}（路径原始实体链）已删除——经消费链路核查，
 * 其实体内容从未被遍历消费，仅用于 {@code !isEmpty()} 判空，而该判空可由
 * {@code pathEndMessageId} 非空等价表达。路径相关唯一实际消费是 {@code pathEndMessageId}
 * （Summary 挂载点候选）。删字段后 {@code messages} 与 {@code pathEndMessageId} 职责清晰。
 */
@Data
@Builder
public class RecoveredHistory {

    /** 已重建（含 tool 回放）的 langchain4j 消息列表，从旧到新。 */
    private List<ChatMessage> messages;

    /** 路径上命中的最近 summary 实体（"之前"语义）；无则 null。 */
    private org.linxing.linxing_agent.agent.entity.ChatMessage summaryEntity;

    /** 当前路径末端 message id（用户消息的 parent 链末端，summary 的挂载点候选）。 */
    private Integer pathEndMessageId;

    /** history 段的 Turn 边界列表（供 DefaultContextBuilder.assembleMessages 应用 SkipTurnRule 用）；与 messages 下标对齐。 */
    private List<TurnBoundary> turnBoundaries;
}
