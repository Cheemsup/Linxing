package org.linxing.linxing_agent.rag.service.impl;

import dev.langchain4j.data.document.Document;
import dev.langchain4j.data.document.DocumentParser;
import dev.langchain4j.data.document.loader.FileSystemDocumentLoader;
import dev.langchain4j.data.document.parser.TextDocumentParser;
import dev.langchain4j.data.document.parser.apache.poi.ApachePoiDocumentParser;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.linxing.linxing_agent.rag.constant.DocumentStatus;
import org.linxing.linxing_agent.rag.config.RagProperties;
import org.linxing.linxing_agent.rag.dto.IngestResponse;
import org.linxing.linxing_agent.rag.entity.DocRecord;
import org.linxing.linxing_agent.rag.mapper.DocumentMapper;
import org.linxing.linxing_agent.rag.pipeline.ChunkPipelineCoordinator;
import org.linxing.linxing_agent.rag.service.IIngestService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;

@Slf4j
@Service
@RequiredArgsConstructor
public class IngestServiceImpl implements IIngestService {

    private final ChunkPipelineCoordinator chunkPipelineCoordinator;
    private final DocumentMapper documentMapper;
    private final RagProperties ragProperties;
    private final SemanticCacheService semanticCacheService;

    @Override
    @Transactional
    public IngestResponse ingestFile(MultipartFile file, Integer userId) {
        if (file.isEmpty()) {
            return IngestResponse.builder()
                    .success(false)
                    .message("上传文件为空")
                    .chunksCount(0)
                    .build();
        }

        String originalFilename = file.getOriginalFilename();
        log.info("[用户{}]，文件上传: {}, 大小: {} bytes", userId, originalFilename, file.getSize());

        DocRecord docRecord = null;
        try {
            Path storedFile = persistFile(file);
            log.debug("文件已持久化到: {}", storedFile);

            String fileType = extractFileType(originalFilename);//根据文件名称，获取文件类型为后续chunk决策做参考

            docRecord = DocRecord.builder()
                    .userId(userId)
                    .fileName(originalFilename)
                    .filePath(storedFile.toString())
                    .fileSize(file.getSize())
                    .fileType(fileType)
                    .status(DocumentStatus.PROCESSING)
                    .createdAt(OffsetDateTime.now())
                    .build();
            documentMapper.insert(docRecord);//插入原始文件元数据记录
            log.info("文档记录已入库，documentId: {}", docRecord.getId());

            DocumentParser parser = createParser(fileType);
            Document document = FileSystemDocumentLoader.loadDocument(storedFile, parser);
            document.metadata().put("file_name", originalFilename);
            document.metadata().put("stored_path", storedFile.toString());

            int chunksCount = chunkPipelineCoordinator.processDocument(docRecord, document.text(), document);//根据策略选择器+责任链，对文档进行切分、向量化和持久化

            semanticCacheService.clearUserCache(userId);

            return IngestResponse.builder()
                    .success(true)
                    .message(String.format("文档 '%s' 导入成功，切分 %d 个文本块并完成向量化", originalFilename, chunksCount))
                    .chunksCount(chunksCount)
                    .build();

        } catch (IOException e) {
            log.error("文件处理IO异常: {}", e.getMessage(), e);
            return IngestResponse.builder()
                    .success(false)
                    .message("文件处理异常: " + e.getMessage())
                    .chunksCount(0)
                    .build();
        } catch (Exception e) {
            log.error("文档处理异常: {}", e.getMessage(), e);
            if (docRecord != null) {
                documentMapper.updateStatus(docRecord.getId(), DocumentStatus.FAILED);
            }
            return IngestResponse.builder()
                    .success(false)
                    .message("处理失败: " + e.getMessage())
                    .chunksCount(0)
                    .build();
        }
    }

    //将文件持久化到指定目录，且具备重名处理（在文件名后添加序号）
    private Path persistFile(MultipartFile file) throws IOException {
        LocalDate today = LocalDate.now();
        String datePath = today.format(DateTimeFormatter.ofPattern("yyyy/MM/dd"));
        Path targetDir = Paths.get(ragProperties.getStorePath(), datePath);
        Files.createDirectories(targetDir);

        String originalFilename = file.getOriginalFilename();
        Path targetFile = targetDir.resolve(originalFilename);

        for (int counter = 1; Files.exists(targetFile); counter++) {
            int dotIndex = originalFilename.lastIndexOf('.');
            if (dotIndex > 0) {
                targetFile = targetDir.resolve(
                        originalFilename.substring(0, dotIndex) + "_" + counter + originalFilename.substring(dotIndex)
                );
            } else {
                targetFile = targetDir.resolve(originalFilename + "_" + counter);
            }
        }

        Files.copy(file.getInputStream(), targetFile, StandardCopyOption.REPLACE_EXISTING);
        return targetFile;
    }

    //根据文件名提取文件类型
    private String extractFileType(String fileName) {
        if (fileName == null || !fileName.contains(".")) {
            return "unknown";
        }
        String extension = fileName.substring(fileName.lastIndexOf('.') + 1).toLowerCase();
        return switch (extension) {
            case "pdf" -> "pdf";
            case "doc", "docx" -> "docx";
            case "xls", "xlsx" -> "xlsx";
            case "txt", "text" -> "txt";
            case "md" -> "md";
            case "java" -> "java";
            case "csv" -> "csv";
            case "html", "htm" -> "html";
            default -> extension;
        };
    }

    //根据文件类型创建对应的文档解析器
    private DocumentParser createParser(String fileType) {
        return switch (fileType) {
            case "docx", "xlsx" -> new ApachePoiDocumentParser();
            default -> new TextDocumentParser();
        };
    }
}
