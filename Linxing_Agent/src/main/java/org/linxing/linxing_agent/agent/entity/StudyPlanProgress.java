package org.linxing.linxing_agent.agent.entity;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.OffsetDateTime;

/**
 * 学习计划进度表实体
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class StudyPlanProgress {
    private Integer id;
    private Integer planId;
    private Integer phaseId;
    private Integer userId;
    private String status;
    private String notes;
    private OffsetDateTime completedAt;
    private OffsetDateTime createdAt;
    private OffsetDateTime updatedAt;
}
