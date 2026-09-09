package com.hospital.core.patient.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
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
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.hospital.core.patient.api.PatientRegisterResponse;
import com.hospital.core.patient.domain.Patient;
import com.hospital.core.patient.infrastructure.PatientMapper;
import com.hospital.core.platform.domain.SysUser;
import com.hospital.core.platform.infrastructure.SysUserMapper;
import com.hospital.core.platform.infrastructure.SysUserRoleMapper;

@ExtendWith(MockitoExtension.class)
class PatientServiceTest {

    @Mock PatientMapper patientMapper;
    @Mock SysUserMapper sysUserMapper;
    @Mock SysUserRoleMapper sysUserRoleMapper;
    @Mock PasswordEncoder passwordEncoder;

    PatientService service;

    @BeforeEach
    void setUp() {
        service = new PatientService(patientMapper, sysUserMapper, sysUserRoleMapper, passwordEncoder);
    }

    @Nested
    @DisplayName("注册患者")
    class Register {

        @Test
        @DisplayName("新患者注册 → 创建患者并返回注册响应")
        void register_success() {
            Patient input = buildPatient("张三", "13800000001");
            when(patientMapper.selectOne(any(QueryWrapper.class))).thenReturn(null);
            when(sysUserMapper.findByPhone("13800000001")).thenReturn(null);
            when(sysUserRoleMapper.findRoleCodesByUserId(any())).thenReturn(Set.of());
            when(passwordEncoder.encode("123456")).thenReturn("$2a$10$encoded");

            PatientRegisterResponse result = service.register(input);

            assertThat(result.getPatient()).isNotNull();
            assertThat(result.getUsername()).isEqualTo("13800000001");
            verify(patientMapper).insert((Patient) any());
            verify(sysUserMapper).insert((SysUser) any());
            verify(sysUserRoleMapper).insertRole(any(), eq("PATIENT"));
        }

        @Test
        @DisplayName("手机号已存在 → 返回已有患者信息,不重复创建")
        void register_duplicatePhone_returnsExisting() {
            Patient existing = buildPatient("李四", "13800000002");
            existing.setId(10L);
            existing.setUsername("13800000002");
            when(patientMapper.selectOne(any(QueryWrapper.class))).thenReturn(existing);
            SysUser sysUser = buildSysUser(10L, "13800000002");
            when(sysUserMapper.findByPhone("13800000002")).thenReturn(sysUser);
            when(sysUserRoleMapper.findRoleCodesByUserId(10L)).thenReturn(Set.of("PATIENT"));

            PatientRegisterResponse result = service.register(buildPatient("李四", "13800000002"));

            assertThat(result.getPatient().getId()).isEqualTo(10L);
            assertThat(result.getTempPassword()).isNull();
            verify(patientMapper, never()).insert((Patient) any());
        }
    }

    @Nested
    @DisplayName("按 ID 查询")
    class GetById {

        @Test
        @DisplayName("患者存在 → 返回患者对象")
        void get_exists() {
            Patient p = buildPatient("王五", "13800000003");
            p.setId(1L);
            when(patientMapper.selectById(1L)).thenReturn(p);

            Patient result = service.get(1L);

            assertThat(result.getName()).isEqualTo("王五");
        }

        @Test
        @DisplayName("患者不存在 → 返回 null")
        void get_notFound_returnsNull() {
            when(patientMapper.selectById(999L)).thenReturn(null);

            Patient result = service.get(999L);

            assertThat(result).isNull();
        }
    }

    @Nested
    @DisplayName("按手机号/用户名查询")
    class FindByUsername {

        @Test
        @DisplayName("用户名存在 → 返回患者对象")
        void findByUsername_exists() {
            Patient p = buildPatient("赵六", "13800000004");
            p.setUsername("13800000004");
            when(patientMapper.selectOne(any(QueryWrapper.class))).thenReturn(p);

            Patient result = service.findByUsername("13800000004");

            assertThat(result.getPhone()).isEqualTo("13800000004");
        }

        @Test
        @DisplayName("用户名不存在 → 返回 null")
        void findByUsername_notFound_returnsNull() {
            when(patientMapper.selectOne(any(QueryWrapper.class))).thenReturn(null);

            Patient result = service.findByUsername("unknown");

            assertThat(result).isNull();
        }
    }

    @Nested
    @DisplayName("更新患者")
    class Update {

        @Test
        @DisplayName("患者存在 → 更新成功并返回")
        void update_success() {
            withAuthority("system:admin");
            try {
                Patient existing = buildPatient("旧名", "13800000005");
                existing.setId(5L);
                when(patientMapper.selectById(5L)).thenReturn(existing);

                Patient updateData = new Patient();
                updateData.setName("新名");
                Patient result = service.update(5L, updateData);

                assertThat(result.getName()).isEqualTo("新名");
                verify(patientMapper).updateById((Patient) any());
            } finally {
                SecurityContextHolder.clearContext();
            }
        }

        // R-07: 姓名/身份证属身份标识字段,仅 system:admin 可改,
        // 否则持有 visit:entry 的账号就能把患者身份改成任意合法号码(身份冒用/医保欺诈)
        @Test
        @DisplayName("非管理员修改姓名 → 拒绝(AccessDeniedException)")
        void update_changeNameWithoutAdmin_throws() {
            withAuthority("visit:entry");
            try {
                Patient existing = buildPatient("旧名", "13800000005");
                existing.setId(5L);
                when(patientMapper.selectById(5L)).thenReturn(existing);

                Patient updateData = new Patient();
                updateData.setName("新名");

                assertThatThrownBy(() -> service.update(5L, updateData))
                        .isInstanceOf(AccessDeniedException.class)
                        .hasMessageContaining("管理员权限");
                verify(patientMapper, never()).updateById((Patient) any());
            } finally {
                SecurityContextHolder.clearContext();
            }
        }

        @Test
        @DisplayName("非管理员修改身份证 → 拒绝(AccessDeniedException)")
        void update_changeIdCardWithoutAdmin_throws() {
            withAuthority("visit:entry");
            try {
                Patient existing = buildPatient("旧名", "13800000005");
                existing.setIdCard("110101199001011234");
                existing.setId(5L);
                when(patientMapper.selectById(5L)).thenReturn(existing);

                Patient updateData = new Patient();
                updateData.setIdCard("110101199001019999");

                assertThatThrownBy(() -> service.update(5L, updateData))
                        .isInstanceOf(AccessDeniedException.class)
                        .hasMessageContaining("管理员权限");
                verify(patientMapper, never()).updateById((Patient) any());
            } finally {
                SecurityContextHolder.clearContext();
            }
        }

        @Test
        @DisplayName("非管理员修改手机号 → 静默还原为旧值(防账号接管)")
        void update_changePhoneWithoutAdmin_keepsOldPhone() {
            withAuthority("visit:entry");
            try {
                Patient existing = buildPatient("旧名", "13800000005");
                existing.setId(5L);
                when(patientMapper.selectById(5L)).thenReturn(existing);

                Patient updateData = new Patient();
                updateData.setPhone("13900000000");
                Patient result = service.update(5L, updateData);

                assertThat(result.getPhone()).isEqualTo("13800000005");
                assertThat(updateData.getPhone()).isEqualTo("13800000005");
            } finally {
                SecurityContextHolder.clearContext();
            }
        }

        @Test
        @DisplayName("患者不存在 → 抛出 IllegalArgumentException")
        void update_notFound_throws() {
            when(patientMapper.selectById(999L)).thenReturn(null);

            assertThatThrownBy(() -> service.update(999L, new Patient()))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("患者不存在");
        }

        /** R-07: 以给定 authority 构造 SecurityContext,模拟不同角色调用 update。 */
        private void withAuthority(String authority) {
            SecurityContextHolder.getContext().setAuthentication(
                    new UsernamePasswordAuthenticationToken(
                            "tester", null, List.of(new SimpleGrantedAuthority(authority))));
        }
    }

    private Patient buildPatient(String name, String phone) {
        Patient p = new Patient();
        p.setName(name);
        p.setPhone(phone);
        return p;
    }

    private SysUser buildSysUser(Long id, String phone) {
        SysUser u = new SysUser();
        u.setId(id);
        u.setPhone(phone);
        u.setName("test");
        u.setStatus("ACTIVE");
        return u;
    }
}
