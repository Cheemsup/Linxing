package org.linxing.linxing_agent.rag.parse;

import org.linxing.linxing_agent.rag.dto.ParseResult;

import java.nio.file.Path;

/**
 * 文档分析服务接口，定义文档解析的统一抽象，Python 服务与 Java 备用方案均实现此接口。
 */
public interface DocumentAnalysisService {

    /**
     * 分析文档，返回 Node 列表（带文档/用户 ID）。
     *
     * @param file       文档文件路径
     * @param documentId 文档 ID，用于图片目录隔离
     * @param userId     用户 ID，用于图片目录隔离
     * @return 解析结果，包含文档类型和 Node 序列
     */
    ParseResult analyze(Path file, int documentId, int userId);

    /**
     * 分析文档，返回 Node 列表（兼容旧调用，默认 ID 为 0）。
     *
     * @param file 文档文件路径
     * @return 解析结果，包含文档类型和 Node 序列
     */
    default ParseResult analyze(Path file) {
        return analyze(file, 0, 0);
    }
}