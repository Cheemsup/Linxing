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
public class Exam {
    private Integer id;
    private Integer userId;
    private String title;
    private String description;
    private String status;
    private String sourceType;
    private String sourceRefs;
    private Integer questionCount;
    private OffsetDateTime createdAt;
}
