package com.hospital.core.lab.api;

import com.hospital.core.lab.application.LabRequisitionDetail;
import com.hospital.core.lab.application.LabRequisitionListItem;
import com.hospital.core.lab.application.LabService;
import com.hospital.core.lab.domain.LabRequisition;
import com.hospital.core.platform.annotation.AuditLog;
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

    @GetMapping("/api/lab/requisitions")
    public ResponseEntity<List<LabRequisitionListItem>> list(
            @RequestParam(required = false) String status) {
        return ResponseEntity.ok(labService.listWithDetail(status));
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
    public ResponseEntity<LabRequisition> submitResults(
            @PathVariable Long id, @RequestBody SubmitResultsRequest req) {
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
    public ResponseEntity<LabRequisition> cancel(@PathVariable Long id) {
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
}
