package org.linxing.linxing_agent.entity;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.OffsetDateTime;

/**
 * 文档分块实体类
 * 用于存储文档切分后的文本块信息
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Chunk {

    private Integer id;

    private Integer userId;

    private Integer documentId;

    private String chunkText;

    private Integer pageNumber;

    private OffsetDateTime createdAt;
}
