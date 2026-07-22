package com.hospital.core.org.application;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.hospital.core.org.domain.Department;
import com.hospital.core.org.domain.Staff;
import com.hospital.core.org.infrastructure.DepartmentMapper;
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
    private final DepartmentMapper departmentMapper;

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

    /**
     * 按统一账号 id 解析员工所属科室名称;无员工 / 无科室返回 null。
     * 用 staff.user_id 外键关联 sys_user,不依赖手机号字符串,dev/真实登录均可靠。
     */
    public String findDepartmentNameByUserId(Long userId) {
        if (userId == null) return null;
        // 使用 selectList 避免 TooManyResultsException
        List<Staff> staffList = staffMapper.selectList(
                new LambdaQueryWrapper<Staff>().eq(Staff::getUserId, userId).last("LIMIT 1"));
        if (staffList.isEmpty()) return null;
        Staff staff = staffList.get(0);
        if (staff.getDeptId() == null) return null;
        Department dept = departmentMapper.selectById(staff.getDeptId());
        return dept != null ? dept.getName() : null;
    }

    /**
     * 按统一账号 id 解析员工所属科室 ID;无员工 / 无科室返回 null。
     */
    public Long findDepartmentIdByUserId(Long userId) {
        if (userId == null) return null;
        List<Staff> staffList = staffMapper.selectList(
                new LambdaQueryWrapper<Staff>().eq(Staff::getUserId, userId).last("LIMIT 1"));
        if (staffList.isEmpty()) return null;
        Staff staff = staffList.get(0);
        return staff.getDeptId();
    }
}
