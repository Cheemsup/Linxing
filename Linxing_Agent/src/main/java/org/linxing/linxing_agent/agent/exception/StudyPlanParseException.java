package org.linxing.linxing_agent.agent.exception;

/**
 * 学习计划 JSON 解析异常
 */
public class StudyPlanParseException extends RuntimeException {
    public StudyPlanParseException(String message) {
        super(message);
    }

    public StudyPlanParseException(String message, Throwable cause) {
        super(message, cause);
    }
}
