package com.hospital.core.clinical.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.hospital.core.clinical.domain.Registration;
import com.hospital.core.clinical.domain.Visit;
import com.hospital.core.clinical.infrastructure.RegistrationMapper;
import com.hospital.core.patient.application.PatientService;

@ExtendWith(MockitoExtension.class)
class RegistrationServiceTest {

    @Mock RegistrationMapper registrationMapper;
    @Mock VisitService visitService;
    @Mock PatientService patientService;

    @Captor ArgumentCaptor<Registration> regCaptor;

    RegistrationService service;

    @BeforeEach
    void setUp() {
        service = new RegistrationService(registrationMapper, visitService, patientService);
    }

    private static Registration registration(Long id, Long patientId, Long deptId, String status, int queueNo) {
        Registration r = new Registration();
        r.setId(id);
        r.setPatientId(patientId);
        r.setDeptId(deptId);
        r.setStatus(status);
        r.setQueueNo(queueNo);
        return r;
    }

    @Nested
    @DisplayName("挂号 register")
    class Register {

        @Test
        @DisplayName("挂号成功:生成排队号、WAITING 状态、正确设置 patientId/deptId")
        void register_success() {
            when(patientService.get(42L)).thenReturn(new com.hospital.core.patient.domain.Patient());
            when(registrationMapper.selectCount(any())).thenReturn(3L);

            Registration result = service.register(42L, 7L, 10L);

            verify(registrationMapper).insert(regCaptor.capture());
            Registration saved = regCaptor.getValue();
            assertThat(saved.getPatientId()).isEqualTo(42L);
            assertThat(saved.getDeptId()).isEqualTo(7L);
            assertThat(saved.getDoctorId()).isEqualTo(10L);
            assertThat(saved.getQueueNo()).isEqualTo(4);
            assertThat(saved.getStatus()).isEqualTo("WAITING");
            assertThat(saved.getCreatedAt()).isNotNull();
        }

        @Test
        @DisplayName("患者不存在 → IllegalArgumentException")
        void register_patientNotFound_throws() {
            when(patientService.get(99L)).thenReturn(null);

            assertThatThrownBy(() -> service.register(99L, 7L, null))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("患者不存在");
        }
    }

    @Nested
    @DisplayName("叫号 callNext")
    class CallNext {

        @Test
        @DisplayName("叫号成功:取 WAITING 中最小排队号、创建就诊单、关联 visitId、状态变 CALLED")
        void callNext_success() {
            Registration waiting = registration(1L, 42L, 7L, "WAITING", 1);
            when(registrationMapper.selectOne(any())).thenReturn(waiting);
            Visit createdVisit = new Visit();
            createdVisit.setId(100L);
            when(visitService.create(any(Visit.class))).thenReturn(createdVisit);

            Registration result = service.callNext(7L);

            assertThat(result.getStatus()).isEqualTo("CALLED");
            assertThat(result.getVisitId()).isEqualTo(100L);
            assertThat(result.getCalledAt()).isNotNull();
            verify(registrationMapper).updateById(result);
        }

        @Test
        @DisplayName("叫号空队列:无 WAITING 记录 → IllegalStateException")
        void callNext_emptyQueue_throws() {
            when(registrationMapper.selectOne(any())).thenReturn(null);

            assertThatThrownBy(() -> service.callNext(7L))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("当前无候诊患者");
        }
    }

    @Nested
    @DisplayName("取消挂号 cancel")
    class Cancel {

        @Test
        @DisplayName("取消成功:WAITING → CANCELLED")
        void cancel_success() {
            Registration waiting = registration(1L, 42L, 7L, "WAITING", 1);
            when(registrationMapper.selectById(1L)).thenReturn(waiting);

            Registration result = service.cancel(1L);

            assertThat(result.getStatus()).isEqualTo("CANCELLED");
            verify(registrationMapper).updateById(result);
        }

        @Test
        @DisplayName("挂号记录不存在 → IllegalArgumentException")
        void cancel_notFound_throws() {
            when(registrationMapper.selectById(99L)).thenReturn(null);

            assertThatThrownBy(() -> service.cancel(99L))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("挂号记录不存在");
        }

        @Test
        @DisplayName("非 WAITING 状态 → IllegalStateException")
        void cancel_notWaiting_throws() {
            Registration called = registration(1L, 42L, 7L, "CALLED", 1);
            when(registrationMapper.selectById(1L)).thenReturn(called);

            assertThatThrownBy(() -> service.cancel(1L))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("仅候诊状态可取消");
        }
    }
}
