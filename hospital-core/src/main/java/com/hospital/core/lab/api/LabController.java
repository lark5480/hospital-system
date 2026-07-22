package com.hospital.core.lab.api;

import com.hospital.core.lab.application.LabRequisitionDetail;
import com.hospital.core.lab.application.LabRequisitionListItem;
import com.hospital.core.lab.application.LabService;
import com.hospital.core.lab.domain.LabRequisition;
import com.hospital.core.org.application.StaffService;
import com.hospital.core.org.domain.Staff;
import com.hospital.core.platform.annotation.AuditLog;
import com.hospital.core.platform.security.CurrentUserResolver;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequiredArgsConstructor
public class LabController {

    private final LabService labService;
    private final StaffService staffService;

    @GetMapping("/api/lab/requisitions")
    public ResponseEntity<List<LabRequisitionListItem>> list(
            @RequestParam(required = false) String status,
            @RequestParam(required = false) Long deptId) {
        return ResponseEntity.ok(labService.listWithDetail(status, deptId));
    }

    @GetMapping("/api/lab/requisitions/{id}")
    public ResponseEntity<LabRequisitionDetail> get(@PathVariable Long id) {
        LabRequisitionDetail detail = labService.getDetail(id);
        if (detail == null) return ResponseEntity.notFound().build();
        return ResponseEntity.ok(detail);
    }

    @PreAuthorize("hasAuthority('visit:entry')")
    @AuditLog(action = "CREATE_REQUISITION")
    @PostMapping("/api/lab/requisitions")
    public ResponseEntity<LabRequisition> create(@RequestBody CreateRequisitionRequest req) {
        return ResponseEntity.ok(labService.createFromVisit(
                req.getVisitId(), req.getDoctorId(), req.getOrderIds()));
    }

    @PreAuthorize("hasAuthority('order:execute')")
    @AuditLog(action = "SUBMIT_RESULTS")
    @PostMapping("/api/lab/requisitions/{id}/results")
    public ResponseEntity<?> submitResults(
            @PathVariable Long id, @RequestBody SubmitResultsRequest req) {
        // 校验执行科室:只有申请所属科室的人员才能执行
        Long currentDeptId = currentDeptId();
        if (currentDeptId != null) {
            boolean hasAccess = labService.hasAccessToRequisition(id, currentDeptId);
            if (!hasAccess) {
                return ResponseEntity.status(403).body(Map.of("message", "无权执行其他科室的检验申请"));
            }
        }
        var entries = req.getItems().stream().map(i -> {
            var e = new LabService.ResultEntry();
            e.setItemId(i.getItemId());
            e.setResultValue(i.getResultValue());
            e.setUnit(i.getUnit());
            e.setRefRange(i.getRefRange());
            e.setAbnormalFlag(i.getAbnormalFlag());
            return e;
        }).toList();
        return ResponseEntity.ok(labService.submitResults(id, req.getTechnicianId(), entries));
    }

    @PreAuthorize("hasAuthority('order:execute')")
    @AuditLog(action = "CANCEL_REQUISITION")
    @PostMapping("/api/lab/requisitions/{id}/cancel")
    public ResponseEntity<?> cancel(@PathVariable Long id) {
        // 校验执行科室:只有申请所属科室的人员才能取消
        Long currentDeptId = currentDeptId();
        if (currentDeptId != null) {
            boolean hasAccess = labService.hasAccessToRequisition(id, currentDeptId);
            if (!hasAccess) {
                return ResponseEntity.status(403).body(Map.of("message", "无权取消其他科室的检验申请"));
            }
        }
        return ResponseEntity.ok(labService.cancel(id));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, String>> handleNotFound(IllegalArgumentException ex) {
        return ResponseEntity.status(404).body(Map.of("message", ex.getMessage()));
    }

    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<Map<String, String>> handleConflict(IllegalStateException ex) {
        return ResponseEntity.status(409).body(Map.of("message", ex.getMessage()));
    }

    private Long currentDeptId() {
        String phone = CurrentUserResolver.resolveUsername(null);
        if (phone == null) return null;
        Staff staff = staffService.findByPhone(phone);
        return staff != null ? staff.getDeptId() : null;
    }
}
