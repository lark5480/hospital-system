package com.hospital.core.clinical.api;

import com.hospital.core.clinical.application.MedicalRecordService;
import com.hospital.core.clinical.domain.MedicalRecord;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/core/medical-records")
@RequiredArgsConstructor
public class MedicalRecordController {

    private final MedicalRecordService medicalRecordService;

    /** GET /api/core/medical-records?visitId=X */
    @GetMapping
    public ResponseEntity<MedicalRecord> get(@RequestParam Long visitId) {
        MedicalRecord record = medicalRecordService.get(visitId);
        if (record == null) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(record);
    }

    /** GET /api/core/medical-records/{id} */
    @GetMapping("/{id}")
    public ResponseEntity<MedicalRecord> getById(@PathVariable Long id) {
        MedicalRecord record = medicalRecordService.getById(id);
        if (record == null) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(record);
    }

    /** POST /api/core/medical-records?visitId=X */
    @PreAuthorize("hasAuthority('visit:entry')")
    @PostMapping
    public ResponseEntity<MedicalRecord> save(
            @RequestParam Long visitId,
            @RequestBody MedicalRecord record) {
        return ResponseEntity.ok(medicalRecordService.save(visitId, record));
    }

    /** PUT /api/core/medical-records/{visitId}/finalize */
    @PreAuthorize("hasAuthority('visit:entry')")
    @PutMapping("/{visitId}/finalize")
    public ResponseEntity<MedicalRecord> finalize(@PathVariable Long visitId) {
        return ResponseEntity.ok(medicalRecordService.finalize(visitId));
    }

    /** GET /api/core/medical-records/patient/{patientId} */
    @GetMapping("/patient/{patientId}")
    public ResponseEntity<List<MedicalRecord>> listByPatient(@PathVariable Long patientId) {
        return ResponseEntity.ok(medicalRecordService.listByPatientId(patientId));
    }

    /** DELETE /api/core/medical-records/{visitId} */
    @PreAuthorize("hasAuthority('visit:entry')")
    @DeleteMapping("/{visitId}")
    public ResponseEntity<Void> delete(@PathVariable Long visitId) {
        medicalRecordService.delete(visitId);
        return ResponseEntity.noContent().build();
    }
}
