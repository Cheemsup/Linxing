package org.linxing.linxing_agent.agent.dto;

import lombok.Data;

import java.util.Map;

@Data
public class ExamSubmitRequest {
    private Map<String, Object> answers;
}
