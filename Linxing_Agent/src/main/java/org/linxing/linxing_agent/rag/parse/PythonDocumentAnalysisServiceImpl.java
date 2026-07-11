package org.linxing.linxing_agent.rag.parse;

import lombok.extern.slf4j.Slf4j;
import org.linxing.linxing_agent.rag.config.RagProperties;
import org.linxing.linxing_agent.rag.dto.ParseResult;
import org.springframework.core.io.FileSystemResource;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;

import java.nio.file.Path;

/**
 * Python 文档分析服务实现（主方案）。
 * 通过 HTTP 调用 Python运行的document_analysis_service 服务的 /parse 接口，获取有序、原子化的 Node JSON 序列。
 */
@Slf4j
@Service("pythonDocumentAnalysisService")
public class PythonDocumentAnalysisServiceImpl implements DocumentAnalysisService {

    private final RagProperties ragProperties;
    private final RestClient restClient;//http发起的动作执行对象

    public PythonDocumentAnalysisServiceImpl(RagProperties ragProperties) {
        this.ragProperties = ragProperties;
        RagProperties.PythonService pythonService = ragProperties.getPythonService();
        // 超时配置：连接 10s，读取按配置（大文件解析耗时较长）
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(10_000);
        factory.setReadTimeout(pythonService.getTimeoutSeconds() * 1000);

        this.restClient = RestClient.builder()
                .baseUrl(pythonService.getUrl())
                .requestFactory(factory)
                .build();
    }

    @Override
    public ParseResult analyze(Path file, int documentId, int userId) {
        RagProperties.PythonService pythonService = ragProperties.getPythonService();
        if (!pythonService.isEnabled()) {
            throw new IllegalStateException("Python 文档解析服务未启用");
        }

        try {
            FileSystemResource resource = new FileSystemResource(file);

            MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
            body.add("file", resource);
            body.add("documentId", String.valueOf(documentId));
            body.add("userId", String.valueOf(userId));

            //发起post请求到python服务
            ParseResult result = restClient.post()
                    .uri("/parse")
                    .contentType(MediaType.MULTIPART_FORM_DATA)
                    .body(body)
                    .retrieve()
                    .body(ParseResult.class);

            if (result == null || result.getNodes() == null || result.getNodes().isEmpty()) {
                throw new IllegalStateException("Python 解析返回空 Node 序列");
            }

            log.info("Python 解析完成，文档类型: {}，返回 {} 个 Node",
                    result.getDocumentType(), result.getNodes().size());
            return result;

        } catch (Exception e) {
            log.error("调用 Python 服务失败: {}", e.getMessage(), e);
            throw new RuntimeException("Python 文档解析失败: " + e.getMessage(), e);
        }
    }
}