package org.linxing.linxing_agent.rag.controller;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.linxing.linxing_agent.common.userInfoMaintainer.BaseContext;
import org.linxing.linxing_agent.rag.vo.ChunkTreeVO;
import org.linxing.linxing_agent.rag.vo.DocumentPreviewVO;
import org.linxing.linxing_agent.rag.vo.DocumentVO;
import org.linxing.linxing_agent.common.result.PageResult;
import org.linxing.linxing_agent.common.result.Result;
import org.linxing.linxing_agent.rag.service.IDocumentService;
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
import java.util.List;

@Slf4j
@RestController
@RequestMapping("/rag")
@RequiredArgsConstructor
public class DocumentController {

    private final IDocumentService documentService;

    @GetMapping("/documents")
    public Result<PageResult<DocumentVO>> listDocuments(
            @RequestParam(value = "page", defaultValue = "1") int page,
            @RequestParam(value = "size", defaultValue = "10") int size) {
        Integer userId = getCurrentUserId();
        PageResult<DocumentVO> result = documentService.listDocuments(userId, page, size);
        return Result.success(result);
    }

    @GetMapping("/documents/{id}")
    public Result<DocumentVO> getDocumentDetail(@PathVariable Integer id) {
        Integer userId = getCurrentUserId();
        DocumentVO vo = documentService.getDocumentDetail(id, userId);
        return Result.success(vo);
    }

    @DeleteMapping("/documents/{id}")
    public Result<Void> deleteDocument(@PathVariable Integer id) {
        Integer userId = getCurrentUserId();
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

    @GetMapping("/documents/{id}/preview")
    public Result<DocumentPreviewVO> previewDocument(@PathVariable Integer id) {
        Integer userId = getCurrentUserId();
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

    @GetMapping("/documents/{id}/chunk-tree")
    public Result<List<ChunkTreeVO>> getChunkTree(@PathVariable Integer id) {
        Integer userId = getCurrentUserId();
        try {
            List<ChunkTreeVO> tree = documentService.getChunkTree(id, userId);
            return Result.success(tree);
        } catch (IllegalArgumentException e) {
            log.warn("获取chunk树失败: {}", e.getMessage());
            return Result.error(e.getMessage());
        } catch (Exception e) {
            log.error("获取chunk树异常: {}", e.getMessage(), e);
            return Result.error("获取chunk树失败: " + e.getMessage());
        }
    }

    @GetMapping("/documents/{id}/download")
    public ResponseEntity<Resource> downloadDocument(@PathVariable Integer id) {
        Integer userId = getCurrentUserId();
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

    private static Integer getCurrentUserId() {
        Long userId = BaseContext.getCurrentId();
        if (userId == null) {
            throw new IllegalStateException("用户未登录");
        }
        return userId.intValue();
    }
}
