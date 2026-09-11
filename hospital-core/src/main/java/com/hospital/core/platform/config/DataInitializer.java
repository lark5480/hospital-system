package com.hospital.core.platform.config;

import java.util.List;

import org.springframework.boot.CommandLineRunner;
import org.springframework.jdbc.core.JdbcTemplate;
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
 * 1. 加固 org.staff.phone 的唯一约束(仅首启一次;存量重复数据只告警不清洗,见 R-15)。
 * 2. 将 org.staff / patient.patient 迁移到统一账号 platform.sys_user(默认密码 123456)。
 * 3. 初始化就诊读模型(VisitReadModelService.initAll,内部带水位守卫)。
 *
 * <p>R-15: 本类原实现每次启动都无条件执行"全量"重活 —— 迁移对 staff/patient 全表 selectList
 * 且每行一次 findByPhone(10 万行 → 数十万次 SQL),读模型对全表 visit 逐条 refresh
 * (10 万 visit → 约 80 万次 SQL),估算启动近 18 分钟。改造后:
 * <ul>
 *   <li>读模型:水位守卫 + 分批增量(见 {@link VisitReadModelService#initAll()});</li>
 *   <li>账号迁移:先做 count 短路,无未迁移行则整段跳过,只处理 user_id 为空的行;</li>
 *   <li>唯一约束:破坏性 DELETE 改为"先探测 + 告警跳过",不静默删数据。</li>
 * </ul>
 * 幂等:已有密码/已迁移的记录跳过。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DataInitializer implements CommandLineRunner {

    /** 默认密码,登录后提示修改。 */
    public static final String DEFAULT_PASSWORD = "123456";

    /** R-15: 统一账号迁移的水位键。仅用于观测"迁移已完成到哪个版本",不参与短路判定。 */
    private static final String USER_MIGRATION_VERSION_KEY = "user_migration_version";
    /** R-15: 当前账号迁移版本号。迁移口径变更时 +1,便于按水位排查/强制重跑。 */
    private static final String USER_MIGRATION_VERSION = "1";

    private final PasswordEncoder passwordEncoder;
    private final StaffMapper staffMapper;
    private final PatientMapper patientMapper;
    private final SysUserMapper sysUserMapper;
    private final SysUserRoleMapper sysUserRoleMapper;
    private final VisitReadModelService visitReadModelService;
    private final JdbcTemplate jdbcTemplate;

    @Override
    public void run(String... args) {
        // R-15: 逐步计时,便于观测每次启动到底做了多少重活(日常启动应接近 0ms)。
        long totalStart = System.currentTimeMillis();

        long stepStart = System.currentTimeMillis();
        ensureStaffPhoneUnique();
        log.info("[DataInitializer] 步骤1 唯一约束加固 耗时 {}ms", System.currentTimeMillis() - stepStart);

        stepStart = System.currentTimeMillis();
        migrateUsers();
        log.info("[DataInitializer] 步骤2 统一账号迁移 耗时 {}ms", System.currentTimeMillis() - stepStart);

        stepStart = System.currentTimeMillis();
        visitReadModelService.initAll();  // 初始化读模型(内部水位守卫,日常启动直接跳过)
        log.info("[DataInitializer] 步骤3 读模型初始化 耗时 {}ms", System.currentTimeMillis() - stepStart);

        log.info("[DataInitializer] 启动期数据初始化完成,总耗时 {}ms", System.currentTimeMillis() - totalStart);
    }

    /**
     * 确保 org.staff.phone 有 UNIQUE 约束(幂等:已存在则跳过)。
     *
     * <p>R-15: 原实现在约束缺失时执行
     * {@code DELETE FROM org.staff WHERE id NOT IN (SELECT MIN(id) FROM org.staff GROUP BY phone)}
     * 直接静默删除重复员工行,再建约束 —— 无人值守启动时这是**破坏性**操作。
     * 现改为"先探测":若探测到同一 phone 存在多行,仅 log.error 打印冲突 phone 清单并**跳过建约束**,
     * 把"是否清理"交给人决定(表 DDL 已声明 {@code phone ... UNIQUE},正常情况下本方法等价 no-op)。
     *
     * <p>选"跳过"而非"抛异常中止启动"的理由:该约束属于"额外加固",不是应用运行的硬依赖;
     * 因历史脏数据而让整个系统拒绝启动,会把"局部数据问题"放大成"全站不可用",
     * 且启动失败时人未必在场、排障成本更高。告警 + 跳过可保证系统继续可用,同时显式暴露问题。
     */
    private void ensureStaffPhoneUnique() {
        Boolean exists = jdbcTemplate.queryForObject(
                "SELECT EXISTS(SELECT 1 FROM pg_constraint WHERE conname='uk_staff_phone' AND conrelid='org.staff'::regclass)",
                Boolean.class);
        if (Boolean.TRUE.equals(exists)) {
            return;
        }

        // R-15: 先探测是否存在重复 phone(空 phone 不参与:PostgreSQL 唯一约束允许多个 NULL)。
        List<String> conflicts = jdbcTemplate.queryForList(
                "SELECT phone FROM org.staff WHERE phone IS NOT NULL "
                        + "GROUP BY phone HAVING count(*) > 1 ORDER BY phone",
                String.class);
        if (!conflicts.isEmpty()) {
            log.error("[DataInitializer] org.staff.phone 存在 {} 组重复,已跳过 UNIQUE 约束创建(未删除任何数据),"
                            + "请人工确认后清理再重启;冲突 phone 清单: {}",
                    conflicts.size(), conflicts);
            return;
        }

        jdbcTemplate.execute("ALTER TABLE org.staff ADD CONSTRAINT uk_staff_phone UNIQUE (phone)");
        log.info("[DataInitializer] 已为 org.staff.phone 添加 UNIQUE 约束");
    }

    /**
     * 迁移 staff / patient → sys_user。
     * 默认密码统一为 "123456"(BCrypt 哈希),登录后提示修改。
     *
     * <p>R-15: 改为"短路 + 增量" —— 原先每次启动都对 staff/patient 全表 selectList,
     * 且每行一次 findByPhone,10 万行规模下是数十万次 SQL。现在:
     * <ol>
     *   <li>先各做一次 {@code count(*) WHERE user_id IS NULL};两者都为 0(含 NULL 视为已迁移)则整段跳过;</li>
     *   <li>否则只对 {@code user_id IS NULL} 的行做迁移(O(待迁移行) 而非 O(全表))。</li>
     * </ol>
     * 迁移完成后写入/更新 {@code user_migration_version} 水位。
     *
     * <p>注意:原实现"每次启动按最新 phone 重算 user_id,修复 phone 变更 / user_id 错挂"的行为,
     * 为消除全表扫描**不再随启动执行**;若确需修复某行错挂的 user_id,将其 user_id 置空后重启即可
     * (会被本次增量迁移捕获)。
     */
    private void migrateUsers() {
        // R-15: count 短路 —— 无未迁移行则整段跳过,杜绝全表 selectList + 逐行 findByPhone。
        Long staffPending = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM org.staff WHERE user_id IS NULL", Long.class);
        Long patientPending = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM patient.patient WHERE user_id IS NULL", Long.class);
        long pending = (staffPending == null ? 0 : staffPending) + (patientPending == null ? 0 : patientPending);
        if (pending == 0) {
            log.info("[DataInitializer] 统一账号迁移:无未迁移行,跳过(水位 {}={})",
                    USER_MIGRATION_VERSION_KEY, readMeta(USER_MIGRATION_VERSION_KEY));
            return;
        }

        long start = System.currentTimeMillis();
        String defaultHash = passwordEncoder.encode(DEFAULT_PASSWORD);

        // R-15: 只取 user_id 为空的行(增量)。即便存在"无 phone 无法迁移"的行导致 count>0,
        // 这里也只加载少量待迁移行,不会退化成全表扫描。
        // 员工
        int staffMigrated = 0;
        for (Staff s : staffMapper.selectList(new LambdaQueryWrapper<Staff>().isNull(Staff::getUserId))) {
            if (s.getPhone() == null || s.getPhone().isBlank()) continue;
            Long userId = findOrCreateUser(s.getPhone(), defaultHash, s.getName());
            if (s.getPosition() != null && !hasRole(userId, s.getPosition())) {
                sysUserRoleMapper.insertRole(userId, s.getPosition());
            }
            s.setUserId(userId);
            staffMapper.updateById(s);
            staffMigrated++;
        }

        // 患者
        int patientMigrated = 0;
        for (Patient p : patientMapper.selectList(new LambdaQueryWrapper<Patient>().isNull(Patient::getUserId))) {
            if (p.getPhone() == null || p.getPhone().isBlank()) continue;
            Long userId = findOrCreateUser(p.getPhone(), defaultHash, p.getName());
            if (!hasRole(userId, "PATIENT")) {
                sysUserRoleMapper.insertRole(userId, "PATIENT");
            }
            p.setUserId(userId);
            patientMapper.updateById(p);
            patientMigrated++;
        }

        // R-15: 迁移完成后写回水位
        writeMeta(USER_MIGRATION_VERSION_KEY, USER_MIGRATION_VERSION);
        log.info("[DataInitializer] 统一账号迁移完成:staff 迁移 {} 行,patient 迁移 {} 行,耗时 {}ms",
                staffMigrated, patientMigrated, System.currentTimeMillis() - start);
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

    /** R-15: 读取平台水位(不存在返回 null)。 */
    private String readMeta(String key) {
        List<String> values = jdbcTemplate.queryForList(
                "SELECT value FROM platform.meta WHERE key = ?", String.class, key);
        return values.isEmpty() ? null : values.get(0);
    }

    /** R-15: 写入/更新平台水位(upsert)。 */
    private void writeMeta(String key, String value) {
        jdbcTemplate.update(
                "INSERT INTO platform.meta(key, value, updated_at) VALUES (?, ?, now()) "
                        + "ON CONFLICT (key) DO UPDATE SET value = EXCLUDED.value, updated_at = now()",
                key, value);
    }
}
