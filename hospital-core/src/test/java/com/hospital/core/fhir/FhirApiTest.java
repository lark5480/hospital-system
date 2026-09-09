package com.hospital.core.fhir;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
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
 */
@SpringBootTest
@AutoConfigureMockMvc
@WithMockUser(authorities = "system:admin")
class FhirApiTest {

    @Autowired
    private MockMvc mockMvc;

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
        mockMvc.perform(get("/fhir/Patient/1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.resourceType").value("Patient"))
                .andExpect(jsonPath("$.id").value("1"));
    }

    @Test
    void shouldReturn404ForNonExistentPatient() throws Exception {
        mockMvc.perform(get("/fhir/Patient/99999"))
                .andExpect(status().isNotFound());
    }

    /** R-02: 搜索必须带 patient 过滤条件,无参不再返回全量。 */
    @Test
    void shouldReturnEncounterList() throws Exception {
        mockMvc.perform(get("/fhir/Encounter").param("patient", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray());
    }

    /** R-02: 搜索必须带 patient 过滤条件,无参不再返回全量。 */
    @Test
    void shouldReturnConditionList() throws Exception {
        mockMvc.perform(get("/fhir/Condition").param("patient", "1"))
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
