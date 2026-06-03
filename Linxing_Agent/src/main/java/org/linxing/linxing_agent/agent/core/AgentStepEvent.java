package org.linxing.linxing_agent.agent.core;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Map;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AgentStepEvent {

    private String eventType;
    private int stepNumber;
    private String phase;
    private Map<String, Object> stepData;
    private String answer;
    private String error;
    private boolean finalStep;
}
