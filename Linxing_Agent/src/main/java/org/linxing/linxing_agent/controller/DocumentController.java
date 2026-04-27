package org.linxing.linxing_agent.controller;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.linxing.linxing_agent.constant.CommonConstants;
import org.linxing.linxing_agent.vo.DocumentPreviewVO;
import org.linxing.linxing_agent.vo.DocumentVO;
import org.linxing.linxing_agent.dto.PageResult;
import org.linxing.linxing_agent.result.Result;
import org.linxing.linxing_agent.service.IDocumentService;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.Paths;

@Slf4j
@RestController
@RequestMapping("/api/documents")
@RequiredArgsConstructor
public class DocumentController {

    private final IDocumentService documentService;

    @GetMapping
    public Result<PageResult<DocumentVO>> listDocuments(
            @RequestParam(value = "page", defaultValue = "1") int page,
            @RequestParam(value = "size", defaultValue = "10") int size,
            @RequestParam(value = "userId", required = false, defaultValue = "1") Integer userId) {
        if (userId == null) {
            userId = CommonConstants.DEFAULT_USER_ID;
        }
        PageResult<DocumentVO> result = documentService.listDocuments(userId, page, size);
        return Result.success(result);
    }

    @GetMapping("/{id}")
    public Result<DocumentVO> getDocumentDetail(
            @PathVariable Integer id,
            @RequestParam(value = "userId", required = false, defaultValue = "1") Integer userId) {
        if (userId == null) {
            userId = CommonConstants.DEFAULT_USER_ID;
        }
        DocumentVO vo = documentService.getDocumentDetail(id, userId);
        return Result.success(vo);
    }

    @DeleteMapping("/{id}")
    public Result<Void> deleteDocument(
            @PathVariable Integer id,
            @RequestParam(value = "userId", required = false, defaultValue = "1") Integer userId) {
        if (userId == null) {
            userId = CommonConstants.DEFAULT_USER_ID;
        }
        try {
            documentService.deleteDocument(id, userId);
            return Result.success();
        } catch (IllegalArgumentException e) {
            log.warn("删除文档失败: {}", e.getMessage());
            return Result.error(e.getMessage());
        } catch (Exception e) {
            log.error("删除文档异常: {}", e.getMessage(), e);
            return Result.error("删除文档失败: " + e.getMessage());
        }
    }

    @GetMapping("/{id}/preview")
    public Result<DocumentPreviewVO> previewDocument(
            @PathVariable Integer id,
            @RequestParam(value = "userId", required = false, defaultValue = "1") Integer userId) {
        if (userId == null) {
            userId = CommonConstants.DEFAULT_USER_ID;
        }
        try {
            DocumentPreviewVO vo = documentService.previewDocument(id, userId);
            return Result.success(vo);
        } catch (IllegalArgumentException e) {
            log.warn("预览文档失败: {}", e.getMessage());
            return Result.error(e.getMessage());
        } catch (Exception e) {
            log.error("预览文档异常: {}", e.getMessage(), e);
            return Result.error("文档预览失败: " + e.getMessage());
        }
    }

    @GetMapping("/{id}/download")
    public ResponseEntity<Resource> downloadDocument(
            @PathVariable Integer id,
            @RequestParam(value = "userId", required = false, defaultValue = "1") Integer userId) {
        if (userId == null) {
            userId = CommonConstants.DEFAULT_USER_ID;
        }
        String filePath = documentService.getFilePath(id, userId);
        Path path = Paths.get(filePath);
        FileSystemResource resource = new FileSystemResource(path);

        if (!resource.exists()) {
            return ResponseEntity.notFound().build();
        }

        String encodedFileName = URLEncoder.encode(path.getFileName().toString(), StandardCharsets.UTF_8)
                .replace("+", "%20");

        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename*=UTF-8''" + encodedFileName)
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .body(resource);
    }
}
