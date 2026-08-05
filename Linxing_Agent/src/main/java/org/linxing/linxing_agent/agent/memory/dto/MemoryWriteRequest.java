package org.linxing.linxing_agent.agent.memory.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/**
 * 长期记忆文件写入请求。
 *
 * @param path    相对用户根目录的路径，如 {@code Learning/Current.md}；不允许为空
 * @param content Markdown 全文；允许空串表示清空文件
 */
@Data
public class MemoryWriteRequest {

    @NotBlank(message = "path 不能为空")
    private String path;

    private String content;
}