package com.hospital.core.org.application;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import com.hospital.core.platform.config.DataInitializer;
import com.hospital.core.platform.domain.SysUser;
import com.hospital.core.platform.infrastructure.JwtTokenService;
import com.hospital.core.platform.infrastructure.RoleAuthorityMapper;
import com.hospital.core.platform.infrastructure.SysUserMapper;
import com.hospital.core.platform.infrastructure.SysUserRoleMapper;
import com.hospital.core.platform.security.LoginAttemptService;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 统一认证服务:手机号 + 密码 → 查 sys_user → 多角色 → 权限并集 → 签发 JWT。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AuthService {

    private final SysUserMapper sysUserMapper;
    private final SysUserRoleMapper sysUserRoleMapper;
    private final RoleAuthorityMapper roleAuthorityMapper;
    private final JwtTokenService jwtTokenService;
    private final PasswordEncoder passwordEncoder;
    private final StaffService staffService;
    /** R-10/R-12: 登录失败计数 / 账号锁定 / IP 限流(构造注入)。 */
    private final LoginAttemptService loginAttemptService;

    /**
     * 登录:校验手机号 + 密码 → 查多角色 → 权限并集 → 签发 JWT。
     *
     * @param phone    手机号(登录账号)
     * @param password 明文密码
     * @return token 信息;用户不存在、密码错误、账号锁定或触发限流时返回 null
     */
    public LoginResult login(String phone, String password) {
        String clientIp = loginAttemptService.currentClientIp();

        // R-10: 账号已锁定 → 直接拒绝,不再查库、不再比对密码
        if (loginAttemptService.isLocked(phone)) {
            log.warn("[AuthService] 账号已锁定,拒绝登录: phone={}, ip={}", maskPhone(phone), clientIp);
            return null;
        }
        // R-12: 来源 IP 触发粗粒度限流(撞库防护)
        if (loginAttemptService.isIpBlocked(clientIp)) {
            log.warn("[AuthService] 来源 IP 登录失败过多,已限流: ip={}", clientIp);
            return null;
        }

        SysUser user = sysUserMapper.findByPhone(phone);
        if (user == null) {
            // R-12: 只计 IP,不按手机号计数 —— 否则攻击者可用任意不存在/他人手机号把账号锁死
            loginAttemptService.onIpFailure(clientIp);
            log.debug("[AuthService] 用户不存在: phone={}", maskPhone(phone));
            return null;
        }

        // R-57: 删除"密码为 NULL 即免密放行"的后门分支。
        // 密码为空 / 账号未设置密码 / 比对失败,一律按认证失败处理并计入失败次数。
        if (password == null || password.isBlank()
                || user.getPassword() == null || user.getPassword().isBlank()
                || !passwordEncoder.matches(password, user.getPassword())) {
            log.warn("[AuthService] 登录失败(密码错误或账号未设置密码): userId={}, phone={}, ip={}",
                    user.getId(), maskPhone(phone), clientIp);
            loginAttemptService.onFailure(phone, clientIp);
            return null;
        }

        // 多角色
        Set<String> roleSet = sysUserRoleMapper.findRoleCodesByUserId(user.getId());
        List<String> roles = new ArrayList<>(roleSet);
        if (roles.isEmpty()) {
            roles = List.of("PATIENT");  // 保底角色
        }

        // 多角色权限并集
        Set<String> authSet = roleAuthorityMapper.findAuthoritiesByRoles(roles);
        List<String> authorities = new ArrayList<>(authSet);

        // 所属科室:经 staff.user_id 外键解析(患者/C 端无 staff 行则为 null)
        String department = staffService.findDepartmentNameByUserId(user.getId());
        Long departmentId = staffService.findDepartmentIdByUserId(user.getId());

        // R-10: 仍在用初始弱口令 → 前端应强制引导改密
        boolean mustChangePassword =
                passwordEncoder.matches(DataInitializer.DEFAULT_PASSWORD, user.getPassword());

        String token = jwtTokenService.issue(user.getPhone(), roles, authorities);
        // R-10: 登录成功清零失败计数;日志不打印手机号明文
        loginAttemptService.onSuccess(phone);
        log.info("[AuthService] 登录成功: userId={}, phone={}, roles={}, dept={}, deptId={}, mustChangePassword={}",
                user.getId(), maskPhone(phone), roles, department, departmentId, mustChangePassword);
        return new LoginResult(token, user.getPhone(), user.getName(), roles.get(0), roles, authorities,
                department, departmentId, mustChangePassword);
    }

    /** R-10: 供 Controller 区分「密码错误 401」与「账号锁定 429」。 */
    public boolean isLocked(String phone) {
        return loginAttemptService.isLocked(phone);
    }

    /** R-12: 供 Controller 判断是否命中 IP 限流。 */
    public boolean isIpBlocked(String ip) {
        return loginAttemptService.isIpBlocked(ip);
    }

    /** 手机号掩码:保留前 3 后 2,其余打码(避免日志落明文 PII)。 */
    private String maskPhone(String phone) {
        if (phone == null || phone.isBlank()) return "***";
        if (phone.length() <= 5) return "***";
        return phone.substring(0, 3) + "****" + phone.substring(phone.length() - 2);
    }

    /** 登录响应载体(仅携带必要字段)。 */
    public record LoginResult(String token, String username, String name, String position,
                              List<String> roles, List<String> authorities, String department, Long departmentId,
                              boolean mustChangePassword) {}
}
