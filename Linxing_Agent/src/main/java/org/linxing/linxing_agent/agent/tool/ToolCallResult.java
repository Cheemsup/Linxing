package org.linxing.linxing_agent.agent.tool;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ToolCallResult {

    private String toolCallId;
    private String toolName;
    private String result;
    private boolean success;
    private String error;

    public static ToolCallResult success(String toolCallId, String toolName, String result) {
        return ToolCallResult.builder()
                .toolCallId(toolCallId)
                .toolName(toolName)
                .result(result)
                .success(true)
                .build();
    }

    public static ToolCallResult failure(String toolCallId, String toolName, String error) {
        return ToolCallResult.builder()
                .toolCallId(toolCallId)
                .toolName(toolName)
                .error(error)
                .success(false)
                .build();
    }
}
