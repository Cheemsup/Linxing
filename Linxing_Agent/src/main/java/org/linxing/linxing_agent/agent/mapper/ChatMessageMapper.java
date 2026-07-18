package org.linxing.linxing_agent.agent.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.linxing.linxing_agent.agent.entity.ChatMessage;

import java.util.List;

@Mapper
public interface ChatMessageMapper {
    int insert(ChatMessage message);
    ChatMessage selectById(@Param("id") Integer id);
    List<ChatMessage> selectBySessionId(@Param("sessionId") Integer sessionId);
    ChatMessage selectLatestBySessionId(@Param("sessionId") Integer sessionId);
    int countBySessionId(@Param("sessionId") Integer sessionId);
    int deleteByIds(@Param("ids") List<Integer> ids);
    int deleteBySessionId(@Param("sessionId") Integer sessionId);

    /**
     * 点查某消息回溯路径上最近的 summary 节点 id（thePlan P1-3 Recovery 加速）。
     * @return nearest_summary_message_id；未填值返回 null
     */
    Integer selectNearestSummaryId(@Param("id") Integer messageId);

    /**
     * 批量更新一组消息的 nearest_summary_message_id，使其指向新落盘的 summary（thePlan P1-2 第 3 步）。
     * 只对 summary 之后新写入的消息填值，被压缩的旧消息不在此批次内。
     */
    int updateNearestSummaryId(@Param("ids") List<Integer> messageIds,
                               @Param("summaryId") Integer summaryId);

    /**
     * 更新某消息的 parent_id（thePlan P1-2：summary 落盘后将用户消息挂到 summary 节点下）。
     */
    int updateParentId(@Param("id") Integer messageId, @Param("parentId") Integer parentId);
}
