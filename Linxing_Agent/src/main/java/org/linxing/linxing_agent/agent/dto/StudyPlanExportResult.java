package org.linxing.linxing_agent.agent.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 学习计划导出结果（含内容与文件元信息）
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class StudyPlanExportResult {

    /** 导出内容正文 */
    private String content;

    /** Content-Type，如 text/markdown; charset=UTF-8 */
    private String contentType;

    /** 文件扩展名，如 md / html */
    private String fileExtension;

    /** 从内容中解析出的标题，用于文件命名 */
    private String title;
}
