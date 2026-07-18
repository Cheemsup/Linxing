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
    /** 消息类型：user / assistant / summary（thePlan P1-1：原 role 更名并扩 CHECK） */
    private String type;
    private String content;
    private String sources;
    /** 当前节点之后最近的 summary 节点 id；只对 summary 之后新增的消息填值，被压缩旧消息与 summary 自身为 NULL */
    private Integer nearestSummaryMessageId;
    private OffsetDateTime createdAt;
}
