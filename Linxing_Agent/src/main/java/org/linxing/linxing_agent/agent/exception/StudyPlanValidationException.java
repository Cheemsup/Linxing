package org.linxing.linxing_agent.agent.exception;

import lombok.Getter;
import org.linxing.linxing_agent.agent.dto.QuestionError;

import java.util.List;

/**
 * 学习计划校验异常（收集所有错误后抛出）
 */
@Getter
public class StudyPlanValidationException extends RuntimeException {
    private final List<QuestionError> errors;

    public StudyPlanValidationException(List<QuestionError> errors) {
        super("学习计划校验失败");
        this.errors = errors;
    }
}
