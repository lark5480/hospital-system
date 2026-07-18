package com.hospital.core.booking.api;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import com.hospital.core.booking.application.BookingService;
import com.hospital.core.booking.domain.Appointment;
import com.hospital.core.booking.domain.ExamPackage;
import com.hospital.core.platform.annotation.AuditLog;
import com.hospital.core.booking.domain.Slot;

import lombok.RequiredArgsConstructor;

@RestController
@RequiredArgsConstructor
public class BookingController {
    
    private final BookingService bookingService;

    @GetMapping("/api/patient/packages")
    public ResponseEntity<List<ExamPackage>> listPackages() {
        return ResponseEntity.ok(bookingService.listPackages());
    }

    @GetMapping("/api/patient/packages/{id}")
    public ResponseEntity<Map<String, Object>> getPackage(@PathVariable Long id) {
        Map<String, Object> body = new HashMap<>();
        body.put("package", bookingService.getPackage(id));
        body.put("items", bookingService.listItems(id));
        return ResponseEntity.ok(body);
    }

    @GetMapping("/api/patient/slots")
    public ResponseEntity<List<Slot>> listSlots(@RequestParam Long packageId) {
        return ResponseEntity.ok(bookingService.listSlots(packageId));
    }

    @PreAuthorize("hasAuthority('patient:booking')")
    @AuditLog(action = "BOOK_APPOINTMENT")
    @PostMapping("/api/patient/appointments")
    public ResponseEntity<AppointmentDetail> book(@RequestBody AppointmentRequest request) {
        Appointment appt = bookingService.book(
                request.getPatientId(), request.getPackageId(), request.getSlotId());
        return ResponseEntity.ok(toDetail(appt));
    }

    @GetMapping("/api/patient/appointments")
    public ResponseEntity<List<AppointmentDetail>> listByPatient(@RequestParam Long patientId) {
        List<AppointmentDetail> details = bookingService.listByPatient(patientId).stream()
                .map(this::toDetail)
                .toList();
        return ResponseEntity.ok(details);
    }

    @GetMapping("/api/patient/appointments/{id}")
    public ResponseEntity<AppointmentDetail> get(@PathVariable Long id) {
        return ResponseEntity.ok(toDetail(bookingService.getAppointment(id)));
    }

    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<Map<String, String>> handleConflict(IllegalStateException ex) {
        Map<String, String> body = new HashMap<>();
        body.put("error", ex.getMessage());
        return ResponseEntity.status(409).body(body);
    }

    private AppointmentDetail toDetail(Appointment appt) {
        ExamPackage pkg = bookingService.getPackage(appt.getPackageId());
        Slot slot = bookingService.listSlots(appt.getPackageId()).stream()
                .filter(s -> s.getId().equals(appt.getSlotId()))
                .findFirst()
                .orElse(null);
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
