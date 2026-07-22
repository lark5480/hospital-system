package com.hospital.notification.store;

import com.hospital.notification.model.NotificationBroadcastEvent;
import com.hospital.notification.model.NotificationRecord;
import com.hospital.notification.model.OrderCreatedEvent;
import com.hospital.notification.model.PatientCalledEvent;
import com.hospital.notification.model.VisitCreatedEvent;
import com.hospital.notification.model.VisitStatusEvent;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 内存版通知记录存储(演示用途,非生产持久化)。
 * 线程安全(CopyOnWriteArrayList),保留最近 N 条,供前端 /api/notify 拉取展示。
 */
@Component
@RequiredArgsConstructor
public class NotificationStore {

    private static final int CAP = 200;

    private final ApplicationEventPublisher eventPublisher;
    private final List<NotificationRecord> records = new CopyOnWriteArrayList<>();
    private final AtomicLong seq = new AtomicLong(1);

    public void record(VisitCreatedEvent event) {
        // 建单通知不再发送(患者看大屏排队,医生不需要收到自己建单的通知)
    }

    /** 记录「叫号通知」事件,驱动 C 端患者端叫号提示。 */
    public void recordCalled(PatientCalledEvent event) {
        NotificationRecord r = new NotificationRecord(
                seq.getAndIncrement(),
                "PATIENT_CALLED",
                event.appointmentId(),
                event.patientId(),
                null,
                event.calledAt(),
                LocalDateTime.now(),
                "IN_APP",
                "您已被叫号,请到【" + event.station() + "】进行【" + event.itemName() + "】检查",
                "PATIENT",
                null
        );
        records.add(0, r);
        if (records.size() > CAP) {
            records.remove(records.size() - 1);
        }
        eventPublisher.publishEvent(new NotificationBroadcastEvent(r));
    }

    /** 记录就诊状态变更事件 */
    public void recordVisitStatus(VisitStatusEvent event) {
        String type;
        String targetRole;
        switch (event.status()) {
            case "PAID":
                type = "VISIT_PAID";
                targetRole = "PHARMACIST";  // 缴费完成通知药房
                break;
            case "FINISHED":
                type = "VISIT_FINISHED";
                targetRole = "PATIENT";  // 就诊完成通知患者
                break;
            default:
                type = "VISIT_STATUS";
                targetRole = "DOCTOR";
        }
        NotificationRecord r = new NotificationRecord(
                seq.getAndIncrement(),
                type,
                event.visitId(),
                event.patientId(),
                null,
                LocalDateTime.now(),
                LocalDateTime.now(),
                "IN_APP",
                event.message(),
                targetRole,
                null
        );
        records.add(0, r);
        if (records.size() > CAP) {
            records.remove(records.size() - 1);
        }
        eventPublisher.publishEvent(new NotificationBroadcastEvent(r));
    }

    /** 记录医嘱创建事件 */
    public void recordOrderCreated(OrderCreatedEvent event) {
        String type = switch (event.orderType()) {
            case "EXAM" -> "ORDER_EXAM";
            case "LAB" -> "ORDER_LAB";
            default -> "ORDER_MEDICATION";
        };
        String targetRole;
        Long targetDeptId;
        if ("EXAM".equals(event.orderType()) || "LAB".equals(event.orderType())) {
            // 检查/检验医嘱 → 通知执行科室
            targetRole = "DOCTOR";
            targetDeptId = event.executionDeptId();
        } else {
            // 药品医嘱 → 不在创建时通知(缴费后才通知药房)
            targetRole = "PHARMACIST";
            targetDeptId = null;
        }
        NotificationRecord r = new NotificationRecord(
                seq.getAndIncrement(),
                type,
                event.visitId(),
                event.patientId(),
                null,
                LocalDateTime.now(),
                LocalDateTime.now(),
                "IN_APP",
                "新" + (event.orderType().equals("EXAM") ? "检查" : event.orderType().equals("LAB") ? "检验" : "药品") +
                "医嘱: " + event.itemName() + "，请安排执行",
                targetRole,
                targetDeptId
        );
        records.add(0, r);
        if (records.size() > CAP) {
            records.remove(records.size() - 1);
        }
        eventPublisher.publishEvent(new NotificationBroadcastEvent(r));
    }

    public List<NotificationRecord> all() {
        return new ArrayList<>(records);
    }

    public List<NotificationRecord> findByRole(String role) {
        return records.stream()
                .filter(r -> role.equals(r.targetRole()))
                .toList();
    }

    /** 按科室过滤通知:targetDeptId 为 null 的通知(如药品缴费通知)也返回 */
    public List<NotificationRecord> findByDeptId(Long deptId) {
        return records.stream()
                .filter(r -> r.targetDeptId() == null || deptId.equals(r.targetDeptId()))
                .toList();
    }
}
