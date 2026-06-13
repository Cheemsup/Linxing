package org.linxing.linxing_agent.agent.service.impl;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;
import lombok.AllArgsConstructor;
import lombok.Data;
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

        // 逐元素校验（遇到第一个错误即中断，保持原有行为）
        for (int i = 0; i < questions.size(); i++) {
            JsonNode q = questions.get(i);
            String type = q.has("type") ? q.get("type").asText() : null;
            if (type == null || !VALID_QUESTION_TYPES.contains(type)) {
                throw new ExamParseException("第 " + (i + 1) + " 题缺少有效的 type 字段，当前值: " + type);
            }
            if (!q.has("stem") || q.get("stem").asText().isBlank()) {
                throw new ExamParseException("第 " + (i + 1) + " 题缺少必填字段: stem");
            }
            if (!q.has("answer") || isAnswerBlank(q.get("answer"))) {
                throw new ExamParseException("第 " + (i + 1) + " 题缺少必填字段: answer");
            }
        }

        return doSave(userId, root);
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

        // 一份 exam 只能提交一次
        ExamAnswer existing = examAnswerMapper.selectByExamIdAndUserId(examId, userId);
        if (existing != null && existing.getIsCompleted()) {
            throw new ExamNotFoundException("该测验已提交，不能重复作答");
        }

        List<ExamContext> contexts = examContextMapper.selectByExamId(examId);
        Map<String, Object> rawAnswers = body.getAnswers();
        if (rawAnswers == null) {
            rawAnswers = Collections.emptyMap();
        }

        // 将 Map<String, Object> 转为 Map<String, String>：
        // 多选题的 List 值序列化为 JSON 字符串，其余直接 toString
        Map<String, String> userAnswers = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : rawAnswers.entrySet()) {
            Object val = entry.getValue();
            if (val instanceof List<?> list) {
                try {
                    userAnswers.put(entry.getKey(), objectMapper.writeValueAsString(list));
                } catch (Exception e) {
                    userAnswers.put(entry.getKey(), list.toString());
                }
            } else if (val != null) {
                userAnswers.put(entry.getKey(), val.toString());
            } else {
                userAnswers.put(entry.getKey(), "");
            }
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

        if (existing != null && !existing.getIsCompleted()) {
            // 从草稿升级为正式提交
            examAnswerMapper.updateToSubmitted(examId, userId, answersJson, correctCount, total);
        } else {
            ExamAnswer examAnswer = ExamAnswer.builder()
                    .examId(examId)
                    .userId(userId)
                    .answers(answersJson)
                    .score(correctCount)
                    .total(total)
                    .isCompleted(true)
                    .build();
            examAnswerMapper.insert(examAnswer);
        }

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
     * 保存草稿答案，不判分，更新 exams.status 为 in_progress
     */
    @Transactional
    public void saveDraft(Integer userId, Integer examId, ExamSubmitRequest body) {
        Exam exam = examMapper.selectById(userId, examId);
        if (exam == null) {
            throw new ExamNotFoundException("测验不存在或无权访问: " + examId);
        }

        // 已正式提交的测验不能再保存草稿
        ExamAnswer existing = examAnswerMapper.selectByExamIdAndUserId(examId, userId);
        if (existing != null && existing.getIsCompleted()) {
            throw new ExamNotFoundException("该测验已提交，不能修改");
        }

        Map<String, Object> rawAnswers = body.getAnswers();
        String answersJson;
        try {
            answersJson = objectMapper.writeValueAsString(rawAnswers != null ? rawAnswers : Collections.emptyMap());
        } catch (Exception e) {
            answersJson = "{}";
        }

        if (existing != null && !existing.getIsCompleted()) {
            // 更新已有草稿
            examAnswerMapper.updateDraft(examId, userId, answersJson);
        } else {
            ExamAnswer examAnswer = ExamAnswer.builder()
                    .examId(examId)
                    .userId(userId)
                    .answers(answersJson)
                    .score(0)
                    .total(0)
                    .isCompleted(false)
                    .build();
            examAnswerMapper.insert(examAnswer);
        }

        // 更新测验状态为 in_progress
        examMapper.updateStatus(examId, "in_progress");

        log.info("用户 {} 保存测验 {} 草稿", userId, examId);
    }

    /**
     * 获取草稿答案，用于前端恢复已填答案
     */
    public Map<String, Object> getDraft(Integer userId, Integer examId) {
        ExamAnswer existing = examAnswerMapper.selectByExamIdAndUserId(examId, userId);
        if (existing == null || existing.getIsCompleted()) {
            return null;
        }
        try {
            return objectMapper.readValue(existing.getAnswers(), Map.class);
        } catch (Exception e) {
            return null;
        }
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

    /**
     * 判断 answer 节点是否为空。
     * 数组节点：空数组视为空；非空数组视为非空。
     * 文本节点：空白字符串视为空。
     */
    private boolean isAnswerBlank(JsonNode answerNode) {
        if (answerNode.isArray()) {
            return answerNode.isEmpty();
        }
        return answerNode.asText().isBlank();
    }

    /**
     * 从 answer 节点提取答案字符串。
     * 数组节点（multi_choice）：序列化为 JSON 字符串，如 ["A. xxx","C. xxx"]
     * 文本节点（其他题型）：直接取文本值
     */
    private String extractAnswer(JsonNode answerNode) {
        if (answerNode.isArray()) {
            return answerNode.toString();
        }
        return answerNode.asText();
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

    /**
     * 逐元素校验 questions 数组，返回所有错误（不中断）。
     * 用于分批模式下 save 工具校验失败时返回索引级错误信息。
     */
    public List<QuestionError> validateQuestions(ObjectNode root) {
        List<QuestionError> errors = new ArrayList<>();

        // 校验 metadata 必填字段
        if (!root.has("title") || root.get("title").asText().isBlank()) {
            errors.add(new QuestionError(-1, "title", "缺少必填字段: title"));
        }

        if (!root.has("questions") || !root.get("questions").isArray()) {
            errors.add(new QuestionError(-1, "questions", "缺少必填字段: questions（数组）"));
            return errors;
        }

        ArrayNode questions = (ArrayNode) root.get("questions");
        if (questions.isEmpty()) {
            errors.add(new QuestionError(-1, "questions", "questions 数组不能为空"));
            return errors;
        }

        for (int i = 0; i < questions.size(); i++) {
            JsonNode q = questions.get(i);

            String type = q.has("type") ? q.get("type").asText() : null;
            if (type == null || !VALID_QUESTION_TYPES.contains(type)) {
                errors.add(new QuestionError(i, "type",
                        "非法题型: " + type + "，仅限 single_choice/multi_choice/fill_blank/true_false/short_answer"));
            }

            if (!q.has("stem") || q.get("stem").asText().isBlank()) {
                errors.add(new QuestionError(i, "stem", "缺少必填字段: stem"));
            }

            if (!q.has("answer") || isAnswerBlank(q.get("answer"))) {
                errors.add(new QuestionError(i, "answer", "缺少必填字段: answer"));
            } else if ("multi_choice".equals(type) && !q.get("answer").isArray()) {
                errors.add(new QuestionError(i, "answer",
                        "multi_choice 的 answer 应为数组，如 [\"A\",\"C\"]"));
            } else if ("fill_blank".equals(type) && q.get("answer").isArray()) {
                // fill_blank 允许数组（多空填空），每个元素是一个空的答案
                // 无需额外校验
            } else if (!"multi_choice".equals(type) && !"fill_blank".equals(type) && q.get("answer").isArray()) {
                errors.add(new QuestionError(i, "answer",
                        "该题型的 answer 应为字符串，当前为数组"));
            }

            // 选择题必须有 options
            if (("single_choice".equals(type) || "multi_choice".equals(type))
                    && (!q.has("options") || !q.get("options").isArray() || q.get("options").isEmpty())) {
                errors.add(new QuestionError(i, "options",
                        "选择题必须有 options 数组"));
            }
        }

        return errors;
    }

    /**
     * 从容器拼装的 JSON 持久化测验。先逐元素校验，校验通过则写入数据库。
     *
     * @return 校验通过返回 examId；校验失败抛出 ExamValidationException
     */
    @Transactional
    public Integer parseAndSaveFromContainer(Integer userId, ObjectNode root) {
        List<QuestionError> errors = validateQuestions(root);
        if (!errors.isEmpty()) {
            throw new ExamValidationException(errors);
        }

        return doSave(userId, root);
    }

    /**
     * 实际写入数据库的逻辑，从 parseAndSave 中提取
     */
    private Integer doSave(Integer userId, JsonNode root) {
        String title = root.get("title").asText();
        String sourceType = root.has("source_type") ? root.get("source_type").asText() : "mixed";
        String description = root.has("description") ? root.get("description").asText() : null;
        String sourceRefs = root.has("source_refs")
                ? root.get("source_refs").toString() : "[]";
        ArrayNode questions = (ArrayNode) root.get("questions");

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

        List<ExamContext> contextList = new ArrayList<>();
        for (int i = 0; i < questions.size(); i++) {
            JsonNode q = questions.get(i);

            String type = q.get("type").asText();
            String options = q.has("options") ? q.get("options").toString() : null;
            String answer = extractAnswer(q.get("answer"));
            String explanation = q.has("explanation") ? q.get("explanation").asText() : null;
            String difficulty = q.has("difficulty") ? q.get("difficulty").asText() : "medium";

            ExamContext ctx = ExamContext.builder()
                    .examId(exam.getId())
                    .userId(userId)
                    .questionOrder(i + 1)
                    .questionType(type)
                    .stem(q.get("stem").asText())
                    .options(options)
                    .answer(answer)
                    .explanation(explanation)
                    .difficulty(difficulty)
                    .build();
            contextList.add(ctx);
        }

        examContextMapper.batchInsert(contextList);

        log.info("用户 {} 生成测验 {}，共 {} 题", userId, exam.getId(), questions.size());
        return exam.getId();
    }

    /**
     * 单个题目的校验错误
     */
    @Data
    @AllArgsConstructor
    public static class QuestionError {
        private int index;   // -1 表示 metadata 级别错误
        private String field;
        private String message;
    }

    /**
     * 校验异常，携带所有错误信息
     */
    public static class ExamValidationException extends RuntimeException {
        private final List<QuestionError> errors;

        public ExamValidationException(List<QuestionError> errors) {
            super("测验校验失败");
            this.errors = errors;
        }

        public List<QuestionError> getErrors() {
            return errors;
        }
    }
}
