package com.hospital.core.platform.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.annotation.Transactional;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * R-10 / R-12 / R-34 / R-08 的<b>端到端</b>回归测试。
 *
 * <p>为什么必须有这一层:前面两个缺陷都是"单个方法正确、交互语义错误",单测完全测不出来 ——
 * <ol>
 *   <li><b>锁定失效</b>:{@code isLocked()} 有清除副作用,而 Controller 在登录失败后会再查一次,
 *       导致计数每次被清零,连续失败永远攒不到 5 次。见 {@link LoginAttemptServiceTest}。</li>
 *   <li><b>重置密码后用户被锁死</b>:吊销只写存在性标记,与 token 签发时间无关,
 *       于是新登录签发的 token 也被判失效。见 {@link TokenRevocationServiceTest}。</li>
 * </ol>
 * 这两个都只有在"登录 → 改密 → 复用 token"的真实请求链路上才会暴露,故用 MockMvc 固化。
 *
 * <p>测试走真实 HTTP 链路(含 JwtAuthFilter 与全部安全配置),但不绑定网络端口。
 * 标注 {@code @Transactional} 以便改密等写操作在用例结束后回滚,不污染演示数据;
 * Redis 中的吊销标记不受事务管辖,需显式清理。
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class AuthenticationFlowEndToEndTest {

    /**
     * 用于改密流程的账号(密码会在用例内被改,结束后由事务回滚)。
     * 必须是持有 visit:entry 的医生账号 —— 用例用 /api/core/visits 验证 token 可用性,
     * 收银员等角色只有 charge:pay,访问该接口会 403,无法区分"token 失效"与"权限不足"。
     */
    private static final String TEST_PHONE = "13800000001";

    /** 用于锁定流程的账号,与改密账号分开,避免相互影响。 */
    private static final String LOCK_PHONE = "13800000006";

    /** 患者账号(仅 patient:booking),用于越权用例。 */
    private static final String PATIENT_PHONE = "13700000000";

    private static final String DEFAULT_PASSWORD = "123456";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private StringRedisTemplate stringRedisTemplate;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private PasswordEncoder passwordEncoder;

    private final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * 把用例依赖的共享种子账号口令<b>归一化到 {@link #DEFAULT_PASSWORD}</b>,并清理 Redis 吊销标记。
     *
     * <p>为什么必须做:这两个账号是演示环境的<b>共享</b>种子账号,使用者随时可能改掉口令
     * (R-10 还会强制首次登录改密)。一旦被改,本用例就会以「登录 401」的形式失败 ——
     * 症状看起来完全像鉴权回归,极难定位(本次真实踩到:账号 {@code 13800000001}
     * 被使用者改成其它口令后,`login()` 直接断言失败)。
     *
     * <p>不会污染演示数据:本类标注 {@code @Transactional},这里的 UPDATE 随事务回滚,
     * 用例结束后环境里仍是使用者设定的口令。这与 R-49(消除集成测试对种子数据的耦合)
     * 是同一类治理 —— 用例必须自己保证前置状态。
     */
    @BeforeEach
    void normalizeSeedPasswordsAndClearRevocation() {
        String encoded = passwordEncoder.encode(DEFAULT_PASSWORD);
        for (String phone : List.of(TEST_PHONE, PATIENT_PHONE)) {
            jdbcTemplate.update("update platform.sys_user set password = ? where phone = ?", encoded, phone);
        }
        clearRevocationMarks();
    }

    @AfterEach
    void clearRevocationMarksAfter() {
        // 用例内改密会写入吊销标记,若不清理,演示环境里该账号在 TTL 内会被旧版本代码判为失效
        clearRevocationMarks();
    }

    /** 吊销标记不受 @Transactional 管辖,必须显式清理,否则会污染其它用例与运行环境。 */
    private void clearRevocationMarks() {
        stringRedisTemplate.delete(List.of(
                "auth:revoked:user:" + TEST_PHONE,
                "auth:revoked:user:" + LOCK_PHONE,
                "auth:revoked:user:" + PATIENT_PHONE));
    }

    @Test
    @DisplayName("R-34 端到端:改密后旧 token 立即失效,新 token 可正常访问业务接口")
    void changePasswordShouldInvalidateOldTokenButAllowNewOne() throws Exception {
        String oldToken = login(TEST_PHONE, DEFAULT_PASSWORD);
        assertThat(oldToken).isNotBlank();

        // 改密前:旧 token 可用
        assertThat(statusOf(authenticatedGet("/api/core/visits?pageNum=1&pageSize=5", oldToken)))
                .as("改密前旧 token 应可用")
                .isEqualTo(200);

        // 改密(新密码需满足策略:8~64 位 + 字母数字 + 非弱口令 + 不同于旧密码)
        String newPassword = "Hospital@2026";
        int changeStatus = statusOf(postJson("/api/auth/password/change", oldToken,
                "{\"oldPassword\":\"" + DEFAULT_PASSWORD + "\",\"newPassword\":\"" + newPassword + "\"}"));
        assertThat(changeStatus).as("改密应成功").isEqualTo(200);

        // 改密后:旧 token 必须立即失效
        assertThat(statusOf(authenticatedGet("/api/core/visits?pageNum=1&pageSize=5", oldToken)))
                .as("改密后旧 token 必须失效")
                .isEqualTo(401);

        // 关键回归:用新密码重新登录,签发的 token 必须可用
        // (早期实现把"用户被吊销"做成永久标记,导致这里也被 401 —— 重置一次密码锁死用户 4 小时)
        String newToken = login(TEST_PHONE, newPassword);
        assertThat(newToken).isNotBlank();
        assertThat(statusOf(authenticatedGet("/api/core/visits?pageNum=1&pageSize=5", newToken)))
                .as("改密后重新登录签发的 token 必须可用")
                .isEqualTo(200);

        // 旧密码应已失效
        assertThat(loginStatus(TEST_PHONE, DEFAULT_PASSWORD))
                .as("旧密码应无法再登录")
                .isEqualTo(401);
    }

    @Test
    @DisplayName("R-10 端到端:连续失败达上限后返回 429,且正确密码也被拒绝")
    void consecutiveFailuresShouldLockAccount() throws Exception {
        // 前 4 次:401 密码错误,未锁定
        for (int i = 1; i <= 4; i++) {
            assertThat(loginStatus(LOCK_PHONE, "definitely-wrong"))
                    .as("第 %d 次失败应返回 401", i)
                    .isEqualTo(401);
        }

        // 第 5 次:触发锁定 → 429
        assertThat(loginStatus(LOCK_PHONE, "definitely-wrong"))
                .as("达到失败上限后应返回 429")
                .isEqualTo(429);

        // 锁定期间即使密码正确也必须拒绝
        assertThat(loginStatus(LOCK_PHONE, DEFAULT_PASSWORD))
                .as("锁定期间正确密码也必须被拒绝")
                .isEqualTo(429);
    }

    @Test
    @DisplayName("R-08 端到端:患者不能读取他人报告(归属校验)")
    void patientShouldNotReadOthersReport() throws Exception {
        Long ownPatientId = jdbcTemplate.queryForObject(
                "select id from patient.patient where username = ?", Long.class, PATIENT_PHONE);
        assertThat(ownPatientId).as("患者账号应已绑定患者档案").isNotNull();

        Long otherReportId = jdbcTemplate.query(
                        "select id from report.record where patient_id is not null and patient_id <> ? limit 1",
                        rs -> rs.next() ? rs.getLong(1) : null,
                        ownPatientId);

        if (otherReportId == null) {
            // 库里没有"他人报告"可供验证时跳过,避免用例因数据缺失而误报失败
            return;
        }

        String patientToken = login(PATIENT_PHONE, DEFAULT_PASSWORD);
        // 注意报告域的路径是 /api/reports/**(网关 hospital-report 路由),不是 /api/core/reports/**
        assertThat(statusOf(authenticatedGet("/api/reports/" + otherReportId, patientToken)))
                .as("患者读取他人报告应被拒绝(403)")
                .isEqualTo(403);
    }

    @Test
    @DisplayName("R-07 端到端:患者不能访问全院患者列表(无 visit:entry)")
    void patientShouldNotListAllPatients() throws Exception {
        String patientToken = login(PATIENT_PHONE, DEFAULT_PASSWORD);

        assertThat(statusOf(authenticatedGet("/api/patient?pageNum=1&pageSize=5", patientToken)))
                .as("患者访问全院患者列表应被拒绝(403)")
                .isEqualTo(403);
    }

    // ===================== 辅助方法 =====================

    /** 登录成功返回 token;失败抛出断言错误(便于给出可读失败信息)。 */
    private String login(String phone, String password) throws Exception {
        MvcResult result = mockMvc.perform(postJson("/api/auth/login", null,
                "{\"phone\":\"" + phone + "\",\"password\":\"" + password + "\"}")).andReturn();

        assertThat(result.getResponse().getStatus())
                .as("登录应成功(phone=%s)", phone)
                .isEqualTo(200);

        JsonNode body = objectMapper.readTree(result.getResponse().getContentAsString());
        return body.path("token").asText();
    }

    /** 返回登录接口的 HTTP 状态码(不抛异常)。 */
    private int loginStatus(String phone, String password) throws Exception {
        return mockMvc.perform(postJson("/api/auth/login", null,
                "{\"phone\":\"" + phone + "\",\"password\":\"" + password + "\"}"))
                .andReturn().getResponse().getStatus();
    }

    /** 带 Bearer token 的 GET 请求,返回 HTTP 状态码。 */
    private int statusOf(org.springframework.test.web.servlet.RequestBuilder request) throws Exception {
        return mockMvc.perform(request).andReturn().getResponse().getStatus();
    }

    private org.springframework.test.web.servlet.RequestBuilder authenticatedGet(String url, String token) {
        return get(url).header(HttpHeaders.AUTHORIZATION, "Bearer " + token);
    }

    private org.springframework.test.web.servlet.RequestBuilder postJson(String url, String token, String json) {
        var builder = post(url).contentType(MediaType.APPLICATION_JSON).content(json);
        if (token != null) {
            builder = builder.header(HttpHeaders.AUTHORIZATION, "Bearer " + token);
        }
        return builder;
    }
}
