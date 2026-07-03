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

    /**
     * 标题路径（如 "第一章 > 第一节"）。
     * 由 Python 侧结构识别产出，非标题块也带其所属标题路径；
     * docx/pdf 等结构化文档的 chunk 据此携带 nodeMetadata。
     */
    private String titlePath;

    /**
     * 超长单元的父 Node ID（用于父子 chunk）。
     * Python 侧对超长 section/段落/方法做二次切分时，拆出的子 Node 标 parentId 指向同源 Level1 父 Node 的 id；
     * 普通块为 null。
     */
    private String parentId;
}