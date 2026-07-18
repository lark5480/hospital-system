package com.hospital.core.platform.api;

import com.hospital.core.org.application.AuthService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequiredArgsConstructor
public class AuthController {

    private final AuthService authService;

    @PostMapping("/api/auth/login")
    public ResponseEntity<?> login(@RequestParam String phone, @RequestParam String password) {
        AuthService.LoginResult result = authService.login(phone, password);
        if (result == null) {
            return ResponseEntity.status(401).body(Map.of("error", "手机号或密码错误"));
        }
        return ResponseEntity.ok(Map.of(
                "token", result.token(),
                "username", result.username(),
                "name", result.name(),
                "position", result.position(),
                "roles", result.roles(),
                "authorities", result.authorities()));
    }
}
