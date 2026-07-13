package org.linxing.linxing_agent.agent.controller;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.linxing.linxing_agent.agent.dto.StudyPlanExportResult;
import org.linxing.linxing_agent.agent.dto.StudyPlanProgressUpdateRequest;
import org.linxing.linxing_agent.agent.service.IStudyPlanService;
import org.linxing.linxing_agent.agent.vo.StudyPlanDetailVO;
import org.linxing.linxing_agent.agent.vo.StudyPlanVO;
import org.linxing.linxing_agent.common.result.PageResult;
import org.linxing.linxing_agent.common.result.Result;
import org.linxing.linxing_agent.common.userInfoMaintainer.BaseContext;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

/**
 * 学习计划
 */
@RestController
@RequestMapping("/study-plan")
@RequiredArgsConstructor
@Slf4j
public class StudyPlanController {

    private final IStudyPlanService studyPlanService;

    @GetMapping
    public Result<PageResult<StudyPlanVO>> listPlans(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "10") int size,
            @RequestParam(required = false) String status) {
        Integer userId = BaseContext.requireCurrentUserId();
        return Result.success(studyPlanService.listPlans(userId, status, page, size));
    }

    @GetMapping("/{planId}")
    public Result<StudyPlanDetailVO> getPlanDetail(@PathVariable Integer planId) {
        Integer userId = BaseContext.requireCurrentUserId();
        return Result.success(studyPlanService.getPlanDetail(userId, planId));
    }

    @PutMapping("/{planId}/phase/{phaseId}/progress")
    public Result<Void> updatePhaseStatus(
            @PathVariable Integer planId,
            @PathVariable Integer phaseId,
            @RequestBody StudyPlanProgressUpdateRequest body) {
        Integer userId = BaseContext.requireCurrentUserId();
        studyPlanService.updatePhaseStatus(userId, planId, phaseId, body);
        return Result.success(null);
    }

    /**
     * 导出学习计划（支持 Markdown / HTML 格式，返回文件下载）
     * @param format 导出格式：md（默认）/ html
     */
    @GetMapping("/{planId}/export")
    public ResponseEntity<byte[]> exportPlan(
            @PathVariable Integer planId,
            @RequestParam(defaultValue = "md") String format) {
        Integer userId = BaseContext.requireCurrentUserId();
        StudyPlanExportResult r = studyPlanService.export(userId, planId, format);

        // 文件名格式 {title}_学习计划.md/html，中文需 URL 编码
        String fileName = URLEncoder.encode(r.getTitle() + "_学习计划." + r.getFileExtension(), StandardCharsets.UTF_8)
                .replace("+", "%20");
        byte[] bytes = r.getContent().getBytes(StandardCharsets.UTF_8);

        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename*=UTF-8''" + fileName)
                .header(HttpHeaders.CONTENT_TYPE, r.getContentType())
                .header(HttpHeaders.CONTENT_LENGTH, String.valueOf(bytes.length))
                .body(bytes);
    }
}
