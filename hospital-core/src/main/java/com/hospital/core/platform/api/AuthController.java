package com.hospital.core.platform.api;

import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import com.hospital.core.org.application.AuthService;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import lombok.RequiredArgsConstructor;

@Tag(name = "平台功能", description = "用户登录认证")
@RestController
@RequiredArgsConstructor
public class AuthController {

    private final AuthService authService;

    /**
     * R-12: 登录入参走 JSON 请求体,不再使用 @RequestParam。
     * 原实现把密码放在 URL query 上,会被 access log / 浏览器历史 / Referer / 网关注日志记录。
     */
    public record LoginRequest(@NotBlank(message = "手机号不能为空") String phone,
                               @NotBlank(message = "密码不能为空") String password) {}

    @Operation(summary = "用户登录")
    @PostMapping("/api/auth/login")
    public ResponseEntity<?> login(@RequestBody @Valid LoginRequest request) {
        String phone = request.phone();

        // R-10: 已处于锁定状态 → 429,提示 15 分钟后重试
        if (authService.isLocked(phone)) {
            return tooManyAttempts();
        }

        AuthService.LoginResult result = authService.login(phone, request.password());
        if (result == null) {
            // R-10: 本次失败刚好触发锁定 → 同样返回 429
            if (authService.isLocked(phone)) {
                return tooManyAttempts();
            }
            return ResponseEntity.status(401).body(Map.of("error", "手机号或密码错误"));
        }

        // 用 LinkedHashMap(允许 null 值):患者/C 端无 staff 行时 department 为 null,
        // 而 Map.of 不允许 null,会抛 NPE。
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("token", result.token());
        body.put("username", result.username());
        body.put("name", result.name());
        body.put("position", result.position());
        body.put("department", result.department());
        body.put("departmentId", result.departmentId());
        body.put("roles", result.roles());
        body.put("authorities", result.authorities());
        // R-10: 使用初始弱口令登录时提示前端强制改密
        body.put("mustChangePassword", result.mustChangePassword());
        return ResponseEntity.ok(body);
    }

    /** R-10: 账号锁定统一响应(429 + locked 标记)。 */
    private ResponseEntity<Map<String, Object>> tooManyAttempts() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("error", "登录失败次数过多,账号已锁定,请 15 分钟后重试");
        body.put("locked", true);
        return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS).body(body);
    }
}
