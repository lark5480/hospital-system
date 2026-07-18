package com.hospital.core.org.application;

import com.hospital.core.org.domain.Department;
import com.hospital.core.org.infrastructure.DepartmentMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 科室应用服务:CRUD。
 */
@Service
@RequiredArgsConstructor
public class DepartmentService {

    private final DepartmentMapper departmentMapper;

    public List<Department> list() {
        return departmentMapper.selectList(null);
    }

    public Department get(Long id) {
        return departmentMapper.selectById(id);
    }

    public Department create(Department department) {
        department.setCreatedAt(LocalDateTime.now());
        departmentMapper.insert(department);
        return department;
    }

    public Department update(Long id, Department department) {
        Department existing = departmentMapper.selectById(id);
        if (existing == null) throw new IllegalArgumentException("科室不存在:" + id);
        if (department.getName() != null) existing.setName(department.getName());
        if (department.getCode() != null) existing.setCode(department.getCode());
        if (department.getDescription() != null) existing.setDescription(department.getDescription());
        departmentMapper.updateById(existing);
        return existing;
    }

    public void delete(Long id) {
        departmentMapper.deleteById(id);
    }
}
