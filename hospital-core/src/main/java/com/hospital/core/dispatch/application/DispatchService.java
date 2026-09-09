package com.hospital.core.dispatch.application;

import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.hospital.core.booking.domain.AppointmentCreatedEvent;
import com.hospital.core.booking.domain.AppointmentStatusEvent;
import com.hospital.core.booking.domain.ExamItemBrief;
import com.hospital.core.dispatch.api.DispatchSseController;
import com.hospital.core.dispatch.domain.ExamTask;
import com.hospital.core.dispatch.domain.PatientCalledEvent;
import com.hospital.core.dispatch.domain.QueueBoard;
import com.hospital.core.dispatch.infrastructure.ExamTaskMapper;
import com.hospital.core.dispatch.infrastructure.QueueBoardMapper;
import com.hospital.core.report.application.ReportService;
import com.hospital.core.report.domain.Report;
import com.hospital.core.report.domain.ReportPdfEvent;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 排队分发引擎(应用服务)。
 * - 写侧:消费 booking 发布的 AppointmentCreatedEvent,为套餐内每个项目生成一条 ExamTask。
 * - 读侧(CQRS):同步维护 dispatch.queue_board 物化投影,看板只查投影,不碰写模型。
 * - 生命周期:start / complete 推进任务状态并双向同步投影。
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class DispatchService {

    private final ExamTaskMapper taskMapper;
    private final QueueBoardMapper boardMapper;
    private final ReportService reportService;
    private final ApplicationEventPublisher eventPublisher;
    private final DispatchSseController sseController;

    private static final Map<String, Integer> STATUS_ORDER = Map.of(
            "PENDING", 0, "IN_PROGRESS", 1, "SKIPPED", 2, "DONE", 3);

    /**
     * 事件驱动入口:预约创建后(booking 事务提交),按项目顺序生成各 station 任务 + 看板投影。
     * 事件为自包含快照,此处不回查 booking/patient,零跨模块 DAO 依赖。
     */
    @TransactionalEventListener
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void onAppointmentCreated(AppointmentCreatedEvent event) {
        int seq = 0;
        for (ExamItemBrief item : event.items()) {
            seq++;
            ExamTask task = new ExamTask();
            task.setAppointmentId(event.appointmentId());
            task.setPatientId(event.patientId());
            task.setPackageId(event.packageId());
            task.setStation(item.station());
            task.setItemName(item.name());
            task.setPatientName(event.patientName());
            task.setStatus("PENDING");
            task.setSeq(seq);
            task.setCreatedAt(LocalDateTime.now());
            taskMapper.insert(task);

            // 投影:看板读模型(与写模型 1:1,id 相同)
            QueueBoard board = new QueueBoard();
            board.setId(task.getId());
            board.setStation(task.getStation());
            board.setItemName(task.getItemName());
            board.setPatientName(task.getPatientName());
            board.setStatus(task.getStatus());
            board.setSeq(task.getSeq());
            board.setCreatedAt(task.getCreatedAt());
            boardMapper.insert(board);
        }
    }

    /** 工位开始检查某任务。 */
    @Transactional
    public void start(Long taskId) {
        ExamTask t = requireTask(taskId);
        if (!"PENDING".equals(t.getStatus())) {
            throw new IllegalStateException("仅 PENDING 任务可开始");
        }
        // 患者级单活跃约束:同一患者一次只能在一个科室检查(真实场景:做完一项再去下一项)。
        assertPatientFree(t);
        // 医生指定顺序:仅当该任务是患者当前最早待检项时才允许开始。
        assertIsPatientNext(t);
        t.setStatus("IN_PROGRESS");
        t.setStartedAt(LocalDateTime.now());
        taskMapper.updateById(t);
        syncBoard(t);
        // 回写预约单:该预约首个任务开始 = 到院(CHECKED_IN)
        maybePublishCheckedIn(t.getAppointmentId());
        // SSE推送(R-13: 事务提交后再广播)
        broadcastAfterCommit(new BoardUpdateEvent(t.getStation(), taskId, "start"));
    }

    /** 工位完成检查某任务。若某预约的全部任务均已终态(完成/跳过),自动生成报告。 */
    @Transactional
    public void complete(Long taskId) {
        ExamTask t = requireTask(taskId);
        if (!"IN_PROGRESS".equals(t.getStatus())) {
            throw new IllegalStateException("仅 IN_PROGRESS 任务可完成");
        }
        t.setStatus("DONE");
        t.setDoneAt(LocalDateTime.now());
        taskMapper.updateById(t);
        syncBoard(t);

        // 检查该预约是否所有任务都已完成 → 自动出报告
        maybeGenerateReport(t.getAppointmentId(), t.getPatientId());

        // 自动叫号:推进同 station 下一位待检患者(过号重排由人工 reorder-tail 处理)
        callNext(t.getStation());
        // SSE推送(R-13: 事务提交后再广播;注册晚于 callNext,提交时顺序与改造前一致)
        broadcastAfterCommit(new BoardUpdateEvent(t.getStation(), taskId, "complete"));
    }

    /**
     * 若某预约已无待检/检查中任务且至少完成一项,自动创建一份已发布的体检报告。
     * 跳过项不阻塞出报告(真实场景:患者放弃某项仍应出具已检项目的报告),报告内标注未检项。
     * 全部跳过(零完成)不出报告。
     */
    private void maybeGenerateReport(Long appointmentId, Long patientId) {
        // 幂等护栏:同一预约只出一份报告(以真实报告记录为准)
        if (reportService.existsForAppointment(appointmentId)) return;
        List<ExamTask> all = taskMapper.selectList(
                new QueryWrapper<ExamTask>().eq("appointment_id", appointmentId));
        boolean anyActive = all.stream().anyMatch(t ->
                "PENDING".equals(t.getStatus()) || "IN_PROGRESS".equals(t.getStatus()));
        if (anyActive) return;
        List<ExamTask> done = all.stream().filter(t -> "DONE".equals(t.getStatus())).toList();
        List<ExamTask> skipped = all.stream().filter(t -> "SKIPPED".equals(t.getStatus())).toList();
        if (done.isEmpty()) return;

        String title = "体检报告(完成 " + done.size() + "/" + all.size() + " 项)";
        StringBuilder content = new StringBuilder();
        content.append("## 体检总结\n\n");
        content.append("**完成项目**:\n\n");
        for (ExamTask t : done) {
            content.append("- **").append(t.getItemName()).append("**");
            content.append(" (").append(t.getStation()).append(")");
            content.append(" → 未见异常\n");
        }
        if (!skipped.isEmpty()) {
            content.append("\n**未检项目**(已跳过):\n\n");
            for (ExamTask t : skipped) {
                content.append("- ").append(t.getItemName())
                        .append(" (").append(t.getStation()).append(")\n");
            }
        }
        content.append("\n---\n\n");
        if (skipped.isEmpty()) {
            content.append("**结论**:本次体检各项目均已完成,未见明显异常。\n");
        } else {
            content.append("**结论**:已完成项目未见明显异常;存在 ").append(skipped.size())
                    .append(" 项未检,建议另行预约补检。\n");
        }
        content.append("**建议**:保持良好生活习惯,定期复查。\n");

        Report report = reportService.createPatientReport(patientId, appointmentId, title, content.toString());
        // 体检报告落库后,发布 PDF 生成事件(异步预生成并上传 MinIO,解耦生成与下载)
        eventPublisher.publishEvent(new ReportPdfEvent(report.getId(), patientId, title, content.toString()));
        // 回写预约单:全部任务完成 = DONE
        eventPublisher.publishEvent(new AppointmentStatusEvent(appointmentId, "DONE"));
    }

    /** 当前患者的排队情况(按 patientId 过滤)。 */
    public List<ExamTask> myQueue(Long patientId) {
        QueryWrapper<ExamTask> q = new QueryWrapper<ExamTask>()
                .eq("patient_id", patientId)
                .ne("status", "DONE")
                .orderByAsc("seq");
        return taskMapper.selectList(q);
    }

    /** 看板读模型查询(按 station 过滤可选)。同 station 内:进行中/待检前置,再按 seq。 */
    public List<QueueBoard> board(String station) {
        QueryWrapper<QueueBoard> q = new QueryWrapper<>();
        if (station != null && !station.isBlank()) {
            q.eq("station", station);
        }
        q.orderByAsc("station", "seq");
        List<QueueBoard> rows = boardMapper.selectList(q);
        rows.sort(Comparator.comparingInt((QueueBoard r) ->
                STATUS_ORDER.getOrDefault(r.getStatus(), 9)).thenComparing(QueueBoard::getSeq));
        return rows;
    }

    /**
     * 自动叫号:取该 station 下一个待检任务置为 IN_PROGRESS(被叫到/开始检查)。
     * 双重护栏(契合真实体检场景):
     *  1) 患者级单活跃 — 正在其他科室检查中的患者不会被叫到(一次只在一个科室);
     *  2) 医生指定顺序 — 仅当该任务是患者"当前最早待检项"时才叫号(前面的项目没做完不叫后面的)。
     * 返回被叫到的任务;该 station 无合规待检任务时返回 null。complete() 完成后会自动调用本方法推进下一位。
     */
    @Transactional
    public ExamTask callNext(String station) {
        List<ExamTask> pending = taskMapper.selectList(new QueryWrapper<ExamTask>()
                .eq("station", station)
                .eq("status", "PENDING")
                .orderByAsc("seq"));
        for (ExamTask cand : pending) {
            // 护栏1:跳过正在其他科室检查的患者
            if (patientHasInProgress(cand.getPatientId())) continue;
            // 护栏2:跳过顺序更靠前项目仍未完成的患者(遵循医生指定顺序)
            if (cand.getSeq() > patientMinPendingSeq(cand.getPatientId())) continue;
            cand.setStatus("IN_PROGRESS");
            LocalDateTime calledAt = LocalDateTime.now();
            cand.setStartedAt(calledAt);
            taskMapper.updateById(cand);
            syncBoard(cand);
            // 发布叫号事件 → 通知服务驱动 C 端「叫号通知」(事务提交后由 AmqpBridge 异步发出)
            eventPublisher.publishEvent(new PatientCalledEvent(
                    cand.getId(), cand.getAppointmentId(), cand.getPatientId(),
                    cand.getPatientName(), cand.getStation(), cand.getItemName(), calledAt));
            // 回写预约单:该预约首个任务开始 = 到院(CHECKED_IN)
            maybePublishCheckedIn(cand.getAppointmentId());
            // SSE推送(R-13: 事务提交后再广播)
            broadcastAfterCommit(new BoardUpdateEvent(station, cand.getId(), "callNext"));
            return cand;
        }
        return null;
    }

    /**
     * 过号重排:将某 PENDING 任务移到该 station 队列末尾(保留待检状态),实现"过期往后排"。
     * 仅 PENDING 可重排;已在检查中或已完成的不允许。
     */
    @Transactional
    public void reorderToTail(Long taskId) {
        ExamTask t = requireTask(taskId);
        if (!"PENDING".equals(t.getStatus())) {
            throw new IllegalStateException("仅 PENDING 任务可过号重排");
        }
        Integer maxSeq = taskMapper.selectList(new QueryWrapper<ExamTask>().eq("station", t.getStation()))
                .stream().map(ExamTask::getSeq).max(Integer::compareTo).orElse(0);
        t.setSeq(maxSeq + 1);
        taskMapper.updateById(t);
        syncBoard(t);
        // SSE推送(R-13: 事务提交后再广播)
        broadcastAfterCommit(new BoardUpdateEvent(t.getStation(), taskId, "reorder"));
    }

    /** 跳过:放弃某任务置 SKIPPED(用于患者离开等场景)。仅待检/检查中可跳过。 */
    @Transactional
    public void skip(Long taskId) {
        ExamTask t = requireTask(taskId);
        if (!"PENDING".equals(t.getStatus()) && !"IN_PROGRESS".equals(t.getStatus())) {
            throw new IllegalStateException("仅待检/检查中任务可跳过");
        }
        t.setStatus("SKIPPED");
        taskMapper.updateById(t);
        syncBoard(t);
        // 跳过可能是该预约最后一个活跃任务 → 检查是否可出报告(跳过项不阻塞)
        maybeGenerateReport(t.getAppointmentId(), t.getPatientId());
        // 该工位空出来了,自动叫号下一位
        callNext(t.getStation());
        // SSE推送(R-13: 事务提交后再广播;注册晚于 callNext,提交时顺序与改造前一致)
        broadcastAfterCommit(new BoardUpdateEvent(t.getStation(), taskId, "skip"));
    }

    /**
     * 重新排队:将某 SKIPPED 任务恢复为 PENDING 并排到该 station 队尾(患者去而复返场景)。
     * 若该预约已出具报告(查真实报告记录,不靠任务状态推断)则不允许,避免重复出报告。
     */
    @Transactional
    public void requeue(Long taskId) {
        ExamTask t = requireTask(taskId);
        if (!"SKIPPED".equals(t.getStatus())) {
            throw new IllegalStateException("仅已跳过任务可重新排队");
        }
        if (reportService.existsForAppointment(t.getAppointmentId())) {
            throw new IllegalStateException("该预约已出具报告,跳过项不可再重新排队,请另行预约补检");
        }
        Integer maxSeq = taskMapper.selectList(new QueryWrapper<ExamTask>().eq("station", t.getStation()))
                .stream().map(ExamTask::getSeq).max(Integer::compareTo).orElse(0);
        t.setStatus("PENDING");
        t.setSeq(maxSeq + 1);
        t.setStartedAt(null);
        taskMapper.updateById(t);
        syncBoard(t);
        // SSE推送(R-13: 事务提交后再广播)
        broadcastAfterCommit(new BoardUpdateEvent(t.getStation(), taskId, "requeue"));
    }

    /** 活跃工位列表(存在 PENDING/IN_PROGRESS 任务的 station),供大屏页选择。 */
    public List<String> listActiveStations() {
        List<QueueBoard> rows = boardMapper.selectList(new QueryWrapper<QueueBoard>()
                .select("distinct station")
                .in("status", "PENDING", "IN_PROGRESS"));
        return rows.stream().map(QueueBoard::getStation).distinct().toList();
    }

    private ExamTask requireTask(Long taskId) {
        ExamTask t = taskMapper.selectById(taskId);
        if (t == null) {
            throw new IllegalArgumentException("任务不存在: " + taskId);
        }
        return t;
    }

    /** 护栏:同一患者不得同时在多个科室处于检查中(真实场景:一次只在一个科室)。 */
    private boolean patientHasInProgress(Long patientId) {
        return !taskMapper.selectList(new QueryWrapper<ExamTask>()
                .eq("patient_id", patientId).eq("status", "IN_PROGRESS")).isEmpty();
    }

    /** 该患者所有待检项里的最小 seq(即医生指定顺序中最靠前的项目)。 */
    private int patientMinPendingSeq(Long patientId) {
        return taskMapper.selectList(new QueryWrapper<ExamTask>()
                .eq("patient_id", patientId).eq("status", "PENDING"))
                .stream().map(ExamTask::getSeq).min(Integer::compareTo).orElse(Integer.MAX_VALUE);
    }

    /** 患者级单活跃约束:若患者正在其他科室检查中则拒绝开始。 */
    private void assertPatientFree(ExamTask t) {
        List<ExamTask> active = taskMapper.selectList(new QueryWrapper<ExamTask>()
                .eq("patient_id", t.getPatientId()).eq("status", "IN_PROGRESS"));
        if (!active.isEmpty()) {
            throw new IllegalStateException("该患者正在[" + active.get(0).getStation()
                    + "]检查中,请完成当前项目后再开始其他科室检查");
        }
    }

    /** 医生指定顺序约束:仅当该任务是患者当前最早待检项时才允许开始。 */
    private void assertIsPatientNext(ExamTask t) {
        int minSeq = patientMinPendingSeq(t.getPatientId());
        if (t.getSeq() > minSeq) {
            throw new IllegalStateException("请按医生指定顺序检查:当前应先完成顺序更靠前的项目");
        }
    }

    /** 写模型 → 投影单向同步(同事务,强一致)。 */
    private void syncBoard(ExamTask t) {
        QueueBoard b = boardMapper.selectById(t.getId());
        if (b == null) {
            return;
        }
        b.setStatus(t.getStatus());
        b.setStartedAt(t.getStartedAt());
        b.setDoneAt(t.getDoneAt());
        b.setSeq(t.getSeq());
        boardMapper.updateById(b);
    }

    /** 若该预约已有任务处于检查中,发布 CHECKED_IN 事件(Booking 模块幂等回写,仅 BOOKED→CHECKED_IN)。 */
    private void maybePublishCheckedIn(Long appointmentId) {
        long inProgress = taskMapper.selectCount(
                new QueryWrapper<ExamTask>().eq("appointment_id", appointmentId).eq("status", "IN_PROGRESS"));
        if (inProgress >= 1) {
            eventPublisher.publishEvent(new AppointmentStatusEvent(appointmentId, "CHECKED_IN"));
        }
    }

    /**
     * R-13: SSE 广播必须挪到数据库事务提交之后执行。
     * 原来在 @Transactional 方法内同步调用 broadcastBoardUpdate，慢客户端(网络拥塞/半开连接)会把
     * 数据库事务拖长、持锁不放，极端情况耗尽连接池；改为注册事务同步回调在 afterCommit 推送。
     * 无事务上下文(如单元测试、被非事务方法调用)时保持原行为直接推送。
     */
    private void broadcastAfterCommit(BoardUpdateEvent event) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    try {
                        sseController.broadcastBoardUpdate(event);
                    } catch (Exception e) {
                        // R-13: 事务已提交，推送失败不能回滚业务，仅记录告警
                        log.warn("[SSE] 看板事件广播失败(事务已提交,不影响业务): {}", e.toString());
                    }
                }
            });
        } else {
            sseController.broadcastBoardUpdate(event);
        }
    }
}
