package org.linxing.linxing_agent.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 文档导入响应DTO
 * 用于封装文档上传和处理的结果信息
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class IngestResponse {

    private boolean success;

    private String message;

    private int chunksCount;
}
