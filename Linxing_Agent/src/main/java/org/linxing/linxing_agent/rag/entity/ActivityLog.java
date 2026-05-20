package org.linxing.linxing_agent.rag.entity;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.OffsetDateTime;

/**
 * 活动日志实体类
 * 用于记录用户的操作日志，包括文件上传、查询等行为
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ActivityLog {

    private Long id;

    private Integer userId;

    private String actionType;

    private String targetType;

    private String targetId;

    private String details;

    private OffsetDateTime createdAt;
}
