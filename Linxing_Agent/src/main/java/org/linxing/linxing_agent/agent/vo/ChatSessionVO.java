package org.linxing.linxing_agent.agent.vo;

import lombok.Builder;
import lombok.Data;

import java.time.OffsetDateTime;

@Data
@Builder
public class ChatSessionVO {
    private Integer id;
    private String title;
    private OffsetDateTime createdAt;
    private OffsetDateTime updatedAt;
    private Integer messageCount;
}
