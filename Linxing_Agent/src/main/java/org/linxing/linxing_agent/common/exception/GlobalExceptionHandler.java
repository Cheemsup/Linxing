package org.linxing.linxing_agent.common.exception;

import lombok.extern.slf4j.Slf4j;
import org.linxing.linxing_agent.agent.exception.ExamNotFoundException;
import org.linxing.linxing_agent.agent.exception.ExamParseException;
import org.linxing.linxing_agent.agent.exception.ExamValidationException;
import org.linxing.linxing_agent.agent.memory.longterm.workspace.MemoryAccessException;
import org.linxing.linxing_agent.common.result.Result;
import org.linxing.linxing_agent.user.exception.AccountDisabledException;
import org.linxing.linxing_agent.user.exception.AccountNotFoundException;
import org.linxing.linxing_agent.user.exception.PasswordIncorrectException;
import org.linxing.linxing_agent.user.exception.UsernameDuplicateException;
import org.springframework.dao.DataAccessException;
import org.springframework.transaction.TransactionSystemException;
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

    @ExceptionHandler(ExamNotFoundException.class)
    public Result<String> handleExamNotFound(ExamNotFoundException ex) {
        log.warn("测验不存在: {}", ex.getMessage());
        return Result.error(ex.getMessage());
    }

    @ExceptionHandler(ExamParseException.class)
    public Result<String> handleExamParse(ExamParseException ex) {
        log.warn("测验解析失败: {}", ex.getMessage());
        return Result.error(ex.getMessage());
    }

    @ExceptionHandler(ExamValidationException.class)
    public Result<Map<String, Object>> handleExamValidation(ExamValidationException ex) {
        log.warn("测验校验失败: {}", ex.getMessage());
        Map<String, Object> response = new HashMap<>();
        response.put("errors", ex.getErrors());
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

    @ExceptionHandler(MemoryAccessException.class)
    public Result<Void> handleMemoryAccess(MemoryAccessException ex) {
        log.warn("Memory 访问异常: {}", ex.getMessage());
        return Result.error(ex.getMessage());
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public Result<Void> handleIllegalArgument(IllegalArgumentException ex) {
        log.warn("非法参数异常: {}", ex.getMessage());
        return Result.error(ex.getMessage());
    }

    /**
     * 数据库访问异常（含 PostgreSQL 25P02 事务中止、约束冲突、连接中断等）。
     * 原始 SQL 报错文本不应直接暴露给前端（泄露 SQL 细节/内部结构），统一降级为安全消息；
     * 完整异常堆栈保留在服务端日志用于排查。
     */
    @ExceptionHandler(DataAccessException.class)
    public Result<Void> handleDataAccess(DataAccessException ex) {
        log.error("数据库访问异常: {}", ex.getMessage(), ex);
        return Result.error("数据处理失败，请稍后重试");
    }

    /**
     * 事务边界异常：@Transactional 方法在提交/回滚阶段失败（如被中止事务上继续执行 SQL 触发的 25P02）。
     * 与 DataAccessException 分开处理，便于按异常类型区分日志；同样不向前端泄露原始消息。
     */
    @ExceptionHandler(TransactionSystemException.class)
    public Result<Void> handleTransactionSystem(TransactionSystemException ex) {
        log.error("事务处理异常: {}", ex.getMessage(), ex);
        return Result.error("事务处理失败，请稍后重试");
    }

    @ExceptionHandler(RuntimeException.class)
    public Result<Void> handleRuntimeException(RuntimeException ex) {
        log.error("运行时异常: {}", ex.getMessage(), ex);
        return Result.error("系统处理请求时出现错误，请稍后重试");
    }

    @ExceptionHandler(Exception.class)
    public Result<Void> handleGeneralException(Exception ex) {
        log.error("系统异常: {}", ex.getMessage(), ex);
        return Result.error("系统暂时无法处理您的请求");
    }
}
