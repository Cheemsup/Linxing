package org.linxing.linxing_agent.agent.entity;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.OffsetDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ExamAnswer {
    private Integer id;
    private Integer examId;
    private Integer userId;
    private String answers;
    private Integer score;
    private Integer total;
    private OffsetDateTime completedAt;
}
