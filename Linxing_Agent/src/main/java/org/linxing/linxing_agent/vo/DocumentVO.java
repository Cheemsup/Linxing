package org.linxing.linxing_agent.vo;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.OffsetDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DocumentVO {

    private Integer id;

    private String fileName;

    private Long fileSize;

    private String fileType;

    private String status;

    private String chunkStrategy;

    private OffsetDateTime createdAt;
}
