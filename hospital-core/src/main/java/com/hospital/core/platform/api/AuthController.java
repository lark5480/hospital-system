package com.hospital.core.platform.api;

import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.hospital.core.org.application.AuthService;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;

@Tag(name = "平台功能", description = "用户登录认证")
@RestController
@RequiredArgsConstructor
public class AuthController {

    private final AuthService authService;

    @Operation(summary = "用户登录")
    @PostMapping("/api/auth/login")
    public ResponseEntity<?> login(
            @Parameter(description = "手机号") @RequestParam String phone,
            @Parameter(description = "密码") @RequestParam String password) {
        AuthService.LoginResult result = authService.login(phone, password);
        if (result == null) {
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
        return ResponseEntity.ok(body);
    }
}
