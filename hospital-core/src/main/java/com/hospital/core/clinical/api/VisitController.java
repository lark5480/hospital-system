package com.hospital.core.clinical.api;

import java.util.List;
import java.util.Map;

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

import com.hospital.core.clinical.application.ExamTaskVO;
import com.hospital.core.clinical.application.PageResult;
import com.hospital.core.clinical.application.PatientVisitHistoryVO;
import com.hospital.core.clinical.application.VisitDetail;
import com.hospital.core.clinical.application.VisitService;
import com.hospital.core.clinical.domain.Order;
import com.hospital.core.clinical.domain.Visit;
import com.hospital.core.org.application.StaffService;
import com.hospital.core.org.domain.Staff;
import com.hospital.core.patient.application.PatientService;
import com.hospital.core.platform.annotation.AuditLog;
import com.hospital.core.platform.security.CurrentUserResolver;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

@Tag(name = "就诊管理", description = "就诊单的创建、查询、医嘱管理及状态流转")
@RestController
@RequiredArgsConstructor
public class VisitController {

    private final VisitService visitService;
    private final StaffService staffService;
    // R-08: 用于解析当前登录患者档案,做 IDOR 归属校验
    private final PatientService patientService;

    @Operation(summary = "分页查询就诊列表")
    // R-08: 科室 / 全院维度的分页列表,入参只有关键字、无 patientId 可归属校验 ——
    //       患者角色一律不放行(放行即等于可翻全院就诊),保持医护 / 管理员专用
    @PreAuthorize("hasAnyAuthority('visit:entry','visit:audit','system:admin')")
    @GetMapping("/api/core/visits/page")
    public ResponseEntity<PageResult<VisitDetail>> listPage(
            @Parameter(description = "关键字搜索") @RequestParam(required = false) String keyword,
            @Parameter(description = "页码") @RequestParam(defaultValue = "1") int pageNum,
            @Parameter(description = "每页条数") @RequestParam(defaultValue = "10") int pageSize) {
        return ResponseEntity.ok(visitService.listPage(keyword, pageNum, pageSize, currentDeptId()));
    }

    @Operation(summary = "创建就诊单")
    @PreAuthorize("hasAuthority('visit:entry')")
    @AuditLog(action = "CREATE_VISIT")
    @PostMapping("/api/core/visits")
    public ResponseEntity<Visit> create(@Valid @RequestBody Visit visit) {
        return ResponseEntity.ok(visitService.create(visit));
    }

    @Operation(summary = "创建就诊单并附带医嘱")
    @PreAuthorize("hasAuthority('visit:entry')")
    @AuditLog(action = "CREATE_VISIT")
    @PostMapping("/api/core/visits/with-orders")
    public ResponseEntity<VisitDetail> createWithOrders(@Valid @RequestBody VisitWithOrdersRequest request) {
        return ResponseEntity.ok(visitService.createWithOrders(request.getVisit(), request.getOrders()));
    }

    @Operation(summary = "查询当前科室就诊列表")
    // R-08: 读接口对患者(patient:booking)开放;患者账号无科室上下文,
    //       VisitService.list 已对"无科室上下文的全量查询"兜底返回空列表,不会因此泄全量就诊
    @PreAuthorize("hasAnyAuthority('visit:entry','visit:audit','system:admin','patient:booking')")
    @GetMapping("/api/core/visits")
    public ResponseEntity<List<VisitDetail>> list() {
        return ResponseEntity.ok(visitService.list(currentDeptId()));
    }

    @Operation(summary = "获取就诊单详情")
    // R-08: 读接口对患者(patient:booking)开放,并补 IDOR 归属校验(就诊单 ID 由前端直传)
    @PreAuthorize("hasAnyAuthority('visit:entry','visit:audit','system:admin','patient:booking')")
    @GetMapping("/api/core/visits/{id}")
    public ResponseEntity<Visit> get(@Parameter(description = "就诊单ID") @PathVariable Long id) {
        Visit visit = visitService.get(id);
        if (visit == null) {
            return ResponseEntity.notFound().build();
        }
        // R-08: 患者角色只能读自己名下的就诊单
        if (!canReadPatient(visit.getPatientId())) {
            return ResponseEntity.status(403).build();
        }
        return ResponseEntity.ok(visit);
    }

    @Operation(summary = "删除就诊单")
    @DeleteMapping("/api/core/visits/{id}")
    @PreAuthorize("hasAuthority('visit:entry')")
    @AuditLog(action = "DELETE_VISIT")
    public ResponseEntity<Void> delete(@Parameter(description = "就诊单ID") @PathVariable Long id) {
        visitService.delete(id);
        return ResponseEntity.noContent().build();
    }

    @Operation(summary = "获取就诊单详细信息")
    // R-08: 读接口对患者(patient:booking)开放,保留 IDOR 归属校验(visitId 由前端直传)
    @PreAuthorize("hasAnyAuthority('visit:entry','visit:audit','system:admin','patient:booking')")
    @GetMapping("/api/core/visits/{id}/detail")
    public ResponseEntity<VisitDetail> detail(@Parameter(description = "就诊单ID") @PathVariable Long id) {
        Visit visit = visitService.get(id);
        if (visit == null) {
            return ResponseEntity.notFound().build();
        }
        if (!canReadPatient(visit.getPatientId())) {
            return ResponseEntity.status(403).build();
        }
        return ResponseEntity.ok(visitService.getDetail(id));
    }

    @Operation(summary = "为就诊单添加医嘱")
    @PreAuthorize("hasAuthority('visit:entry')")
    @PostMapping("/api/core/visits/{id}/orders")
    public ResponseEntity<VisitDetail> addOrder(@Parameter(description = "就诊单ID") @PathVariable Long id, @Valid @RequestBody Order order) {
        return ResponseEntity.ok(visitService.addOrder(id, order));
    }

    @Operation(summary = "编辑医嘱")
    @PreAuthorize("hasAuthority('visit:entry')")
    @AuditLog(action = "EDIT_ORDER")
    @PutMapping("/api/core/visits/{id}/orders/{orderId}")
    public ResponseEntity<VisitDetail> editOrder(@Parameter(description = "就诊单ID") @PathVariable Long id, @Parameter(description = "医嘱ID") @PathVariable Long orderId, @Valid @RequestBody Order order) {
        return ResponseEntity.ok(visitService.editOrder(id, orderId, order));
    }

    @Operation(summary = "取消医嘱")
    @PreAuthorize("hasAuthority('visit:entry')")
    @AuditLog(action = "CANCEL_ORDER")
    @DeleteMapping("/api/core/visits/{id}/orders/{orderId}")
    public ResponseEntity<VisitDetail> cancelOrder(@Parameter(description = "就诊单ID") @PathVariable Long id, @Parameter(description = "医嘱ID") @PathVariable Long orderId) {
        return ResponseEntity.ok(visitService.cancelOrder(id, orderId));
    }

    @Operation(summary = "医嘱退费")
    @PreAuthorize("hasAuthority('charge:pay')")
    @AuditLog(action = "REFUND_ORDER")
    @PostMapping("/api/core/visits/{id}/orders/{orderId}/refund")
    public ResponseEntity<VisitDetail> refundOrder(@Parameter(description = "就诊单ID") @PathVariable Long id, @Parameter(description = "医嘱ID") @PathVariable Long orderId) {
        return ResponseEntity.ok(visitService.refundOrder(id, orderId));
    }

    @Operation(summary = "执行检查医嘱")
    @PreAuthorize("hasAuthority('order:execute')")
    @AuditLog(action = "EXECUTE_EXAM_ORDER")
    @PostMapping("/api/core/visits/{id}/exams/{orderId}/execute")
    public ResponseEntity<VisitDetail> executeExam(@Parameter(description = "就诊单ID") @PathVariable Long id, @Parameter(description = "医嘱ID") @PathVariable Long orderId,
                                                   @RequestBody(required = false) java.util.Map<String, String> body) {
        String finding = body != null ? body.get("finding") : null;
        return ResponseEntity.ok(visitService.executeExam(id, orderId, finding));
    }

    @Operation(summary = "就诊缴费")
    @PreAuthorize("hasAuthority('charge:pay')")
    @AuditLog(action = "PAY_CHARGE")
    @PostMapping("/api/core/visits/{id}/pay")
    public ResponseEntity<VisitDetail> pay(@Parameter(description = "就诊单ID") @PathVariable Long id) {
        return ResponseEntity.ok(visitService.pay(id));
    }

    @Operation(summary = "批量查询缴费状态")
    @PostMapping("/api/core/visits/payment-status")
    @PreAuthorize("hasAuthority('order:execute')")
    public ResponseEntity<Map<Long, Boolean>> paymentStatus(@RequestBody List<Long> visitIds) {
        return ResponseEntity.ok(visitService.paymentStatus(visitIds));
    }

    @Operation(summary = "查询待执行检查列表")
    @GetMapping("/api/core/visits/exams/pending")
    @PreAuthorize("hasAuthority('order:execute')")
    public ResponseEntity<List<ExamTaskVO>> pendingExams() {
        return ResponseEntity.ok(visitService.listPendingExams(currentDeptId()));
    }

    @Operation(summary = "查询检查列表")
    @GetMapping("/api/core/visits/exams")
    @PreAuthorize("hasAuthority('order:execute')")
    public ResponseEntity<List<ExamTaskVO>> listExams(@Parameter(description = "检查状态") @RequestParam(required = false) String status) {
        return ResponseEntity.ok(visitService.listExams(status, currentDeptId()));
    }

    @Operation(summary = "查询患者历史就诊记录")
    // R-08: 读接口对患者(patient:booking)开放;入参 patientId 一律经 resolveReadablePatientId 覆盖,
    //       患者角色被强制改写成本人 patientId,不存在"改 id 看他人就诊史"的绕行路径
    @PreAuthorize("hasAnyAuthority('visit:entry','visit:audit','system:admin','patient:booking')")
    @GetMapping("/api/core/visits/patient-history")
    public ResponseEntity<List<PatientVisitHistoryVO>> patientHistory(@Parameter(description = "患者ID") @RequestParam Long patientId) {
        Long effectivePatientId = resolveReadablePatientId(patientId);
        if (effectivePatientId == null) {
            return ResponseEntity.status(403).build();
        }
        return ResponseEntity.ok(visitService.getPatientHistory(effectivePatientId));
    }

    private Long currentDeptId() {
        String phone = CurrentUserResolver.resolveUsername();
        if (phone == null) return null;
        Staff staff = staffService.findByPhone(phone);
        if (staff == null) return null;
        if ("ADMIN".equals(staff.getPosition()) || "system:admin".equals(staff.getPosition())) return null;
        return staff.getDeptId();
    }

    /**
     * R-08: 归属校验 —— 以 patientId 为入参的读接口统一走这里。
     * 员工(医护 / 管理员,按 org.staff 判定)允许按入参查询;
     * 患者角色强制用"当前登录用户绑定的 patientId"覆盖入参,解析不到档案则拒绝。
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

    @Operation(summary = "确单")
    @PreAuthorize("hasAuthority('visit:entry')")
    @AuditLog(action = "CONFIRM_VISIT")
    @PostMapping("/api/core/visits/{id}/confirm")
    public ResponseEntity<VisitDetail> confirm(@Parameter(description = "就诊单ID") @PathVariable Long id) {
        return ResponseEntity.ok(visitService.confirm(id));
    }

    @Operation(summary = "完成就诊")
    @PreAuthorize("hasAuthority('visit:audit')")
    @AuditLog(action = "FINISH_VISIT")
    @PostMapping("/api/core/visits/{id}/finish")
    public ResponseEntity<VisitDetail> finish(@Parameter(description = "就诊单ID") @PathVariable Long id,
            @Parameter(description = "是否强制完成") @RequestParam(defaultValue = "false") boolean force) {
        return ResponseEntity.ok(visitService.finishVisit(id, force));
    }
}
