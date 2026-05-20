package org.linxing.linxing_agent.common.exception;

import lombok.extern.slf4j.Slf4j;
import org.linxing.linxing_agent.rag.dto.ChatResponse;
import org.linxing.linxing_agent.common.result.Result;
import org.linxing.linxing_agent.user.exception.AccountDisabledException;
import org.linxing.linxing_agent.user.exception.AccountNotFoundException;
import org.linxing.linxing_agent.user.exception.PasswordIncorrectException;
import org.linxing.linxing_agent.user.exception.UsernameDuplicateException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.HashMap;
import java.util.Map;

@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(AccountNotFoundException.class)
    public Result<String> handleAccountNotFound(AccountNotFoundException ex) {
        log.warn("账户不存在: {}", ex.getMessage());
        return Result.error(ex.getMessage());
    }

    @ExceptionHandler(PasswordIncorrectException.class)
    public Result<String> handlePasswordIncorrect(PasswordIncorrectException ex) {
        log.warn("密码错误: {}", ex.getMessage());
        return Result.error(ex.getMessage());
    }

    @ExceptionHandler(AccountDisabledException.class)
    public Result<String> handleAccountDisabled(AccountDisabledException ex) {
        log.warn("账户已禁用: {}", ex.getMessage());
        return Result.error(ex.getMessage());
    }

    @ExceptionHandler(UsernameDuplicateException.class)
    public Result<String> handleUsernameDuplicate(UsernameDuplicateException ex) {
        log.warn("用户名已存在: {}", ex.getMessage());
        return Result.error(ex.getMessage());
    }

    @ExceptionHandler(AuthenticationException.class)
    public Result<String> handleAuthentication(AuthenticationException ex) {
        log.warn("认证失败: {}", ex.getMessage());
        return Result.error(ex.getMessage());
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public Result<Map<String, String>> handleValidationExceptions(MethodArgumentNotValidException ex) {
        Map<String, String> errors = new HashMap<>();
        ex.getBindingResult().getAllErrors().forEach(error -> {
            String fieldName = ((FieldError) error).getField();
            String errorMessage = error.getDefaultMessage();
            errors.put(fieldName, errorMessage);
        });
        log.warn("请求参数校验失败: {}", errors);
        return Result.error("参数校验失败: " + errors);
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public Result<Void> handleIllegalArgument(IllegalArgumentException ex) {
        log.warn("非法参数异常: {}", ex.getMessage());
        return Result.error(ex.getMessage());
    }

    @ExceptionHandler(RuntimeException.class)
    public Result<ChatResponse> handleRuntimeException(RuntimeException ex) {
        log.error("运行时异常: {}", ex.getMessage(), ex);
        ChatResponse errorResponse = ChatResponse.builder()
                .answer("抱歉，系统处理请求时出现错误，请稍后重试。")
                .sources(java.util.List.of())
                .build();
        return Result.error("系统处理请求时出现错误: " + ex.getMessage());
    }

    @ExceptionHandler(Exception.class)
    public Result<ChatResponse> handleGeneralException(Exception ex) {
        log.error("系统异常: {}", ex.getMessage(), ex);
        ChatResponse errorResponse = ChatResponse.builder()
                .answer("抱歉，系统暂时无法处理您的请求，请稍后重试。")
                .sources(java.util.List.of())
                .build();
        return Result.error("系统暂时无法处理您的请求");
    }
}
