package org.linxing.linxing_agent.rag.dto;

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

    /**
     * 业务状态码：
     * 0 - 失败；1 - 成功；2 - 重名待确认（需要用户确认是否覆盖）
     */
    @Builder.Default
    private int code = 1;

    /**
     * 重名待确认时返回的原文档 ID，供前端确认后带上覆盖
     */
    private Integer duplicateDocumentId;
}
