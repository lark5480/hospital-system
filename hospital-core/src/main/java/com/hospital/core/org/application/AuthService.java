package com.hospital.core.org.application;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import com.hospital.core.platform.domain.SysUser;
import com.hospital.core.platform.infrastructure.JwtTokenService;
import com.hospital.core.platform.infrastructure.RoleAuthorityMapper;
import com.hospital.core.platform.infrastructure.SysUserMapper;
import com.hospital.core.platform.infrastructure.SysUserRoleMapper;

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

    /**
     * 登录:校验手机号 + 密码 → 查多角色 → 权限并集 → 签发 JWT。
     *
     * @param phone    手机号(登录账号)
     * @param password 明文密码
     * @return token 信息;用户不存在或密码错误返回 null
     */
    public LoginResult login(String phone, String password) {
        SysUser user = sysUserMapper.findByPhone(phone);
        if (user == null) {
            log.debug("[AuthService] 用户不存在: {}", phone);
            return null;
        }

        // 密码校验:有密码则 BCrypt 比对;无密码(老数据兼容)允许免密一次
        if (user.getPassword() != null && !passwordEncoder.matches(password, user.getPassword())) {
            log.debug("[AuthService] 密码错误: {}", phone);
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

        String token = jwtTokenService.issue(user.getPhone(), roles, authorities);
        log.info("[AuthService] 登录成功: {}, roles={}, dept={}, deptId={}", phone, roles, department, departmentId);
        return new LoginResult(token, user.getPhone(), user.getName(), roles.get(0), roles, authorities, department, departmentId);
    }

    /** 登录响应载体(仅携带必要字段)。 */
    public record LoginResult(String token, String username, String name, String position,
                              List<String> roles, List<String> authorities, String department, Long departmentId) {}
}
