package org.linxing.linxing_agent.agent.exception;

/**
 * 学习计划不存在异常
 */
public class StudyPlanNotFoundException extends RuntimeException {
    public StudyPlanNotFoundException(String message) {
        super(message);
    }
}
