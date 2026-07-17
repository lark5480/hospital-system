package com.hospital.core.fhir.api;

import com.hospital.core.clinical.application.VisitService;
import com.hospital.core.clinical.domain.Visit;
import com.hospital.core.fhir.converter.EncounterConverter;
import com.hospital.core.fhir.domain.FhirEncounter;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/fhir/Encounter")
@RequiredArgsConstructor
public class FhirEncounterController {

    private final VisitService visitService;
    private final EncounterConverter encounterConverter;

    /** GET /fhir/Encounter/{id} */
    @GetMapping("/{id}")
    public ResponseEntity<FhirEncounter> getById(@PathVariable Long id) {
        Visit visit = visitService.get(id);
        if (visit == null) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(encounterConverter.toFhir(visit));
    }

    /** GET /fhir/Encounter?patient=X */
    @GetMapping
    public ResponseEntity<List<FhirEncounter>> search(
            @RequestParam(required = false) Long patient) {
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
