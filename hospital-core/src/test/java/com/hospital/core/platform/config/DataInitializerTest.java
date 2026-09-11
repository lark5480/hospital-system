package com.hospital.core.platform.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;

import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.util.ReflectionTestUtils;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.hospital.core.clinical.application.VisitReadModelService;
import com.hospital.core.clinical.domain.Charge;
import com.hospital.core.clinical.domain.Order;
import com.hospital.core.clinical.domain.Visit;
import com.hospital.core.clinical.infrastructure.ChargeMapper;
import com.hospital.core.clinical.infrastructure.OrderMapper;
import com.hospital.core.clinical.infrastructure.VisitMapper;
import com.hospital.core.clinical.infrastructure.VisitReadModelMapper;
import com.hospital.core.org.application.DepartmentService;
import com.hospital.core.org.application.StaffService;
import com.hospital.core.org.infrastructure.StaffMapper;
import com.hospital.core.patient.application.PatientService;
import com.hospital.core.patient.infrastructure.PatientMapper;
import com.hospital.core.platform.infrastructure.SysUserMapper;
import com.hospital.core.platform.infrastructure.SysUserRoleMapper;

/**
 * R-50: {@link DataInitializer#run()} 启动副作用的零断言补齐测试。
 *
 * <p>用 Mockito,<b>不启 Spring 上下文</b>。DataInitializer 的三个步骤:
 * <ol>
 *   <li>唯一约束加固(ensureStaffPhoneUnique)—— 探测到重复 phone 时只告警不删数据(R-58 核心护栏);</li>
 *   <li>统一账号迁移(migrateUsers)—— count 短路,无未迁移行则不下发全表 selectList;</li>
 *   <li>读模型初始化 —— 委托 {@link VisitReadModelService#initAll()},其内部带水位守卫。</li>
 * </ol>
 *
 * <p>构造:
 * <ul>
 *   <li>{@link DataInitializer} 是 {@code @RequiredArgsConstructor},按字段顺序注入 7 个依赖(末位是 JdbcTemplate);</li>
 *   <li>步骤3 的"水位守卫"实现在 {@link VisitReadModelService#initAll()} 内,故此处使用<b>真实</b>
 *       VisitReadModelService(其 {@code jdbcTemplate} 为 {@code @Autowired(required=false)} 字段,
 *       通过 {@link ReflectionTestUtils} 反射注入一个独立 mock),用另一份 mock 的 {@code coreJdbcTemplate}
 *       喂给 DataInitializer 自身,从而可分别观测"读模型是否重建""迁移是否全表扫描"。</li>
 * </ul>
 *
 * <p>采用 LENIENT 严格度:run() 会串行执行三步,不同用例关注的桩各不相同,统一避免 UnnecessaryStubbing 噪声。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class DataInitializerTest {

    // ---- DataInitializer 直接依赖 ----
    @Mock PasswordEncoder passwordEncoder;
    @Mock StaffMapper staffMapper;
    @Mock PatientMapper patientMapper;
    @Mock SysUserMapper sysUserMapper;
    @Mock SysUserRoleMapper sysUserRoleMapper;
    /** 注入给 DataInitializer 的 JdbcTemplate(步骤1/2 用)。 */
    @Mock JdbcTemplate coreJdbcTemplate;

    // ---- 真实 VisitReadModelService 的依赖 ----
    @Mock VisitReadModelMapper readModelMapper;
    @Mock VisitMapper visitMapper;
    @Mock OrderMapper orderMapper;
    @Mock ChargeMapper chargeMapper;
    @Mock PatientService patientService;
    @Mock StaffService staffService;
    @Mock DepartmentService departmentService;
    /** 注入给真实 VisitReadModelService 的 JdbcTemplate(读水位/写水位用)。 */
    @Mock JdbcTemplate rmJdbcTemplate;

    DataInitializer initializer;

    @BeforeAll
    static void initMpLambdaCache() {
        // 读模型重建时会构造 LambdaQueryWrapper<Visit>(.gt(Visit::getId ...)),
        // 纯单测无 Spring 上下文,需手动初始化 MP 的 TableInfo / lambda 缓存。
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(new MybatisConfiguration(), "");
        TableInfoHelper.initTableInfo(assistant, Visit.class);
        TableInfoHelper.initTableInfo(assistant, Order.class);
        TableInfoHelper.initTableInfo(assistant, Charge.class);
    }

    @BeforeEach
    void setUp() {
        // 步骤1:默认"约束已存在" → 直接跳过
        when(coreJdbcTemplate.queryForObject(contains("pg_constraint"), eq(Boolean.class))).thenReturn(true);
        // 步骤2:默认无未迁移行 → 短路
        when(coreJdbcTemplate.queryForObject(contains("org.staff WHERE user_id IS NULL"), eq(Long.class)))
                .thenReturn(0L);
        when(coreJdbcTemplate.queryForObject(contains("patient.patient WHERE user_id IS NULL"), eq(Long.class)))
                .thenReturn(0L);

        initializer = new DataInitializer(passwordEncoder, staffMapper, patientMapper, sysUserMapper,
                sysUserRoleMapper, realReadModelService(), coreJdbcTemplate);
    }

    /** 构造一个使用 mock jdbcTemplate 的真实 VisitReadModelService(水位守卫逻辑在其 initAll 内)。 */
    private VisitReadModelService realReadModelService() {
        VisitReadModelService service = new VisitReadModelService(readModelMapper, visitMapper, orderMapper,
                chargeMapper, patientService, staffService, departmentService);
        ReflectionTestUtils.setField(service, "jdbcTemplate", rmJdbcTemplate);
        return service;
    }

    /** 桩定"读模型水位已是当前版本" → initAll 应直接短路,不重建。 */
    private void stubReadModelWatermarkHit() {
        // 用原始值(非 matcher)桩定,避免 varargs 匹配歧义
        when(rmJdbcTemplate.queryForList(
                "SELECT value FROM platform.meta WHERE key = ?",
                String.class,
                VisitReadModelService.READ_MODEL_INIT_VERSION_KEY))
                .thenReturn(List.of(VisitReadModelService.READ_MODEL_INIT_VERSION));
    }

    /** 收集某个 JdbcTemplate mock 上所有"第一个参数是 SQL 字符串"的调用 SQL。 */
    private static List<String> sqlInvocations(JdbcTemplate jdbcTemplate) {
        return Mockito.mockingDetails(jdbcTemplate).getInvocations().stream()
                .map(inv -> inv.getArguments().length > 0 ? inv.getArgument(0) : null)
                .filter(String.class::isInstance)
                .map(String.class::cast)
                .toList();
    }

    // ---------------- R-50 步骤3:读模型水位 ----------------

    @Test
    @DisplayName("run_watermarkHit_skipsReadModelRebuild: 水位=当前版本 → 不触发读模型全量重建")
    void run_watermarkHit_skipsReadModelRebuild() {
        stubReadModelWatermarkHit();

        initializer.run();

        // 重建的核心动作是"按主键游标扫描 clinical.visit";水位命中时不应发生
        verify(visitMapper, never()).selectList(Mockito.any());
    }

    @Test
    @DisplayName("run_watermarkMissing_triggersRebuild: 水位缺失 → 触发读模型全量重建(扫描 visit)")
    void run_watermarkMissing_triggersRebuild() {
        // rmJdbcTemplate 默认返回空 list → readMeta 得到 null → 判定水位缺失
        when(rmJdbcTemplate.queryForList(
                "SELECT value FROM platform.meta WHERE key = ?",
                String.class,
                VisitReadModelService.READ_MODEL_INIT_VERSION_KEY))
                .thenReturn(List.of());
        // 扫描返回空批 → 重建循环立即结束(无需真正刷新任何读模型)
        when(visitMapper.selectList(Mockito.any())).thenReturn(List.of());

        initializer.run();

        // 触发了重建:至少扫描过一次 visit
        verify(visitMapper, atLeastOnce()).selectList(Mockito.any());
    }

    // ---------------- R-50 步骤2:账号迁移 count 短路 ----------------

    @Test
    @DisplayName("migrateUsers_noUnmigratedRows_skips: count(user_id IS NULL) 均为 0 → 不下发全表 selectList")
    void migrateUsers_noUnmigratedRows_skips() {
        stubReadModelWatermarkHit();

        initializer.run();

        // 关键:无待迁移行时,绝不做 staff/patient 全表 selectList(R-15 启动耗时的根因之一)
        verify(staffMapper, never()).selectList(Mockito.any());
        verify(patientMapper, never()).selectList(Mockito.any());
    }

    // ---------------- R-50 步骤1:唯一约束加固(R-58 护栏) ----------------

    @Test
    @DisplayName("ensureStaffPhoneUnique_conflict_doesNotDelete: 探测到重复 phone → 不执行任何 DELETE、不建约束")
    void ensureStaffPhoneUnique_conflict_doesNotDelete() {
        // 约束不存在(否则会提前 return)
        when(coreJdbcTemplate.queryForObject(contains("pg_constraint"), eq(Boolean.class))).thenReturn(false);
        // 探测到 1 组重复 phone
        when(coreJdbcTemplate.queryForList(contains("GROUP BY phone"), eq(String.class)))
                .thenReturn(List.of("13800000000"));
        stubReadModelWatermarkHit();

        initializer.run();

        // 确实走到了"重复探测"分支(SQL 含 GROUP BY phone HAVING)
        verify(coreJdbcTemplate).queryForList(contains("GROUP BY phone"), eq(String.class));

        // R-58 核心护栏:存在冲突时绝不删除任何数据(R-15 之前是会静默 DELETE 重复行的破坏性操作)
        assertThat(sqlInvocations(coreJdbcTemplate))
                .as("冲突路径下不应出现任何 DELETE")
                .noneMatch(sql -> sql.toUpperCase().contains("DELETE"));
        // 也不应创建 UNIQUE 约束(有冲突时跳过,交给人工处理)
        assertThat(sqlInvocations(coreJdbcTemplate))
                .as("冲突路径下不应创建 UNIQUE 约束")
                .noneMatch(sql -> sql.toUpperCase().contains("ADD CONSTRAINT"));
        verify(coreJdbcTemplate, never()).execute(Mockito.anyString());
    }
}
