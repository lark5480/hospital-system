package com.hospital.core.clinical.api;

import java.util.List;

import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.hospital.core.clinical.application.MedicalRecordService;
import com.hospital.core.clinical.domain.MedicalRecord;
import com.hospital.core.platform.annotation.AuditLog;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;

@Tag(name = "病历管理", description = "病历创建、查询、定稿")
@RestController
@RequiredArgsConstructor
public class MedicalRecordController {

    private final MedicalRecordService medicalRecordService;

    @Operation(summary = "按就诊ID查询病历")
    @GetMapping("/api/core/medical-records")
    public ResponseEntity<MedicalRecord> get(@Parameter(description = "就诊ID") @RequestParam Long visitId) {
        MedicalRecord record = medicalRecordService.get(visitId);
        if (record == null) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(record);
    }

    @Operation(summary = "按ID查询病历")
    @GetMapping("/api/core/medical-records/{id}")
    public ResponseEntity<MedicalRecord> getById(@Parameter(description = "病历ID") @PathVariable Long id) {
        MedicalRecord record = medicalRecordService.getById(id);
        if (record == null) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(record);
    }

    @AuditLog(action = "SAVE_MEDICAL_RECORD")
    @Operation(summary = "保存病历")
    @PreAuthorize("hasAuthority('visit:entry')")
    @PostMapping("/api/core/medical-records")
    public ResponseEntity<MedicalRecord> save(
            @Parameter(description = "就诊ID") @RequestParam Long visitId,
            @RequestBody MedicalRecord record) {
        return ResponseEntity.ok(medicalRecordService.save(visitId, record));
    }

    @Operation(summary = "定稿病历")
    @PreAuthorize("hasAuthority('visit:entry')")
    @PutMapping("/api/core/medical-records/{visitId}/finalize")
    public ResponseEntity<MedicalRecord> finalize(@Parameter(description = "就诊ID") @PathVariable Long visitId) {
        return ResponseEntity.ok(medicalRecordService.finalize(visitId));
    }

    @Operation(summary = "按患者查询病历列表")
    @GetMapping("/api/core/medical-records/patient/{patientId}")
    public ResponseEntity<List<MedicalRecord>> listByPatient(@Parameter(description = "患者ID") @PathVariable Long patientId) {
        return ResponseEntity.ok(medicalRecordService.listByPatientId(patientId));
    }

    @Operation(summary = "删除病历")
    @PreAuthorize("hasAuthority('visit:entry')")
    @DeleteMapping("/api/core/medical-records/{visitId}")
    public ResponseEntity<Void> delete(@Parameter(description = "就诊ID") @PathVariable Long visitId) {
        medicalRecordService.delete(visitId);
        return ResponseEntity.noContent().build();
    }
}
