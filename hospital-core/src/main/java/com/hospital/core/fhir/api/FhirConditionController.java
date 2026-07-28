package com.hospital.core.fhir.api;

import java.util.List;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.hospital.core.clinical.application.VisitService;
import com.hospital.core.clinical.domain.Visit;
import com.hospital.core.fhir.converter.ConditionConverter;
import com.hospital.core.fhir.domain.FhirCondition;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;

@Tag(name = "FHIR R4 标准接口", description = "FHIR Condition 资源接口")
@RestController
@RequiredArgsConstructor
public class FhirConditionController {

    private final VisitService visitService;
    private final ConditionConverter conditionConverter;

    @Operation(summary = "按ID获取Condition资源")
    @GetMapping("/fhir/Condition/{id}")
    public ResponseEntity<FhirCondition> getById(@Parameter(description = "就诊单ID") @PathVariable Long id) {
        Visit visit = visitService.get(id);
        if (visit == null) {
            return ResponseEntity.notFound().build();
        }
        FhirCondition condition = conditionConverter.toFhir(visit);
        if (condition == null) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(condition);
    }

    @Operation(summary = "搜索Condition资源")
    @GetMapping("/fhir/Condition")
    public ResponseEntity<List<FhirCondition>> search(
            @Parameter(description = "患者ID") @RequestParam(required = false) Long patient) {
        List<Visit> visits;
        if (patient != null) {
            visits = visitService.listByPatientId(patient);
        } else {
            visits = visitService.listAll();
        }
        List<FhirCondition> conditions = visits.stream()
                .map(conditionConverter::toFhir)
                .filter(c -> c != null)
                .toList();
        return ResponseEntity.ok(conditions);
    }
}
