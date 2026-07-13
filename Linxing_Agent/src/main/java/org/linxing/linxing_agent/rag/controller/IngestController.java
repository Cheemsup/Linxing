package org.linxing.linxing_agent.rag.controller;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.linxing.linxing_agent.common.userInfoMaintainer.BaseContext;
import org.linxing.linxing_agent.rag.dto.IngestResponse;
import org.linxing.linxing_agent.common.result.Result;
import org.linxing.linxing_agent.rag.service.IIngestService;
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
    public Result<IngestResponse> ingestFile(@RequestParam("file") MultipartFile file) {
        Integer userId = BaseContext.requireCurrentUserId();
        IngestResponse response = ingestService.ingestFile(file, userId);
        return response.isSuccess() ? Result.success(response) : Result.error(response.getMessage());
    }
}
