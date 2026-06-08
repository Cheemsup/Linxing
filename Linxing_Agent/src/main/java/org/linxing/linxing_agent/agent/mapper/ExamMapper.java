package org.linxing.linxing_agent.agent.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.linxing.linxing_agent.agent.entity.Exam;

import java.util.List;

@Mapper
public interface ExamMapper {
    int insert(Exam exam);
    Exam selectById(@Param("userId") Integer userId, @Param("examId") Integer examId);
    List<Exam> selectByUserId(@Param("userId") Integer userId,
                              @Param("status") String status,
                              @Param("offset") int offset,
                              @Param("limit") int limit);
    int countByUserId(@Param("userId") Integer userId, @Param("status") String status);
    int updateStatus(@Param("examId") Integer examId, @Param("status") String status);
}
