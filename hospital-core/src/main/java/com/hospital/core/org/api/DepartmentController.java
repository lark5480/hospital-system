package com.hospital.core.org.api;

import com.hospital.core.org.application.DepartmentService;
import com.hospital.core.org.domain.Department;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequiredArgsConstructor
public class DepartmentController {

    private final DepartmentService departmentService;

    @GetMapping("/api/core/org/departments/list")
    @PreAuthorize("hasAuthority('visit:entry')")
    public ResponseEntity<List<Department>> list() {
        return ResponseEntity.ok(departmentService.list());
    }

    @GetMapping("/api/core/org/departments/{id}")
    @PreAuthorize("hasAuthority('visit:entry')")
    public ResponseEntity<Department> get(@PathVariable Long id) {
        Department dept = departmentService.get(id);
        if (dept == null) return ResponseEntity.notFound().build();
        return ResponseEntity.ok(dept);
    }

    @PostMapping("/api/core/org/departments")
    @PreAuthorize("hasAuthority('system:admin')")
    public ResponseEntity<Department> create(@RequestBody Department department) {
        return ResponseEntity.ok(departmentService.create(department));
    }

    @PutMapping("/api/core/org/departments/{id}")
    @PreAuthorize("hasAuthority('system:admin')")
    public ResponseEntity<Department> update(@PathVariable Long id, @RequestBody Department department) {
        return ResponseEntity.ok(departmentService.update(id, department));
    }

    @DeleteMapping("/api/core/org/departments/{id}")
    @PreAuthorize("hasAuthority('system:admin')")
    public ResponseEntity<Void> delete(@PathVariable Long id) {
        departmentService.delete(id);
        return ResponseEntity.noContent().build();
    }
}
