package com.hospital.core.booking.application;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.hospital.core.booking.domain.Appointment;
import com.hospital.core.booking.domain.AppointmentCreatedEvent;
import com.hospital.core.booking.domain.AppointmentStatusEvent;
import com.hospital.core.booking.domain.ExamItem;
import com.hospital.core.booking.domain.ExamItemBrief;
import com.hospital.core.booking.domain.ExamPackage;
import com.hospital.core.booking.domain.Slot;
import com.hospital.core.booking.infrastructure.AppointmentMapper;
import com.hospital.core.booking.infrastructure.ExamItemMapper;
import com.hospital.core.booking.infrastructure.ExamPackageMapper;
import com.hospital.core.booking.infrastructure.SlotMapper;
import com.hospital.core.patient.api.PatientApi;
import lombok.RequiredArgsConstructor;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionalEventListener;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.List;

@Service
@RequiredArgsConstructor
public class BookingService {

    private final ExamPackageMapper packageMapper;
    private final ExamItemMapper itemMapper;
    private final SlotMapper slotMapper;
    private final AppointmentMapper appointmentMapper;
    private final PatientApi patientApi;
    private final ApplicationEventPublisher publisher;

    @Transactional
    @CacheEvict(value = "examPackages", allEntries = true)
    public ExamPackage createPackage(String name, BigDecimal price, String description) {
        ExamPackage p = new ExamPackage();
        p.setName(name);
        p.setPrice(price);
        p.setDescription(description);
        p.setCreatedAt(LocalDateTime.now());
        packageMapper.insert(p);
        return p;
    }

    public ExamPackage getPackage(Long id) {
        return packageMapper.selectById(id);
    }

    @Cacheable("examPackages")
    public List<ExamPackage> listPackages() {
        return packageMapper.selectList(null);
    }

    /** 仅返回套餐 ID 列表,供定时任务使用(避免 job 层直接依赖 domain 实体)。 */
    public List<Long> listPackageIds() {
        return packageMapper.selectList(null).stream()
                .map(ExamPackage::getId)
                .toList();
    }

    /** 查询某套餐已有号源数(仅计数,不走 domain 实体)。 */
    public int countSlots(Long packageId) {
        Long count = slotMapper.selectCount(new LambdaQueryWrapper<Slot>()
                .eq(Slot::getPackageId, packageId));
        return count.intValue();
    }

    public List<ExamItem> listItems(Long packageId) {
        return itemMapper.selectList(new QueryWrapper<ExamItem>().eq("package_id", packageId));
    }

    @Transactional
    public List<Slot> listSlots(Long packageId) {
        List<Slot> slots = slotMapper.selectList(new QueryWrapper<Slot>().eq("package_id", packageId));
        if (slots.isEmpty()) {
            generateSlots(packageId);
            slots = slotMapper.selectList(new QueryWrapper<Slot>().eq("package_id", packageId));
        }
        return slots;
    }

    /**
     * 为指定套餐批量生成未来 N 天的号源(幂等:已有号源跳过)。
     * 给定时任务调度用,也接替原私有 generateSlots。
     */
    @Transactional
    public void ensureSlotsExist(Long packageId, int days, int capacity) {
        LocalDate today = LocalDate.now();
        for (int i = 0; i < days; i++) {
            LocalDate date = today.plusDays(i);
            for (String period : List.of("AM", "PM")) {
                boolean exists = slotMapper.selectCount(
                        new LambdaQueryWrapper<Slot>()
                                .eq(Slot::getPackageId, packageId)
                                .eq(Slot::getExamDate, date)
                                .eq(Slot::getPeriod, period)) > 0;
                if (exists) continue;
                Slot s = new Slot();
                s.setPackageId(packageId);
                s.setExamDate(date);
                s.setPeriod(period);
                s.setCapacity(capacity);
                s.setBooked(0);
                slotMapper.insert(s);
            }
        }
    }

    /**
     * 清理过期预约:检查日期已过的 BOOKED 预约 → 标记 CANCELLED 并释放号源。
     * 给定时任务调度用。
     */
    @Transactional
    public int cleanupExpiredAppointments() {
        List<Appointment> expired = appointmentMapper.selectExpiredBooked(LocalDate.now());
        for (Appointment a : expired) {
            a.setStatus("CANCELLED");
            appointmentMapper.updateById(a);
            if (a.getSlotId() != null) {
                slotMapper.decrementBooked(a.getSlotId());
            }
        }
        return expired.size();
    }

    /** 为指定套餐生成未来 7 天的 AM/PM 号源,capacity=2 为每时段默认额度。 */
    private void generateSlots(Long packageId) {
        LocalDate today = LocalDate.now();
        for (int i = 0; i < 7; i++) {
            LocalDate date = today.plusDays(i);
            for (String period : List.of("AM", "PM")) {
                Slot s = new Slot();
                s.setPackageId(packageId);
                s.setExamDate(date);
                s.setPeriod(period);
                s.setCapacity(2);
                s.setBooked(0);
                slotMapper.insert(s);
            }
        }
    }

    /**
     * C 端预约核心:同一事务内原子占号 + 落预约单。
     * 先以 SlotMapper.incrementBooked 原子占号(数据库保证 booked<capacity 才成功),
     * 失败(返回 0)即号源已满,抛异常触发事务回滚,绝不会写出超额预约。
     * 成功后发布 AppointmentCreatedEvent(提交后由 Dispatch 模块消费,生成各 station 任务)。
     */
    @Transactional
    public Appointment book(Long patientId, Long packageId, Long slotId) {
        int updated = slotMapper.incrementBooked(slotId);
        if (updated == 0) {
            throw new IllegalStateException("号源已满");
        }
        Appointment appt = new Appointment();
        appt.setPatientId(patientId);
        appt.setPackageId(packageId);
        appt.setSlotId(slotId);
        appt.setStatus("BOOKED");
        appt.setCreatedAt(LocalDateTime.now());
        appointmentMapper.insert(appt);

        // C端预约演示:自动标记已付费,金额取套餐定价
        ExamPackage pkg = packageMapper.selectById(packageId);
        if (pkg != null && pkg.getPrice() != null) {
            appt.setPayStatus("PAID");
            appt.setPayAmount(pkg.getPrice());
            appointmentMapper.updateById(appt);
        }

        // 组装自包含事件快照(患者名 + 项目简报),下游 Dispatch 零回查。
        String patientName = patientApi.getName(patientId);
        List<ExamItemBrief> briefs = itemMapper.selectList(new QueryWrapper<ExamItem>().eq("package_id", packageId))
                .stream()
                .sorted(Comparator.comparing(i -> i.getOrderNo() == null ? 0 : i.getOrderNo()))
                .map(i -> new ExamItemBrief(i.getStation(), i.getName(), i.getOrderNo() == null ? 0 : i.getOrderNo()))
                .toList();
        publisher.publishEvent(new AppointmentCreatedEvent(
                appt.getId(), patientId, patientName, packageId, briefs));

        return appt;
    }

    public List<Appointment> listByPatient(Long patientId) {
        return appointmentMapper.selectList(new QueryWrapper<Appointment>().eq("patient_id", patientId));
    }

    public Appointment getAppointment(Long id) {
        return appointmentMapper.selectById(id);
    }

    /**
     * 监听 Dispatch 回写的体检进度事件,更新预约单状态机。
     * CHECKED_IN:仅当当前为 BOOKED 时推进(幂等,避免回退)。
     * DONE:全部任务完成,直接置终态。
     * 与 AppointmentCreatedEvent 同源设计,保持模块单向依赖,不破坏 ArchUnit。
     */
    @TransactionalEventListener
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void onAppointmentStatus(AppointmentStatusEvent event) {
        Appointment a = appointmentMapper.selectById(event.appointmentId());
        if (a == null) return;
        if ("DONE".equals(event.status())) {
            a.setStatus("DONE");
            appointmentMapper.updateById(a);
        } else if ("CHECKED_IN".equals(event.status()) && "BOOKED".equals(a.getStatus())) {
            a.setStatus("CHECKED_IN");
            appointmentMapper.updateById(a);
        }
    }
}
