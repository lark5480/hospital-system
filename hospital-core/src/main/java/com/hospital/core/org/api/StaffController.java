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

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;

@Tag(name = "组织机构", description = "员工的增删改查")
@RestController
@RequiredArgsConstructor
public class StaffController {

    private final StaffService staffService;

    @Operation(summary = "获取当前登录员工信息")
    @GetMapping("/api/core/org/staff/me")
    @PreAuthorize("permitAll()")
    public ResponseEntity<Staff> me() {
        String phone = CurrentUserResolver.resolveUsername();
        if (phone == null) return ResponseEntity.notFound().build();
        Staff staff = staffService.findByPhone(phone);
        if (staff == null) return ResponseEntity.notFound().build();
        return ResponseEntity.ok(staff);
    }

    @Operation(summary = "查询员工列表")
    @GetMapping("/api/core/org/staff/list")
    @PreAuthorize("hasAuthority('visit:entry')")
    public ResponseEntity<List<Staff>> list(@Parameter(description = "职位") @RequestParam(required = false) String position) {
        return ResponseEntity.ok(staffService.list(position, null));
    }

    @Operation(summary = "获取员工详情")
    @GetMapping("/api/core/org/staff/{id}")
    @PreAuthorize("hasAuthority('visit:entry')")
    public ResponseEntity<Staff> get(@Parameter(description = "员工ID") @PathVariable Long id) {
        Staff staff = staffService.get(id);
        if (staff == null) return ResponseEntity.notFound().build();
        return ResponseEntity.ok(staff);
    }

    @Operation(summary = "创建员工")
    @PostMapping("/api/core/org/staff")
    @PreAuthorize("hasAuthority('system:admin')")
    public ResponseEntity<Staff> create(@RequestBody Staff staff) {
        return ResponseEntity.ok(staffService.create(staff));
    }

    @Operation(summary = "更新员工")
    @PutMapping("/api/core/org/staff/{id}")
    @PreAuthorize("hasAuthority('system:admin')")
    public ResponseEntity<Staff> update(@Parameter(description = "员工ID") @PathVariable Long id, @RequestBody Staff staff) {
        return ResponseEntity.ok(staffService.update(id, staff));
    }

    @Operation(summary = "删除员工")
    @DeleteMapping("/api/core/org/staff/{id}")
    @PreAuthorize("hasAuthority('system:admin')")
    public ResponseEntity<Void> delete(@Parameter(description = "员工ID") @PathVariable Long id) {
        staffService.delete(id);
        return ResponseEntity.noContent().build();
    }
}
