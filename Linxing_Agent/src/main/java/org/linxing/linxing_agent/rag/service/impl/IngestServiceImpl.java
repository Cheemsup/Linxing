package org.linxing.linxing_agent.rag.service.impl;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.linxing.linxing_agent.rag.constant.DocumentStatus;
import org.linxing.linxing_agent.rag.config.RagProperties;
import org.linxing.linxing_agent.rag.dto.DuplicateCheckResponse;
import org.linxing.linxing_agent.rag.dto.IngestResponse;
import org.linxing.linxing_agent.rag.entity.DocRecord;
import org.linxing.linxing_agent.rag.mapper.DocumentMapper;
import org.linxing.linxing_agent.rag.node.DocumentNode;
import org.linxing.linxing_agent.rag.pipeline.ChunkIngestCoordinator;
import org.linxing.linxing_agent.rag.parse.DocumentAnalysisFacade;
import org.linxing.linxing_agent.rag.service.IIngestService;
import org.linxing.linxing_agent.rag.utils.FileTypeValidator;
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
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class IngestServiceImpl implements IIngestService {

    private final ChunkIngestCoordinator chunkIngestCoordinator;
    private final DocumentMapper documentMapper;
    private final RagProperties ragProperties;
    private final DocumentAnalysisFacade documentAnalysisFacade;

    @Override
    @Transactional
    public IngestResponse ingestFile(MultipartFile file, Integer userId, Boolean overwrite) {
        if (file.isEmpty()) {
            return IngestResponse.builder()
                    .success(false)
                    .code(0)
                    .message("上传文件为空")
                    .chunksCount(0)
                    .build();
        }

        String originalFilename = file.getOriginalFilename();
        if (originalFilename != null && !FileTypeValidator.isAllowed(originalFilename)) {
            throw new IllegalArgumentException(
                    "不支持的文件格式，允许的格式: " + String.join(", ", FileTypeValidator.allowedExtensions()));
        }
        log.info("[用户{}]，文件上传: {}, 大小: {} bytes, overwrite: {}", userId, originalFilename, file.getSize(), overwrite);

        //重名判重：同 user_id 下已存在同名文件时，未显式确认覆盖则返回待确认状态交由前端提示
        DocRecord existing = documentMapper.findByUserIdAndFileName(userId, originalFilename).orElse(null);
        if (existing != null && !Boolean.TRUE.equals(overwrite)) {
            return IngestResponse.builder()
                    .success(false)
                    .code(2)
                    .message("已存在同名文件「" + originalFilename + "」，确认将覆盖旧文件并重新切分、向量化")
                    .duplicateDocumentId(existing.getId())
                    .chunksCount(0)
                    .build();
        }

        DocRecord docRecord = null;
        try {
            //覆盖场景：先删除旧文档对应的 chunk / embedding / 元数据，并删除旧磁盘物理文件，再重新入库
            if (existing != null) {
                log.info("覆盖旧文档 documentId: {}", existing.getId());
                chunkIngestCoordinator.deleteByDocumentId(userId, existing.getId());
                deleteOldPhysicalFile(existing.getFilePath());
            }

            Path storedFile = persistFile(file);
            log.debug("文件已持久化到: {}", storedFile);

            String fileType = FileTypeValidator.normalizedType(originalFilename);//根据文件名称，获取文件类型为后续chunk决策做参考

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

            // 调用 Python 文档分析服务，获取有序、原子化的 Node 序列（同时java版本作为兜底）
            List<DocumentNode> nodes = documentAnalysisFacade.analyze(
                    storedFile, docRecord.getId(), userId);
            log.info("文档 {} 解析完成，获得 {} 个 Node", docRecord.getId(), nodes.size());

            // 基于 Node 序列进行切分、向量化和持久化
            int chunksCount = chunkIngestCoordinator.processDocumentFromNodes(docRecord, nodes);

            String successMsg = existing != null
                    ? String.format("文档 '%s' 已覆盖更新，重新切分 %d 个文本块并完成向量化", originalFilename, chunksCount)
                    : String.format("文档 '%s' 导入成功，切分 %d 个文本块并完成向量化", originalFilename, chunksCount);
            return IngestResponse.builder()
                    .success(true)
                    .code(1)
                    .message(successMsg)
                    .chunksCount(chunksCount)
                    .build();

        } catch (IOException e) {
            log.error("文件处理IO异常: {}", e.getMessage(), e);
            return IngestResponse.builder()
                    .success(false)
                    .code(0)
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
                    .code(0)
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

    //删除旧文档对应的磁盘物理文件；文件不存在或删除失败仅记录日志，不影响覆盖入库主流程
    private void deleteOldPhysicalFile(String filePath) {
        if (filePath == null || filePath.isBlank()) {
            return;
        }
        try {
            Path old = Paths.get(filePath);
            boolean deleted = Files.deleteIfExists(old);
            log.info("旧物理文件 {} 删除{}", filePath, deleted ? "成功" : "（文件已不存在）");
        } catch (IOException e) {
            log.warn("删除旧物理文件 {} 失败: {}", filePath, e.getMessage());
        }
    }

    //上传前同名文件预检：查询当前 user_id 下是否已存在同名文件，返回结果供前端弹出覆盖确认框
    @Override
    public DuplicateCheckResponse checkDuplicate(Integer userId, String fileName) {
        DocRecord existing = documentMapper.findByUserIdAndFileName(userId, fileName).orElse(null);
        if (existing == null) {
            return DuplicateCheckResponse.builder()
                    .duplicate(false)
                    .documentId(null)
                    .fileName(fileName)
                    .createdAt(null)
                    .build();
        }
        return DuplicateCheckResponse.builder()
                .duplicate(true)
                .documentId(existing.getId())
                .fileName(existing.getFileName())
                .createdAt(existing.getCreatedAt())
                .build();
    }
}
