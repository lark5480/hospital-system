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

import lombok.RequiredArgsConstructor;

/**
 * 门诊挂号 / 分诊排队 / 叫号接口。
 */
@RestController
@RequiredArgsConstructor
public class RegistrationController {

    private final RegistrationService registrationService;

    /** 挂号:患者进入科室候诊队列(B端代挂 或 C端自助)。 */
    @PreAuthorize("hasAnyAuthority('visit:entry', 'patient:booking')")
    @AuditLog(action = "REGISTER")
    @PostMapping("/api/core/registrations")
    public ResponseEntity<Registration> register(@RequestBody Registration req) {
        return ResponseEntity.ok(
                registrationService.register(req.getPatientId(), req.getDeptId(), req.getDoctorId()));
    }

    /** 叫号:取最前面候诊患者,自动创建就诊单。 */
    @PreAuthorize("hasAuthority('visit:entry')")
    @AuditLog(action = "CALL_NEXT")
    @PostMapping("/api/core/registrations/call-next")
    public ResponseEntity<Registration> callNext(@RequestParam Long deptId) {
        return ResponseEntity.ok(registrationService.callNext(deptId));
    }

    /** 取消挂号。 */
    @PreAuthorize("hasAuthority('visit:entry')")
    @AuditLog(action = "CANCEL_REGISTRATION")
    @PostMapping("/api/core/registrations/{id}/cancel")
    public ResponseEntity<Registration> cancel(@PathVariable Long id) {
        return ResponseEntity.ok(registrationService.cancel(id));
    }

    /** 查询科室当日排队列表(管理端:含全部状态)。 */
    @GetMapping("/api/core/registrations")
    public ResponseEntity<List<Registration>> list(@RequestParam Long deptId) {
        return ResponseEntity.ok(registrationService.listByDept(deptId));
    }

    /** 查询科室活跃队列(大屏用:仅 WAITING + CALLED)。 */
    @GetMapping("/api/core/registrations/active")
    public ResponseEntity<List<Registration>> activeQueue(@RequestParam Long deptId) {
        return ResponseEntity.ok(registrationService.activeQueue(deptId));
    }
}
