package com.hospital.core.platform.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 审计日志注解。标注在需要记录审计日志的 Controller 方法上,
 * 由 {@code AuditLogAspect} 在方法执行成功后自动落库。
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface AuditLog {

    /** 操作类型,如 CREATE_VISIT / PAY_CHARGE / BOOK_APPOINTMENT。 */
    String action();

    /** 操作明细(可选),如 "强制作废未执行医嘱"。留空则不记录。 */
    String detail() default "";
}
