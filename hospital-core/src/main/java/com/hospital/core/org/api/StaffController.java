package com.hospital.core.org.api;

import java.util.List;

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

import com.hospital.core.org.application.StaffService;
import com.hospital.core.org.domain.Staff;
import com.hospital.core.platform.security.CurrentUserResolver;

import lombok.RequiredArgsConstructor;

@RestController
@RequiredArgsConstructor
public class StaffController {

    private final StaffService staffService;

    @GetMapping("/api/core/org/staff/me")
    @PreAuthorize("permitAll()")
    public ResponseEntity<Staff> me() {
        String phone = CurrentUserResolver.resolveUsername();
        if (phone == null) return ResponseEntity.notFound().build();
        Staff staff = staffService.findByPhone(phone);
        if (staff == null) return ResponseEntity.notFound().build();
        return ResponseEntity.ok(staff);
    }

    @GetMapping("/api/core/org/staff/list")
    @PreAuthorize("hasAuthority('visit:entry')")
    public ResponseEntity<List<Staff>> list(@RequestParam(required = false) String position) {
        return ResponseEntity.ok(staffService.list(position, null));
    }

    @GetMapping("/api/core/org/staff/{id}")
    @PreAuthorize("hasAuthority('visit:entry')")
    public ResponseEntity<Staff> get(@PathVariable Long id) {
        Staff staff = staffService.get(id);
        if (staff == null) return ResponseEntity.notFound().build();
        return ResponseEntity.ok(staff);
    }

    @PostMapping("/api/core/org/staff")
    @PreAuthorize("hasAuthority('system:admin')")
    public ResponseEntity<Staff> create(@RequestBody Staff staff) {
        return ResponseEntity.ok(staffService.create(staff));
    }

    @PutMapping("/api/core/org/staff/{id}")
    @PreAuthorize("hasAuthority('system:admin')")
    public ResponseEntity<Staff> update(@PathVariable Long id, @RequestBody Staff staff) {
        return ResponseEntity.ok(staffService.update(id, staff));
    }

    @DeleteMapping("/api/core/org/staff/{id}")
    @PreAuthorize("hasAuthority('system:admin')")
    public ResponseEntity<Void> delete(@PathVariable Long id) {
        staffService.delete(id);
        return ResponseEntity.noContent().build();
    }
}
