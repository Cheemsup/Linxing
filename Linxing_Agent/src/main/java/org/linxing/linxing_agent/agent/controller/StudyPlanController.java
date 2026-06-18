package org.linxing.linxing_agent.agent.controller;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.linxing.linxing_agent.agent.dto.StudyPlanProgressUpdateRequest;
import org.linxing.linxing_agent.agent.service.IStudyPlanService;
import org.linxing.linxing_agent.agent.vo.StudyPlanDetailVO;
import org.linxing.linxing_agent.agent.vo.StudyPlanVO;
import org.linxing.linxing_agent.common.result.PageResult;
import org.linxing.linxing_agent.common.result.Result;
import org.linxing.linxing_agent.common.userInfoMaintainer.BaseContext;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

/**
 * 学习计划 Controller
 * 前端代理剥离 /api 前缀：前端调 /api/study-plan/xxx，后端路径 /study-plan/xxx
 */
@RestController
@RequestMapping("/study-plan")
@RequiredArgsConstructor
@Slf4j
public class StudyPlanController {

    private final IStudyPlanService studyPlanService;

    /**
     * 1. 列表查询（分页，支持 status 筛选）
     */
    @GetMapping
    public Result<PageResult<StudyPlanVO>> listPlans(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "10") int size,
            @RequestParam(required = false) String status) {
        Integer userId = getCurrentUserId();
        return Result.success(studyPlanService.listPlans(userId, status, page, size));
    }

    /**
     * 2. 详情查询（含阶段列表与进度）
     */
    @GetMapping("/{planId}")
    public Result<StudyPlanDetailVO> getPlanDetail(@PathVariable Integer planId) {
        Integer userId = getCurrentUserId();
        return Result.success(studyPlanService.getPlanDetail(userId, planId));
    }

    /**
     * 3. 更新阶段进度状态
     */
    @PutMapping("/{planId}/phase/{phaseId}/progress")
    public Result<Void> updatePhaseStatus(
            @PathVariable Integer planId,
            @PathVariable Integer phaseId,
            @RequestBody StudyPlanProgressUpdateRequest body) {
        Integer userId = getCurrentUserId();
        studyPlanService.updatePhaseStatus(userId, planId, phaseId, body);
        return Result.success(null);
    }

    /**
     * 4. 导出学习计划（支持 Markdown / HTML 格式，返回文件下载）
     *
     * @param format 导出格式：md（默认）/ html
     */
    @GetMapping("/{planId}/export")
    public ResponseEntity<byte[]> exportPlan(
            @PathVariable Integer planId,
            @RequestParam(defaultValue = "md") String format) {
        Integer userId = getCurrentUserId();

        String content;
        String contentType;
        String fileExtension;
        if ("html".equalsIgnoreCase(format)) {
            content = studyPlanService.exportAsHtml(userId, planId);
            contentType = "text/html; charset=UTF-8";
            fileExtension = "html";
        } else {
            // 默认 Markdown
            content = studyPlanService.exportAsMarkdown(userId, planId);
            contentType = "text/markdown; charset=UTF-8";
            fileExtension = "md";
        }

        // 文件名格式 {title}_学习计划.md/html，中文需 URL 编码
        // 从内容中提取标题（Markdown 第一行 # xxx，HTML <title>xxx</title>）
        String title = extractTitle(content, format);
        String fileName = URLEncoder.encode(title + "_学习计划." + fileExtension, StandardCharsets.UTF_8)
                .replace("+", "%20");

        byte[] bytes = content.getBytes(StandardCharsets.UTF_8);

        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename*=UTF-8''" + fileName)
                .header(HttpHeaders.CONTENT_TYPE, contentType)
                .header(HttpHeaders.CONTENT_LENGTH, String.valueOf(bytes.length))
                .body(bytes);
    }

    /**
     * 从导出内容中提取标题用于文件命名
     */
    private String extractTitle(String content, String format) {
        try {
            if ("html".equalsIgnoreCase(format)) {
                // HTML: <title>xxx</title>
                int start = content.indexOf("<title>");
                int end = content.indexOf("</title>");
                if (start >= 0 && end > start) {
                    return content.substring(start + 7, end).replace(" - 学习计划", "").trim();
                }
            } else {
                // Markdown: # xxx
                int start = content.indexOf("# ");
                int end = content.indexOf("\n", start);
                if (start >= 0 && end > start) {
                    return content.substring(start + 2, end).trim();
                }
            }
        } catch (Exception ignored) {
        }
        return "学习计划";
    }

    private static Integer getCurrentUserId() {
        Long userId = BaseContext.getCurrentId();
        if (userId == null) {
            throw new IllegalStateException("用户未登录");
        }
        return userId.intValue();
    }
}
