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
public class ExamContext {
    private Integer id;
    private Integer examId;
    private Integer userId;
    private Integer questionOrder;
    private String questionType;
    private String stem;
    private String options;
    private String answer;
    private String explanation;
    private String difficulty;
    private OffsetDateTime createdAt;
}
