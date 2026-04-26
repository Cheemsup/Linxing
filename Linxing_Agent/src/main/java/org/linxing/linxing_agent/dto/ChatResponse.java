package org.linxing.linxing_agent.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 聊天响应DTO
 * 用于封装RAG系统生成的回答及引用来源信息
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ChatResponse {

    private String answer;

    private List<String> sources;

    private String sessionId;
}
