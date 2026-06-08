package org.linxing.linxing_agent.agent.vo;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class ExamContextVO {
    private Integer id;
    private Integer questionOrder;
    private String questionType;
    private String stem;
    private String options;
    private String difficulty;
}
