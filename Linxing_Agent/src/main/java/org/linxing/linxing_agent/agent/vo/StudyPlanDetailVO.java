package org.linxing.linxing_agent.agent.vo;

import lombok.Builder;
import lombok.Data;

import java.util.List;

/**
 * 学习计划详情 VO（含阶段列表与进度）
 */
@Data
@Builder
public class StudyPlanDetailVO {
    private Integer id;
    private String title;
    private String description;
    private String goal;
    private String duration;
    private String status;
    private String sourceType;
    private String sourceRefs;
    private Integer phaseCount;
    private String createdAt;
    private String updatedAt;
    private List<StudyPlanPhaseVO> phases;

    /**
     * 完成进度统计
     */
    private ProgressStats progress;

    @Data
    @Builder
    public static class ProgressStats {
        private int totalPhases;
        private int completedPhases;
        private int inProgressPhases;
        private int notStartedPhases;
        /**
         * 完成百分比 0-100
         */
        private int completionPercentage;
    }
}
