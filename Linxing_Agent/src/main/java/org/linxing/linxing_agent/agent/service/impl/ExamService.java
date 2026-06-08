package org.linxing.linxing_agent.agent.service.impl;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.linxing.linxing_agent.agent.dto.ExamSubmitRequest;
import org.linxing.linxing_agent.agent.entity.Exam;
import org.linxing.linxing_agent.agent.entity.ExamAnswer;
import org.linxing.linxing_agent.agent.entity.ExamContext;
import org.linxing.linxing_agent.agent.mapper.ExamAnswerMapper;
import org.linxing.linxing_agent.agent.mapper.ExamContextMapper;
import org.linxing.linxing_agent.agent.mapper.ExamMapper;
import org.linxing.linxing_agent.agent.vo.ExamContextVO;
import org.linxing.linxing_agent.agent.vo.ExamDetailVO;
import org.linxing.linxing_agent.agent.vo.ExamSubmitVO;
import org.linxing.linxing_agent.agent.vo.ExamVO;
import org.linxing.linxing_agent.common.result.PageResult;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class ExamService {

    private final ExamMapper examMapper;
    private final ExamContextMapper examContextMapper;
    private final ExamAnswerMapper examAnswerMapper;
    private final ObjectMapper objectMapper;

    private static final Set<String> VALID_QUESTION_TYPES = Set.of(
            "single_choice", "multi_choice", "fill_blank", "true_false", "short_answer"
    );

    /**
     * 解析 LLM 生成的测验 JSON，写入 exams + exam_context 表。
     * 做容错处理：schema drift 时尝试修复，修复失败则抛出异常。
     *
     * @return 新生成的 examId
     */
    @Transactional
    public Integer parseAndSave(Integer userId, String json) {
        JsonNode root;
        try {
            root = objectMapper.readTree(json);
        } catch (Exception e) {
            throw new ExamParseException("JSON 解析失败: " + e.getMessage(), e);
        }

        // 校验顶层必填字段
        if (!root.has("title") || root.get("title").asText().isBlank()) {
            throw new ExamParseException("缺少必填字段: title");
        }
        if (!root.has("questions") || !root.get("questions").isArray()) {
            throw new ExamParseException("缺少必填字段: questions（数组）");
        }
        ArrayNode questions = (ArrayNode) root.get("questions");
        if (questions.isEmpty()) {
            throw new ExamParseException("questions 数组不能为空");
        }

        String title = root.get("title").asText();
        String sourceType = root.has("source_type") ? root.get("source_type").asText() : "mixed";
        String description = root.has("description") ? root.get("description").asText() : null;
        String sourceRefs = root.has("source_refs")
                ? root.get("source_refs").toString() : "[]";

        // 写入 exams 表
        Exam exam = Exam.builder()
                .userId(userId)
                .title(title)
                .description(description)
                .status("created")
                .sourceType(sourceType)
                .sourceRefs(sourceRefs)
                .questionCount(questions.size())
                .build();
        examMapper.insert(exam);

        // 遍历 questions，校验并构建 ExamContext 列表
        List<ExamContext> contextList = new ArrayList<>();
        for (int i = 0; i < questions.size(); i++) {
            JsonNode q = questions.get(i);

            String type = q.has("type") ? q.get("type").asText() : null;
            if (type == null || !VALID_QUESTION_TYPES.contains(type)) {
                throw new ExamParseException("第 " + (i + 1) + " 题缺少有效的 type 字段，当前值: " + type);
            }
            if (!q.has("stem") || q.get("stem").asText().isBlank()) {
                throw new ExamParseException("第 " + (i + 1) + " 题缺少必填字段: stem");
            }
            if (!q.has("answer") || q.get("answer").asText().isBlank()) {
                throw new ExamParseException("第 " + (i + 1) + " 题缺少必填字段: answer");
            }

            String options = q.has("options") ? q.get("options").toString() : null;
            String explanation = q.has("explanation") ? q.get("explanation").asText() : null;
            String difficulty = q.has("difficulty") ? q.get("difficulty").asText() : "medium";

            ExamContext ctx = ExamContext.builder()
                    .examId(exam.getId())
                    .userId(userId)
                    .questionOrder(i + 1)
                    .questionType(type)
                    .stem(q.get("stem").asText())
                    .options(options)
                    .answer(q.get("answer").asText())
                    .explanation(explanation)
                    .difficulty(difficulty)
                    .build();
            contextList.add(ctx);
        }

        // 批量写入 exam_context 表
        examContextMapper.batchInsert(contextList);

        log.info("用户 {} 生成测验 {}，共 {} 题", userId, exam.getId(), questions.size());
        return exam.getId();
    }

    /**
     * 获取测验详情（含试题列表，不含答案）
     */
    public ExamDetailVO getExam(Integer userId, Integer examId) {
        Exam exam = examMapper.selectById(userId, examId);
        if (exam == null) {
            throw new ExamNotFoundException("测验不存在或无权访问: " + examId);
        }

        List<ExamContext> contexts = examContextMapper.selectByExamId(examId);
        List<ExamContextVO> questionVOs = contexts.stream()
                .map(this::toContextVO)
                .collect(Collectors.toList());

        return ExamDetailVO.builder()
                .id(exam.getId())
                .title(exam.getTitle())
                .status(exam.getStatus())
                .sourceType(exam.getSourceType())
                .questionCount(exam.getQuestionCount())
                .createdAt(exam.getCreatedAt())
                .questions(questionVOs)
                .build();
    }

    /**
     * 获取用户测验列表，支持按 status 筛选、分页
     */
    public PageResult<ExamVO> listExams(Integer userId, String status, int page, int size) {
        int offset = (page - 1) * size;
        List<Exam> exams = examMapper.selectByUserId(userId, status, offset, size);
        int total = examMapper.countByUserId(userId, status);

        List<ExamVO> vos = exams.stream()
                .map(this::toExamVO)
                .collect(Collectors.toList());

        return PageResult.of(vos, total, page, size);
    }

    /**
     * 保存答题记录，更新 exams.status 为 in_progress/completed
     */
    @Transactional
    public ExamSubmitVO saveAttempt(Integer userId, Integer examId, ExamSubmitRequest body) {
        Exam exam = examMapper.selectById(userId, examId);
        if (exam == null) {
            throw new ExamNotFoundException("测验不存在或无权访问: " + examId);
        }

        List<ExamContext> contexts = examContextMapper.selectByExamId(examId);
        Map<String, String> userAnswers = body.getAnswers();
        if (userAnswers == null) {
            userAnswers = Collections.emptyMap();
        }

        // 判分
        int correctCount = 0;
        List<ExamSubmitVO.AnswerResultItem> details = new ArrayList<>();

        for (ExamContext ctx : contexts) {
            String qKey = String.valueOf(ctx.getId());
            String userAns = userAnswers.getOrDefault(qKey, "");
            String correctAns = ctx.getAnswer();
            boolean isCorrect = checkAnswer(ctx.getQuestionType(), userAns, correctAns);

            if (isCorrect) {
                correctCount++;
            }

            details.add(ExamSubmitVO.AnswerResultItem.builder()
                    .questionId(ctx.getId())
                    .questionType(ctx.getQuestionType())
                    .userAnswer(userAns)
                    .correctAnswer(correctAns)
                    .correct(isCorrect)
                    .explanation(ctx.getExplanation())
                    .build());
        }

        int total = contexts.size();

        // 保存答题记录
        String answersJson;
        try {
            answersJson = objectMapper.writeValueAsString(userAnswers);
        } catch (Exception e) {
            answersJson = "{}";
        }

        ExamAnswer examAnswer = ExamAnswer.builder()
                .examId(examId)
                .userId(userId)
                .answers(answersJson)
                .score(correctCount)
                .total(total)
                .build();
        examAnswerMapper.insert(examAnswer);

        // 更新测验状态为 completed
        examMapper.updateStatus(examId, "completed");

        log.info("用户 {} 完成测验 {}，得分 {}/{}", userId, examId, correctCount, total);

        return ExamSubmitVO.builder()
                .examId(examId)
                .score(correctCount)
                .total(total)
                .correctCount(correctCount)
                .details(details)
                .build();
    }

    /**
     * 判分逻辑：根据题型比较用户答案与正确答案
     */
    private boolean checkAnswer(String questionType, String userAnswer, String correctAnswer) {
        if (userAnswer == null || userAnswer.isBlank()) {
            return false;
        }

        return switch (questionType) {
            case "single_choice", "true_false", "fill_blank" ->
                    userAnswer.trim().equalsIgnoreCase(correctAnswer.trim());
            case "multi_choice" -> {
                // 多选题：排序后比较
                Set<String> userSet = parseMultiAnswer(userAnswer);
                Set<String> correctSet = parseMultiAnswer(correctAnswer);
                yield userSet.equals(correctSet);
            }
            case "short_answer" ->
                    // 简答题不做自动判分，始终视为错误，由人工复核
                    false;
            default -> false;
        };
    }

    private Set<String> parseMultiAnswer(String answer) {
        try {
            JsonNode node = objectMapper.readTree(answer);
            if (node.isArray()) {
                Set<String> set = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
                for (JsonNode item : node) {
                    set.add(item.asText().trim());
                }
                return set;
            }
        } catch (Exception ignored) {
        }
        // 如果不是 JSON 数组，按逗号分隔
        return Arrays.stream(answer.split("[,，]"))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .collect(Collectors.toCollection(() -> new TreeSet<>(String.CASE_INSENSITIVE_ORDER)));
    }

    private ExamVO toExamVO(Exam exam) {
        return ExamVO.builder()
                .id(exam.getId())
                .title(exam.getTitle())
                .status(exam.getStatus())
                .sourceType(exam.getSourceType())
                .questionCount(exam.getQuestionCount())
                .createdAt(exam.getCreatedAt())
                .build();
    }

    private ExamContextVO toContextVO(ExamContext ctx) {
        return ExamContextVO.builder()
                .id(ctx.getId())
                .questionOrder(ctx.getQuestionOrder())
                .questionType(ctx.getQuestionType())
                .stem(ctx.getStem())
                .options(ctx.getOptions())
                .difficulty(ctx.getDifficulty())
                .build();
    }

    // ---- 自定义异常 ----

    public static class ExamParseException extends RuntimeException {
        public ExamParseException(String message) {
            super(message);
        }
        public ExamParseException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    public static class ExamNotFoundException extends RuntimeException {
        public ExamNotFoundException(String message) {
            super(message);
        }
    }
}
