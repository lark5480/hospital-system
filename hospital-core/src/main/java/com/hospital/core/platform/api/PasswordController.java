package com.hospital.core.platform.api;

import java.util.Locale;
import java.util.Map;
import java.util.Set;

import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import com.hospital.core.platform.annotation.AuditLog;
import com.hospital.core.platform.config.DataInitializer;
import com.hospital.core.platform.domain.SysUser;
import com.hospital.core.platform.infrastructure.SysUserMapper;
import com.hospital.core.platform.security.LoginAttemptService;
import com.hospital.core.platform.security.TokenRevocationService;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;

@Tag(name = "平台功能", description = "密码修改与重置")
@RestController
@RequiredArgsConstructor
public class PasswordController {

    /** R-10: 新密码最小长度。 */
    private static final int MIN_PASSWORD_LENGTH = 8;

    /** R-10: 新密码最大长度(避免超长明文带来额外开销)。 */
    private static final int MAX_PASSWORD_LENGTH = 64;

    /** R-10: 常见弱口令黑名单(小写比对)。 */
    private static final Set<String> WEAK_PASSWORDS = Set.of(
            "123456", "1234567", "12345678", "123456789", "1234567890",
            "password", "password1", "passw0rd", "abc12345", "abc123456",
            "11111111", "00000000", "88888888", "a1234567", "qwerty123",
            "iloveyou", "admin123", "administrator", "letmein", "welcome1");

    private final SysUserMapper sysUserMapper;
    private final PasswordEncoder passwordEncoder;
    /** R-10: 改密成功后用于清零登录失败计数 / 解锁账号。 */
    private final LoginAttemptService loginAttemptService;
    /** R-34: 改密 / 重置后吊销旧 token,否则旧会话在过期前仍可用。 */
    private final TokenRevocationService tokenRevocationService;

    @AuditLog(action = "CHANGE_PASSWORD")
    @Operation(summary = "修改当前用户密码")
    @PostMapping("/api/auth/password/change")
    public ResponseEntity<?> changePassword(@RequestBody Map<String, String> body) {
        String oldPassword = body == null ? "" : body.getOrDefault("oldPassword", "");
        String newPassword = body == null ? "" : body.getOrDefault("newPassword", "");

        String phone = currentPhone();
        if (phone == null) {
            return ResponseEntity.status(401).body(Map.of("error", "未登录"));
        }

        SysUser user = sysUserMapper.findByPhone(phone);
        if (user == null) {
            return ResponseEntity.status(404).body(Map.of("error", "用户不存在"));
        }

        // R-10: 新密码复杂度校验(长度 8~64、必须同时含字母和数字、非弱口令、不得与旧密码相同)
        String rejectReason = validateNewPassword(newPassword, oldPassword, user.getPassword());
        if (rejectReason != null) {
            return ResponseEntity.badRequest().body(Map.of("error", rejectReason));
        }

        // R-10: 删除"旧密码为空则跳过校验"的分支 —— 该分支与 AuthService 的免密分支是一对后门;
        // 旧密码为空时同样必须校验(空串自然无法匹配 BCrypt,会被拒绝)。
        if (oldPassword.isBlank() || user.getPassword() == null
                || !passwordEncoder.matches(oldPassword, user.getPassword())) {
            return ResponseEntity.status(400).body(Map.of("error", "旧密码错误"));
        }

        user.setPassword(passwordEncoder.encode(newPassword));
        sysUserMapper.updateById(user);
        // R-10: 改密成功 → 清零失败计数并解除锁定
        loginAttemptService.onSuccess(phone);
        // R-34: 吊销该用户全部旧 token —— 不改密后旧会话(含攻击者持有的)在过期前仍可用
        tokenRevocationService.revokeUser(phone);
        return ResponseEntity.ok(Map.of("message", "密码已修改,请使用新密码重新登录"));
    }

    @Operation(summary = "管理员重置用户密码")
    @PostMapping("/api/core/iam/users/{id}/reset-password")
    @PreAuthorize("hasAuthority('system:admin')")
    public ResponseEntity<?> resetPassword(@Parameter(description = "用户ID") @PathVariable Long id) {
        SysUser user = sysUserMapper.selectById(id);
        if (user == null) {
            return ResponseEntity.status(404).body(Map.of("error", "用户不存在"));
        }
        user.setPassword(passwordEncoder.encode(DataInitializer.DEFAULT_PASSWORD));
        sysUserMapper.updateById(user);
        // R-10: 重置后解锁(用户可能已被连续失败锁定,管理员重置即视为新的起点)
        loginAttemptService.onSuccess(user.getPhone());
        // R-34: 重置密码同样要踢掉该用户已有会话
        tokenRevocationService.revokeUser(user.getPhone());
        return ResponseEntity.ok(Map.of("message", "密码已重置为默认(123456),该用户已强制下线,请提醒其尽快修改"));
    }

    /**
     * R-10: 新密码策略校验。
     *
     * @param newPassword 新密码明文
     * @param oldPassword 用户提交的旧密码明文(用于"不得与旧密码相同")
     * @param storedHash  库中保存的旧密码哈希(用于"不得与旧密码相同")
     * @return 校验通过返回 null;否则返回中文拒绝原因
     */
    private String validateNewPassword(String newPassword, String oldPassword, String storedHash) {
        if (newPassword == null || newPassword.isBlank()) {
            return "新密码不能为空";
        }
        if (newPassword.length() < MIN_PASSWORD_LENGTH || newPassword.length() > MAX_PASSWORD_LENGTH) {
            return "新密码长度需为 " + MIN_PASSWORD_LENGTH + "~" + MAX_PASSWORD_LENGTH + " 位";
        }
        boolean hasLetter = newPassword.chars().anyMatch(Character::isLetter);
        boolean hasDigit = newPassword.chars().anyMatch(Character::isDigit);
        if (!hasLetter || !hasDigit) {
            return "新密码必须同时包含字母和数字";
        }
        String lower = newPassword.toLowerCase(Locale.ROOT);
        if (WEAK_PASSWORDS.contains(lower) || lower.equals(DataInitializer.DEFAULT_PASSWORD)) {
            return "新密码过于简单,属于常见弱口令,请更换";
        }
        if (newPassword.equals(oldPassword)) {
            return "新密码不能与旧密码相同";
        }
        // 与库中旧密码哈希比对,覆盖"新密码恰好等于当前密码"的情况
        if (storedHash != null && passwordEncoder.matches(newPassword, storedHash)) {
            return "新密码不能与旧密码相同";
        }
        return null;
    }

    private String currentPhone() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        return auth != null ? auth.getName() : null;
    }
}
