package org.linxing.linxing_agent.rag.controller;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.linxing.linxing_agent.common.userInfoMaintainer.BaseContext;
import org.linxing.linxing_agent.rag.dto.DuplicateCheckResponse;
import org.linxing.linxing_agent.rag.dto.IngestResponse;
import org.linxing.linxing_agent.common.result.Result;
import org.linxing.linxing_agent.rag.service.IIngestService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@Slf4j
@RestController
@RequestMapping("/rag")
@RequiredArgsConstructor
public class IngestController {

    private final IIngestService ingestService;

    @PostMapping("/ingest/file")
    public Result<IngestResponse> ingestFile(
            @RequestParam("file") MultipartFile file,
            @RequestParam(value = "overwrite", required = false, defaultValue = "false") Boolean overwrite) {
        Integer userId = BaseContext.requireCurrentUserId();
        IngestResponse response = ingestService.ingestFile(file, userId, overwrite);
        //code=2 表示存在重名文件待用户确认覆盖，此时也走 Result.success，
        //使完整的 IngestResponse（含 code 与 duplicateDocumentId）透传到前端供其识别并弹出确认框
        int bizCode = response.getCode();
        if (bizCode == 0) {
            return Result.error(response.getMessage());
        }
        return Result.success(response);
    }

    //上传前同名文件预检：前端选择文件后立即判重，存在同名则弹出覆盖确认框
    @GetMapping("/ingest/check")
    public Result<DuplicateCheckResponse> checkDuplicate(@RequestParam("fileName") String fileName) {
        Integer userId = BaseContext.requireCurrentUserId();
        return Result.success(ingestService.checkDuplicate(userId, fileName));
    }
}
