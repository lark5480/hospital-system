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
import org.springframework.web.bind.annotation.RestController;

import com.hospital.core.org.application.DepartmentService;
import com.hospital.core.org.domain.Department;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;

@Tag(name = "组织机构", description = "科室的增删改查")
@RestController
@RequiredArgsConstructor
public class DepartmentController {

    private final DepartmentService departmentService;

    @Operation(summary = "查询科室列表")
    @GetMapping("/api/core/org/departments/list")
    @PreAuthorize("hasAnyAuthority('visit:entry', 'patient:booking')")
    public ResponseEntity<List<Department>> list() {
        return ResponseEntity.ok(departmentService.list());
    }

    @Operation(summary = "获取科室详情")
    @GetMapping("/api/core/org/departments/{id}")
    @PreAuthorize("hasAnyAuthority('visit:entry', 'patient:booking')")
    public ResponseEntity<Department> get(@Parameter(description = "科室ID") @PathVariable Long id) {
        Department dept = departmentService.get(id);
        if (dept == null) return ResponseEntity.notFound().build();
        return ResponseEntity.ok(dept);
    }

    @Operation(summary = "创建科室")
    @PostMapping("/api/core/org/departments")
    @PreAuthorize("hasAuthority('system:admin')")
    public ResponseEntity<Department> create(@RequestBody Department department) {
        return ResponseEntity.ok(departmentService.create(department));
    }

    @Operation(summary = "更新科室")
    @PutMapping("/api/core/org/departments/{id}")
    @PreAuthorize("hasAuthority('system:admin')")
    public ResponseEntity<Department> update(@Parameter(description = "科室ID") @PathVariable Long id, @RequestBody Department department) {
        return ResponseEntity.ok(departmentService.update(id, department));
    }

    @Operation(summary = "删除科室")
    @DeleteMapping("/api/core/org/departments/{id}")
    @PreAuthorize("hasAuthority('system:admin')")
    public ResponseEntity<Void> delete(@Parameter(description = "科室ID") @PathVariable Long id) {
        departmentService.delete(id);
        return ResponseEntity.noContent().build();
    }
}
