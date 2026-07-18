package com.hospital.core.org.application;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.hospital.core.org.domain.Staff;
import com.hospital.core.org.infrastructure.StaffMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 员工应用服务:CRUD + 按岗位/科室筛选。
 */
@Service
@RequiredArgsConstructor
public class StaffService {

    private final StaffMapper staffMapper;

    public List<Staff> list(String position, Long deptId) {
        LambdaQueryWrapper<Staff> q = new LambdaQueryWrapper<Staff>()
                .orderByDesc(Staff::getCreatedAt);
        if (position != null && !position.isBlank()) {
            q.eq(Staff::getPosition, position);
        }
        if (deptId != null) {
            q.eq(Staff::getDeptId, deptId);
        }
        return staffMapper.selectList(q);
    }

    public Staff get(Long id) {
        return staffMapper.selectById(id);
    }

    public Staff create(Staff staff) {
        staff.setStatus("ACTIVE");
        staff.setCreatedAt(LocalDateTime.now());
        staffMapper.insert(staff);
        return staff;
    }

    public Staff update(Long id, Staff staff) {
        Staff existing = staffMapper.selectById(id);
        if (existing == null) throw new IllegalArgumentException("员工不存在:" + id);
        if (staff.getName() != null) existing.setName(staff.getName());
        if (staff.getGender() != null) existing.setGender(staff.getGender());
        if (staff.getPhone() != null) existing.setPhone(staff.getPhone());
        if (staff.getDeptId() != null) existing.setDeptId(staff.getDeptId());
        if (staff.getPosition() != null) existing.setPosition(staff.getPosition());
        if (staff.getUsername() != null) existing.setUsername(staff.getUsername());
        if (staff.getStatus() != null) existing.setStatus(staff.getStatus());
        staffMapper.updateById(existing);
        return existing;
    }

    public void delete(Long id) {
        staffMapper.deleteById(id);
    }

    /** 按用户名查找员工。 */
    public Staff findByUsername(String username) {
        return staffMapper.findByUsername(username);
    }

    /** 按手机号查找员工(统一账号登录后使用)。 */
    public Staff findByPhone(String phone) {
        return staffMapper.findByPhone(phone);
    }
}
