package org.linxing.linxing_agent.agent.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.linxing.linxing_agent.agent.entity.ExamContext;

import java.util.List;

@Mapper
public interface ExamContextMapper {
    int batchInsert(@Param("list") List<ExamContext> list);
    List<ExamContext> selectByExamId(@Param("examId") Integer examId);
}
