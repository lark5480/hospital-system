package com.hospital.core.fhir.api;

import com.hospital.core.clinical.application.VisitService;
import com.hospital.core.clinical.domain.Visit;
import com.hospital.core.fhir.converter.ConditionConverter;
import com.hospital.core.fhir.domain.FhirCondition;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/fhir/Condition")
@RequiredArgsConstructor
public class FhirConditionController {

    private final VisitService visitService;
    private final ConditionConverter conditionConverter;

    /** GET /fhir/Condition/{id} */
    @GetMapping("/{id}")
    public ResponseEntity<FhirCondition> getById(@PathVariable Long id) {
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

    /** GET /fhir/Condition?patient=X */
    @GetMapping
    public ResponseEntity<List<FhirCondition>> search(
            @RequestParam(required = false) Long patient) {
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
