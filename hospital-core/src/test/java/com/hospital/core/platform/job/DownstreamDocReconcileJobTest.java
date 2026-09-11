package com.hospital.core.platform.job;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.hospital.core.lab.application.LabService;
import com.hospital.core.pharmacy.application.PrescriptionService;
import com.hospital.core.platform.infrastructure.AuditRecorder;

/**
 * R-64: 下游单据对账兜底任务。
 *
 * <p>这个 job 是"确单事件化 + 监听器吞异常"这一取舍的<b>安全网</b>:
 * 没有它,生成失败就只是从浏览器搬到了服务端的黑盒,单据依旧永久缺失。
 * 因此这里重点验证三件事:
 * <ol>
 *   <li>无缺失时不做事(正常情况下每轮都走这条,不能制造噪音或误补建);</li>
 *   <li>有缺失时逐单补建,且走各模块既有的幂等路径;</li>
 *   <li><b>单张就诊单失败不影响其它单</b>,对账查询本身失败也不能让整轮任务崩溃。</li>
 * </ol>
 */
@ExtendWith(MockitoExtension.class)
class DownstreamDocReconcileJobTest {

    @Mock
    LabService labService;
    @Mock
    PrescriptionService prescriptionService;
    @Mock
    AuditRecorder auditRecorder;

    DownstreamDocReconcileJob job;

    @BeforeEach
    void setUp() {
        job = new DownstreamDocReconcileJob(labService, prescriptionService, auditRecorder);
    }

    @Test
    @DisplayName("两侧都无缺失 → 不调用任何补建(正常路径,不应产生任何写操作)")
    void noMissing_doesNothing() {
        when(labService.findVisitIdsNeedingReconcile()).thenReturn(List.of());
        when(prescriptionService.findVisitIdsNeedingReconcile()).thenReturn(List.of());

        job.execute();

        verify(labService, never()).createFromVisit(any(), any(), any());
        verify(prescriptionService, never()).createFromVisit(any(), any());
        // 没有补建就不该有审计(否则审计轨迹里会出现"凭空生成"的记录)
        verify(auditRecorder, never()).record(any(), any(), any());
    }

    @Test
    @DisplayName("有缺失 → 逐单补建(每单独立调用,各自事务)")
    void missing_repairsEachVisit() {
        when(labService.findVisitIdsNeedingReconcile()).thenReturn(List.of(1L, 2L));
        when(prescriptionService.findVisitIdsNeedingReconcile()).thenReturn(List.of(3L));

        job.execute();

        verify(labService).createFromVisit(1L, null, null);
        verify(labService).createFromVisit(2L, null, null);
        verify(prescriptionService).createFromVisit(3L, null);
        // 每次补建都要留痕,且明细标明是系统对账而非人工操作(actor 由 AuditRecorder 取不到安全上下文 → system)
        verify(auditRecorder).record("CREATE_REQUISITION", "visit_id=1", "触发: 对账补建(确单时生成失败或数据异常导入)");
        verify(auditRecorder).record("CREATE_REQUISITION", "visit_id=2", "触发: 对账补建(确单时生成失败或数据异常导入)");
        verify(auditRecorder).record("CREATE_PRESCRIPTION", "visit_id=3", "触发: 对账补建(确单时生成失败或数据异常导入)");
    }

    @Test
    @DisplayName("单张失败不影响其它单:第一张抛异常,第二张仍必须被补建")
    void oneVisitFails_othersStillRepaired() {
        when(labService.findVisitIdsNeedingReconcile()).thenReturn(List.of(1L, 2L));
        when(prescriptionService.findVisitIdsNeedingReconcile()).thenReturn(List.of());
        doThrow(new IllegalStateException("模拟该单补建失败"))
                .when(labService).createFromVisit(eq(1L), any(), any());

        job.execute();

        verify(labService).createFromVisit(2L, null, null);
        // 失败的那一单不得留审计:否则审计轨迹会声称"1 号单已生成检验申请",而库里没有
        verify(auditRecorder, never()).record(any(), eq("visit_id=1"), any());
        verify(auditRecorder).record("CREATE_REQUISITION", "visit_id=2", "触发: 对账补建(确单时生成失败或数据异常导入)");
    }

    @Test
    @DisplayName("对账查询失败不影响整轮任务,也不影响另一侧(下一轮再试)")
    void queryFails_doesNotAbortRound() {
        when(labService.findVisitIdsNeedingReconcile())
                .thenThrow(new IllegalStateException("模拟数据库不可用"));
        when(prescriptionService.findVisitIdsNeedingReconcile()).thenReturn(List.of(3L));

        assertThatCode(() -> job.execute()).doesNotThrowAnyException();

        // 检验侧查询失败,但处方侧仍应照常完成
        verify(prescriptionService).createFromVisit(3L, null);
    }
}
