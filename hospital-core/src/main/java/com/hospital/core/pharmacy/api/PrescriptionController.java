package com.hospital.core.pharmacy.api;

import com.hospital.core.org.application.StaffService;
import com.hospital.core.org.domain.Staff;
import com.hospital.core.pharmacy.application.PrescriptionDetail;
import com.hospital.core.pharmacy.application.PrescriptionService;
import com.hospital.core.pharmacy.domain.Prescription;
import com.hospital.core.platform.annotation.AuditLog;
import com.hospital.core.platform.security.CurrentUserResolver;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequiredArgsConstructor
public class PrescriptionController {

    private final PrescriptionService prescriptionService;
    private final StaffService staffService;

    @GetMapping("/api/pharmacy/prescriptions")
    public ResponseEntity<List<Prescription>> list(
            @RequestParam(required = false) String status) {
        return ResponseEntity.ok(prescriptionService.list(status));
    }

    @GetMapping("/api/pharmacy/prescriptions/list/detail")
    public ResponseEntity<List<PrescriptionDetail>> listDetail(
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String keyword) {
        return ResponseEntity.ok(prescriptionService.listWithDetail(status, keyword));
    }

    @GetMapping("/api/pharmacy/prescriptions/{id}")
    public ResponseEntity<PrescriptionDetail> get(@PathVariable Long id) {
        PrescriptionDetail detail = prescriptionService.getDetail(id);
        if (detail == null) return ResponseEntity.notFound().build();
        return ResponseEntity.ok(detail);
    }

    @PreAuthorize("hasAuthority('visit:entry')")
    @AuditLog(action = "CREATE_PRESCRIPTION")
    @PostMapping("/api/pharmacy/prescriptions")
    public ResponseEntity<Prescription> create(@RequestBody CreatePrescriptionRequest req) {
        Prescription p = prescriptionService.createFromVisit(req.getVisitId(), req.getDoctorId());
        return ResponseEntity.ok(p);
    }

    @PreAuthorize("hasAuthority('pharmacy:dispense')")
    @AuditLog(action = "DISPENSE")
    @PostMapping("/api/pharmacy/prescriptions/{id}/dispense")
    public ResponseEntity<Prescription> dispense(@PathVariable Long id,
            @RequestBody DispenseRequest req,
            HttpServletRequest request) {
        Long pharmacistId = resolvePharmacistId(request);
        return ResponseEntity.ok(prescriptionService.dispense(id, pharmacistId, req.getRemark()));
    }

    private Long resolvePharmacistId(HttpServletRequest request) {
        String phone = CurrentUserResolver.resolveUsername(request);
        if (phone == null) return 0L;
        Staff staff = staffService.findByPhone(phone);
        return staff != null ? staff.getId() : 0L;
    }

    @PreAuthorize("hasAuthority('pharmacy:dispense')")
    @AuditLog(action = "CANCEL_PRESCRIPTION")
    @PostMapping("/api/pharmacy/prescriptions/{id}/cancel")
    public ResponseEntity<Prescription> cancel(@PathVariable Long id) {
        return ResponseEntity.ok(prescriptionService.cancel(id));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, String>> handleNotFound(IllegalArgumentException ex) {
        return ResponseEntity.status(404).body(Map.of("message", ex.getMessage()));
    }

    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<Map<String, String>> handleConflict(IllegalStateException ex) {
        return ResponseEntity.status(409).body(Map.of("message", ex.getMessage()));
    }
}
