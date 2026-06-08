package org.linxing.linxing_agent.agent.vo;

import lombok.Builder;
import lombok.Data;

import java.util.List;

@Data
@Builder
public class ExamSubmitVO {
    private Integer examId;
    private Integer score;
    private Integer total;
    private Integer correctCount;
    private List<AnswerResultItem> details;

    @Data
    @Builder
    public static class AnswerResultItem {
        private Integer questionId;
        private String questionType;
        private String userAnswer;
        private String correctAnswer;
        private Boolean correct;
        private String explanation;
    }
}
