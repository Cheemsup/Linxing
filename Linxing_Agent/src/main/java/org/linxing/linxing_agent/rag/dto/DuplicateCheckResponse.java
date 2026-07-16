package org.linxing.linxing_agent.rag.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.OffsetDateTime;

/**
 * 同名文件预检响应DTO
 * 用于上传前判重：告知前端当前 user_id 下是否已存在同名文件，供其弹出覆盖确认框
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DuplicateCheckResponse {

    //是否已存在同名文件
    private boolean duplicate;

    //已存在的文档 ID（duplicate=false 时为 null）
    private Integer documentId;

    //文件名（回显，便于前端提示）
    private String fileName;

    //已存在文档的创建时间（duplicate=false 时为 null）
    private OffsetDateTime createdAt;
}
