package org.linxing.linxing_agent.service.impl;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.linxing.linxing_agent.vo.DocumentPreviewVO;
import org.linxing.linxing_agent.vo.DocumentVO;
import org.linxing.linxing_agent.dto.PageResult;
import org.linxing.linxing_agent.entity.DocRecord;
import org.linxing.linxing_agent.mapper.DocumentMapper;
import org.linxing.linxing_agent.pipeline.ChunkPipelineCoordinator;
import org.linxing.linxing_agent.service.IDocumentService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class DocumentServiceImpl implements IDocumentService {

    private final DocumentMapper documentMapper;
    private final ChunkPipelineCoordinator chunkPipelineCoordinator;

    @Override
    public PageResult<DocumentVO> listDocuments(Integer userId, int page, int size) {
        if (page < 1) page = 1;
        if (size < 1) size = 10;
        int offset = (page - 1) * size;

        long total = documentMapper.countByUserId(userId);
        List<DocRecord> records = documentMapper.findByUserIdPaged(userId, offset, size);

        List<DocumentVO> voList = records.stream()
                .map(this::toDocumentVO)
                .toList();

        return PageResult.of(voList, total, page, size);
    }

    @Override
    public DocumentVO getDocumentDetail(Integer id, Integer userId) {
        DocRecord record = documentMapper.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("文档不存在"));
        if (!record.getUserId().equals(userId)) {
            throw new IllegalArgumentException("无权访问该文档");
        }
        return toDocumentVO(record);
    }

    @Override
    @Transactional
    public boolean deleteDocument(Integer id, Integer userId) {
        DocRecord record = documentMapper.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("文档不存在"));
        if (!record.getUserId().equals(userId)) {
            throw new IllegalArgumentException("无权删除该文档");
        }

        chunkPipelineCoordinator.deleteByDocumentId(userId, id);

        try {
            Path filePath = Paths.get(record.getFilePath());
            Files.deleteIfExists(filePath);
            log.info("已删除文件: {}", record.getFilePath());
        } catch (IOException e) {
            log.warn("删除物理文件失败: {}, 原因: {}", record.getFilePath(), e.getMessage());
        }

        return true;
    }

    @Override
    public DocumentPreviewVO previewDocument(Integer id, Integer userId) {
        DocRecord record = documentMapper.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("文档不存在"));
        if (!record.getUserId().equals(userId)) {
            throw new IllegalArgumentException("无权访问该文档");
        }

        String fileType = record.getFileType();
        Path filePath = Paths.get(record.getFilePath());

        if ("pdf".equalsIgnoreCase(fileType)) {
            return previewPdf(record, filePath);
        } else if ("txt".equalsIgnoreCase(fileType) || "md".equalsIgnoreCase(fileType) || "text".equalsIgnoreCase(fileType)) {
            return previewText(record, filePath);
        } else if ("doc".equalsIgnoreCase(fileType) || "docx".equalsIgnoreCase(fileType)
                || "xls".equalsIgnoreCase(fileType) || "xlsx".equalsIgnoreCase(fileType)) {
            return previewOffice(record, filePath);
        }

        return DocumentPreviewVO.builder()
                .id(record.getId())
                .fileName(record.getFileName())
                .fileType(fileType)
                .previewType("unsupported")
                .textContent("该文件类型暂不支持在线预览，请下载后查看。")
                .build();
    }

    @Override
    public String getFilePath(Integer id, Integer userId) {
        DocRecord record = documentMapper.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("文档不存在"));
        if (!record.getUserId().equals(userId)) {
            throw new IllegalArgumentException("无权访问该文档");
        }
        return record.getFilePath();
    }

    private DocumentPreviewVO previewPdf(DocRecord record, Path filePath) {
        try (PDDocument document = Loader.loadPDF(filePath.toFile())) {
            PDFTextStripper stripper = new PDFTextStripper();
            int pageCount = document.getNumberOfPages();
            List<String> pages = new ArrayList<>();

            for (int i = 1; i <= pageCount; i++) {
                stripper.setStartPage(i);
                stripper.setEndPage(i);
                String pageText = stripper.getText(document);
                pages.add(pageText);
            }

            return DocumentPreviewVO.builder()
                    .id(record.getId())
                    .fileName(record.getFileName())
                    .fileType(record.getFileType())
                    .previewType("pdf")
                    .pages(pages)
                    .build();
        } catch (IOException e) {
            log.error("PDF预览失败: {}", e.getMessage(), e);
            throw new RuntimeException("PDF文件预览失败: " + e.getMessage());
        }
    }

    private DocumentPreviewVO previewText(DocRecord record, Path filePath) {
        try {
            String content = Files.readString(filePath);
            return DocumentPreviewVO.builder()
                    .id(record.getId())
                    .fileName(record.getFileName())
                    .fileType(record.getFileType())
                    .previewType("text")
                    .textContent(content)
                    .build();
        } catch (IOException e) {
            log.error("文本文件预览失败: {}", e.getMessage(), e);
            throw new RuntimeException("文本文件预览失败: " + e.getMessage());
        }
    }

    private DocumentPreviewVO previewOffice(DocRecord record, Path filePath) {
        try {
            String content = Files.readString(filePath);
            return DocumentPreviewVO.builder()
                    .id(record.getId())
                    .fileName(record.getFileName())
                    .fileType(record.getFileType())
                    .previewType("text")
                    .textContent(content)
                    .build();
        } catch (IOException e) {
            log.error("Office文件预览失败: {}", e.getMessage(), e);
            return DocumentPreviewVO.builder()
                    .id(record.getId())
                    .fileName(record.getFileName())
                    .fileType(record.getFileType())
                    .previewType("unsupported")
                    .textContent("该Office文件暂不支持在线预览，请下载后查看。")
                    .build();
        }
    }

    private DocumentVO toDocumentVO(DocRecord record) {
        return DocumentVO.builder()
                .id(record.getId())
                .fileName(record.getFileName())
                .fileSize(record.getFileSize())
                .fileType(record.getFileType())
                .status(record.getStatus())
                .createdAt(record.getCreatedAt())
                .build();
    }
}
