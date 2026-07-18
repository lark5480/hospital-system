package com.hospital.notification.store;

import com.hospital.notification.model.NotificationRecord;
import com.hospital.notification.model.PatientCalledEvent;
import com.hospital.notification.model.VisitCreatedEvent;
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
public class NotificationStore {

    private static final int CAP = 200;

    private final List<NotificationRecord> records = new CopyOnWriteArrayList<>();
    private final AtomicLong seq = new AtomicLong(1);

    public void record(VisitCreatedEvent event) {
        NotificationRecord r = new NotificationRecord(
                seq.getAndIncrement(),
                "VISIT_CREATED",
                event.visitId(),
                event.patientId(),
                event.doctorId(),
                event.createdAt(),
                LocalDateTime.now(),
                "IN_APP",
                "新就诊创建,就诊号 #" + event.visitId()
        );
        records.add(0, r);
        if (records.size() > CAP) {
            records.remove(records.size() - 1);
        }
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
                "您已被叫号,请到【" + event.station() + "】进行【" + event.itemName() + "】检查"
        );
        records.add(0, r);
        if (records.size() > CAP) {
            records.remove(records.size() - 1);
        }
    }

    public List<NotificationRecord> all() {
        return new ArrayList<>(records);
    }
}
