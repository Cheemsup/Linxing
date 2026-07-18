package org.linxing.linxing_agent.agent.vo;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.OffsetDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ChatMessageVO {
    private Integer id;
    private Integer userId;
    private Integer sessionId;
    private Integer parentId;
    /** 消息类型：user / assistant / summary。前端对 summary 做特殊 CSS 装饰（thePlan P1-1/P1-D） */
    private String type;
    private String content;
    private String sources;
    private OffsetDateTime createdAt;
}
