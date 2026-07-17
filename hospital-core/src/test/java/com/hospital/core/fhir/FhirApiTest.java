package com.hospital.core.fhir;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@WithMockUser
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

    @Test
    void shouldReturnEncounterList() throws Exception {
        mockMvc.perform(get("/fhir/Encounter"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray());
    }

    @Test
    void shouldReturnConditionList() throws Exception {
        mockMvc.perform(get("/fhir/Condition"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray());
    }
}
