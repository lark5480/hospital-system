package com.hospital.core.booking.api;

import java.util.HashMap;
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

import com.hospital.core.booking.application.BookingService;
import com.hospital.core.booking.domain.Appointment;
import com.hospital.core.booking.domain.ExamPackage;
import com.hospital.core.booking.domain.Slot;
import com.hospital.core.platform.annotation.AuditLog;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;

@Tag(name = "预约管理", description = "体检套餐查询、号源查询、预约与查询")
@RestController
@RequiredArgsConstructor
public class BookingController {
    
    private final BookingService bookingService;

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

    @Operation(summary = "预约体检")
    @PreAuthorize("hasAuthority('patient:booking')")
    @AuditLog(action = "BOOK_APPOINTMENT")
    @PostMapping("/api/patient/appointments")
    public ResponseEntity<AppointmentDetail> book(@RequestBody AppointmentRequest request) {
        Appointment appt = bookingService.book(
                request.getPatientId(), request.getPackageId(), request.getSlotId());
        return ResponseEntity.ok(toDetail(appt));
    }

    @Operation(summary = "查询患者的预约列表")
    @GetMapping("/api/patient/appointments")
    public ResponseEntity<List<AppointmentDetail>> listByPatient(@Parameter(description = "患者ID") @RequestParam Long patientId) {
        List<AppointmentDetail> details = bookingService.listByPatient(patientId).stream()
                .map(this::toDetail)
                .toList();
        return ResponseEntity.ok(details);
    }

    @Operation(summary = "获取预约详情")
    @GetMapping("/api/patient/appointments/{id}")
    public ResponseEntity<AppointmentDetail> get(@Parameter(description = "预约ID") @PathVariable Long id) {
        return ResponseEntity.ok(toDetail(bookingService.getAppointment(id)));
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
