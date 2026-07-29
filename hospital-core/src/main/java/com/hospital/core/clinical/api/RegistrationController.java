package com.hospital.core.clinical.api;

import java.util.List;

import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.hospital.core.clinical.application.RegistrationService;
import com.hospital.core.clinical.domain.Registration;
import com.hospital.core.platform.annotation.AuditLog;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

/**
 * 门诊挂号 / 分诊排队 / 叫号接口。
 */
@Tag(name = "门诊挂号", description = "挂号、叫号、取消挂号、排队查询")
@RestController
@RequiredArgsConstructor
public class RegistrationController {

    private final RegistrationService registrationService;

    @Operation(summary = "挂号", description = "患者进入科室候诊队列（B端代挂或C端自助）")
    @PreAuthorize("hasAnyAuthority('visit:entry', 'patient:booking')")
    @AuditLog(action = "REGISTER")
    @PostMapping("/api/core/registrations")
    public ResponseEntity<Registration> register(@Valid @RequestBody Registration req) {
        return ResponseEntity.ok(
                registrationService.register(req.getPatientId(), req.getDeptId(), req.getDoctorId()));
    }

    @Operation(summary = "叫号", description = "取最前面候诊患者，自动创建就诊单")
    @PreAuthorize("hasAuthority('visit:entry')")
    @AuditLog(action = "CALL_NEXT")
    @PostMapping("/api/core/registrations/call-next")
    public ResponseEntity<Registration> callNext(@Parameter(description = "科室ID") @RequestParam Long deptId) {
        return ResponseEntity.ok(registrationService.callNext(deptId));
    }

    @Operation(summary = "取消挂号")
    @PreAuthorize("hasAuthority('visit:entry')")
    @AuditLog(action = "CANCEL_REGISTRATION")
    @PostMapping("/api/core/registrations/{id}/cancel")
    public ResponseEntity<Registration> cancel(@Parameter(description = "挂号记录ID") @PathVariable Long id) {
        return ResponseEntity.ok(registrationService.cancel(id));
    }

    @Operation(summary = "查询科室当日排队列表", description = "管理端：含全部状态")
    @GetMapping("/api/core/registrations")
    public ResponseEntity<List<Registration>> list(@Parameter(description = "科室ID") @RequestParam Long deptId) {
        return ResponseEntity.ok(registrationService.listByDept(deptId));
    }

    @Operation(summary = "查询科室活跃队列", description = "大屏用：仅 WAITING + CALLED")
    @GetMapping("/api/core/registrations/active")
    public ResponseEntity<List<Registration>> activeQueue(@Parameter(description = "科室ID") @RequestParam Long deptId) {
        return ResponseEntity.ok(registrationService.activeQueue(deptId));
    }
}
