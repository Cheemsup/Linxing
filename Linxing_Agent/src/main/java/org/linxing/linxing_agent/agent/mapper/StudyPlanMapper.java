package org.linxing.linxing_agent.agent.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.linxing.linxing_agent.agent.entity.StudyPlan;

import java.util.List;

/**
 * 学习计划主表 Mapper
 */
@Mapper
public interface StudyPlanMapper {
    int insert(StudyPlan plan);

    StudyPlan selectById(@Param("userId") Integer userId, @Param("planId") Integer planId);

    List<StudyPlan> selectByUserId(@Param("userId") Integer userId,
                                   @Param("status") String status,
                                   @Param("offset") int offset,
                                   @Param("limit") int limit);

    int countByUserId(@Param("userId") Integer userId, @Param("status") String status);

    int updateStatus(@Param("planId") Integer planId, @Param("status") String status);

    int updatePhaseCount(@Param("planId") Integer planId, @Param("phaseCount") int phaseCount);
}
