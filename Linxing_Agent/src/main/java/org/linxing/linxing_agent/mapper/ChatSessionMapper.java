package org.linxing.linxing_agent.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.linxing.linxing_agent.entity.ChatSession;

import java.util.List;

@Mapper
public interface ChatSessionMapper {
    int insert(ChatSession session);
    ChatSession selectById(@Param("id") Integer id);
    List<ChatSession> selectByUserId(@Param("userId") Integer userId,
                                     @Param("offset") int offset,
                                     @Param("limit") int limit);
    int countByUserId(@Param("userId") Integer userId);
    int updateTitle(@Param("id") Integer id, @Param("title") String title);
    int updateUpdatedAt(@Param("id") Integer id);
    int deleteById(@Param("id") Integer id);
}
