package com.hospital.core.fhir;

import java.sql.Date;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * FHIR facade 接口测试。
 *
 * <p>R-02: /fhir/** 已从 permitAll 收口为必须持 JWT,且各 Controller 补了类级 @PreAuthorize,
 * 因此 @WithMockUser 必须显式声明 authorities(这里用 system:admin),否则全部 403。
 * 搜索接口同时改为"必须提供过滤条件",无参请求断言由 200 + 数组改为 400。
 *
 * <p>R-49: 原先 {@code shouldReturnPatient} 硬编码 {@code /fhir/Patient/1},依赖 schema.sql 种下的
 * 演示患者(id=1)。现改为"测试自造数据 + 自清理":每个用例前用 JdbcTemplate 造一条独立手机号的
 * 测试患者,用例后按自造 phone/username 清理,使本类不再耦合全局种子数据,且可重复运行(幂等)。
 * 该类非 @Transactional,故显式 @AfterEach 清理。
 */
@SpringBootTest
@AutoConfigureMockMvc
@WithMockUser(authorities = "system:admin")
class FhirApiTest {

    /** R-49: 与种子演示数据(13800000000 / 13700000000)隔离的独立手机号。 */
    private static final String TEST_PHONE = "13900000049";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    /** R-49: 本用例自造的测试患者 id(供断言使用,不再依赖 id=1)。 */
    private Long patientId;

    @BeforeEach
    void seedTestPatient() {
        // 先清理可能残留的同名行(上次运行中途失败的兜底),保证幂等
        jdbcTemplate.update("DELETE FROM patient.patient WHERE phone = ? OR username = ?",
                TEST_PHONE, TEST_PHONE);
        patientId = jdbcTemplate.queryForObject(
                "INSERT INTO patient.patient (name, gender, birthday, phone, username) "
                        + "VALUES (?,?,?,?,?) RETURNING id",
                Long.class, "R49测试患者", "M", Date.valueOf("1990-01-01"), TEST_PHONE, TEST_PHONE);
    }

    @AfterEach
    void cleanTestPatient() {
        jdbcTemplate.update("DELETE FROM patient.patient WHERE phone = ? OR username = ?",
                TEST_PHONE, TEST_PHONE);
    }

    @Test
    void shouldReturnCapabilityStatement() throws Exception {
        mockMvc.perform(get("/fhir/metadata"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.resourceType").value("CapabilityStatement"))
                .andExpect(jsonPath("$.status").value("active"))
                .andExpect(jsonPath("$.fhirVersion").value("4.0.1"));
    }

    @Test
    void shouldReturnPatient() throws Exception {
        // R-49: 断言自造患者的 id,而非全局种子患者 id=1
        mockMvc.perform(get("/fhir/Patient/" + patientId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.resourceType").value("Patient"))
                .andExpect(jsonPath("$.id").value(String.valueOf(patientId)));
    }

    @Test
    void shouldReturn404ForNonExistentPatient() throws Exception {
        mockMvc.perform(get("/fhir/Patient/99999"))
                .andExpect(status().isNotFound());
    }

    /** R-02: 搜索必须带 patient 过滤条件,无参不再返回全量。 */
    @Test
    void shouldReturnEncounterList() throws Exception {
        mockMvc.perform(get("/fhir/Encounter").param("patient", String.valueOf(patientId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray());
    }

    /** R-02: 搜索必须带 patient 过滤条件,无参不再返回全量。 */
    @Test
    void shouldReturnConditionList() throws Exception {
        mockMvc.perform(get("/fhir/Condition").param("patient", String.valueOf(patientId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray());
    }

    /** R-02: 无过滤条件的搜索一律 400,不再回落成全量返回。 */
    @Test
    void shouldRejectSearchWithoutFilter() throws Exception {
        mockMvc.perform(get("/fhir/Encounter"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(get("/fhir/Condition"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(get("/fhir/Patient"))
                .andExpect(status().isBadRequest());
    }
}
