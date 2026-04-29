package org.linxing.linxing_agent.entity;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.OffsetDateTime;

/**
 * 文档分块实体类
 * 支持分层存储：Level 1（大粒度结构块）和 Level 2（小粒度检索块）
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

    private Integer parentChunkId;

    private Short chunkLevel;

    private String chunkType;

    private String titlePath;

    private String contextPrefix;

    private String sourceStrategy;

    private Boolean isSearchable;

    private String tsContent;

    private OffsetDateTime createdAt;
}
