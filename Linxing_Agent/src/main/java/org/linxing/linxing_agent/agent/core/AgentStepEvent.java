package org.linxing.linxing_agent.agent.core;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AgentStepEvent {

    private String eventType;
    private int stepNumber;
    private String toolName;
    private String toolArguments;
    private String toolResult;
    private String answer;
    private String error;
    private boolean finalStep;
}
