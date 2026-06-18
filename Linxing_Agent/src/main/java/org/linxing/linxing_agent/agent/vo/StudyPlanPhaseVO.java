package org.linxing.linxing_agent.agent.vo;

import lombok.Builder;
import lombok.Data;

/**
 * 学习阶段 VO（含进度状态）
 */
@Data
@Builder
public class StudyPlanPhaseVO {
    private Integer id;
    private Integer phaseOrder;
    private String title;
    private String duration;
    private String objective;
    /**
     * 关键知识点，JSON 数组字符串
     */
    private String keyTopics;
    /**
     * 学习资源，JSON 数组字符串
     */
    private String resources;
    /**
     * 实践任务，JSON 数组字符串
     */
    private String practiceTasks;
    /**
     * 阶段里程碑，JSON 数组字符串
     */
    private String milestones;
    /**
     * 该阶段的进度状态：not_started / in_progress / completed
     */
    private String progressStatus;
    /**
     * 用户学习笔记
     */
    private String notes;
}
