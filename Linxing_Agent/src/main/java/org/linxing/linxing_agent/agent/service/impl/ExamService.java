package org.linxing.linxing_agent.agent.service.impl;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.linxing.linxing_agent.agent.dto.ExamSubmitRequest;
import org.linxing.linxing_agent.agent.dto.QuestionError;
import org.linxing.linxing_agent.agent.entity.Exam;
import org.linxing.linxing_agent.agent.entity.ExamAnswer;
import org.linxing.linxing_agent.agent.entity.ExamContext;
import org.linxing.linxing_agent.agent.exception.ExamNotFoundException;
import org.linxing.linxing_agent.agent.exception.ExamParseException;
import org.linxing.linxing_agent.agent.exception.ExamValidationException;
import org.linxing.linxing_agent.agent.mapper.ExamAnswerMapper;
import org.linxing.linxing_agent.agent.mapper.ExamContextMapper;
import org.linxing.linxing_agent.agent.mapper.ExamMapper;
import org.linxing.linxing_agent.agent.service.IExamService;
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
public class ExamService implements IExamService {

    private final ExamMapper examMapper;
    private final ExamContextMapper examContextMapper;
    private final ExamAnswerMapper examAnswerMapper;
    private final ObjectMapper objectMapper;

    private static final Set<String> VALID_QUESTION_TYPES = Set.of(
            "single_choice", "multi_choice", "fill_blank", "true_false", "short_answer"
    );

    /**
     * 获取测验详情（含试题列表，不含答案）
     */
    @Override
    public ExamDetailVO getExam(Integer userId, Integer examId) {
        Exam exam = examMapper.selectById(userId, examId);//先通过userId+examId的组合获取exam的原信息
        if (exam == null) {
            throw new ExamNotFoundException("测验不存在或无权访问: " + examId);
        }

        List<ExamContext> contexts = examContextMapper.selectByExamId(examId);//获取具体内容
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
        List<Exam> exams = examMapper.selectByUserId(userId, status, offset, size);//根据id查找该用户的特定分页下的exam对象
        int total = examMapper.countByUserId(userId, status);

        //转为VO返回
        List<ExamVO> vos = exams.stream()
                .map(this::toExamVO)
                .collect(Collectors.toList());

        return PageResult.of(vos, total, page, size);
    }

    /**
     * 提交试卷，判分，更新 exams.status 为 completed
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
    @Override
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


    /**
     * 校验策略：控制校验失败时的行为
     */
    public enum ValidationStrategy {
        /** 遇到第一个错误即抛 ExamParseException */
        FAIL_FAST,
        /** 收集所有错误后抛 ExamValidationException */
        COLLECT_ALL
    }

    /**
     * 统一校验入口：校验测验 JSON 的 metadata 和 questions 数组。
     * 根据 strategy 决定错误处理方式：
     * - FAIL_FAST：遇到第一个错误即抛 ExamParseException
     * - COLLECT_ALL：收集所有错误后抛 ExamValidationException
     *
     * @return 校验通过返回空列表；FAIL_FAST 模式下不会返回错误（直接抛异常）
     */
    public List<QuestionError> validateExamJson(JsonNode root, ValidationStrategy strategy) {
        List<QuestionError> errors = new ArrayList<>();

        // --- metadata 校验 ---
        if (!root.has("title") || root.get("title").asText().isBlank()) {
            errors.add(new QuestionError(-1, "title", "缺少必填字段: title"));
            if (strategy == ValidationStrategy.FAIL_FAST) {
                throw new ExamParseException(errors.get(0).getMessage());
            }
        }

        if (!root.has("questions") || !root.get("questions").isArray()) {
            errors.add(new QuestionError(-1, "questions", "缺少必填字段: questions（数组）"));
            if (strategy == ValidationStrategy.FAIL_FAST) {
                throw new ExamParseException(errors.get(0).getMessage());
            }
            return errors; // 无 questions 则无法继续逐元素校验
        }

        ArrayNode questions = (ArrayNode) root.get("questions");
        if (questions.isEmpty()) {
            errors.add(new QuestionError(-1, "questions", "questions 数组不能为空"));
            if (strategy == ValidationStrategy.FAIL_FAST) {
                throw new ExamParseException(errors.get(0).getMessage());
            }
            return errors;
        }

        // --- 逐元素校验 ---
        for (int i = 0; i < questions.size(); i++) {
            List<QuestionError> qErrors = validateSingleQuestion(i, questions.get(i));
            errors.addAll(qErrors);
            if (strategy == ValidationStrategy.FAIL_FAST && !qErrors.isEmpty()) {
                throw new ExamParseException(
                        String.format("第 %d 题校验失败: %s", i + 1, qErrors.get(0).getMessage()));
            }
        }

        if (strategy == ValidationStrategy.COLLECT_ALL && !errors.isEmpty()) {
            throw new ExamValidationException(errors);
        }

        return errors;
    }

    /**
     * 单题校验：检查 type、stem、answer、options 等字段。
     * 提取为独立方法，消除一次性模式与分批模式的重复校验逻辑。
     */
    private List<QuestionError> validateSingleQuestion(int index, JsonNode q) {
        List<QuestionError> errors = new ArrayList<>();

        String type = q.has("type") ? q.get("type").asText() : null;
        if (type == null || !VALID_QUESTION_TYPES.contains(type)) {
            errors.add(new QuestionError(index, "type",
                    "非法题型: " + type + "，仅限 single_choice/multi_choice/fill_blank/true_false/short_answer"));
        }

        if (!q.has("stem") || q.get("stem").asText().isBlank()) {
            errors.add(new QuestionError(index, "stem", "缺少必填字段: stem"));
        }

        if (!q.has("answer") || isAnswerBlank(q.get("answer"))) {
            errors.add(new QuestionError(index, "answer", "缺少必填字段: answer"));
        } else if ("multi_choice".equals(type) && !q.get("answer").isArray()) {
            errors.add(new QuestionError(index, "answer",
                    "multi_choice 的 answer 应为数组，如 [\"A. 冒泡排序\",\"C. 归并排序\"]"));
        } else if ("fill_blank".equals(type) && q.get("answer").isArray()) {
            // fill_blank 允许数组（多空填空），无需额外校验
        } else if (!"multi_choice".equals(type) && !"fill_blank".equals(type) && q.get("answer").isArray()) {
            errors.add(new QuestionError(index, "answer",
                    "该题型的 answer 应为字符串，当前为数组"));
        }

        // 选择题必须有 options
        if (("single_choice".equals(type) || "multi_choice".equals(type))
                && (!q.has("options") || !q.get("options").isArray() || q.get("options").isEmpty())) {
            errors.add(new QuestionError(index, "options",
                    "选择题必须有 options 数组"));
        }

        // 校验 answer 与 options 一致性（单选/多选）以及判断题取值
        if ("single_choice".equals(type) && q.has("answer") && q.get("answer").isTextual()
                && q.has("options") && q.get("options").isArray()) {
            Set<String> optionSet = new HashSet<>();
            q.get("options").forEach(o -> optionSet.add(o.asText()));
            if (!optionSet.contains(q.get("answer").asText())) {
                errors.add(new QuestionError(index, "answer",
                        "单选题 answer 必须与 options 中的某一项完全一致（含字母前缀和文本）"));
            }
        }
        if ("multi_choice".equals(type) && q.has("answer") && q.get("answer").isArray()
                && q.has("options") && q.get("options").isArray()) {
            Set<String> optionSet = new HashSet<>();
            q.get("options").forEach(o -> optionSet.add(o.asText()));
            for (JsonNode ans : q.get("answer")) {
                if (!optionSet.contains(ans.asText())) {
                    errors.add(new QuestionError(index, "answer",
                            "多选题 answer 的每个元素都必须与 options 中的某一项完全一致（含字母前缀和文本）"));
                    break;
                }
            }
        }
        if ("true_false".equals(type) && q.has("answer") && q.get("answer").isTextual()) {
            String ans = q.get("answer").asText();
            if (!"正确".equals(ans) && !"错误".equals(ans)) {
                errors.add(new QuestionError(index, "answer",
                        "判断题 answer 必须为 \"正确\" 或 \"错误\""));
            }
        }

        return errors;
    }

    /**
     * 一次性生成试题：解析 JSON + 校验（fail-fast）+ 持久化
     */
    @Transactional
    public Integer parseAndSave(Integer userId, String json) {
        JsonNode root;
        try {
            root = objectMapper.readTree(json);
        } catch (Exception e) {
            throw new ExamParseException("JSON 解析失败: " + e.getMessage(), e);
        }

        return parseAndSave(userId, root, ValidationStrategy.FAIL_FAST);
    }

    /**
     * 解析 + 校验 + 持久化（JsonNode 重载），默认 fail-fast 策略
     */
    @Transactional
    public Integer parseAndSave(Integer userId, JsonNode root) {
        return parseAndSave(userId, root, ValidationStrategy.FAIL_FAST);
    }

    /**
     * 解析 + 校验 + 持久化（指定校验策略）
     * 一次性模式使用 FAIL_FAST，分批模式使用 COLLECT_ALL
     */
    @Transactional
    public Integer parseAndSave(Integer userId, JsonNode root, ValidationStrategy strategy) {
        validateExamJson(root, strategy);
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
}
