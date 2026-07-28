package com.hospital.core.lab.api;

import java.util.List;
import java.util.Map;

import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.hospital.core.lab.application.LabRequisitionDetail;
import com.hospital.core.lab.application.LabRequisitionListItem;
import com.hospital.core.lab.application.LabService;
import com.hospital.core.lab.domain.LabRequisition;
import com.hospital.core.org.application.StaffService;
import com.hospital.core.org.domain.Staff;
import com.hospital.core.platform.annotation.AuditLog;
import com.hospital.core.platform.security.CurrentUserResolver;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;

@Tag(name = "检验管理", description = "检验申请的创建、查询、结果录入与取消")
@RestController
@RequiredArgsConstructor
public class LabController {

    private final LabService labService;
    private final StaffService staffService;

    @Operation(summary = "查询检验申请列表")
    @GetMapping("/api/lab/requisitions")
    public ResponseEntity<List<LabRequisitionListItem>> list(
            @Parameter(description = "申请状态") @RequestParam(required = false) String status,
            @Parameter(description = "科室ID") @RequestParam(required = false) Long deptId) {
        return ResponseEntity.ok(labService.listWithDetail(status, deptId));
    }

    @Operation(summary = "获取检验申请详情")
    @GetMapping("/api/lab/requisitions/{id}")
    public ResponseEntity<LabRequisitionDetail> get(@Parameter(description = "检验申请ID") @PathVariable Long id) {
        LabRequisitionDetail detail = labService.getDetail(id);
        if (detail == null) return ResponseEntity.notFound().build();
        return ResponseEntity.ok(detail);
    }

    @Operation(summary = "创建检验申请")
    @PreAuthorize("hasAuthority('visit:entry')")
    @AuditLog(action = "CREATE_REQUISITION")
    @PostMapping("/api/lab/requisitions")
    public ResponseEntity<LabRequisition> create(@RequestBody CreateRequisitionRequest req) {
        return ResponseEntity.ok(labService.createFromVisit(
                req.getVisitId(), req.getDoctorId(), req.getOrderIds()));
    }

    @Operation(summary = "录入检验结果")
    @PreAuthorize("hasAuthority('order:execute')")
    @AuditLog(action = "SUBMIT_RESULTS")
    @PostMapping("/api/lab/requisitions/{id}/results")
    public ResponseEntity<?> submitResults(
            @Parameter(description = "检验申请ID") @PathVariable Long id, @RequestBody SubmitResultsRequest req) {
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

    @Operation(summary = "取消检验申请")
    @PreAuthorize("hasAuthority('order:execute')")
    @AuditLog(action = "CANCEL_REQUISITION")
    @PostMapping("/api/lab/requisitions/{id}/cancel")
    public ResponseEntity<?> cancel(@Parameter(description = "检验申请ID") @PathVariable Long id) {
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

    private Long currentDeptId() {
        String phone = CurrentUserResolver.resolveUsername();
        if (phone == null) return null;
        Staff staff = staffService.findByPhone(phone);
        return staff != null ? staff.getDeptId() : null;
    }
}
