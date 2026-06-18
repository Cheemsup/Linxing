package org.linxing.linxing_agent.agent.exception;

public class ExamParseException extends RuntimeException {
    public ExamParseException(String message) {
        super(message);
    }
    public ExamParseException(String message, Throwable cause) {
        super(message, cause);
    }
}
