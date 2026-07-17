package com.hospital.core.dispatch.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.ArgumentMatchers;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

import com.hospital.core.booking.domain.AppointmentCreatedEvent;
import com.hospital.core.booking.domain.ExamItemBrief;
import com.hospital.core.dispatch.domain.ExamTask;
import com.hospital.core.dispatch.domain.QueueBoard;
import com.hospital.core.dispatch.infrastructure.ExamTaskMapper;
import com.hospital.core.dispatch.infrastructure.QueueBoardMapper;
import com.hospital.core.report.application.ReportService;

/**
 * DispatchService 核心路径:状态机转换 + 事件消费 + 看板排序。
 * <p>
 * 覆盖场景:
 * - 事件消费:预约创建 → 生成 ExamTask + QueueBoard 投影
 * - 状态机:PENDING → IN_PROGRESS → DONE(非法状态拒绝)
 * - 看板查询:按 station 筛选 + 状态/seq 排序
 */
@ExtendWith(MockitoExtension.class)
class DispatchServiceTest {

    @Mock ExamTaskMapper taskMapper;
    @Mock QueueBoardMapper boardMapper;
    @Mock ReportService reportService;
    @Mock ApplicationEventPublisher eventPublisher;

    @Captor ArgumentCaptor<ExamTask> taskCaptor;
    @Captor ArgumentCaptor<QueueBoard> boardCaptor;

    DispatchService service;

    @BeforeEach
    void setUp() {
        service = new DispatchService(taskMapper, boardMapper, reportService,eventPublisher);
    }

    @Nested
    @DisplayName("事件驱动:预约创建 → 任务生成")
    class EventDriven {

        @Test
        @DisplayName("套餐含 3 个项目 → 生成 3 条 ExamTask + 3 条 QueueBoard 投影")
        void onAppointmentCreated_generatesTasksAndBoards() {
            var event = new AppointmentCreatedEvent(
                    100L, 42L, "张三", 7L,
                    List.of(
                            new ExamItemBrief("采血室", "血常规", 1),
                            new ExamItemBrief("B超室", "腹部B超", 2),
                            new ExamItemBrief("影像科", "胸部CT", 3)
                    ));

            service.onAppointmentCreated(event);

            // 验证 INSERT 3 条 ExamTask
            verify(taskMapper, times(3)).insert(taskCaptor.capture());
            List<ExamTask> tasks = taskCaptor.getAllValues();
            assertThat(tasks).hasSize(3);
            assertThat(tasks.get(0).getSeq()).isEqualTo(1);
            assertThat(tasks.get(0).getStation()).isEqualTo("采血室");
            assertThat(tasks.get(0).getStatus()).isEqualTo("PENDING");
            assertThat(tasks.get(1).getSeq()).isEqualTo(2);
            assertThat(tasks.get(2).getSeq()).isEqualTo(3);

            // 验证 INSERT 3 条 QueueBoard 投影
            verify(boardMapper, times(3)).insert(boardCaptor.capture());
            List<QueueBoard> boards = boardCaptor.getAllValues();
            assertThat(boards).hasSize(3);
            assertThat(boards.get(0).getStation()).isEqualTo("采血室");
            assertThat(boards.get(0).getPatientName()).isEqualTo("张三");
            assertThat(boards.get(0).getStatus()).isEqualTo("PENDING");
        }

        @Test
        @DisplayName("空项目列表 → 零任务零投影")
        void onAppointmentCreated_emptyItems_noInsert() {
            var event = new AppointmentCreatedEvent(100L, 42L, "张三", 7L, List.of());

            service.onAppointmentCreated(event);

            verify(taskMapper, never()).insert(ArgumentMatchers.isA(ExamTask.class));
            verify(boardMapper, never()).insert(ArgumentMatchers.isA(QueueBoard.class));
        }
    }

    @Nested
    @DisplayName("状态机: start / complete")
    class StateMachine {

        private ExamTask pendingTask() {
            var t = new ExamTask();
            t.setId(1L);
            t.setAppointmentId(1L);
            t.setPatientId(1L);
            t.setStatus("PENDING");
            t.setStation("采血室");
            t.setSeq(1);
            return t;
        }

        @Test
        @DisplayName("start: PENDING → IN_PROGRESS, 回填 startedAt, 同步看板")
        void start_success() {
            when(taskMapper.selectById(1L)).thenReturn(pendingTask());
            when(boardMapper.selectById(1L)).thenReturn(new QueueBoard());

            service.start(1L);

            verify(taskMapper).updateById(taskCaptor.capture());
            ExamTask updated = taskCaptor.getValue();
            assertThat(updated.getStatus()).isEqualTo("IN_PROGRESS");
            assertThat(updated.getStartedAt()).isNotNull();

            verify(boardMapper).updateById(boardCaptor.capture());
            assertThat(boardCaptor.getValue().getStatus()).isEqualTo("IN_PROGRESS");
        }

        @Test
        @DisplayName("start: 非 PENDING 状态 → IllegalStateException")
        void start_wrongStatus_throws() {
            var t = pendingTask();
            t.setStatus("IN_PROGRESS");
            when(taskMapper.selectById(1L)).thenReturn(t);

            assertThatThrownBy(() -> service.start(1L))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("仅 PENDING");
            verify(taskMapper, never()).updateById(ArgumentMatchers.isA(ExamTask.class));
        }

        @Test
        @DisplayName("complete: IN_PROGRESS → DONE, 回填 doneAt, 同步看板")
        void complete_success() {
            var t = pendingTask();
            t.setStatus("IN_PROGRESS");
            when(taskMapper.selectById(1L)).thenReturn(t);
            when(boardMapper.selectById(1L)).thenReturn(new QueueBoard());
            // Mock reportService to return a report with id
            var mockReport = new com.hospital.core.report.domain.Report();
            mockReport.setId(1L);
            when(reportService.createPatientReport(any(), any(), any())).thenReturn(mockReport);

            service.complete(1L);

            verify(taskMapper).updateById(taskCaptor.capture());
            ExamTask updated = taskCaptor.getValue();
            assertThat(updated.getStatus()).isEqualTo("DONE");
            assertThat(updated.getDoneAt()).isNotNull();

            verify(boardMapper).updateById(boardCaptor.capture());
            assertThat(boardCaptor.getValue().getStatus()).isEqualTo("DONE");
        }

        @Test
        @DisplayName("complete: 非 IN_PROGRESS 状态 → IllegalStateException")
        void complete_wrongStatus_throws() {
            var t = pendingTask(); // status = PENDING
            when(taskMapper.selectById(1L)).thenReturn(t);

            assertThatThrownBy(() -> service.complete(1L))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("仅 IN_PROGRESS");
            verify(taskMapper, never()).updateById(ArgumentMatchers.isA(ExamTask.class));
        }

        @Test
        @DisplayName("start: 任务不存在 → IllegalArgumentException")
        void start_taskNotFound_throws() {
            when(taskMapper.selectById(999L)).thenReturn(null);
            assertThatThrownBy(() -> service.start(999L))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("任务不存在");
        }

        @Test
        @DisplayName("complete: 任务不存在 → IllegalArgumentException")
        void complete_taskNotFound_throws() {
            when(taskMapper.selectById(999L)).thenReturn(null);
            assertThatThrownBy(() -> service.complete(999L))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("任务不存在");
        }
    }

    @Nested
    @DisplayName("看板查询排序")
    class BoardQuery {

        @Test
        @DisplayName("同 station 按 状态优先级(IN_PROGRESS=1 < PENDING=0)再按 seq 排序")
        void board_sortsByStatusThenSeq() {
            var pending1 = board("采血室", "PENDING", 1);
            var inProgress = board("采血室", "IN_PROGRESS", 2);
            var pending2 = board("采血室", "PENDING", 3);
            when(boardMapper.selectList(any())).thenReturn(new ArrayList<>(List.of(pending1, inProgress, pending2)));

            List<QueueBoard> result = service.board("采血室");

            assertThat(result).hasSize(3);
            // PENDING(0) < IN_PROGRESS(1),待检先排;同 PENDING 按 seq 升序
            assertThat(result.get(0).getStatus()).isEqualTo("PENDING");
            assertThat(result.get(0).getSeq()).isEqualTo(1);
            assertThat(result.get(1).getStatus()).isEqualTo("PENDING");
            assertThat(result.get(1).getSeq()).isEqualTo(3);
            assertThat(result.get(2).getStatus()).isEqualTo("IN_PROGRESS");
        }

        @Test
        @DisplayName("station 为空 → 返回全部 station")
        void board_nullStation_returnsAll() {
            when(boardMapper.selectList(any())).thenReturn(new ArrayList<>(List.of(
                    board("A室", "PENDING", 1),
                    board("B室", "PENDING", 1)
            )));
            assertThat(service.board(null)).hasSize(2);
            assertThat(service.board("")).hasSize(2);
            assertThat(service.board("  ")).hasSize(2);
        }

        private QueueBoard board(String station, String status, int seq) {
            var b = new QueueBoard();
            b.setStation(station);
            b.setStatus(status);
            b.setSeq(seq);
            return b;
        }
    }
}
