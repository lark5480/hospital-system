package com.hospital.core.fhir.api;

import java.util.List;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.hospital.core.fhir.converter.PatientConverter;
import com.hospital.core.fhir.domain.FhirPatient;
import com.hospital.core.patient.application.PatientService;
import com.hospital.core.patient.domain.Patient;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;

@Tag(name = "FHIR R4 标准接口", description = "FHIR Patient 资源接口")
@RestController
@RequiredArgsConstructor
public class FhirPatientController {

    private final PatientService patientService;
    private final PatientConverter patientConverter;

    @Operation(summary = "按ID获取Patient资源")
    @GetMapping("/fhir/Patient/{id}")
    public ResponseEntity<FhirPatient> getById(@Parameter(description = "患者ID") @PathVariable Long id) {
        Patient patient = patientService.get(id);
        if (patient == null) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(patientConverter.toFhir(patient));
    }

    @Operation(summary = "搜索Patient资源")
    @GetMapping("/fhir/Patient")
    public ResponseEntity<List<FhirPatient>> search(
            @Parameter(description = "身份证号") @RequestParam(required = false) String identifier) {
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
