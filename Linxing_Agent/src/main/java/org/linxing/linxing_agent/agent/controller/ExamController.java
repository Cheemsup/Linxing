package org.linxing.linxing_agent.agent.controller;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.linxing.linxing_agent.agent.dto.ExamSubmitRequest;
import org.linxing.linxing_agent.agent.service.impl.ExamService;
import org.linxing.linxing_agent.agent.vo.ExamDetailVO;
import org.linxing.linxing_agent.agent.vo.ExamSubmitVO;
import org.linxing.linxing_agent.agent.vo.ExamVO;
import org.linxing.linxing_agent.common.result.PageResult;
import org.linxing.linxing_agent.common.result.Result;
import org.linxing.linxing_agent.common.userInfoMaintainer.BaseContext;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/exam")
@RequiredArgsConstructor
@Slf4j
public class ExamController {

    private final ExamService examService;

    @GetMapping
    public Result<PageResult<ExamVO>> listExams(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "10") int size,
            @RequestParam(required = false) String status) {
        Integer userId = getCurrentUserId();
        return Result.success(examService.listExams(userId, status, page, size));
    }

    @GetMapping("/{examId}")
    public Result<ExamDetailVO> getExam(@PathVariable Integer examId) {
        Integer userId = getCurrentUserId();
        return Result.success(examService.getExam(userId, examId));
    }

    @PostMapping("/{examId}/submit")
    public Result<ExamSubmitVO> submitAnswer(
            @PathVariable Integer examId,
            @RequestBody ExamSubmitRequest body) {
        Integer userId = getCurrentUserId();
        return Result.success(examService.saveAttempt(userId, examId, body));
    }

    @PostMapping("/{examId}/draft")
    public Result<Void> saveDraft(
            @PathVariable Integer examId,
            @RequestBody ExamSubmitRequest body) {
        Integer userId = getCurrentUserId();
        examService.saveDraft(userId, examId, body);
        return Result.success(null);
    }

    @GetMapping("/{examId}/draft")
    public Result<Map<String, Object>> getDraft(@PathVariable Integer examId) {
        Integer userId = getCurrentUserId();
        return Result.success(examService.getDraft(userId, examId));
    }

    private static Integer getCurrentUserId() {
        Long userId = BaseContext.getCurrentId();
        if (userId == null) {
            throw new IllegalStateException("用户未登录");
        }
        return userId.intValue();
    }
}
