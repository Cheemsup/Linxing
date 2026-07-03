package org.linxing.linxing_agent.rag.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 单个 Node 的 DTO，对应 Python 服务返回的 Node JSON。
 * 用于反序列化 Python 解析结果，随后转换为 Java DocumentNode。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class NodeDTO {

    /**
     * Node 唯一标识（如 "n1"、"n2"）
     */
    private String id;

    /**
     * Node 类型：heading、text、image、code、table、formula
     */
    private String type;

    /**
     * 文本内容（适用于 text、heading、code、formula）
     */
    private String text;

    /**
     * 图片路径（适用于 image）
     */
    private String imagePath;

    /**
     * 图片哈希值（适用于 image，用于去重）
     */
    private String hash;

    /**
     * 表格 HTML 内容（适用于 table）
     */
    private String html;

    /**
     * 代码语言（适用于 code）
     */
    private String language;

    /**
     * 图片标题/说明（适用于 image）
     */
    private String caption;

    /**
     * 标题级别（适用于 heading，1-6）
     */
    private Integer level;

    /**
     * 页码（PDF 文档）
     */
    private Integer page;

    /**
     * 边界框 [x, y, width, height]，用于定位
     */
    private List<Float> bbox;

    /**
     * 表格行数（适用于 table）
     */
    private Integer rowCount;

    /**
     * 表格列数（适用于 table）
     */
    private Integer colCount;
}