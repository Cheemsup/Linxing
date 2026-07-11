package org.linxing.linxing_agent.rag.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;

/**
 * Python 文档解析服务返回的解析结果。
 * 封装文档类型和 Node 序列。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ParseResult {

    /**
     * 文档类型
     */
    private String documentType;

    /**
     * Node 序列，按阅读顺序排列
     */
    @Builder.Default
    private List<NodeDTO> nodes = new ArrayList<>();
}