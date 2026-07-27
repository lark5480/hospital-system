package com.hospital.core.dispatch.api;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.hospital.core.dispatch.application.DispatchService;
import com.hospital.core.dispatch.domain.ExamTask;
import com.hospital.core.dispatch.domain.QueueBoard;
import com.hospital.core.patient.application.PatientService;
import com.hospital.core.platform.security.CurrentUserResolver;

@RestController
public class DispatchController {

    private final DispatchService dispatchService;
    private final PatientService patientService;

    public DispatchController(DispatchService dispatchService, PatientService patientService) {
        this.dispatchService = dispatchService;
        this.patientService = patientService;
    }

    @GetMapping("/api/core/dispatch/my-queue")
    public ResponseEntity<List<ExamTask>> myQueue() {
        String username = CurrentUserResolver.resolveUsername();
        var patient = patientService.findByUsername(username);
        if (patient == null) return ResponseEntity.notFound().build();
        return ResponseEntity.ok(dispatchService.myQueue(patient.getId()));
    }

    @GetMapping("/api/core/dispatch/board")
    public ResponseEntity<List<QueueBoard>> board(@RequestParam(required = false) String station) {
        return ResponseEntity.ok(dispatchService.board(station));
    }

    @PreAuthorize("hasAnyAuthority('visit:entry','visit:audit','order:execute','charge:pay','system:admin')")
    @PostMapping("/api/core/dispatch/{id}/start")
    public ResponseEntity<Void> start(@PathVariable Long id) {
        dispatchService.start(id);
        return ResponseEntity.ok().build();
    }

    @PreAuthorize("hasAnyAuthority('visit:entry','visit:audit','order:execute','charge:pay','system:admin')")
    @PostMapping("/api/core/dispatch/{id}/complete")
    public ResponseEntity<Void> complete(@PathVariable Long id) {
        dispatchService.complete(id);
        return ResponseEntity.ok().build();
    }

    @PreAuthorize("hasAnyAuthority('visit:entry','visit:audit','order:execute','charge:pay','system:admin')")
    @PostMapping("/api/core/dispatch/call-next")
    public ResponseEntity<ExamTask> callNext(@RequestParam String station) {
        ExamTask called = dispatchService.callNext(station);
        if (called == null) return ResponseEntity.noContent().build();
        return ResponseEntity.ok(called);
    }

    @PreAuthorize("hasAnyAuthority('visit:entry','visit:audit','order:execute','charge:pay','system:admin')")
    @PostMapping("/api/core/dispatch/tasks/{id}/reorder-tail")
    public ResponseEntity<Void> reorderTail(@PathVariable Long id) {
        dispatchService.reorderToTail(id);
        return ResponseEntity.ok().build();
    }

    @PreAuthorize("hasAnyAuthority('visit:entry','visit:audit','order:execute','charge:pay','system:admin')")
    @PostMapping("/api/core/dispatch/tasks/{id}/skip")
    public ResponseEntity<Void> skip(@PathVariable Long id) {
        dispatchService.skip(id);
        return ResponseEntity.ok().build();
    }

    @PreAuthorize("hasAnyAuthority('visit:entry','visit:audit','order:execute','charge:pay','system:admin')")
    @PostMapping("/api/core/dispatch/tasks/{id}/requeue")
    public ResponseEntity<Void> requeue(@PathVariable Long id) {
        dispatchService.requeue(id);
        return ResponseEntity.ok().build();
    }

    @GetMapping("/api/core/dispatch/stations")
    public ResponseEntity<List<String>> stations() {
        return ResponseEntity.ok(dispatchService.listActiveStations());
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, String>> handleNotFound(IllegalArgumentException ex) {
        Map<String, String> body = new HashMap<>();
        body.put("error", ex.getMessage());
        return ResponseEntity.status(404).body(body);
    }

    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<Map<String, String>> handleConflict(IllegalStateException ex) {
        Map<String, String> body = new HashMap<>();
        body.put("error", ex.getMessage());
        return ResponseEntity.status(409).body(body);
    }
}
