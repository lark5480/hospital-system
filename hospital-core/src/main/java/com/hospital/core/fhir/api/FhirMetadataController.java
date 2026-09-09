package com.hospital.core.fhir.api;

import java.time.LocalDateTime;
import java.util.List;

import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import com.hospital.core.fhir.domain.FhirCapabilityStatement;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

/**
 * FHIR CapabilityStatement 元数据接口。
 *
 * <p>R-02: 该接口虽不含 PHI,但会暴露本院开通的 FHIR 资源清单,属于能力面探测信息;
 * 与其它 FHIR 端点一并收口,仅允许管理员 / 接诊 / 审核角色读取。
 */
@Tag(name = "FHIR R4 标准接口", description = "FHIR CapabilityStatement 元数据接口")
@RestController
// R-02: 与其它 FHIR 端点一致收口,避免匿名探测本院 FHIR 能力面
@PreAuthorize("hasAnyAuthority('system:admin','visit:entry','visit:audit')")
public class FhirMetadataController {

    @Operation(summary = "获取FHIR服务能力声明")
    @GetMapping("/fhir/metadata")
    public ResponseEntity<FhirCapabilityStatement> getMetadata() {
        FhirCapabilityStatement cs = new FhirCapabilityStatement();
        cs.setDate(LocalDateTime.now().toString());

        FhirCapabilityStatement.RestComponent rest = new FhirCapabilityStatement.RestComponent();

        FhirCapabilityStatement.ResourceComponent patientResource = new FhirCapabilityStatement.ResourceComponent();
        patientResource.setType("Patient");
        patientResource.setInteraction(List.of(
            createInteraction("read"),
            createInteraction("search")
        ));

        FhirCapabilityStatement.ResourceComponent encounterResource = new FhirCapabilityStatement.ResourceComponent();
        encounterResource.setType("Encounter");
        encounterResource.setInteraction(List.of(
            createInteraction("read"),
            createInteraction("search")
        ));

        FhirCapabilityStatement.ResourceComponent conditionResource = new FhirCapabilityStatement.ResourceComponent();
        conditionResource.setType("Condition");
        conditionResource.setInteraction(List.of(
            createInteraction("read"),
            createInteraction("search")
        ));

        rest.setResource(List.of(patientResource, encounterResource, conditionResource));
        cs.setRest(List.of(rest));

        return ResponseEntity.ok(cs);
    }

    private FhirCapabilityStatement.InteractionComponent createInteraction(String code) {
        FhirCapabilityStatement.InteractionComponent interaction = new FhirCapabilityStatement.InteractionComponent();
        interaction.setCode(code);
        return interaction;
    }
}
