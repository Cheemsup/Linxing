package org.linxing.linxing_agent.agent.entity;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.OffsetDateTime;

/**
 * 学习计划主表实体
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class StudyPlan {
    private Integer id;
    private Integer userId;
    private String title;
    private String description;
    private String goal;
    private String duration;
    private String sourceType;
    private String sourceRefs;
    private String status;
    private Integer phaseCount;
    private OffsetDateTime createdAt;
    private OffsetDateTime updatedAt;
}
