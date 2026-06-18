package org.linxing.linxing_agent.agent.dto;

import lombok.AllArgsConstructor;
import lombok.Data;

@Data
@AllArgsConstructor
public class QuestionError {
    private int index;   // -1 表示 metadata 级别错误
    private String field;
    private String message;
}
