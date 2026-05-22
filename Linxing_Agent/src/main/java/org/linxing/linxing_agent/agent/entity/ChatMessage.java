package org.linxing.linxing_agent.agent.entity;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.OffsetDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ChatMessage {
    private Integer id;
    private Integer userId;
    private Integer sessionId;
    private Integer parentId;
    private String role;
    private String content;
    private String sources;
    private OffsetDateTime createdAt;
}
