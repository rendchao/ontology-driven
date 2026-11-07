package com.jjy.infrastructure.web;

import com.jjy.infrastructure.exception.BizException;
import com.jjy.infrastructure.exception.NotFoundException;
import com.jjy.infrastructure.exception.RuleViolationException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.stereotype.Component;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.validation.BindException;
import org.springframework.validation.FieldError;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.NoHandlerFoundException;

import javax.servlet.http.HttpServletRequest;
import javax.validation.ConstraintViolation;
import javax.validation.ConstraintViolationException;
import java.util.stream.Collectors;

/**
 * 全局异常处理
 *
 * @author jjy-base
 */
@Slf4j
@Component("infraGlobalExceptionHandler")
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(BizException.class)
    public R<?> handleBiz(BizException e, HttpServletRequest req) {
        log.warn("[BizException] {} {} -> {}", req.getMethod(), req.getRequestURI(), e.getMessage());
        return R.fail(e.getCode(), e.getMessage());
    }

    @ExceptionHandler(NotFoundException.class)
    public R<?> handleNotFound(NotFoundException e, HttpServletRequest req) {
        log.warn("[NotFound] {} {} -> {}", req.getMethod(), req.getRequestURI(), e.getMessage());
        return R.fail(R.CODE_NOT_FOUND, e.getMessage());
    }

    @ExceptionHandler(RuleViolationException.class)
    public R<?> handleRule(RuleViolationException e, HttpServletRequest req) {
        log.warn("[RuleViolation] rule={} {} {} -> {}",
                e.getRuleId(), req.getMethod(), req.getRequestURI(), e.getMessage());
        return R.fail(e.getCode(), e.getMessage());
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public R<?> handleValid(MethodArgumentNotValidException e) {
        String msg = e.getBindingResult().getFieldErrors().stream()
                .map(fe -> fe.getField() + ": " + fe.getDefaultMessage())
                .collect(Collectors.joining("; "));
        return R.fail(R.CODE_BAD_REQUEST, msg.isEmpty() ? "参数校验失败" : msg);
    }

    @ExceptionHandler(BindException.class)
    public R<?> handleBind(BindException e) {
        String msg = e.getBindingResult().getFieldErrors().stream()
                .map(FieldError::getDefaultMessage)
                .collect(Collectors.joining("; "));
        return R.fail(R.CODE_BAD_REQUEST, msg.isEmpty() ? "参数绑定失败" : msg);
    }

    @ExceptionHandler(ConstraintViolationException.class)
    public R<?> handleConstraint(ConstraintViolationException e) {
        String msg = e.getConstraintViolations().stream()
                .map(ConstraintViolation::getMessage)
                .collect(Collectors.joining("; "));
        return R.fail(R.CODE_BAD_REQUEST, msg.isEmpty() ? "参数约束违反" : msg);
    }

    @ExceptionHandler(MissingServletRequestParameterException.class)
    public R<?> handleMissing(MissingServletRequestParameterException e) {
        return R.fail(R.CODE_BAD_REQUEST, "缺少参数: " + e.getParameterName());
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public R<?> handleNotReadable(HttpMessageNotReadableException e) {
        return R.fail(R.CODE_BAD_REQUEST, "请求体不可读: " + e.getMostSpecificCause().getMessage());
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public R<?> handleMethodNotSupported(HttpRequestMethodNotSupportedException e) {
        return R.fail(R.CODE_BAD_REQUEST, "不支持的 HTTP 方法: " + e.getMethod());
    }

    @ExceptionHandler(NoHandlerFoundException.class)
    public R<?> handleNoHandler(NoHandlerFoundException e) {
        return R.fail(R.CODE_NOT_FOUND, "接口不存在: " + e.getRequestURL());
    }

    @ExceptionHandler(AccessDeniedException.class)
    public R<?> handleAccessDenied(AccessDeniedException e) {
        return R.fail(R.CODE_FORBIDDEN, "无权访问: " + e.getMessage());
    }

    @ExceptionHandler(AuthenticationException.class)
    public R<?> handleAuth(AuthenticationException e) {
        return R.fail(R.CODE_UNAUTHORIZED, "认证失败: " + e.getMessage());
    }

    @ExceptionHandler(BadCredentialsException.class)
    public R<?> handleBadCreds(BadCredentialsException e) {
        return R.fail(R.CODE_UNAUTHORIZED, "用户名或密码错误");
    }

    @ExceptionHandler(org.springframework.dao.DataIntegrityViolationException.class)
    public R<?> handleDataIntegrity(org.springframework.dao.DataIntegrityViolationException e, HttpServletRequest req) {
        Throwable cause = e.getCause();
        String detail = cause != null ? cause.getMessage() : e.getMessage();
        log.error("[DataIntegrity] {} {} -> {}", req.getMethod(), req.getRequestURI(), detail, e);
        return R.fail(R.CODE_BAD_REQUEST, "数据约束违反: " + (detail != null ? detail.split("\n")[0] : "未知"));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public R<?> handleIllegalArg(IllegalArgumentException e, HttpServletRequest req) {
        log.warn("[IllegalArg] {} {} -> {}", req.getMethod(), req.getRequestURI(), e.getMessage());
        return R.fail(R.CODE_BAD_REQUEST, "参数非法: " + e.getMessage());
    }

    @ExceptionHandler(Exception.class)
    public R<?> handleAny(Exception e, HttpServletRequest req) {
        log.error("[Unhandled] {} {}", req.getMethod(), req.getRequestURI(), e);
        Throwable cause = e.getCause();
        String msg = cause != null ? cause.getMessage() : e.getMessage();
        return R.fail(R.CODE_INTERNAL, "系统异常: " + (msg != null && !msg.isEmpty() ? e.getClass().getSimpleName() + ": " + msg.split("\n")[0] : e.getClass().getSimpleName()));
    }
}
