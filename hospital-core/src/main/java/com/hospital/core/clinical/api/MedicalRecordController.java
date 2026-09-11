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
import com.hospital.core.org.application.StaffService;
import com.hospital.core.patient.application.PatientService;
import com.hospital.core.platform.annotation.AuditLog;
import com.hospital.core.platform.security.CurrentUserResolver;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;

/**
 * 病历管理接口。
 *
 * <p>R-08 收口说明:病历读接口原先无鉴权且 ID 由前端直传、无归属校验(典型 IDOR)。
 * 现补读权限,并对"按 ID / 按患者"的读接口做归属校验 —— 患者角色只能看自己。
 */
@Tag(name = "病历管理", description = "病历创建、查询、定稿")
@RestController
@RequiredArgsConstructor
public class MedicalRecordController {

    private final MedicalRecordService medicalRecordService;
    // R-08: 归属校验依赖 —— 员工判定 + 当前患者档案解析
    private final StaffService staffService;
    private final PatientService patientService;

    @Operation(summary = "按就诊ID查询病历")
    // R-08: 读接口对患者(patient:booking)开放(病历属 PHI),仍保留 IDOR 归属校验(visitId 由前端直传)
    @PreAuthorize("hasAnyAuthority('visit:entry','visit:audit','system:admin','patient:booking')")
    @GetMapping("/api/core/medical-records")
    public ResponseEntity<MedicalRecord> get(@Parameter(description = "就诊ID") @RequestParam Long visitId) {
        MedicalRecord record = medicalRecordService.get(visitId);
        if (record == null) {
            return ResponseEntity.notFound().build();
        }
        if (!canReadPatient(record.getPatientId())) {
            return ResponseEntity.status(403).build();
        }
        return ResponseEntity.ok(record);
    }

    @Operation(summary = "按ID查询病历")
    // R-08: 读接口对患者(patient:booking)开放,仍保留 IDOR 归属校验(病历 ID 由前端直传)
    @PreAuthorize("hasAnyAuthority('visit:entry','visit:audit','system:admin','patient:booking')")
    @GetMapping("/api/core/medical-records/{id}")
    public ResponseEntity<MedicalRecord> getById(@Parameter(description = "病历ID") @PathVariable Long id) {
        MedicalRecord record = medicalRecordService.getById(id);
        if (record == null) {
            return ResponseEntity.notFound().build();
        }
        if (!canReadPatient(record.getPatientId())) {
            return ResponseEntity.status(403).build();
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
    // R-08: 读接口对患者(patient:booking)开放;入参 patientId 一律经 resolveReadablePatientId 覆盖,
    //       患者角色被强制改写成本人 patientId,不存在"改 id 看他人病历"的绕行路径
    @PreAuthorize("hasAnyAuthority('visit:entry','visit:audit','system:admin','patient:booking')")
    @GetMapping("/api/core/medical-records/patient/{patientId}")
    public ResponseEntity<List<MedicalRecord>> listByPatient(@Parameter(description = "患者ID") @PathVariable Long patientId) {
        Long effectivePatientId = resolveReadablePatientId(patientId);
        if (effectivePatientId == null) {
            return ResponseEntity.status(403).build();
        }
        return ResponseEntity.ok(medicalRecordService.listByPatientId(effectivePatientId));
    }

    @Operation(summary = "删除病历")
    @PreAuthorize("hasAuthority('visit:entry')")
    @DeleteMapping("/api/core/medical-records/{visitId}")
    public ResponseEntity<Void> delete(@Parameter(description = "就诊ID") @PathVariable Long visitId) {
        medicalRecordService.delete(visitId);
        return ResponseEntity.noContent().build();
    }

    /**
     * R-08: 归属校验 —— 以 patientId 为入参的读接口统一走这里。
     * 员工(医护 / 管理员)允许按入参查询;患者角色强制覆盖为本人 patientId,解析不到则拒绝。
     *
     * @return 允许查询的患者 ID;null 表示无权(调用方返回 403)
     */
    private Long resolveReadablePatientId(Long requestedPatientId) {
        if (isStaff()) {
            return requestedPatientId;
        }
        return patientService.currentPatientId();
    }

    /** R-08: 当前主体是否有权读取指定患者的数据(患者角色仅可看自己)。 */
    private boolean canReadPatient(Long targetPatientId) {
        if (isStaff()) {
            return true;
        }
        Long own = patientService.currentPatientId();
        return own != null && own.equals(targetPatientId);
    }

    /** R-08: 当前登录主体是否为员工(医护 / 管理员);患者账号返回 false。 */
    private boolean isStaff() {
        String phone = CurrentUserResolver.resolveUsername();
        return phone != null && staffService.findByPhone(phone) != null;
    }
}
