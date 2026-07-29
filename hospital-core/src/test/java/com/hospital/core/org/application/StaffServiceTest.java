package com.hospital.core.org.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.hospital.core.org.domain.Department;
import com.hospital.core.org.domain.Staff;
import com.hospital.core.org.infrastructure.DepartmentMapper;
import com.hospital.core.org.infrastructure.StaffMapper;

@ExtendWith(MockitoExtension.class)
class StaffServiceTest {

    @Mock StaffMapper staffMapper;
    @Mock DepartmentMapper departmentMapper;

    StaffService service;

    @BeforeEach
    void setUp() {
        service = new StaffService(staffMapper, departmentMapper);
    }

    @Nested
    @DisplayName("创建员工")
    class Create {

        @Test
        @DisplayName("正常创建 → 设置状态和时间并插入")
        void create_success() {
            Staff staff = buildStaff("王医生", "内科", 1L);

            Staff result = service.create(staff);

            assertThat(result.getStatus()).isEqualTo("ACTIVE");
            assertThat(result.getCreatedAt()).isNotNull();
            verify(staffMapper).insert(staff);
        }
    }

    @Nested
    @DisplayName("更新员工")
    class Update {

        @Test
        @DisplayName("员工存在 → 更新成功")
        void update_success() {
            Staff existing = buildStaff("旧名", "内科", 1L);
            existing.setId(1L);
            when(staffMapper.selectById(1L)).thenReturn(existing);

            Staff updateData = new Staff();
            updateData.setName("新名");
            updateData.setPosition("外科");

            Staff result = service.update(1L, updateData);

            assertThat(result.getName()).isEqualTo("新名");
            assertThat(result.getPosition()).isEqualTo("外科");
            verify(staffMapper).updateById(existing);
        }

        @Test
        @DisplayName("员工不存在 → 抛出 IllegalArgumentException")
        void update_notFound_throws() {
            when(staffMapper.selectById(999L)).thenReturn(null);

            assertThatThrownBy(() -> service.update(999L, new Staff()))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("员工不存在");
        }
    }

    @Nested
    @DisplayName("按科室查询")
    class ListByDept {

        @Test
        @DisplayName("按科室 ID 筛选员工列表")
        void listByDept_returnsFiltered() {
            when(staffMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(
                    List.of(buildStaff("医生A", "内科", 1L), buildStaff("医生B", "内科", 1L)));

            List<Staff> result = service.list(null, 1L);

            assertThat(result).hasSize(2);
        }
    }

    @Nested
    @DisplayName("按 userId 查科室名称")
    class FindDepartmentNameByUserId {

        @Test
        @DisplayName("有员工且有科室 → 返回科室名称")
        void findDeptName_success() {
            Staff staff = buildStaff("医生A", "内科", 1L);
            staff.setUserId(100L);
            staff.setDeptId(5L);
            when(staffMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(List.of(staff));
            Department dept = new Department();
            dept.setId(5L);
            dept.setName("内科");
            when(departmentMapper.selectById(5L)).thenReturn(dept);

            String result = service.findDepartmentNameByUserId(100L);

            assertThat(result).isEqualTo("内科");
        }

        @Test
        @DisplayName("无员工 → 返回 null")
        void findDeptName_noStaff_returnsNull() {
            when(staffMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(List.of());

            String result = service.findDepartmentNameByUserId(999L);

            assertThat(result).isNull();
        }

        @Test
        @DisplayName("userId 为 null → 返回 null")
        void findDeptName_nullUserId_returnsNull() {
            String result = service.findDepartmentNameByUserId(null);

            assertThat(result).isNull();
        }
    }

    @Nested
    @DisplayName("按 userId 查科室 ID")
    class FindDepartmentIdByUserId {

        @Test
        @DisplayName("有员工 → 返回科室 ID")
        void findDeptId_success() {
            Staff staff = buildStaff("医生A", "内科", 1L);
            staff.setUserId(100L);
            staff.setDeptId(5L);
            when(staffMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(List.of(staff));

            Long result = service.findDepartmentIdByUserId(100L);

            assertThat(result).isEqualTo(5L);
        }

        @Test
        @DisplayName("无员工 → 返回 null")
        void findDeptId_noStaff_returnsNull() {
            when(staffMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(List.of());

            Long result = service.findDepartmentIdByUserId(999L);

            assertThat(result).isNull();
        }
    }

    private Staff buildStaff(String name, String position, Long deptId) {
        Staff s = new Staff();
        s.setName(name);
        s.setPosition(position);
        s.setDeptId(deptId);
        return s;
    }
}
