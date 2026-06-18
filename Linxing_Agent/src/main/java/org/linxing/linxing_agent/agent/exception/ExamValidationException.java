package org.linxing.linxing_agent.agent.exception;

import lombok.Getter;
import org.linxing.linxing_agent.agent.dto.QuestionError;

import java.util.List;

@Getter
public class ExamValidationException extends RuntimeException {
    private final List<QuestionError> errors;

    public ExamValidationException(List<QuestionError> errors) {
        super("测验校验失败");
        this.errors = errors;
    }
}
