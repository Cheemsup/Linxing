package org.linxing.linxing_agent.agent.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.linxing.linxing_agent.agent.entity.AgentStep;

import java.util.List;

@Mapper
public interface AgentStepMapper {

    int insert(AgentStep step);

    List<AgentStep> selectByChatMessageId(@Param("chatMessageId") Integer chatMessageId);

    List<AgentStep> selectBySessionId(@Param("sessionId") Integer sessionId);

    int deleteBySessionId(@Param("sessionId") Integer sessionId);

    int updateChatMessageId(@Param("sessionId") Integer sessionId, @Param("chatMessageId") Integer chatMessageId);
}
