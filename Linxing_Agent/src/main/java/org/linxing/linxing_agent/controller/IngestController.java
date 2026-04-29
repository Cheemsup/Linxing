package org.linxing.linxing_agent.controller;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.linxing.linxing_agent.constant.CommonConstants;
import org.linxing.linxing_agent.dto.IngestResponse;
import org.linxing.linxing_agent.result.Result;
import org.linxing.linxing_agent.service.IIngestService;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.util.Set;

@Slf4j
@RestController
@RequestMapping("/ingest")
@RequiredArgsConstructor
public class IngestController {

    private static final Set<String> ALLOWED_EXTENSIONS = Set.of(
            "txt", "md", "text", "pdf", "doc", "docx", "xls", "xlsx"
    );

    private final IIngestService ingestService;

    @PostMapping("/file")
    public Result<IngestResponse> ingestFile(
            @RequestParam("file") MultipartFile file,
            @RequestParam(value = "userId", required = false, defaultValue = "1") Integer userId) {
        if (userId == null) {
            userId = CommonConstants.DEFAULT_USER_ID;
        }

        String originalFilename = file.getOriginalFilename();
        if (originalFilename != null && !isAllowedFileType(originalFilename)) {
            return Result.error("不支持的文件格式，允许的格式: " + String.join(", ", ALLOWED_EXTENSIONS));
        }

        try {
            IngestResponse response = ingestService.ingestFile(file, userId);
            if (response.isSuccess()) {
                return Result.success(response);
            } else {
                return Result.error(response.getMessage());
            }
        } catch (Exception e) {
            log.error("文件处理异常: {}", e.getMessage(), e);
            return Result.error("文件处理失败: " + e.getMessage());
        }
    }

    private boolean isAllowedFileType(String fileName) {
        int dotIndex = fileName.lastIndexOf('.');
        if (dotIndex < 0 || dotIndex == fileName.length() - 1) {
            return false;
        }
        String extension = fileName.substring(dotIndex + 1).toLowerCase();
        return ALLOWED_EXTENSIONS.contains(extension);
    }
}
