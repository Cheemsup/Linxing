package org.linxing.linxing_agent.agent.vo;

import lombok.Builder;
import lombok.Data;

import java.time.OffsetDateTime;

/**
 * 学习计划列表项 VO
 */
@Data
@Builder
public class StudyPlanVO {
    private Integer id;
    private String title;
    private String goal;
    private String duration;
    private String status;
    private String sourceType;
    private Integer phaseCount;
    private OffsetDateTime createdAt;
}
