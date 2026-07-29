package com.hospital.core.pharmacy.api;

import java.util.List;

import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.hospital.core.org.application.StaffService;
import com.hospital.core.org.domain.Staff;
import com.hospital.core.pharmacy.application.PrescriptionDetail;
import com.hospital.core.pharmacy.application.PrescriptionService;
import com.hospital.core.pharmacy.domain.Prescription;
import com.hospital.core.platform.annotation.AuditLog;
import com.hospital.core.platform.security.CurrentUserResolver;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

@Tag(name = "药房管理", description = "处方的创建、查询、发药与取消")
@RestController
@RequiredArgsConstructor
public class PrescriptionController {

    private final PrescriptionService prescriptionService;
    private final StaffService staffService;

    @Operation(summary = "查询处方列表")
    @GetMapping("/api/pharmacy/prescriptions")
    public ResponseEntity<List<Prescription>> list(
            @Parameter(description = "处方状态") @RequestParam(required = false) String status) {
        return ResponseEntity.ok(prescriptionService.list(status));
    }

    @Operation(summary = "查询处方详细列表")
    @GetMapping("/api/pharmacy/prescriptions/list/detail")
    public ResponseEntity<List<PrescriptionDetail>> listDetail(
            @Parameter(description = "处方状态") @RequestParam(required = false) String status,
            @Parameter(description = "关键字搜索") @RequestParam(required = false) String keyword) {
        return ResponseEntity.ok(prescriptionService.listWithDetail(status, keyword));
    }

    @Operation(summary = "获取处方详情")
    @GetMapping("/api/pharmacy/prescriptions/{id}")
    public ResponseEntity<PrescriptionDetail> get(@Parameter(description = "处方ID") @PathVariable Long id) {
        PrescriptionDetail detail = prescriptionService.getDetail(id);
        if (detail == null) return ResponseEntity.notFound().build();
        return ResponseEntity.ok(detail);
    }

    @Operation(summary = "创建处方")
    @PreAuthorize("hasAuthority('visit:entry')")
    @AuditLog(action = "CREATE_PRESCRIPTION")
    @PostMapping("/api/pharmacy/prescriptions")
    public ResponseEntity<Prescription> create(@Valid @RequestBody CreatePrescriptionRequest req) {
        Prescription p = prescriptionService.createFromVisit(req.getVisitId(), req.getDoctorId());
        return ResponseEntity.ok(p);
    }

    @Operation(summary = "处方发药")
    @PreAuthorize("hasAuthority('pharmacy:dispense')")
    @AuditLog(action = "DISPENSE")
    @PostMapping("/api/pharmacy/prescriptions/{id}/dispense")
    public ResponseEntity<Prescription> dispense(@Parameter(description = "处方ID") @PathVariable Long id,
            @Valid @RequestBody DispenseRequest req) {
        Long pharmacistId = resolvePharmacistId();
        return ResponseEntity.ok(prescriptionService.dispense(id, pharmacistId, req.getRemark()));
    }

    private Long resolvePharmacistId() {
        String phone = CurrentUserResolver.resolveUsername();
        if (phone == null) return 0L;
        Staff staff = staffService.findByPhone(phone);
        return staff != null ? staff.getId() : 0L;
    }

    @Operation(summary = "取消处方")
    @PreAuthorize("hasAuthority('pharmacy:dispense')")
    @AuditLog(action = "CANCEL_PRESCRIPTION")
    @PostMapping("/api/pharmacy/prescriptions/{id}/cancel")
    public ResponseEntity<Prescription> cancel(@Parameter(description = "处方ID") @PathVariable Long id) {
        return ResponseEntity.ok(prescriptionService.cancel(id));
    }

}
