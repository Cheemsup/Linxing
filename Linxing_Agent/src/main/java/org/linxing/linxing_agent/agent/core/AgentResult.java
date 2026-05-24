package org.linxing.linxing_agent.agent.core;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.linxing.linxing_agent.agent.vo.AgentStepVO;

import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AgentResult {

    private String answer;
    private String sourcesJson;
    private List<AgentStepVO> steps;
    private Integer messageId;
    private int totalSteps;
    private boolean exceededMaxSteps;
}
