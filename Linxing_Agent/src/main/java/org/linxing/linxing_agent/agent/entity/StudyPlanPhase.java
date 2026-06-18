package org.linxing.linxing_agent.agent.entity;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.OffsetDateTime;

/**
 * 学习阶段表实体
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class StudyPlanPhase {
    private Integer id;
    private Integer planId;
    private Integer userId;
    private Integer phaseOrder;
    private String title;
    private String duration;
    private String objective;
    private String keyTopics;
    private String resources;
    private String practiceTasks;
    private String milestones;
    private OffsetDateTime createdAt;
}
