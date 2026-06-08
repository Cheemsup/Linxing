package org.linxing.linxing_agent.agent.vo;

import lombok.Builder;
import lombok.Data;

import java.time.OffsetDateTime;

@Data
@Builder
public class ExamVO {
    private Integer id;
    private String title;
    private String status;
    private String sourceType;
    private Integer questionCount;
    private OffsetDateTime createdAt;
}
