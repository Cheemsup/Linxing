package org.linxing.linxing_agent.agent.entity;

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
public class AgentStep {

    private Integer id;
    private Integer chatMessageId;
    private Integer sessionId;
    private Integer stepOrder;
    private String stepType;
    private String content;
    private Map<String, Object> stepData;
    private OffsetDateTime createdAt;
}
