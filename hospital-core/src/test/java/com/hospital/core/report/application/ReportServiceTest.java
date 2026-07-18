package com.hospital.core.report.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.hospital.core.clinical.infrastructure.VisitMapper;
import com.hospital.core.org.application.DepartmentService;
import com.hospital.core.org.application.StaffService;
import com.hospital.core.patient.application.PatientService;
import com.hospital.core.report.domain.Report;
import com.hospital.core.report.infrastructure.ReportMapper;

@ExtendWith(MockitoExtension.class)
class ReportServiceTest {

    @Mock ReportMapper reportMapper;
    @Mock VisitMapper visitMapper;
    @Mock PatientService patientService;
    @Mock StaffService staffService;
    @Mock DepartmentService departmentService;
    @Captor ArgumentCaptor<Report> reportCaptor;
    ReportService service;

    @BeforeEach
    void setUp() {
        service = new ReportService(reportMapper,visitMapper,patientService,staffService,departmentService);
    }

    @Test
    @DisplayName("创建报告 → DRAFT 状态, INSERT")
    void create_success() {
        Report result = service.create(1L, "LAB", "血常规报告", "结果正常", 5L);

        verify(reportMapper).insert(reportCaptor.capture());
        assertThat(reportCaptor.getValue().getVisitId()).isEqualTo(1L);
        assertThat(reportCaptor.getValue().getTitle()).isEqualTo("血常规报告");
        assertThat(reportCaptor.getValue().getStatus()).isEqualTo("DRAFT");
        assertThat(result.getStatus()).isEqualTo("DRAFT");
    }

    @Test
    @DisplayName("发布报告 → DRAFT → PUBLISHED, 回填发布时间")
    void publish_success() {
        var report = new Report();
        report.setId(1L);
        report.setStatus("DRAFT");
        when(reportMapper.selectById(1L)).thenReturn(report);

        Report result = service.publish(1L);

        verify(reportMapper).updateById(reportCaptor.capture());
        assertThat(reportCaptor.getValue().getStatus()).isEqualTo("PUBLISHED");
        assertThat(reportCaptor.getValue().getPublishedAt()).isNotNull();
        assertThat(result.getStatus()).isEqualTo("PUBLISHED");
    }

    @Test
    @DisplayName("报告不存在 → IllegalArgumentException")
    void publish_notFound_throws() {
        when(reportMapper.selectById(999L)).thenReturn(null);
        assertThatThrownBy(() -> service.publish(999L))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("不存在");
    }
}
