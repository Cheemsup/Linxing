package org.linxing.linxing_agent.agent.vo;

import lombok.Builder;
import lombok.Data;

import java.time.OffsetDateTime;
import java.util.List;

@Data
@Builder
public class ExamDetailVO {
    private Integer id;
    private String title;
    private String status;
    private String sourceType;
    private Integer questionCount;
    private OffsetDateTime createdAt;
    private List<ExamContextVO> questions;
}
