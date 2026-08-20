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
import org.linxing.linxing_agent.rag.enhancement.SemanticEnhancementService;
import org.linxing.linxing_agent.rag.pipeline.ChunkIngestCoordinator;
import org.linxing.linxing_agent.rag.parse.DocumentAnalysisFacade;
import org.linxing.linxing_agent.rag.service.IIngestService;
import org.linxing.linxing_agent.rag.storage.FileStoreLayout;
import org.linxing.linxing_agent.rag.utils.FileTypeValidator;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.OffsetDateTime;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

@Slf4j
@Service
@RequiredArgsConstructor
public class IngestServiceImpl implements IIngestService {

    private final ChunkIngestCoordinator chunkIngestCoordinator;
    private final DocumentMapper documentMapper;
    private final RagProperties ragProperties;
    private final DocumentAnalysisFacade documentAnalysisFacade;
    private final SemanticEnhancementService semanticEnhancementService;

    @Override
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
            //覆盖场景：先删除旧文档对应的 chunk / embedding / 元数据，并删除旧文档整个物理目录（含 source 与 images）
            if (existing != null) {
                log.info("覆盖旧文档 documentId: {}", existing.getId());
                chunkIngestCoordinator.deleteByDocumentId(userId, existing.getId());
                deleteDocDir(userId, existing.getId());
            }

            // 先插入文档记录拿到自增 docId，再据其决定 source 落盘目录（无重名补丁，每 doc 独立目录）
            String fileType = FileTypeValidator.normalizedType(originalFilename);//根据文件名称，获取文件类型为后续chunk决策做参考
            String sanitized = sanitizeFilename(originalFilename);

            // 落盘目录由 userId+documentId 决定、与文件名无关，故可在 insert 前先计算真实目录 + 文件名；
            // 但目录包含 documentId，需等 docId 生成。因此：file_path 先插"占位 docId=0 的可推断路径"保证 INSERT 非空，
            // 拿到 docId 后由 persistFile 落盘，再以真实绝对路径 updateFilePath 回填。
            // 这样即使回填前异常，记录也有可读路径可追踪，且不依赖 DB 空列（兼容仍为 NOT NULL 的旧实表）。
            Path targetDir = FileStoreLayout.docDir(ragProperties.getStorePath(), userId, 0);
            String diskName = (sanitized != null && !sanitized.isBlank())
                    ? sanitized : ("document." + (fileType == null || fileType.isBlank() ? "bin" : fileType));
            String initialPath = targetDir.resolve("source").resolve(diskName).toString();

            docRecord = DocRecord.builder()
                    .userId(userId)
                    .fileName(originalFilename)
                    .filePath(initialPath)//物理路径初始值（占位 docId=0），真实路径在落盘后回填
                    .fileSize(file.getSize())
                    .fileType(fileType)
                    .status(DocumentStatus.PROCESSING)
                    .createdAt(OffsetDateTime.now())
                    .build();
            documentMapper.insert(docRecord);//插入原始文件元数据记录（自增拿到 docId）
            log.info("文档记录已入库，documentId: {}", docRecord.getId());

            Path storedFile = persistFile(file, userId, docRecord.getId(), sanitized, fileType);
            docRecord.setFilePath(storedFile.toString());
            documentMapper.updateFilePath(docRecord.getId(), storedFile.toString());
            log.debug("文件已持久化到: {}", storedFile);

            // 调用 Python 文档分析服务，获取有序、原子化的 Node 序列（同时java版本作为兜底）
            List<DocumentNode> nodes = documentAnalysisFacade.analyze(
                    storedFile, docRecord.getId(), userId);
            log.info("文档 {} 解析完成，获得 {} 个 Node", docRecord.getId(), nodes.size());

            // Node 语义增强移至 ingestFile（无 DB 事务）：VLM/LLM 与 embedding 等外部调用
            // 不应占用数据库连接/持有长事务；增强失败由实现方 fallback 到默认 semanticText，不影响入库
            semanticEnhancementService.enhance(nodes, docRecord.getFileType());

            // 基于 Node 序列进行切分、向量化和持久化（此时进入 DB 事务，仅做数据库操作）
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
                    .message("文件处理异常")
                    .chunksCount(0)
                    .build();
        } catch (Exception e) {
            log.error("文档处理异常: {}", e.getMessage(), e);
            if (docRecord != null) {
                // 无 @Transactional 包裹，这两条 SQL 走独立事务/自动提交，不会因已中止事务触发 25P02
                documentMapper.updateStatus(docRecord.getId(), DocumentStatus.FAILED);
                // 清理半成品物理目录（避免 source/images 残留孤儿，DB 记录保留便于前端查看失败态）
                deleteDocDir(userId, docRecord.getId());
            }
            return IngestResponse.builder()
                    .success(false)
                    .code(0)
                    .message(safeFailureMessage(e))
                    .chunksCount(0)
                    .build();
        }
    }

    /**
     * 失败消息脱敏：不向前端暴露原始异常文本（可能含 SQL 结构 / PG 25P02 等信息）。
     * 数据库访问异常（DataAccessException 或其 cause 链含 java.sql.SQLException）统一降级为安全消息；
     * 其余异常保留原始 message（业务类错误，不含内部结构）。
     */
    private String safeFailureMessage(Exception e) {
        if (e instanceof DataAccessException) {
            return "文档处理失败，请稍后重试";
        }
        // 逐层剥 cause，寻找数据库/SQL 层异常（MyBatis 常向上包装成 RuntimeException）
        Throwable cause = e.getCause();
        while (cause != null) {
            if (cause instanceof DataAccessException || cause instanceof java.sql.SQLException) {
                return "文档处理失败，请稍后重试";
            }
            cause = cause.getCause();
        }
        String msg = e.getMessage();
        return (msg == null || msg.isBlank()) ? "文档处理失败" : "处理失败: " + msg;
    }

    /**
     * 将文件写入多租户目录 {storePath}/tenants/{userId}/documents/{docId}/source/{sanitized}。
     * 每份文档独占目录，故磁盘名天然无冲突，无需重名补丁。
     */
    private Path persistFile(MultipartFile file, Integer userId, Integer documentId,
                             String sanitized, String fileType) throws IOException {
        Path targetDir = FileStoreLayout.sourceDir(ragProperties.getStorePath(), userId, documentId);
        Files.createDirectories(targetDir);

        String safeName = sanitized;
        if (safeName == null || safeName.isBlank()) {
            // 兜底：原始名被彻底 sanitize 后为空 / 无法派生扩展名
            String ext = (fileType == null || fileType.isBlank()) ? "bin" : fileType;
            safeName = "document_" + documentId + "." + ext;
        }
        Path targetFile = targetDir.resolve(safeName);
        try (InputStream in = file.getInputStream()) {
            Files.copy(in, targetFile, StandardCopyOption.REPLACE_EXISTING);
        }
        return targetFile;
    }

    /**
     * 清洗用户原始文件名，得到安全的磁盘文件名。
     * 去掉路径段（防穿越），剔除 Windows 非法字符与控制符，去掉首尾点/空格，限长保扩展名。
     * 结果为空或仅剩扩展名时返回 null（调用方走兜底名）。
     */
    String sanitizeFilename(String original) {
        if (original == null || original.isBlank()) {
            return null;
        }
        // 只取最后一段（反斜杠/正斜杠均视为路径分隔，杜绝 .. 穿越）
        String base = original;
        int bs = base.lastIndexOf('\\'), fs = base.lastIndexOf('/');
        int cut = Math.max(bs, fs);
        if (cut >= 0) {
            base = base.substring(cut + 1);
        }
        // 移除 Windows 非法字符与 ASCII 控制符
        String cleaned = base.replaceAll("[<>:\"/\\\\|?*]", "_").replaceAll("[\\x00-\\x1f\\x7f]", "");
        // 去掉首尾空白与点（Windows 结尾点非法），避免 "./" 混淆
        cleaned = cleaned.trim();
        while (cleaned.endsWith(".") || cleaned.endsWith(" ")) {
            cleaned = cleaned.substring(0, cleaned.length() - 1).trim();
        }

        int dotIndex = cleaned.lastIndexOf('.');
        String stem = dotIndex > 0 ? cleaned.substring(0, dotIndex) : cleaned;
        String ext = dotIndex > 0 ? cleaned.substring(dotIndex) : "";

        // 限长：保留扩展名，主题名截到 100 字符
        final int MAX_STEM = 100;
        if (stem.length() > MAX_STEM) {
            stem = stem.substring(0, MAX_STEM);
        }
        // 空 stem + 有扩展名（如 ".png"）→ 返回 null 走兜底名
        if (stem.isBlank()) {
            return null;
        }
        return stem + ext;
    }

    /** 递归删除某用户在 tenants 布局下的整份文档目录（含 source 与 images）。失败仅记录日志不阻断主流程。 */
    void deleteDocDir(Integer userId, Integer documentId) {
        Path dir = FileStoreLayout.docDir(ragProperties.getStorePath(), userId, documentId);
        try {
            if (Files.exists(dir)) {
                try (Stream<Path> walk = Files.walk(dir)) {
                    walk.sorted(Comparator.reverseOrder())
                            .forEach(p -> {
                                try {
                                    Files.deleteIfExists(p);
                                } catch (IOException e) {
                                    log.warn("删除物理文件/目录失败: {}, 原因: {}", p, e.getMessage());
                                }
                            });
                }
                log.info("已清理文档目录: {}", dir);
            }
        } catch (IOException e) {
            log.warn("清理文档目录 {} 失败: {}", dir, e.getMessage());
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
