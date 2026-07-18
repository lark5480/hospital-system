package com.hospital.core.platform.api;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.Map;

/**
 * 全局异常处理:统一把业务冲突(IllegalStateException,如未缴费拦截、处方/申请已处理)
 * 映射为 409,把资源不存在(IllegalArgumentException,如实体找不到)映射为 404。
 *
 * 各 controller 仍可声明局部 {@code @ExceptionHandler} 覆盖此处(局部优先级更高),
 * 但本类作为兜底,保证任何未声明局部处理器的端点也能返回正确的语义化状态码,
 * 避免业务异常被 Spring Security / 容器翻译成 403 或 500。
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    /** 资源不存在(如处方/医嘱/患者找不到) → 404。 */
    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, String>> handleNotFound(IllegalArgumentException ex) {
        return ResponseEntity.status(404).body(Map.of("message", ex.getMessage()));
    }

    /** 状态冲突(如未缴费拦截、处方已发药) → 409,与 VisitController 保持一致。 */
    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<Map<String, String>> handleConflict(IllegalStateException ex) {
        return ResponseEntity.status(409).body(Map.of("message", ex.getMessage()));
    }
}
