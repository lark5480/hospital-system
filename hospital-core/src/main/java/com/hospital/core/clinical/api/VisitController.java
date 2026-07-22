package com.hospital.core.clinical.api;

import java.util.List;
import java.util.Map;

import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.hospital.core.clinical.application.ExamTaskVO;
import com.hospital.core.clinical.application.PageResult;
import com.hospital.core.clinical.application.PatientVisitHistoryVO;
import com.hospital.core.clinical.application.VisitDetail;
import com.hospital.core.clinical.application.VisitService;
import com.hospital.core.clinical.domain.Order;
import com.hospital.core.clinical.domain.Visit;
import com.hospital.core.org.application.StaffService;
import com.hospital.core.org.domain.Staff;
import com.hospital.core.platform.annotation.AuditLog;
import com.hospital.core.platform.security.CurrentUserResolver;

import lombok.RequiredArgsConstructor;

@RestController
@RequiredArgsConstructor
public class VisitController {

    private final VisitService visitService;
    private final StaffService staffService;

    @GetMapping("/api/core/visits/page")
    public ResponseEntity<PageResult<VisitDetail>> listPage(
            @RequestParam(required = false) String keyword,
            @RequestParam(defaultValue = "1") int pageNum,
            @RequestParam(defaultValue = "10") int pageSize) {
        return ResponseEntity.ok(visitService.listPage(keyword, pageNum, pageSize, currentDeptId()));
    }

    @PreAuthorize("hasAuthority('visit:entry')")
    @AuditLog(action = "CREATE_VISIT")
    @PostMapping("/api/core/visits")
    public ResponseEntity<Visit> create(@RequestBody Visit visit) {
        return ResponseEntity.ok(visitService.create(visit));
    }

    @PreAuthorize("hasAuthority('visit:entry')")
    @AuditLog(action = "CREATE_VISIT")
    @PostMapping("/api/core/visits/with-orders")
    public ResponseEntity<VisitDetail> createWithOrders(@RequestBody VisitWithOrdersRequest request) {
        return ResponseEntity.ok(visitService.createWithOrders(request.getVisit(), request.getOrders()));
    }

    @GetMapping("/api/core/visits")
    public ResponseEntity<List<VisitDetail>> list() {
        return ResponseEntity.ok(visitService.list(currentDeptId()));
    }

    @GetMapping("/api/core/visits/{id}")
    public ResponseEntity<Visit> get(@PathVariable Long id) {
        return ResponseEntity.ok(visitService.get(id));
    }

    @DeleteMapping("/api/core/visits/{id}")
    @PreAuthorize("hasAuthority('visit:entry')")
    @AuditLog(action = "DELETE_VISIT")
    public ResponseEntity<Void> delete(@PathVariable Long id) {
        visitService.delete(id);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/api/core/visits/{id}/detail")
    public ResponseEntity<VisitDetail> detail(@PathVariable Long id) {
        return ResponseEntity.ok(visitService.getDetail(id));
    }

    @PreAuthorize("hasAuthority('visit:entry')")
    @PostMapping("/api/core/visits/{id}/orders")
    public ResponseEntity<VisitDetail> addOrder(@PathVariable Long id, @RequestBody Order order) {
        return ResponseEntity.ok(visitService.addOrder(id, order));
    }

    @PreAuthorize("hasAuthority('visit:entry')")
    @AuditLog(action = "EDIT_ORDER")
    @PutMapping("/api/core/visits/{id}/orders/{orderId}")
    public ResponseEntity<VisitDetail> editOrder(@PathVariable Long id, @PathVariable Long orderId, @RequestBody Order order) {
        return ResponseEntity.ok(visitService.editOrder(id, orderId, order));
    }

    @PreAuthorize("hasAuthority('visit:entry')")
    @AuditLog(action = "CANCEL_ORDER")
    @DeleteMapping("/api/core/visits/{id}/orders/{orderId}")
    public ResponseEntity<VisitDetail> cancelOrder(@PathVariable Long id, @PathVariable Long orderId) {
        return ResponseEntity.ok(visitService.cancelOrder(id, orderId));
    }

    @PreAuthorize("hasAuthority('charge:pay')")
    @AuditLog(action = "REFUND_ORDER")
    @PostMapping("/api/core/visits/{id}/orders/{orderId}/refund")
    public ResponseEntity<VisitDetail> refundOrder(@PathVariable Long id, @PathVariable Long orderId) {
        return ResponseEntity.ok(visitService.refundOrder(id, orderId));
    }

    @PreAuthorize("hasAuthority('order:execute')")
    @AuditLog(action = "EXECUTE_EXAM_ORDER")
    @PostMapping("/api/core/visits/{id}/exams/{orderId}/execute")
    public ResponseEntity<VisitDetail> executeExam(@PathVariable Long id, @PathVariable Long orderId,
                                                   @RequestBody(required = false) java.util.Map<String, String> body) {
        String finding = body != null ? body.get("finding") : null;
        return ResponseEntity.ok(visitService.executeExam(id, orderId, finding));
    }

    @PreAuthorize("hasAuthority('charge:pay')")
    @AuditLog(action = "PAY_CHARGE")
    @PostMapping("/api/core/visits/{id}/pay")
    public ResponseEntity<VisitDetail> pay(@PathVariable Long id) {
        return ResponseEntity.ok(visitService.pay(id));
    }

    @PostMapping("/api/core/visits/payment-status")
    @PreAuthorize("hasAuthority('order:execute')")
    public ResponseEntity<Map<Long, Boolean>> paymentStatus(@RequestBody List<Long> visitIds) {
        return ResponseEntity.ok(visitService.paymentStatus(visitIds));
    }

    @GetMapping("/api/core/visits/exams/pending")
    @PreAuthorize("hasAuthority('order:execute')")
    public ResponseEntity<List<ExamTaskVO>> pendingExams() {
        return ResponseEntity.ok(visitService.listPendingExams(currentDeptId()));
    }

    @GetMapping("/api/core/visits/exams")
    @PreAuthorize("hasAuthority('order:execute')")
    public ResponseEntity<List<ExamTaskVO>> listExams(@RequestParam(required = false) String status) {
        return ResponseEntity.ok(visitService.listExams(status, currentDeptId()));
    }

    /** 患者历史就诊记录(含医嘱和检查所见),供新建就诊时参考。 */
    @GetMapping("/api/core/visits/patient-history")
    public ResponseEntity<List<PatientVisitHistoryVO>> patientHistory(@RequestParam Long patientId) {
        return ResponseEntity.ok(visitService.getPatientHistory(patientId));
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
        if (staff == null) return null;
        if ("ADMIN".equals(staff.getPosition()) || "system:admin".equals(staff.getPosition())) return null;
        return staff.getDeptId();
    }

    @PreAuthorize("hasAuthority('visit:entry')")
    @AuditLog(action = "CONFIRM_VISIT")
    @PostMapping("/api/core/visits/{id}/confirm")
    public ResponseEntity<VisitDetail> confirm(@PathVariable Long id) {
        return ResponseEntity.ok(visitService.confirm(id));
    }

    @PreAuthorize("hasAuthority('visit:audit')")
    @AuditLog(action = "FINISH_VISIT")
    @PostMapping("/api/core/visits/{id}/finish")
    public ResponseEntity<VisitDetail> finish(@PathVariable Long id,
            @RequestParam(defaultValue = "false") boolean force) {
        return ResponseEntity.ok(visitService.finishVisit(id, force));
    }
}
