package org.linxing.linxing_agent.rag.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ChatRequest {

    @NotBlank(message = "问题不能为空")
    @Size(max = 2000, message = "问题长度不能超过2000个字符")
    private String question;

    private Integer sessionId;

    private Integer parentMessageId;

    private Integer userId;
}
