package com.hospital.core.platform.api;

import java.util.Map;

import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.*;

import com.hospital.core.platform.config.DataInitializer;
import com.hospital.core.platform.domain.SysUser;
import com.hospital.core.platform.infrastructure.SysUserMapper;

import lombok.RequiredArgsConstructor;

@RestController
@RequiredArgsConstructor
public class PasswordController {

    private final SysUserMapper sysUserMapper;
    private final PasswordEncoder passwordEncoder;

    @PostMapping("/api/auth/password/change")
    public ResponseEntity<?> changePassword(@RequestBody Map<String, String> body) {
        String oldPassword = body.getOrDefault("oldPassword", "");
        String newPassword = body.getOrDefault("newPassword", "");

        if (newPassword == null || newPassword.length() < 4) {
            return ResponseEntity.badRequest().body(Map.of("error", "新密码长度至少 4 位"));
        }

        String phone = currentPhone();
        if (phone == null) {
            return ResponseEntity.status(401).body(Map.of("error", "未登录"));
        }

        SysUser user = sysUserMapper.findByPhone(phone);
        if (user == null) {
            return ResponseEntity.status(404).body(Map.of("error", "用户不存在"));
        }

        if (user.getPassword() != null && !passwordEncoder.matches(oldPassword, user.getPassword())) {
            return ResponseEntity.status(400).body(Map.of("error", "旧密码错误"));
        }

        user.setPassword(passwordEncoder.encode(newPassword));
        sysUserMapper.updateById(user);
        return ResponseEntity.ok(Map.of("message", "密码已修改"));
    }

    @PostMapping("/api/core/iam/users/{id}/reset-password")
    @PreAuthorize("hasAuthority('system:admin')")
    public ResponseEntity<?> resetPassword(@PathVariable Long id) {
        SysUser user = sysUserMapper.selectById(id);
        if (user == null) {
            return ResponseEntity.status(404).body(Map.of("error", "用户不存在"));
        }
        user.setPassword(passwordEncoder.encode(DataInitializer.DEFAULT_PASSWORD));
        sysUserMapper.updateById(user);
        return ResponseEntity.ok(Map.of("message", "密码已重置为默认(123456)"));
    }

    private String currentPhone() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        return auth != null ? auth.getName() : null;
    }
}
