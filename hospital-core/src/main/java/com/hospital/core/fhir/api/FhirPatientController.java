package com.hospital.core.fhir.api;

import com.hospital.core.fhir.converter.PatientConverter;
import com.hospital.core.fhir.domain.FhirPatient;
import com.hospital.core.patient.application.PatientService;
import com.hospital.core.patient.domain.Patient;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequiredArgsConstructor
public class FhirPatientController {

    private final PatientService patientService;
    private final PatientConverter patientConverter;

    @GetMapping("/fhir/Patient/{id}")
    public ResponseEntity<FhirPatient> getById(@PathVariable Long id) {
        Patient patient = patientService.get(id);
        if (patient == null) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(patientConverter.toFhir(patient));
    }

    @GetMapping("/fhir/Patient")
    public ResponseEntity<List<FhirPatient>> search(
            @RequestParam(required = false) String identifier) {
        if (identifier != null) {
            Patient patient = patientService.findByIdCard(identifier);
            if (patient != null) {
                return ResponseEntity.ok(List.of(patientConverter.toFhir(patient)));
            }
            return ResponseEntity.ok(List.of());
        }
        List<Patient> patients = patientService.list();
        List<FhirPatient> fhirPatients = patients.stream()
                .map(patientConverter::toFhir)
                .toList();
        return ResponseEntity.ok(fhirPatients);
    }
}
