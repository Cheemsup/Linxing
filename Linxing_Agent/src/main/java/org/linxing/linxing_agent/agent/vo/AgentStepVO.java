package org.linxing.linxing_agent.agent.vo;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.OffsetDateTime;
import java.util.Map;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AgentStepVO {

    private Integer id;
    private Integer stepOrder;
    private String stepType;
    private String content;
    private String label;
    private Map<String, Object> stepData;
    private OffsetDateTime createdAt;
}
