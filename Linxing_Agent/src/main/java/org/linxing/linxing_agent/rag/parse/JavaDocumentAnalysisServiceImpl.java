package org.linxing.linxing_agent.rag.parse;

import lombok.extern.slf4j.Slf4j;
import org.linxing.linxing_agent.rag.dto.ParseResult;
import org.springframework.stereotype.Service;

import java.nio.file.Path;

/**
 * Java 文档分析服务实现（备用方案）。
 * 优先级低，暂未实现完整功能。
 * 后续可使用 PDFBox + Apache POI 实现 Node 提取。
 * 
 * TODO：待实现
 */
@Slf4j
@Service("javaDocumentAnalysisService")
public class JavaDocumentAnalysisServiceImpl implements DocumentAnalysisService {

    @Override
    public ParseResult analyze(Path file, int documentId, int userId) {
        log.warn("Java 文档解析备用方案尚未实现，文件: {}", file);
        throw new UnsupportedOperationException(
                "Java 文档解析备用方案尚未实现，请启用 Python 服务");
    }
}