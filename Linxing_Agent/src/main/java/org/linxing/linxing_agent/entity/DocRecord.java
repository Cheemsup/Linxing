package org.linxing.linxing_agent.entity;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.OffsetDateTime;

/**
 * 文档记录实体类
 * 用于存储上传文档的基本信息和处理状态
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DocRecord {

    private Integer id;

    private Integer userId;

    private String fileName;

    private String filePath;

    private Long fileSize;

    private String fileType;

    private String status;

    private String chunkStrategy;

    private OffsetDateTime createdAt;
}
