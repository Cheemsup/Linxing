package org.linxing.linxing_agent.agent.dto;

import lombok.Data;

/**
 * 学习计划进度更新请求 DTO
 */
@Data
public class StudyPlanProgressUpdateRequest {
    /**
     * 阶段状态：not_started / in_progress / completed
     */
    private String status;

    /**
     * 用户学习笔记，可选
     */
    private String notes;
}
