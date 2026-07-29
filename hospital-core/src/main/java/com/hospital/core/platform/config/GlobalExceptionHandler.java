package com.hospital.core.platform.config;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.NoHandlerFoundException;

import jakarta.validation.ConstraintViolationException;

/**
 * 全局异常处理器：统一把各类异常映射为语义化 HTTP 状态码 + 标准 JSON 错误体。
 *
 * <p>各 controller 仍可声明局部 {@code @ExceptionHandler} 覆盖此处（局部优先级更高），
 * 但本类作为兜底，保证任何未声明局部处理器的端点也能返回一致的错误格式，
 * 避免业务异常被 Spring Security / 容器翻译成 403 或 500。</p>
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    /** 权限不足 → 403。 */
    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<Map<String, Object>> handleAccessDenied(AccessDeniedException e) {
        return error(HttpStatus.FORBIDDEN, "权限不足", e.getMessage());
    }

    /** 认证失败（手机号或密码错误） → 401。 */
    @ExceptionHandler(BadCredentialsException.class)
    public ResponseEntity<Map<String, Object>> handleBadCredentials(BadCredentialsException e) {
        return error(HttpStatus.UNAUTHORIZED, "认证失败", "手机号或密码错误");
    }

    /** 参数校验失败（@Valid） → 400。 */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Map<String, Object>> handleValidation(MethodArgumentNotValidException e) {
        String message = e.getBindingResult().getFieldErrors().stream()
                .map(f -> f.getField() + ": " + f.getDefaultMessage())
                .reduce((a, b) -> a + "; " + b)
                .orElse("参数校验失败");
        return error(HttpStatus.BAD_REQUEST, "参数校验失败", message);
    }

    /** 资源不存在（无匹配的 Handler） → 404。 */
    @ExceptionHandler(NoHandlerFoundException.class)
    public ResponseEntity<Map<String, Object>> handleNotFound(NoHandlerFoundException e) {
        return error(HttpStatus.NOT_FOUND, "资源不存在", e.getRequestURL());
    }

    /** 资源不存在（业务层抛 IllegalArgumentException） → 404。 */
    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, Object>> handleIllegalArgument(IllegalArgumentException e) {
        return error(HttpStatus.NOT_FOUND, "资源不存在", e.getMessage());
    }

    /** 业务状态冲突（如未缴费拦截、处方已发药） → 409。 */
    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<Map<String, Object>> handleIllegalState(IllegalStateException e) {
        return error(HttpStatus.CONFLICT, "业务校验失败", e.getMessage());
    }

    /** JSON 解析失败 → 400。 */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<Map<String, Object>> handleHttpMessageNotReadable(HttpMessageNotReadableException e) {
        return error(HttpStatus.BAD_REQUEST, "请求体格式错误", e.getMessage());
    }

    /** @Validated 路径/查询参数校验失败 → 400。 */
    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<Map<String, Object>> handleConstraintViolation(ConstraintViolationException e) {
        String message = e.getConstraintViolations().stream()
                .map(v -> v.getMessage())
                .reduce((a, b) -> a + "; " + b)
                .orElse("参数校验失败");
        return error(HttpStatus.BAD_REQUEST, "参数校验失败", message);
    }

    /** 路径参数类型错误 → 400。 */
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<Map<String, Object>> handleTypeMismatch(MethodArgumentTypeMismatchException e) {
        String name = e.getName();
        String requiredType = e.getRequiredType() != null ? e.getRequiredType().getSimpleName() : "unknown";
        return error(HttpStatus.BAD_REQUEST, "参数 " + name + " 类型错误，期望 " + requiredType, e.getMessage());
    }

    /** 缺少必填参数 → 400。 */
    @ExceptionHandler(MissingServletRequestParameterException.class)
    public ResponseEntity<Map<String, Object>> handleMissingParam(MissingServletRequestParameterException e) {
        return error(HttpStatus.BAD_REQUEST, "缺少必填参数: " + e.getParameterName(), e.getMessage());
    }

    /** 兜底：所有其他未预期异常 → 500。 */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, Object>> handleGeneral(Exception e) {
        return error(HttpStatus.INTERNAL_SERVER_ERROR, "服务器内部错误", "请稍后重试");
    }

    private ResponseEntity<Map<String, Object>> error(HttpStatus status, String title, String detail) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("timestamp", LocalDateTime.now().toString());
        body.put("status", status.value());
        body.put("error", title);
        body.put("message", detail);
        return ResponseEntity.status(status).body(body);
    }
}
