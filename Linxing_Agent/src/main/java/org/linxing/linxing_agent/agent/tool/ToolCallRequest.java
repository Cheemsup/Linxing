package org.linxing.linxing_agent.agent.tool;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ToolCallRequest {

    private String toolCallId;
    private String toolName;
    private String arguments;
}
