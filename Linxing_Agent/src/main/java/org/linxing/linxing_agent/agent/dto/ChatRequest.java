package org.linxing.linxing_agent.agent.dto;

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
    @Size(max = 5000, message = "问题长度不能超过5000个字符")
    private String question;

    private Integer sessionId;

    private Integer parentMessageId;

    private Integer userId;

    /**
     * 请求幂等键（前端 uuid 生成，retry 时复用同一值）。
     * <p>用途：SSE reset 后前端退避重试时，后端按 requestId 识别"已结束请求的 retry"——
     * 命中已完成的缓存结果则直接复用推送，不重跑推理、不重复落库（plan/exam/message 等）。
     * <p>为空时退化为非幂等请求（兼容旧客户端，不做去重）。
     */
    private String requestId;
}
