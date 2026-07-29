package com.hospital.core.org.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.hospital.core.org.domain.Department;
import com.hospital.core.org.infrastructure.DepartmentMapper;

@ExtendWith(MockitoExtension.class)
class DepartmentServiceTest {

    @Mock DepartmentMapper departmentMapper;

    DepartmentService service;

    @BeforeEach
    void setUp() {
        service = new DepartmentService(departmentMapper);
    }

    @Nested
    @DisplayName("列表查询")
    class ListDept {

        @Test
        @DisplayName("返回所有科室列表")
        void list_returnsAll() {
            when(departmentMapper.selectList(null)).thenReturn(
                    java.util.List.of(buildDept(1L, "内科", "NK"), buildDept(2L, "外科", "WK")));

            java.util.List<Department> result = service.list();

            assertThat(result).hasSize(2);
            assertThat(result.get(0).getName()).isEqualTo("内科");
        }
    }

    @Nested
    @DisplayName("创建科室")
    class Create {

        @Test
        @DisplayName("正常创建 → 设置创建时间并插入")
        void create_success() {
            Department dept = new Department();
            dept.setName("儿科");
            dept.setCode("EK");

            Department result = service.create(dept);

            assertThat(result.getName()).isEqualTo("儿科");
            assertThat(result.getCreatedAt()).isNotNull();
            verify(departmentMapper).insert(dept);
        }
    }

    @Nested
    @DisplayName("更新科室")
    class Update {

        @Test
        @DisplayName("科室存在 → 更新成功")
        void update_success() {
            Department existing = buildDept(1L, "旧科室", "OLD");
            when(departmentMapper.selectById(1L)).thenReturn(existing);

            Department updateData = new Department();
            updateData.setName("新科室");
            updateData.setCode("NEW");

            Department result = service.update(1L, updateData);

            assertThat(result.getName()).isEqualTo("新科室");
            assertThat(result.getCode()).isEqualTo("NEW");
            verify(departmentMapper).updateById(existing);
        }

        @Test
        @DisplayName("科室不存在 → 抛出 IllegalArgumentException")
        void update_notFound_throws() {
            when(departmentMapper.selectById(999L)).thenReturn(null);

            assertThatThrownBy(() -> service.update(999L, new Department()))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("科室不存在");
        }
    }

    @Nested
    @DisplayName("删除科室")
    class Delete {

        @Test
        @DisplayName("按 ID 删除科室")
        void delete_success() {
            service.delete(1L);

            verify(departmentMapper).deleteById(1L);
        }
    }

    private Department buildDept(Long id, String name, String code) {
        Department d = new Department();
        d.setId(id);
        d.setName(name);
        d.setCode(code);
        return d;
    }
}
