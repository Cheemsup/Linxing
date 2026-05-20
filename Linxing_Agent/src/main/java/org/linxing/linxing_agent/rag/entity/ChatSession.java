package org.linxing.linxing_agent.rag.entity;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.OffsetDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ChatSession {
    private Integer id;
    private Integer userId;
    private String title;
    private OffsetDateTime createdAt;
    private OffsetDateTime updatedAt;
}
