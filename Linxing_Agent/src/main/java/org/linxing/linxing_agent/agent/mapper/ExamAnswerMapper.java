package org.linxing.linxing_agent.agent.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.linxing.linxing_agent.agent.entity.ExamAnswer;

@Mapper
public interface ExamAnswerMapper {
    int insert(ExamAnswer examAnswer);
    ExamAnswer selectByExamIdAndUserId(@Param("examId") Integer examId, @Param("userId") Integer userId);
    int updateToSubmitted(@Param("examId") Integer examId, @Param("userId") Integer userId,
                          @Param("answers") String answers, @Param("score") int score, @Param("total") int total);
    int updateDraft(@Param("examId") Integer examId, @Param("userId") Integer userId,
                    @Param("answers") String answers);
}
