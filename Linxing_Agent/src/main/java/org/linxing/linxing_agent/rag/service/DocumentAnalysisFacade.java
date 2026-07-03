package org.linxing.linxing_agent.rag.service;

import lombok.extern.slf4j.Slf4j;
import org.linxing.linxing_agent.rag.dto.ParseResult;
import org.linxing.linxing_agent.rag.node.DocumentNode;
import org.linxing.linxing_agent.rag.node.NodeConverter;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import java.nio.file.Path;
import java.util.List;

/**
 * 文档分析服务门面（Facade）。
 * 优先调用 Python 服务获取 Node 序列；失败时 fallback 到 Java 备用方案。
 * //TODO：需说明目前备选方案还未实现，调用会报错（0702）；调整该文件的位置，现在的位置是接口的层次
 */
@Slf4j
@Service
public class DocumentAnalysisFacade {

    private final DocumentAnalysisService pythonService;
    private final DocumentAnalysisService javaService;
    private final NodeConverter nodeConverter;

    public DocumentAnalysisFacade(
            @Qualifier("pythonDocumentAnalysisService") DocumentAnalysisService pythonService,
            @Qualifier("javaDocumentAnalysisService") DocumentAnalysisService javaService,
            NodeConverter nodeConverter) {
        this.pythonService = pythonService;
        this.javaService = javaService;
        this.nodeConverter = nodeConverter;
    }

    /**
     * 分析文档，返回 Node 列表。
     * 优先使用 Python 服务，失败时 fallback 到 Java 备用方案。
     *
     * @param file       文档文件路径
     * @param documentId 文档 ID，用于图片目录隔离
     * @param userId     用户 ID，用于图片目录隔离
     * @return Node 序列
     */
    public List<DocumentNode> analyze(Path file, int documentId, int userId) {
        try {
            ParseResult parseResult = pythonService.analyze(file, documentId, userId);
            return nodeConverter.convert(parseResult.getNodes());
        } catch (Exception e) {
            log.warn("Python 服务不可用，fallback 到 Java 备用方案: {}", e.getMessage());
            try {
                ParseResult parseResult = javaService.analyze(file, documentId, userId);
                return nodeConverter.convert(parseResult.getNodes());
            } catch (Exception fallbackError) {
                log.error("Java 备用方案也失败: {}", fallbackError.getMessage(), fallbackError);
                throw new RuntimeException("文档解析彻底失败", fallbackError);
            }
        }
    }

    /**
     * 分析文档，返回 Node 列表（兼容旧调用，默认 ID 为 0）。
     *
     * @param file 文档文件路径
     * @return Node 序列
     */
    public List<DocumentNode> analyze(Path file) {
        return analyze(file, 0, 0);
    }
}