package org.linxing.linxing_agent.agent.service.impl;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.linxing.linxing_agent.agent.dto.QuestionError;
import org.linxing.linxing_agent.agent.dto.StudyPlanProgressUpdateRequest;
import org.linxing.linxing_agent.agent.entity.StudyPlan;
import org.linxing.linxing_agent.agent.entity.StudyPlanPhase;
import org.linxing.linxing_agent.agent.entity.StudyPlanProgress;
import org.linxing.linxing_agent.agent.exception.StudyPlanNotFoundException;
import org.linxing.linxing_agent.agent.exception.StudyPlanParseException;
import org.linxing.linxing_agent.agent.exception.StudyPlanValidationException;
import org.linxing.linxing_agent.agent.mapper.StudyPlanMapper;
import org.linxing.linxing_agent.agent.mapper.StudyPlanPhaseMapper;
import org.linxing.linxing_agent.agent.mapper.StudyPlanProgressMapper;
import org.linxing.linxing_agent.agent.service.IStudyPlanService;
import org.linxing.linxing_agent.agent.vo.StudyPlanDetailVO;
import org.linxing.linxing_agent.agent.vo.StudyPlanPhaseVO;
import org.linxing.linxing_agent.agent.vo.StudyPlanVO;
import org.linxing.linxing_agent.common.result.PageResult;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 学习计划服务实现
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class StudyPlanService implements IStudyPlanService {

    private final StudyPlanMapper studyPlanMapper;
    private final StudyPlanPhaseMapper studyPlanPhaseMapper;
    private final StudyPlanProgressMapper studyPlanProgressMapper;
    private final ObjectMapper objectMapper;

    private static final Set<String> VALID_SOURCE_TYPES = Set.of("notes", "web_search", "mixed");
    private static final Set<String> VALID_PHASE_STATUSES = Set.of("not_started", "in_progress", "completed");

    // ============================================================
    // 列表查询
    // ============================================================

    @Override
    public PageResult<StudyPlanVO> listPlans(Integer userId, String status, int page, int size) {
        int offset = (page - 1) * size;
        List<StudyPlan> plans = studyPlanMapper.selectByUserId(userId, status, offset, size);
        int total = studyPlanMapper.countByUserId(userId, status);

        List<StudyPlanVO> vos = plans.stream()
                .map(this::toPlanVO)
                .collect(Collectors.toList());

        return PageResult.of(vos, total, page, size);
    }

    // ============================================================
    // 详情查询
    // ============================================================

    @Override
    public StudyPlanDetailVO getPlanDetail(Integer userId, Integer planId) {
        StudyPlan plan = studyPlanMapper.selectById(userId, planId);
        if (plan == null) {
            throw new StudyPlanNotFoundException("学习计划不存在或无权访问: " + planId);
        }

        List<StudyPlanPhase> phases = studyPlanPhaseMapper.selectByPlanId(planId);
        List<StudyPlanProgress> progressList = studyPlanProgressMapper.selectByPlanId(planId, userId);

        // 按 phaseId 索引进度
        Map<Integer, StudyPlanProgress> progressMap = progressList.stream()
                .collect(Collectors.toMap(StudyPlanProgress::getPhaseId, p -> p, (a, b) -> a));

        List<StudyPlanPhaseVO> phaseVOs = phases.stream()
                .map(phase -> {
                    StudyPlanProgress progress = progressMap.get(phase.getId());
                    return toPhaseVO(phase, progress);
                })
                .collect(Collectors.toList());

        // 进度统计
        StudyPlanDetailVO.ProgressStats stats = buildProgressStats(phases.size(), progressList);

        return StudyPlanDetailVO.builder()
                .id(plan.getId())
                .title(plan.getTitle())
                .description(plan.getDescription())
                .goal(plan.getGoal())
                .duration(plan.getDuration())
                .status(plan.getStatus())
                .sourceType(plan.getSourceType())
                .sourceRefs(plan.getSourceRefs())
                .phaseCount(plan.getPhaseCount())
                .createdAt(plan.getCreatedAt() != null ? plan.getCreatedAt().toString() : null)
                .updatedAt(plan.getUpdatedAt() != null ? plan.getUpdatedAt().toString() : null)
                .phases(phaseVOs)
                .progress(stats)
                .build();
    }

    // ============================================================
    // 进度更新
    // ============================================================

    @Override
    @Transactional
    public void updatePhaseStatus(Integer userId, Integer planId, Integer phaseId, StudyPlanProgressUpdateRequest body) {
        StudyPlan plan = studyPlanMapper.selectById(userId, planId);
        if (plan == null) {
            throw new StudyPlanNotFoundException("学习计划不存在或无权访问: " + planId);
        }

        String status = body.getStatus();
        if (status == null || !VALID_PHASE_STATUSES.contains(status)) {
            throw new StudyPlanParseException("非法阶段状态: " + status + "，仅限 not_started/in_progress/completed");
        }

        StudyPlanProgress existing = studyPlanProgressMapper.selectByPhaseId(phaseId, userId);
        if (existing == null) {
            throw new StudyPlanNotFoundException("阶段进度记录不存在: phaseId=" + phaseId);
        }

        String notes = body.getNotes();
        // 如果未传 notes，保留原 notes
        if (notes == null) {
            notes = existing.getNotes();
        }

        studyPlanProgressMapper.updateStatus(phaseId, userId, status, notes);

        // 同步更新计划主表状态
        syncPlanStatus(userId, planId);

        log.info("用户 {} 更新学习计划 {} 阶段 {} 状态为 {}", userId, planId, phaseId, status);
    }

    /**
     * 根据阶段进度同步更新计划主表状态：
     * - 所有阶段 not_started → created
     * - 存在 in_progress 或部分 completed → in_progress
     * - 所有阶段 completed → completed
     */
    private void syncPlanStatus(Integer userId, Integer planId) {
        int total = studyPlanProgressMapper.countByPlanIdAndStatus(planId, userId, null);
        int completed = studyPlanProgressMapper.countByPlanIdAndStatus(planId, userId, "completed");
        int notStarted = studyPlanProgressMapper.countByPlanIdAndStatus(planId, userId, "not_started");

        String newStatus;
        if (total == 0) {
            return;
        } else if (completed == total) {
            newStatus = "completed";
        } else if (notStarted == total) {
            newStatus = "created";
        } else {
            newStatus = "in_progress";
        }

        studyPlanMapper.updateStatus(planId, newStatus);
    }

    // ============================================================
    // 导出
    // ============================================================

    @Override
    public String exportAsMarkdown(Integer userId, Integer planId) {
        StudyPlanDetailVO detail = getPlanDetail(userId, planId);
        return buildMarkdown(detail);
    }

    @Override
    public String exportAsHtml(Integer userId, Integer planId) {
        StudyPlanDetailVO detail = getPlanDetail(userId, planId);
        return buildHtml(detail);
    }

    // ============================================================
    // 解析 + 校验 + 持久化（供 SaveStudyPlanTool 调用）
    // ============================================================

    /**
     * 校验策略：控制校验失败时的行为
     */
    public enum ValidationStrategy {
        /** 遇到第一个错误即抛 StudyPlanParseException */
        FAIL_FAST,
        /** 收集所有错误后抛 StudyPlanValidationException */
        COLLECT_ALL
    }

    /**
     * 一次性解析 + 校验 + 持久化（fail-fast 策略）
     */
    @Transactional
    public Integer parseAndSave(Integer userId, String json) {
        JsonNode root;
        try {
            root = objectMapper.readTree(json);
        } catch (Exception e) {
            throw new StudyPlanParseException("JSON 解析失败: " + e.getMessage(), e);
        }
        return parseAndSave(userId, root, ValidationStrategy.FAIL_FAST);
    }

    /**
     * 解析 + 校验 + 持久化（JsonNode 重载，默认 fail-fast）
     */
    @Transactional
    public Integer parseAndSave(Integer userId, JsonNode root) {
        return parseAndSave(userId, root, ValidationStrategy.FAIL_FAST);
    }

    /**
     * 解析 + 校验 + 持久化（指定校验策略）
     */
    @Transactional
    public Integer parseAndSave(Integer userId, JsonNode root, ValidationStrategy strategy) {
        validatePlanJson(root, strategy);
        return doSave(userId, root);
    }

    /**
     * 统一校验入口：校验学习计划 JSON 的元数据和 phases 数组
     */
    public List<QuestionError> validatePlanJson(JsonNode root, ValidationStrategy strategy) {
        List<QuestionError> errors = new ArrayList<>();

        // --- 元数据校验 ---
        if (!root.has("title") || root.get("title").asText().isBlank()) {
            errors.add(new QuestionError(-1, "title", "缺少必填字段: title"));
            if (strategy == ValidationStrategy.FAIL_FAST) {
                throw new StudyPlanParseException(errors.get(0).getMessage());
            }
        }

        if (!root.has("goal") || root.get("goal").asText().isBlank()) {
            errors.add(new QuestionError(-1, "goal", "缺少必填字段: goal"));
            if (strategy == ValidationStrategy.FAIL_FAST) {
                throw new StudyPlanParseException(errors.get(0).getMessage());
            }
        }

        // source_type 校验（可选，默认 web_search）
        String sourceType = root.has("source_type") ? root.get("source_type").asText() : "web_search";
        if (!VALID_SOURCE_TYPES.contains(sourceType)) {
            errors.add(new QuestionError(-1, "source_type",
                    "非法 source_type: " + sourceType + "，仅限 notes/web_search/mixed"));
            if (strategy == ValidationStrategy.FAIL_FAST) {
                throw new StudyPlanParseException(errors.get(0).getMessage());
            }
        }

        if (!root.has("phases") || !root.get("phases").isArray()) {
            errors.add(new QuestionError(-1, "phases", "缺少必填字段: phases（数组）"));
            if (strategy == ValidationStrategy.FAIL_FAST) {
                throw new StudyPlanParseException(errors.get(0).getMessage());
            }
            return errors;
        }

        ArrayNode phases = (ArrayNode) root.get("phases");
        if (phases.isEmpty()) {
            errors.add(new QuestionError(-1, "phases", "phases 数组不能为空"));
            if (strategy == ValidationStrategy.FAIL_FAST) {
                throw new StudyPlanParseException(errors.get(0).getMessage());
            }
            return errors;
        }

        // --- 逐阶段校验 ---
        for (int i = 0; i < phases.size(); i++) {
            List<QuestionError> phaseErrors = validateSinglePhase(i, phases.get(i));
            errors.addAll(phaseErrors);
            if (strategy == ValidationStrategy.FAIL_FAST && !phaseErrors.isEmpty()) {
                throw new StudyPlanParseException(
                        String.format("第 %d 阶段校验失败: %s", i + 1, phaseErrors.get(0).getMessage()));
            }
        }

        if (strategy == ValidationStrategy.COLLECT_ALL && !errors.isEmpty()) {
            throw new StudyPlanValidationException(errors);
        }

        return errors;
    }

    /**
     * 单阶段校验
     */
    private List<QuestionError> validateSinglePhase(int index, JsonNode phase) {
        List<QuestionError> errors = new ArrayList<>();

        if (!phase.has("title") || phase.get("title").asText().isBlank()) {
            errors.add(new QuestionError(index, "title", "缺少必填字段: title"));
        }

        // objective 推荐但不强制
        // duration 可选
        // key_topics / resources / practice_tasks / milestones 可选，但若存在必须是数组
        String[] arrayFields = {"key_topics", "resources", "practice_tasks", "milestones"};
        for (String field : arrayFields) {
            if (phase.has(field) && !phase.get(field).isNull() && !phase.get(field).isArray()) {
                errors.add(new QuestionError(index, field, field + " 必须是数组"));
            }
        }

        return errors;
    }

    /**
     * 持久化：已校验通过的 JSON 落库
     */
    private Integer doSave(Integer userId, JsonNode root) {
        String title = root.get("title").asText();
        String goal = root.get("goal").asText();
        String description = root.has("description") && !root.get("description").isNull()
                ? root.get("description").asText() : null;
        String duration = root.has("duration") && !root.get("duration").isNull()
                ? root.get("duration").asText() : null;
        String sourceType = root.has("source_type") ? root.get("source_type").asText() : "web_search";

        String sourceRefs;
        try {
            sourceRefs = root.has("source_refs") && root.get("source_refs").isArray()
                    ? objectMapper.writeValueAsString(root.get("source_refs"))
                    : "[]";
        } catch (Exception e) {
            sourceRefs = "[]";
        }

        ArrayNode phasesNode = (ArrayNode) root.get("phases");
        int phaseCount = phasesNode.size();

        // 1. 插入主表
        StudyPlan plan = StudyPlan.builder()
                .userId(userId)
                .title(title)
                .description(description)
                .goal(goal)
                .duration(duration)
                .sourceType(sourceType)
                .sourceRefs(sourceRefs)
                .status("created")
                .phaseCount(phaseCount)
                .build();
        studyPlanMapper.insert(plan);
        Integer planId = plan.getId();

        // 2. 批量插入阶段
        List<StudyPlanPhase> phaseEntities = new ArrayList<>();
        List<StudyPlanProgress> progressEntities = new ArrayList<>();
        for (int i = 0; i < phasesNode.size(); i++) {
            JsonNode phaseNode = phasesNode.get(i);
            StudyPlanPhase phase = StudyPlanPhase.builder()
                    .planId(planId)
                    .userId(userId)
                    .phaseOrder(i + 1)
                    .title(phaseNode.get("title").asText())
                    .duration(extractTextOrNull(phaseNode, "duration"))
                    .objective(extractTextOrNull(phaseNode, "objective"))
                    .keyTopics(extractArrayAsString(phaseNode, "key_topics"))
                    .resources(extractArrayAsString(phaseNode, "resources"))
                    .practiceTasks(extractArrayAsString(phaseNode, "practice_tasks"))
                    .milestones(extractArrayAsString(phaseNode, "milestones"))
                    .build();
            phaseEntities.add(phase);
        }
        studyPlanPhaseMapper.batchInsert(phaseEntities);

        // 3. 批量初始化进度记录（每个阶段一条 not_started）
        for (StudyPlanPhase phase : phaseEntities) {
            StudyPlanProgress progress = StudyPlanProgress.builder()
                    .planId(planId)
                    .phaseId(phase.getId())
                    .userId(userId)
                    .status("not_started")
                    .build();
            progressEntities.add(progress);
        }
        if (!progressEntities.isEmpty()) {
            studyPlanProgressMapper.batchInsert(progressEntities);
        }

        log.info("用户 {} 保存学习计划成功，planId={}，阶段数={}", userId, planId, phaseCount);
        return planId;
    }

    private String extractTextOrNull(JsonNode node, String field) {
        if (node.has(field) && !node.get(field).isNull()) {
            return node.get(field).asText();
        }
        return null;
    }

    private String extractArrayAsString(JsonNode node, String field) {
        if (node.has(field) && node.get(field).isArray()) {
            try {
                return objectMapper.writeValueAsString(node.get(field));
            } catch (Exception e) {
                return "[]";
            }
        }
        return "[]";
    }

    // ============================================================
    // VO 转换
    // ============================================================

    private StudyPlanVO toPlanVO(StudyPlan plan) {
        return StudyPlanVO.builder()
                .id(plan.getId())
                .title(plan.getTitle())
                .goal(plan.getGoal())
                .duration(plan.getDuration())
                .status(plan.getStatus())
                .sourceType(plan.getSourceType())
                .phaseCount(plan.getPhaseCount())
                .createdAt(plan.getCreatedAt())
                .build();
    }

    private StudyPlanPhaseVO toPhaseVO(StudyPlanPhase phase, StudyPlanProgress progress) {
        return StudyPlanPhaseVO.builder()
                .id(phase.getId())
                .phaseOrder(phase.getPhaseOrder())
                .title(phase.getTitle())
                .duration(phase.getDuration())
                .objective(phase.getObjective())
                .keyTopics(phase.getKeyTopics())
                .resources(phase.getResources())
                .practiceTasks(phase.getPracticeTasks())
                .milestones(phase.getMilestones())
                .progressStatus(progress != null ? progress.getStatus() : "not_started")
                .notes(progress != null ? progress.getNotes() : null)
                .build();
    }

    private StudyPlanDetailVO.ProgressStats buildProgressStats(int total, List<StudyPlanProgress> progressList) {
        int completed = 0, inProgress = 0, notStarted = 0;
        for (StudyPlanProgress p : progressList) {
            switch (p.getStatus()) {
                case "completed" -> completed++;
                case "in_progress" -> inProgress++;
                case "not_started" -> notStarted++;
            }
        }
        int percentage = total == 0 ? 0 : (int) Math.round((double) completed * 100 / total);
        return StudyPlanDetailVO.ProgressStats.builder()
                .totalPhases(total)
                .completedPhases(completed)
                .inProgressPhases(inProgress)
                .notStartedPhases(notStarted)
                .completionPercentage(percentage)
                .build();
    }

    // ============================================================
    // 导出实现
    // ============================================================

    /**
     * 构建 Markdown 格式的学习计划
     */
    private String buildMarkdown(StudyPlanDetailVO detail) {
        StringBuilder sb = new StringBuilder();
        sb.append("# ").append(detail.getTitle()).append("\n\n");

        sb.append("> **学习目标**：").append(detail.getGoal()).append("\n\n");

        if (detail.getDescription() != null && !detail.getDescription().isBlank()) {
            sb.append("**描述**：").append(detail.getDescription()).append("\n\n");
        }
        if (detail.getDuration() != null && !detail.getDuration().isBlank()) {
            sb.append("**总时长**：").append(detail.getDuration()).append("\n\n");
        }

        StudyPlanDetailVO.ProgressStats stats = detail.getProgress();
        if (stats != null) {
            sb.append("**进度**：")
                    .append(stats.getCompletedPhases()).append("/").append(stats.getTotalPhases())
                    .append(" 阶段已完成（").append(stats.getCompletionPercentage()).append("%）\n\n");
        }

        sb.append("---\n\n");

        List<StudyPlanPhaseVO> phases = detail.getPhases();
        if (phases != null) {
            for (StudyPlanPhaseVO phase : phases) {
                String statusIcon = switch (phase.getProgressStatus()) {
                    case "completed" -> "[x]";
                    case "in_progress" -> "[~]";
                    default -> "[ ]";
                };
                sb.append("## ").append(statusIcon).append(" 阶段 ")
                        .append(phase.getPhaseOrder()).append("：").append(phase.getTitle()).append("\n\n");

                if (phase.getDuration() != null && !phase.getDuration().isBlank()) {
                    sb.append("- **时长**：").append(phase.getDuration()).append("\n");
                }
                if (phase.getObjective() != null && !phase.getObjective().isBlank()) {
                    sb.append("- **目标**：").append(phase.getObjective()).append("\n");
                }

                appendArraySection(sb, "关键知识点", phase.getKeyTopics());
                appendArraySection(sb, "学习资源", phase.getResources());
                appendArraySection(sb, "实践任务", phase.getPracticeTasks());
                appendArraySection(sb, "里程碑", phase.getMilestones());

                if (phase.getNotes() != null && !phase.getNotes().isBlank()) {
                    sb.append("\n**学习笔记**：\n\n> ").append(phase.getNotes()).append("\n");
                }

                sb.append("\n");
            }
        }

        return sb.toString();
    }

    private void appendArraySection(StringBuilder sb, String label, String jsonArrayStr) {
        List<String> items = parseStringArray(jsonArrayStr);
        if (items.isEmpty()) {
            return;
        }
        sb.append("- **").append(label).append("**：\n");
        for (String item : items) {
            sb.append("  - ").append(item).append("\n");
        }
    }

    /**
     * 构建 HTML 格式的学习计划
     */
    private String buildHtml(StudyPlanDetailVO detail) {
        StringBuilder sb = new StringBuilder();
        sb.append("<!DOCTYPE html>\n<html lang=\"zh-CN\">\n<head>\n");
        sb.append("<meta charset=\"UTF-8\">\n");
        sb.append("<title>").append(escapeHtml(detail.getTitle())).append(" - 学习计划</title>\n");
        sb.append("<style>\n");
        sb.append("body{font-family:-apple-system,BlinkMacSystemFont,'Segoe UI',Roboto,sans-serif;")
                .append("max-width:900px;margin:40px auto;padding:0 20px;color:#333;line-height:1.6;}\n");
        sb.append("h1{color:#1a73e8;border-bottom:2px solid #1a73e8;padding-bottom:10px;}\n");
        sb.append("h2{color:#333;border-left:4px solid #1a73e8;padding-left:12px;margin-top:32px;}\n");
        sb.append(".meta{background:#f5f8ff;padding:16px;border-radius:8px;margin:16px 0;}\n");
        sb.append(".progress-bar{background:#e0e0e0;border-radius:4px;height:20px;overflow:hidden;margin:8px 0;}\n");
        sb.append(".progress-fill{background:#1a73e8;height:100%;display:flex;align-items:center;")
                .append("justify-content:center;color:white;font-size:12px;}\n");
        sb.append(".phase{background:#fff;border:1px solid #e8e8e8;border-radius:8px;padding:20px;margin:16px 0;}\n");
        sb.append(".phase-status{display:inline-block;padding:2px 10px;border-radius:12px;font-size:12px;margin-left:8px;}\n");
        sb.append(".status-completed{background:#e6f4ea;color:#2e7d32;}\n");
        sb.append(".status-in_progress{background:#fef7e0;color:#f57f17;}\n");
        sb.append(".status-not_started{background:#f1f3f4;color:#5f6368;}\n");
        sb.append("ul{padding-left:24px;}\n");
        sb.append("li{margin:4px 0;}\n");
        sb.append(".section-label{font-weight:600;color:#1a73e8;margin-top:12px;display:block;}\n");
        sb.append("</style>\n</head>\n<body>\n");

        sb.append("<h1>").append(escapeHtml(detail.getTitle())).append("</h1>\n");

        sb.append("<div class=\"meta\">\n");
        sb.append("<p><strong>学习目标</strong>：").append(escapeHtml(detail.getGoal())).append("</p>\n");
        if (detail.getDescription() != null && !detail.getDescription().isBlank()) {
            sb.append("<p><strong>描述</strong>：").append(escapeHtml(detail.getDescription())).append("</p>\n");
        }
        if (detail.getDuration() != null && !detail.getDuration().isBlank()) {
            sb.append("<p><strong>总时长</strong>：").append(escapeHtml(detail.getDuration())).append("</p>\n");
        }

        StudyPlanDetailVO.ProgressStats stats = detail.getProgress();
        if (stats != null) {
            sb.append("<p><strong>进度</strong>：")
                    .append(stats.getCompletedPhases()).append("/").append(stats.getTotalPhases())
                    .append(" 阶段已完成（").append(stats.getCompletionPercentage()).append("%）</p>\n");
            sb.append("<div class=\"progress-bar\">");
            sb.append("<div class=\"progress-fill\" style=\"width:")
                    .append(stats.getCompletionPercentage()).append("%\">")
                    .append(stats.getCompletionPercentage()).append("%</div></div>\n");
        }
        sb.append("</div>\n");

        List<StudyPlanPhaseVO> phases = detail.getPhases();
        if (phases != null) {
            for (StudyPlanPhaseVO phase : phases) {
                sb.append("<div class=\"phase\">\n");
                String statusLabel = switch (phase.getProgressStatus()) {
                    case "completed" -> "已完成";
                    case "in_progress" -> "进行中";
                    default -> "未开始";
                };
                String statusClass = "status-" + phase.getProgressStatus();
                sb.append("<h2>阶段 ").append(phase.getPhaseOrder()).append("：")
                        .append(escapeHtml(phase.getTitle()))
                        .append("<span class=\"phase-status ").append(statusClass).append("\">")
                        .append(statusLabel).append("</span></h2>\n");

                if (phase.getDuration() != null && !phase.getDuration().isBlank()) {
                    sb.append("<p><strong>时长</strong>：").append(escapeHtml(phase.getDuration())).append("</p>\n");
                }
                if (phase.getObjective() != null && !phase.getObjective().isBlank()) {
                    sb.append("<p><strong>目标</strong>：").append(escapeHtml(phase.getObjective())).append("</p>\n");
                }

                appendHtmlArraySection(sb, "关键知识点", phase.getKeyTopics());
                appendHtmlArraySection(sb, "学习资源", phase.getResources());
                appendHtmlArraySection(sb, "实践任务", phase.getPracticeTasks());
                appendHtmlArraySection(sb, "里程碑", phase.getMilestones());

                if (phase.getNotes() != null && !phase.getNotes().isBlank()) {
                    sb.append("<p><strong>学习笔记</strong>：</p><blockquote>")
                            .append(escapeHtml(phase.getNotes())).append("</blockquote>\n");
                }

                sb.append("</div>\n");
            }
        }

        sb.append("</body>\n</html>");
        return sb.toString();
    }

    private void appendHtmlArraySection(StringBuilder sb, String label, String jsonArrayStr) {
        List<String> items = parseStringArray(jsonArrayStr);
        if (items.isEmpty()) {
            return;
        }
        sb.append("<span class=\"section-label\">").append(label).append("：</span>\n<ul>\n");
        for (String item : items) {
            sb.append("<li>").append(escapeHtml(item)).append("</li>\n");
        }
        sb.append("</ul>\n");
    }

    /**
     * 解析 JSON 数组字符串为 List<String>，失败返回空列表
     */
    @SuppressWarnings("unchecked")
    private List<String> parseStringArray(String jsonArrayStr) {
        if (jsonArrayStr == null || jsonArrayStr.isBlank() || "[]".equals(jsonArrayStr)) {
            return Collections.emptyList();
        }
        try {
            JsonNode node = objectMapper.readTree(jsonArrayStr);
            if (!node.isArray()) {
                return Collections.emptyList();
            }
            List<String> result = new ArrayList<>();
            for (JsonNode item : node) {
                if (item.isTextual()) {
                    result.add(item.asText());
                } else {
                    // 复杂对象（如 resources 中的 {name, url}），转为可读字符串
                    result.add(item.toString());
                }
            }
            return result;
        } catch (Exception e) {
            return Collections.emptyList();
        }
    }

    private String escapeHtml(String text) {
        if (text == null) {
            return "";
        }
        return text.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "&#39;");
    }
}
