package com.hospital.core.org.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;

import com.hospital.core.org.application.AuthService.LoginResult;
import com.hospital.core.platform.domain.SysUser;
import com.hospital.core.platform.infrastructure.JwtTokenService;
import com.hospital.core.platform.infrastructure.RoleAuthorityMapper;
import com.hospital.core.platform.infrastructure.SysUserMapper;
import com.hospital.core.platform.infrastructure.SysUserRoleMapper;

@ExtendWith(MockitoExtension.class)
class AuthServiceTest {

    @Mock SysUserMapper sysUserMapper;
    @Mock SysUserRoleMapper sysUserRoleMapper;
    @Mock RoleAuthorityMapper roleAuthorityMapper;
    @Mock JwtTokenService jwtTokenService;
    @Mock PasswordEncoder passwordEncoder;
    @Mock StaffService staffService;

    AuthService service;

    @BeforeEach
    void setUp() {
        service = new AuthService(sysUserMapper, sysUserRoleMapper, roleAuthorityMapper,
                jwtTokenService, passwordEncoder, staffService);
    }

    @Nested
    @DisplayName("登录")
    class Login {

        @Test
        @DisplayName("手机号+密码正确 → 返回含 token 的 LoginResult")
        void login_success() {
            SysUser user = buildSysUser(1L, "13800000001", "张医生", "$2a$10$hash");
            when(sysUserMapper.findByPhone("13800000001")).thenReturn(user);
            when(passwordEncoder.matches("password123", "$2a$10$hash")).thenReturn(true);
            when(sysUserRoleMapper.findRoleCodesByUserId(1L)).thenReturn(Set.of("DOCTOR"));
            when(roleAuthorityMapper.findAuthoritiesByRoles(List.of("DOCTOR")))
                    .thenReturn(Set.of("visit:entry"));
            when(staffService.findDepartmentNameByUserId(1L)).thenReturn("内科");
            when(staffService.findDepartmentIdByUserId(1L)).thenReturn(10L);
            when(jwtTokenService.issue(eq("13800000001"), any(), any())).thenReturn("jwt-token-xxx");

            LoginResult result = service.login("13800000001", "password123");

            assertThat(result).isNotNull();
            assertThat(result.token()).isEqualTo("jwt-token-xxx");
            assertThat(result.username()).isEqualTo("13800000001");
            assertThat(result.name()).isEqualTo("张医生");
            assertThat(result.department()).isEqualTo("内科");
            assertThat(result.departmentId()).isEqualTo(10L);
            assertThat(result.roles()).contains("DOCTOR");
        }

        @Test
        @DisplayName("密码错误 → 返回 null")
        void login_wrongPassword_returnsNull() {
            SysUser user = buildSysUser(1L, "13800000002", "李医生", "$2a$10$hash");
            when(sysUserMapper.findByPhone("13800000002")).thenReturn(user);
            when(passwordEncoder.matches("wrong", "$2a$10$hash")).thenReturn(false);

            LoginResult result = service.login("13800000002", "wrong");

            assertThat(result).isNull();
        }

        @Test
        @DisplayName("用户不存在 → 返回 null")
        void login_userNotFound_returnsNull() {
            when(sysUserMapper.findByPhone("13900000000")).thenReturn(null);

            LoginResult result = service.login("13900000000", "password");

            assertThat(result).isNull();
        }

        @Test
        @DisplayName("无角色 → 保底使用 PATIENT 角色")
        void login_noRoles_defaultsToPatient() {
            SysUser user = buildSysUser(2L, "13800000003", "患者A", "$2a$10$hash");
            when(sysUserMapper.findByPhone("13800000003")).thenReturn(user);
            when(passwordEncoder.matches("pass", "$2a$10$hash")).thenReturn(true);
            when(sysUserRoleMapper.findRoleCodesByUserId(2L)).thenReturn(Set.of());
            when(roleAuthorityMapper.findAuthoritiesByRoles(List.of("PATIENT")))
                    .thenReturn(Set.of());
            when(staffService.findDepartmentNameByUserId(2L)).thenReturn(null);
            when(staffService.findDepartmentIdByUserId(2L)).thenReturn(null);
            when(jwtTokenService.issue(eq("13800000003"), any(), any())).thenReturn("jwt-token");

            LoginResult result = service.login("13800000003", "pass");

            assertThat(result).isNotNull();
            assertThat(result.roles()).containsExactly("PATIENT");
            assertThat(result.position()).isEqualTo("PATIENT");
        }
    }

    private SysUser buildSysUser(Long id, String phone, String name, String password) {
        SysUser u = new SysUser();
        u.setId(id);
        u.setPhone(phone);
        u.setName(name);
        u.setPassword(password);
        u.setStatus("ACTIVE");
        return u;
    }
}
