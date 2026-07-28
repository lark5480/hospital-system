package com.hospital.core.fhir.api;

import java.util.List;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.hospital.core.clinical.application.VisitService;
import com.hospital.core.clinical.domain.Visit;
import com.hospital.core.fhir.converter.EncounterConverter;
import com.hospital.core.fhir.domain.FhirEncounter;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;

@Tag(name = "FHIR R4 标准接口", description = "FHIR Encounter 资源接口")
@RestController
@RequiredArgsConstructor
public class FhirEncounterController {

    private final VisitService visitService;
    private final EncounterConverter encounterConverter;

    @Operation(summary = "按ID获取Encounter资源")
    @GetMapping("/fhir/Encounter/{id}")
    public ResponseEntity<FhirEncounter> getById(@Parameter(description = "就诊单ID") @PathVariable Long id) {
        Visit visit = visitService.get(id);
        if (visit == null) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(encounterConverter.toFhir(visit));
    }

    @Operation(summary = "搜索Encounter资源")
    @GetMapping("/fhir/Encounter")
    public ResponseEntity<List<FhirEncounter>> search(
            @Parameter(description = "患者ID") @RequestParam(required = false) Long patient) {
        List<Visit> visits;
        if (patient != null) {
            visits = visitService.listByPatientId(patient);
        } else {
            visits = visitService.listAll();
        }
        List<FhirEncounter> fhirEncounters = visits.stream()
                .map(encounterConverter::toFhir)
                .toList();
        return ResponseEntity.ok(fhirEncounters);
    }
}
