package com.hospital.core.dispatch.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.hospital.core.booking.domain.AppointmentCancelledEvent;
import com.hospital.core.dispatch.domain.ExamTask;
import com.hospital.core.dispatch.domain.QueueBoard;
import com.hospital.core.dispatch.infrastructure.ExamTaskMapper;
import com.hospital.core.dispatch.infrastructure.QueueBoardMapper;
import com.hospital.core.report.application.ReportService;

/**
 * {@link DispatchService#onAppointmentCancelled(AppointmentCancelledEvent)} 的纯 Mockito 单测。
 *
 * <p>这里是「取消」真正有价值的一侧:预约在 booking 只改一行状态,在 dispatch 却要把
 * 已展开的排队任务与看板投影一起撤掉,否则患者会继续挂在队列里。本用例锁死三件事:
 * <ul>
 *   <li>只清理 PENDING(未开始);IN_PROGRESS / DONE / SKIPPED 一律不动(人已经在检查/已出结果);</li>
 *   <li>写模型与 queue_board 投影同步清理(两者 id 相同,一次按 id 批量删);</li>
 *   <li>重复消费不报错(幂等),无 PENDING 可清时直接返回。</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
class DispatchServiceCancelTest {

    @Mock ExamTaskMapper taskMapper;
    @Mock QueueBoardMapper boardMapper;
    @Mock ReportService reportService;
    @Mock ApplicationEventPublisher eventPublisher;
    @Mock com.hospital.core.dispatch.api.DispatchSseController sseController;

    @Captor ArgumentCaptor<QueryWrapper<ExamTask>> taskDeleteCaptor;
    @Captor ArgumentCaptor<QueryWrapper<QueueBoard>> boardDeleteCaptor;
    @Captor ArgumentCaptor<BoardUpdateEvent> sseCaptor;

    DispatchService service;

    private static final Long APPT_ID = 100L;
    private static final Long PATIENT_ID = 42L;

    @BeforeEach
    void setUp() {
        service = new DispatchService(taskMapper, boardMapper, reportService, eventPublisher, sseController);
    }

    private static ExamTask task(Long id, String status, String station) {
        ExamTask t = new ExamTask();
        t.setId(id);
        t.setAppointmentId(APPT_ID);
        t.setPatientId(PATIENT_ID);
        t.setStatus(status);
        t.setStation(station);
        t.setSeq(id.intValue());
        return t;
    }

    /** 取消事件:只带 appointmentId / patientId,dispatch 侧据此自行定位任务(零跨模块回查)。 */
    private static AppointmentCancelledEvent cancelEvent() {
        return new AppointmentCancelledEvent(APPT_ID, PATIENT_ID);
    }

    /**
     * MyBatis-Plus 的 SQL 段(含参数占位)是<b>惰性生成</b>的:只有真正渲染 SQL 时
     * 才会把值写进 paramNameValuePairs。单测里 mapper 是 mock、不会被渲染,
     * 故断言前需显式触发一次,否则读到的是空 map。
     */
    private static Collection<Object> paramsOf(QueryWrapper<?> wrapper) {
        wrapper.getSqlSegment();
        return new ArrayList<>(wrapper.getParamNameValuePairs().values());
    }

    @Test
    @DisplayName("清理 PENDING 任务 + 对应看板投影;IN_PROGRESS/DONE/SKIPPED 一律不动")
    void onAppointmentCancelled_removesOnlyPending() {
        when(taskMapper.selectList(any())).thenReturn(List.of(
                task(1L, "PENDING", "采血室"),
                task(2L, "IN_PROGRESS", "B超室"),   // 人正在检查 → 不动
                task(3L, "DONE", "影像科"),          // 已出结果 → 不动
                task(4L, "SKIPPED", "心电图室"),     // 运营端可重新排队 → 不动
                task(5L, "PENDING", "B超室")));

        service.onAppointmentCancelled(cancelEvent());

        // 写模型:只删 1、5 两条 PENDING
        verify(taskMapper).delete(taskDeleteCaptor.capture());
        Collection<Object> taskParams = paramsOf(taskDeleteCaptor.getValue());
        assertThat(taskParams).as("只有 PENDING 任务 id 进入删除条件").contains(1L, 5L);
        assertThat(taskParams).as("已开始/已完成/已跳过任务不得被删")
                .doesNotContain(2L, 3L, 4L);
        assertThat(taskParams).as("删除条件需带 status=PENDING 二次护栏,避免误删已开始任务")
                .contains("PENDING");

        // 投影:queue_board 与 exam_task id 相同,按同一批 id 清理
        verify(boardMapper).delete(boardDeleteCaptor.capture());
        Collection<Object> boardParams = paramsOf(boardDeleteCaptor.getValue());
        assertThat(boardParams).contains(1L, 5L).doesNotContain(2L, 3L, 4L);
    }

    @Test
    @DisplayName("SSE 广播:每个被清理任务各一条 cancel 事件(无事务上下文时直接推送)")
    void onAppointmentCancelled_broadcastsPerRemovedTask() {
        when(taskMapper.selectList(any())).thenReturn(List.of(
                task(1L, "PENDING", "采血室"),
                task(2L, "PENDING", "B超室"),
                task(3L, "IN_PROGRESS", "影像科")));

        service.onAppointmentCancelled(cancelEvent());

        verify(sseController, times(2)).broadcastBoardUpdate(sseCaptor.capture());
        List<BoardUpdateEvent> events = sseCaptor.getAllValues();
        assertThat(events).extracting(BoardUpdateEvent::action).containsExactly("cancel", "cancel");
        assertThat(events).extracting(BoardUpdateEvent::taskId).containsExactly(1L, 2L);
        assertThat(events).extracting(BoardUpdateEvent::station).containsExactly("采血室", "B超室");
    }

    @Test
    @DisplayName("幂等:已无 PENDING 任务时重复消费 → 静默返回,不删任何数据、不广播")
    void onAppointmentCancelled_idempotent() {
        // 模拟事件重投:此时该预约只剩检查中/已完成的任务
        when(taskMapper.selectList(any())).thenReturn(List.of(
                task(2L, "IN_PROGRESS", "B超室"),
                task(3L, "DONE", "影像科")));

        assertThatCode(() -> service.onAppointmentCancelled(cancelEvent()))
                .as("重复消费不得抛异常(会让 booking 侧事务回滚)").doesNotThrowAnyException();

        verify(taskMapper, never()).delete(any());
        verify(boardMapper, never()).delete(any());
        verify(sseController, never()).broadcastBoardUpdate(any());
    }

    @Test
    @DisplayName("连续两次消费:第二次已无 PENDING → 删除只发生一次,且无异常")
    void onAppointmentCancelled_twice_onlyFirstDeletes() {
        when(taskMapper.selectList(any()))
                .thenReturn(List.of(task(1L, "PENDING", "采血室")))
                .thenReturn(List.of());   // 第二次:任务已被清干净

        service.onAppointmentCancelled(cancelEvent());
        service.onAppointmentCancelled(cancelEvent());

        verify(taskMapper, times(1)).delete(any());
        verify(boardMapper, times(1)).delete(any());
        verify(sseController, times(1)).broadcastBoardUpdate(any());
    }
}
