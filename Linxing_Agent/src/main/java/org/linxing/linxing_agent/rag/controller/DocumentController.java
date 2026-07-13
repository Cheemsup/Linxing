package org.linxing.linxing_agent.rag.controller;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.linxing.linxing_agent.common.userInfoMaintainer.BaseContext;
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
        documentService.deleteDocument(id, userId);
        return Result.success();
    }

    @GetMapping("/documents/{id}/preview")
    public Result<DocumentPreviewVO> previewDocument(@PathVariable Integer id) {
        Integer userId = getCurrentUserId();
        DocumentPreviewVO vo = documentService.previewDocument(id, userId);
        return Result.success(vo);
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
        return BaseContext.requireCurrentUserId();
    }
}
