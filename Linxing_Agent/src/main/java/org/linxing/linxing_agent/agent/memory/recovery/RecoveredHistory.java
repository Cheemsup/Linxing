package org.linxing.linxing_agent.agent.memory.recovery;

import dev.langchain4j.data.message.ChatMessage;
import lombok.Builder;
import lombok.Data;

import java.util.List;

/**
 * Recovery 结果（thePlan P1-3）。
 * <p>
 * 由 {@code HistoryRecoveryService.recoverHistory} 产出，供 {@code ChatServiceImpl.chat} 消费：
 * <ul>
 *   <li>{@link #messages}：已按"工具调用组"重建好的 langchain4j 消息列表（含 tool 回放），
 *       顺序为从旧到新，可直接填入 AgentMemory</li>
 *   <li>{@link #pathEntities}：路径上每条消息的实体（含 id），供 Summary 判定 successorIds 与挂载点</li>
 *   <li>{@link #summaryEntity}：路径上命中的最近 summary 实体（"之前"语义，nearest_summary_message_id 点查）；
 *       非 null 表示历史已被压缩到该 summary，Recovery 返回的 messages 以此为起点</li>
 *   <li>{@link #pathEndMessageId}：当前路径末端 message id（即触发 Recovery 的用户消息的 parent 链末端，
 *       亦即 summary 的挂载点候选）</li>
 *   <li>{@link #turnBoundaries}：history 段的 Turn 边界列表（2-D 起 buildMessages 应用
 *       SkipTurnRule 用）；与 {@link #messages} 下标对齐，左闭右开区间</li>
 * </ul>
 *
 * 注意：本类的 {@code ChatMessage} 指 langchain4j 消息；实体用全限定名
 * {@code org.linxing.linxing_agent.agent.entity.ChatMessage}，二者不冲突。
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
