package org.linxing.linxing_agent.agent.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.linxing.linxing_agent.agent.entity.StudyPlanProgress;

import java.util.List;

/**
 * 学习计划进度表 Mapper
 */
@Mapper
public interface StudyPlanProgressMapper {
    /**
     * 批量插入进度记录（每个阶段一条 not_started 记录）
     */
    int batchInsert(@Param("list") List<StudyPlanProgress> list);

    /**
     * 查询某计划下所有阶段的进度
     */
    List<StudyPlanProgress> selectByPlanId(@Param("planId") Integer planId, @Param("userId") Integer userId);

    /**
     * 查询单个阶段的进度
     */
    StudyPlanProgress selectByPhaseId(@Param("phaseId") Integer phaseId, @Param("userId") Integer userId);

    /**
     * 更新阶段状态
     */
    int updateStatus(@Param("phaseId") Integer phaseId,
                     @Param("userId") Integer userId,
                     @Param("status") String status,
                     @Param("notes") String notes);

    /**
     * 统计计划下各状态的数量
     */
    int countByPlanIdAndStatus(@Param("planId") Integer planId,
                               @Param("userId") Integer userId,
                               @Param("status") String status);
}
