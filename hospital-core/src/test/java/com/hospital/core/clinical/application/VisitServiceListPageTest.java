package com.hospital.core.clinical.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.hospital.core.clinical.domain.VisitReadModel;
import com.hospital.core.clinical.infrastructure.ChargeMapper;
import com.hospital.core.clinical.infrastructure.OrderMapper;
import com.hospital.core.clinical.infrastructure.VisitMapper;
import com.hospital.core.clinical.infrastructure.VisitReadModelMapper;
import com.hospital.core.org.application.DepartmentService;
import com.hospital.core.org.application.StaffService;
import com.hospital.core.patient.application.PatientService;

/**
 * R-52: {@link VisitService#listPage} 分页边界专项(纯 Mockito,不启 Spring 上下文)。
 *
 * <p>背景:原实现 {@code offset = (pageNum - 1) * pageSize},对 {@code pageNum <= 0} 会算出
 * 负 OFFSET(PostgreSQL 直接报错);{@code pageSize <= 0} 生成 "LIMIT 0" 或非法 SQL;
 * 超大 {@code pageSize} 会退化为全量拉取。本用例固化修复后的归一化语义:
 * {@code pageNum} 至少 1,{@code pageSize} 落在 [1, 500],且回填到 {@link PageResult}。
 *
 * <p>Mockito 环境无真实 PG,断言分两条腿:① 返回值的分页元数据;② 下发 mapper 的 wrapper
 * 的 SQL 片段(getSqlSegment 含 LIMIT/OFFSET)与 LIKE 条件。
 */
@ExtendWith(MockitoExtension.class)
class VisitServiceListPageTest {

    @Mock VisitMapper visitMapper;
    @Mock OrderMapper orderMapper;
    @Mock ChargeMapper chargeMapper;
    @Mock ApplicationEventPublisher eventPublisher;
    @Mock PatientService patientService;
    @Mock StaffService staffService;
    @Mock DepartmentService departmentService;
    @Mock VisitReadModelService readModelService;
    @Mock VisitReadModelMapper readModelMapper;

    VisitService service;

    @BeforeEach
    void setUp() {
        service = new VisitService(visitMapper, orderMapper, chargeMapper,
                eventPublisher, patientService, staffService, departmentService,
                readModelService, readModelMapper);
    }

    @Test
    @DisplayName("R-52 pageNum=0 视为第一页,且下发的 OFFSET 非负")
    void pageNumZero_treatedAsFirstPage() {
        when(readModelMapper.selectCount(any())).thenReturn(0L);
        when(readModelMapper.selectList(any())).thenReturn(List.of());

        PageResult<VisitDetail> result = service.listPage(null, 0, 10, null);

        assertThat(result.getPageNum()).isEqualTo(1);

        // 实际下发的分页片段:必须 OFFSET 0,而不是 (0-1)*10 = -10
        ArgumentCaptor<LambdaQueryWrapper<VisitReadModel>> captor =
                ArgumentCaptor.forClass(LambdaQueryWrapper.class);
        verify(readModelMapper).selectList(captor.capture());
        String segment = captor.getValue().getSqlSegment();
        assertThat(segment).contains("LIMIT 10").contains("OFFSET 0");
        assertThat(segment).doesNotContain("OFFSET -");
    }

    @Test
    @DisplayName("R-52 pageSize=0 回落到默认页大小(10)")
    void pageSizeZero_usesDefault() {
        when(readModelMapper.selectCount(any())).thenReturn(0L);
        when(readModelMapper.selectList(any())).thenReturn(List.of());

        PageResult<VisitDetail> result = service.listPage(null, 1, 0, null);

        assertThat(result.getPageSize()).isEqualTo(10);
    }

    @Test
    @DisplayName("R-52 超大 pageSize 被截断到上限 500")
    void pageSizeHuge_capped() {
        when(readModelMapper.selectCount(any())).thenReturn(0L);
        when(readModelMapper.selectList(any())).thenReturn(List.of());

        PageResult<VisitDetail> result = service.listPage(null, 1, Integer.MAX_VALUE, null);

        assertThat(result.getPageSize()).isEqualTo(500);
    }

    @Test
    @DisplayName("R-52 空白 keyword 被忽略(不下推 LIKE 条件)")
    void keywordBlank_ignored() {
        when(readModelMapper.selectCount(any())).thenReturn(0L);
        when(readModelMapper.selectList(any())).thenReturn(List.of());

        service.listPage("   ", 1, 10, null);   // 空白 → 忽略
        service.listPage("张", 1, 10, null);     // 非空 → 下推(对照组)

        ArgumentCaptor<LambdaQueryWrapper<VisitReadModel>> captor =
                ArgumentCaptor.forClass(LambdaQueryWrapper.class);
        verify(readModelMapper, times(2)).selectList(captor.capture());

        List<LambdaQueryWrapper<VisitReadModel>> wrappers = captor.getAllValues();
        // 空白关键字:任何条件下推里都不应出现 LIKE
        assertThat(wrappers.get(0).getSqlSegment()).doesNotContainIgnoringCase("like");
        // 非空关键字:必须生成 patient_name LIKE(证明上面的断言有区分度、不是假通过)
        assertThat(wrappers.get(1).getSqlSegment())
                .containsIgnoringCase("like")
                .contains("patient_name");
    }

    @Test
    @DisplayName("R-52 末页无数据返回空列表而非报错")
    void lastPage_returnsEmptyNotError() {
        when(readModelMapper.selectCount(any())).thenReturn(42L);
        when(readModelMapper.selectList(any())).thenReturn(List.of());

        PageResult<VisitDetail> result = service.listPage(null, 999, 10, null);

        assertThat(result.getItems()).isEmpty();
        assertThat(result.getTotal()).isEqualTo(42);
        assertThat(result.getPageNum()).isEqualTo(999);
    }
}
