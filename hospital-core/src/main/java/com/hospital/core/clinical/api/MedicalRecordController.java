package com.hospital.core.clinical.api;

import com.hospital.core.clinical.application.MedicalRecordService;
import com.hospital.core.clinical.domain.MedicalRecord;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequiredArgsConstructor
public class MedicalRecordController {

    private final MedicalRecordService medicalRecordService;

    @GetMapping("/api/core/medical-records")
    public ResponseEntity<MedicalRecord> get(@RequestParam Long visitId) {
        MedicalRecord record = medicalRecordService.get(visitId);
        if (record == null) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(record);
    }

    @GetMapping("/api/core/medical-records/{id}")
    public ResponseEntity<MedicalRecord> getById(@PathVariable Long id) {
        MedicalRecord record = medicalRecordService.getById(id);
        if (record == null) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(record);
    }

    @PreAuthorize("hasAuthority('visit:entry')")
    @PostMapping("/api/core/medical-records")
    public ResponseEntity<MedicalRecord> save(
            @RequestParam Long visitId,
            @RequestBody MedicalRecord record) {
        return ResponseEntity.ok(medicalRecordService.save(visitId, record));
    }

    @PreAuthorize("hasAuthority('visit:entry')")
    @PutMapping("/api/core/medical-records/{visitId}/finalize")
    public ResponseEntity<MedicalRecord> finalize(@PathVariable Long visitId) {
        return ResponseEntity.ok(medicalRecordService.finalize(visitId));
    }

    @GetMapping("/api/core/medical-records/patient/{patientId}")
    public ResponseEntity<List<MedicalRecord>> listByPatient(@PathVariable Long patientId) {
        return ResponseEntity.ok(medicalRecordService.listByPatientId(patientId));
    }

    @PreAuthorize("hasAuthority('visit:entry')")
    @DeleteMapping("/api/core/medical-records/{visitId}")
    public ResponseEntity<Void> delete(@PathVariable Long visitId) {
        medicalRecordService.delete(visitId);
        return ResponseEntity.noContent().build();
    }
}
