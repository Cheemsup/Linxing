package org.linxing.linxing_agent.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.linxing.linxing_agent.entity.ChatMessage;

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
}
