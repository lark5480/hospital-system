package com.hospital.core.booking.api;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.hospital.core.booking.application.BookingService;
import com.hospital.core.booking.domain.Appointment;
import com.hospital.core.booking.domain.ExamPackage;
import com.hospital.core.booking.domain.Slot;
import com.hospital.core.patient.api.PatientApi;
import com.hospital.core.patient.domain.Patient;
import com.hospital.core.platform.annotation.AuditLog;
import com.hospital.core.platform.security.CurrentUserResolver;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

@Tag(name = "预约管理", description = "体检套餐查询、号源查询、预约与查询")
@RestController
@RequiredArgsConstructor
public class BookingController {
    
    private final BookingService bookingService;
    private final PatientApi patientApi;

    @Operation(summary = "查询体检套餐列表")
    @GetMapping("/api/patient/packages")
    public ResponseEntity<List<ExamPackage>> listPackages() {
        return ResponseEntity.ok(bookingService.listPackages());
    }

    @Operation(summary = "获取体检套餐详情")
    @GetMapping("/api/patient/packages/{id}")
    public ResponseEntity<Map<String, Object>> getPackage(@Parameter(description = "套餐ID") @PathVariable Long id) {
        Map<String, Object> body = new HashMap<>();
        body.put("package", bookingService.getPackage(id));
        body.put("items", bookingService.listItems(id));
        return ResponseEntity.ok(body);
    }

    @Operation(summary = "查询可用号源")
    @GetMapping("/api/patient/slots")
    public ResponseEntity<List<Slot>> listSlots(@Parameter(description = "套餐ID") @RequestParam Long packageId) {
        return ResponseEntity.ok(bookingService.listSlots(packageId));
    }

    @Operation(summary = "预约体检(仅限本人)")
    @PreAuthorize("hasAuthority('patient:booking')")
    @AuditLog(action = "BOOK_APPOINTMENT")
    @PostMapping("/api/patient/appointments")
    public ResponseEntity<AppointmentDetail> book(@Valid @RequestBody AppointmentRequest request) {
        Patient current = resolveCurrentPatient();
        // IDOR 防护:仅允许为当前登录用户本人建档的患者发起预约
        if (current == null || !Objects.equals(request.getPatientId(), current.getId())) {
            return ResponseEntity.status(403).build();
        }
        Appointment appt = bookingService.book(
                request.getPatientId(), request.getPackageId(), request.getSlotId());
        return ResponseEntity.ok(toDetail(appt));
    }

    @Operation(summary = "取消预约(仅限本人)")
    @PreAuthorize("hasAuthority('patient:booking')")
    @AuditLog(action = "CANCEL_APPOINTMENT")
    @PostMapping("/api/patient/appointments/{id}/cancel")
    public ResponseEntity<Void> cancel(@Parameter(description = "预约ID") @PathVariable Long id) {
        Appointment appt = bookingService.getAppointment(id);
        if (appt == null) return ResponseEntity.notFound().build();
        Patient current = resolveCurrentPatient();
        // IDOR 防护:归属校验必须先于 service 调用。
        // 不能只依赖"患者只能看到自己的列表"这种间接约束——接口一旦被直接构造请求(改 id)就绕过了,
        // 且取消是写操作,越权后果远大于读泄漏。
        if (current == null || !Objects.equals(appt.getPatientId(), current.getId())) {
            return ResponseEntity.status(403).build();
        }
        bookingService.cancelAppointment(id);
        return ResponseEntity.ok().build();
    }

    @Operation(summary = "查询当前患者预约列表")
    @GetMapping("/api/patient/appointments")
    public ResponseEntity<List<AppointmentDetail>> myAppointments() {
        Patient current = resolveCurrentPatient();
        if (current == null) return ResponseEntity.notFound().build();
        List<AppointmentDetail> details = bookingService.listByPatient(current.getId()).stream()
                .map(this::toDetail)
                .toList();
        return ResponseEntity.ok(details);
    }

    @Operation(summary = "获取预约详情(仅限本人)")
    @GetMapping("/api/patient/appointments/{id}")
    public ResponseEntity<AppointmentDetail> get(@Parameter(description = "预约ID") @PathVariable Long id) {
        Appointment appt = bookingService.getAppointment(id);
        if (appt == null) return ResponseEntity.notFound().build();
        Patient current = resolveCurrentPatient();
        // IDOR 防护:预约归属校验,非本人预约一律 403
        if (current == null || !Objects.equals(appt.getPatientId(), current.getId())) {
            return ResponseEntity.status(403).build();
        }
        return ResponseEntity.ok(toDetail(appt));
    }

    /** 解析当前登录用户对应的患者档案(按 JWT sub 即手机号绑定)。 */
    private Patient resolveCurrentPatient() {
        String username = CurrentUserResolver.resolveUsername();
        if (username == null) return null;
        return patientApi.findByUsername(username);
    }

    private AppointmentDetail toDetail(Appointment appt) {
        ExamPackage pkg = bookingService.getPackage(appt.getPackageId());
        // 直查号源实体:listSlots 已过滤过期号源,历史预约的号源需按 ID 回查
        Slot slot = appt.getSlotId() == null ? null : bookingService.getSlot(appt.getSlotId());
        AppointmentDetail d = new AppointmentDetail();
        d.setId(appt.getId());
        d.setPatientId(appt.getPatientId());
        d.setPackageId(appt.getPackageId());
        d.setPackageName(pkg == null ? null : pkg.getName());
        d.setSlotId(appt.getSlotId());
        d.setStatus(appt.getStatus());
        d.setPayStatus(appt.getPayStatus());
        d.setPayAmount(appt.getPayAmount());
        d.setExamDate(slot == null ? null : slot.getExamDate().atStartOfDay());
        d.setPeriod(slot == null ? null : slot.getPeriod());
        d.setCreatedAt(appt.getCreatedAt());
        return d;
    }
}
