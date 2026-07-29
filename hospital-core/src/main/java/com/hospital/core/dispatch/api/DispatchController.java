package com.hospital.core.dispatch.api;

import java.util.List;

import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.hospital.core.dispatch.application.DispatchService;
import com.hospital.core.dispatch.domain.ExamTask;
import com.hospital.core.dispatch.domain.QueueBoard;
import com.hospital.core.patient.application.PatientService;
import com.hospital.core.platform.annotation.AuditLog;
import com.hospital.core.platform.security.CurrentUserResolver;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;

@Tag(name = "分诊排队", description = "排队叫号、任务调度与看板管理")
@RestController
public class DispatchController {

    private final DispatchService dispatchService;
    private final PatientService patientService;

    public DispatchController(DispatchService dispatchService, PatientService patientService) {
        this.dispatchService = dispatchService;
        this.patientService = patientService;
    }

    @Operation(summary = "查询当前患者排队队列")
    @GetMapping("/api/core/dispatch/my-queue")
    public ResponseEntity<List<ExamTask>> myQueue() {
        String username = CurrentUserResolver.resolveUsername();
        var patient = patientService.findByUsername(username);
        if (patient == null) return ResponseEntity.notFound().build();
        return ResponseEntity.ok(dispatchService.myQueue(patient.getId()));
    }

    @Operation(summary = "查询排队看板")
    @GetMapping("/api/core/dispatch/board")
    public ResponseEntity<List<QueueBoard>> board(@Parameter(description = "工位名称") @RequestParam(required = false) String station) {
        return ResponseEntity.ok(dispatchService.board(station));
    }

    @AuditLog(action = "DISPATCH_START")
    @Operation(summary = "开始检查任务")
    @PreAuthorize("hasAnyAuthority('visit:entry','visit:audit','order:execute','charge:pay','system:admin')")
    @PostMapping("/api/core/dispatch/{id}/start")
    public ResponseEntity<Void> start(@Parameter(description = "任务ID") @PathVariable Long id) {
        dispatchService.start(id);
        return ResponseEntity.ok().build();
    }

    @AuditLog(action = "DISPATCH_COMPLETE")
    @Operation(summary = "完成检查任务")
    @PreAuthorize("hasAnyAuthority('visit:entry','visit:audit','order:execute','charge:pay','system:admin')")
    @PostMapping("/api/core/dispatch/{id}/complete")
    public ResponseEntity<Void> complete(@Parameter(description = "任务ID") @PathVariable Long id) {
        dispatchService.complete(id);
        return ResponseEntity.ok().build();
    }

    @AuditLog(action = "DISPATCH_CALL_NEXT")
    @Operation(summary = "叫下一位患者")
    @PreAuthorize("hasAnyAuthority('visit:entry','visit:audit','order:execute','charge:pay','system:admin')")
    @PostMapping("/api/core/dispatch/call-next")
    public ResponseEntity<ExamTask> callNext(@Parameter(description = "工位名称") @RequestParam String station) {
        ExamTask called = dispatchService.callNext(station);
        if (called == null) return ResponseEntity.noContent().build();
        return ResponseEntity.ok(called);
    }

    @AuditLog(action = "DISPATCH_REORDER")
    @Operation(summary = "将任务移至队尾")
    @PreAuthorize("hasAnyAuthority('visit:entry','visit:audit','order:execute','charge:pay','system:admin')")
    @PostMapping("/api/core/dispatch/tasks/{id}/reorder-tail")
    public ResponseEntity<Void> reorderTail(@Parameter(description = "任务ID") @PathVariable Long id) {
        dispatchService.reorderToTail(id);
        return ResponseEntity.ok().build();
    }

    @AuditLog(action = "DISPATCH_SKIP")
    @Operation(summary = "跳过任务")
    @PreAuthorize("hasAnyAuthority('visit:entry','visit:audit','order:execute','charge:pay','system:admin')")
    @PostMapping("/api/core/dispatch/tasks/{id}/skip")
    public ResponseEntity<Void> skip(@Parameter(description = "任务ID") @PathVariable Long id) {
        dispatchService.skip(id);
        return ResponseEntity.ok().build();
    }

    @AuditLog(action = "DISPATCH_REQUEUE")
    @Operation(summary = "重新排队")
    @PreAuthorize("hasAnyAuthority('visit:entry','visit:audit','order:execute','charge:pay','system:admin')")
    @PostMapping("/api/core/dispatch/tasks/{id}/requeue")
    public ResponseEntity<Void> requeue(@Parameter(description = "任务ID") @PathVariable Long id) {
        dispatchService.requeue(id);
        return ResponseEntity.ok().build();
    }

    @Operation(summary = "查询活跃工位列表")
    @GetMapping("/api/core/dispatch/stations")
    public ResponseEntity<List<String>> stations() {
        return ResponseEntity.ok(dispatchService.listActiveStations());
    }

}
