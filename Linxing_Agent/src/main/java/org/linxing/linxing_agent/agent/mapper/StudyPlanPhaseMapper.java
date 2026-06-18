package org.linxing.linxing_agent.agent.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.linxing.linxing_agent.agent.entity.StudyPlanPhase;

import java.util.List;

/**
 * 学习阶段表 Mapper
 */
@Mapper
public interface StudyPlanPhaseMapper {
    int batchInsert(@Param("list") List<StudyPlanPhase> list);

    List<StudyPlanPhase> selectByPlanId(@Param("planId") Integer planId);
}
