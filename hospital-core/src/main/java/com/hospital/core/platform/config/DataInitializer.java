package com.hospital.core.platform.config;

import java.util.List;

import org.springframework.boot.CommandLineRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.hospital.core.clinical.application.VisitReadModelService;
import com.hospital.core.org.domain.Staff;
import com.hospital.core.org.infrastructure.StaffMapper;
import com.hospital.core.patient.domain.Patient;
import com.hospital.core.patient.infrastructure.PatientMapper;
import com.hospital.core.platform.domain.SysUser;
import com.hospital.core.platform.infrastructure.SysUserMapper;
import com.hospital.core.platform.infrastructure.SysUserRoleMapper;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 数据初始化:
 * 1. 启动时给无密码的员工填默认密码(BCrypt = 用户名)。
 * 2. 将 org.staff / patient.patient 迁移到统一账号 platform.sys_user。
 * 幂等:已有密码/已迁移的记录跳过。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DataInitializer implements CommandLineRunner {

    /** 默认密码,登录后提示修改。 */
    public static final String DEFAULT_PASSWORD = "123456";

    private final PasswordEncoder passwordEncoder;
    private final StaffMapper staffMapper;
    private final PatientMapper patientMapper;
    private final SysUserMapper sysUserMapper;
    private final SysUserRoleMapper sysUserRoleMapper;
    private final VisitReadModelService visitReadModelService;

    @Override
    public void run(String... args) {
        migrateUsers();
        visitReadModelService.initAll();  // 初始化读模型
    }

    /**
     * 迁移 staff / patient → sys_user。
     * 默认密码统一为 "123456"(BCrypt 哈希),登录后提示修改。
     *
     * 以 phone 为幂等键,每次启动按最新 phone 重算 user_id,修复「phone 变更 / user_id 错挂到他人」导致的登录失败。
     */
    private void migrateUsers() {
        String defaultHash = passwordEncoder.encode(DEFAULT_PASSWORD);

        // 员工
        for (Staff s : staffMapper.selectList(new LambdaQueryWrapper<>())) {
            if (s.getPhone() == null || s.getPhone().isBlank()) continue;
            Long userId = findOrCreateUser(s.getPhone(), defaultHash, s.getName());
            if (s.getPosition() != null && !hasRole(userId, s.getPosition())) {
                sysUserRoleMapper.insertRole(userId, s.getPosition());
            }
            if (!userId.equals(s.getUserId())) {
                s.setUserId(userId);
                staffMapper.updateById(s);
            }
        }

        // 患者
        for (Patient p : patientMapper.selectList(new LambdaQueryWrapper<>())) {
            if (p.getPhone() == null || p.getPhone().isBlank()) continue;
            Long userId = findOrCreateUser(p.getPhone(), defaultHash, p.getName());
            if (!hasRole(userId, "PATIENT")) {
                sysUserRoleMapper.insertRole(userId, "PATIENT");
            }
            if (!userId.equals(p.getUserId())) {
                p.setUserId(userId);
                patientMapper.updateById(p);
            }
        }

        log.info("[DataInitializer] 统一账号迁移完成");
    }

    /** 查找或创建 sys_user(若已存在仅返回 id,不覆盖密码)。 */
    private Long findOrCreateUser(String phone, String password, String name) {
        SysUser existing = sysUserMapper.findByPhone(phone);
        if (existing != null) return existing.getId();

        SysUser u = new SysUser();
        u.setPhone(phone);
        u.setPassword(password);
        u.setName(name);
        u.setStatus("ACTIVE");
        sysUserMapper.insert(u);
        log.info("[DataInitializer] 已创建统一账号: {}", phone);
        return u.getId();
    }

    private boolean hasRole(Long userId, String roleCode) {
        return sysUserRoleMapper.findRoleCodesByUserId(userId).contains(roleCode);
    }
}
