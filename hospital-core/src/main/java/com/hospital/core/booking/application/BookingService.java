package com.hospital.core.booking.application;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionalEventListener;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
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
        List<Slot> slots = selectUpcomingSlots(packageId);
        if (slots.isEmpty()) {
            generateSlots(packageId);
            slots = selectUpcomingSlots(packageId);
        }
        return slots;
    }

    /** 只查今天及以后的号源:过期号源对 C 端不可见(旧行保留在库,供历史预约回查)。 */
    private List<Slot> selectUpcomingSlots(Long packageId) {
        return slotMapper.selectList(new LambdaQueryWrapper<Slot>()
                .eq(Slot::getPackageId, packageId)
                .ge(Slot::getExamDate, LocalDate.now())
                .orderByAsc(Slot::getExamDate)
                .orderByAsc(Slot::getPeriod));
    }

    /** 按 ID 查号源(不过滤日期,历史预约详情回查用)。 */
    public Slot getSlot(Long id) {
        return slotMapper.selectById(id);
    }

    /**
     * 为指定套餐批量生成未来 N 天的号源(幂等:已有号源跳过)。
     * 给定时任务调度用,也接替原私有 generateSlots。
     */
    @Transactional
    public void ensureSlotsExist(Long packageId, int days, int capacity) {
        LocalDate today = LocalDate.now();
        LocalDate end = today.plusDays(Math.max(days - 1, 0));
        // R-42: 一次范围查询取回已存在的 (exam_date, period) 集合做内存去重,
        // 替代原实现 days × 2 次逐格 selectCount 的 N+1。
        List<Slot> existing = slotMapper.selectList(new LambdaQueryWrapper<Slot>()
                .eq(Slot::getPackageId, packageId)
                .ge(Slot::getExamDate, today)
                .le(Slot::getExamDate, end));
        Set<String> existingKeys = new HashSet<>();
        for (Slot s : existing) {
            existingKeys.add(s.getExamDate() + "|" + s.getPeriod());
        }
        List<Slot> toInsert = new ArrayList<>();
        for (int i = 0; i < days; i++) {
            LocalDate date = today.plusDays(i);
            for (String period : List.of("AM", "PM")) {
                if (existingKeys.contains(date + "|" + period)) continue;
                Slot s = new Slot();
                s.setPackageId(packageId);
                s.setExamDate(date);
                s.setPeriod(period);
                s.setCapacity(capacity);
                s.setBooked(0);
                toInsert.add(s);
            }
        }
        // R-42: 单条批量 INSERT 落库,替代逐条 insert 的写放大。
        if (!toInsert.isEmpty()) {
            slotMapper.batchInsert(toInsert);
        }
    }

    /**
     * 清理过期预约:检查日期已过的 BOOKED 预约 → 标记 CANCELLED 并释放号源。
     * 给定时任务调度用。
     */
    @Transactional
    public int cleanupExpiredAppointments() {
        List<Appointment> expired = appointmentMapper.selectExpiredBooked(LocalDate.now());
        if (expired.isEmpty()) {
            return 0;
        }
        // R-42: 批量 UPDATE 替代逐条 updateById + decrementBooked 的写放大。
        // 1) 一次 UPDATE 把这批过期预约置 CANCELLED(替代 N 次 updateById)。
        List<Long> ids = expired.stream().map(Appointment::getId).toList();
        appointmentMapper.update(null, new UpdateWrapper<Appointment>()
                .set("status", "CANCELLED")
                .in("id", ids));
        // 2) 按 slot 聚合取消数量,一次批量释放号源(替代 N 次 decrementBooked)。
        Map<Long, Integer> releaseBySlot = new HashMap<>();
        for (Appointment a : expired) {
            if (a.getSlotId() != null) {
                releaseBySlot.merge(a.getSlotId(), 1, Integer::sum);
            }
        }
        if (!releaseBySlot.isEmpty()) {
            List<Map<String, Object>> decrements = new ArrayList<>(releaseBySlot.size());
            for (Map.Entry<Long, Integer> e : releaseBySlot.entrySet()) {
                decrements.add(Map.of("slotId", e.getKey(), "cnt", e.getValue()));
            }
            slotMapper.releaseBookedBatch(decrements);
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
     * 先预检号源存在且未过期(过期号源即使有余量也不可约),
     * 再以 SlotMapper.incrementBooked 原子占号(数据库保证 booked<capacity 才成功),
     * 失败(返回 0)即号源已满,抛异常触发事务回滚,绝不会写出超额预约。
     * 成功后发布 AppointmentCreatedEvent(提交后由 Dispatch 模块消费,生成各 station 任务)。
     */
    @Transactional
    public Appointment book(Long patientId, Long packageId, Long slotId) {
        Slot slot = slotMapper.selectById(slotId);
        if (slot == null) {
            throw new IllegalArgumentException("号源不存在");
        }
        if (slot.getExamDate().isBefore(LocalDate.now())) {
            throw new IllegalStateException("号源已过期,请选择今天及以后的时段");
        }
        int updated = slotMapper.incrementBooked(slotId);
        if (updated == 0) {
            throw new IllegalStateException("号源已满");
        }
        // R-42: 取价提前到 insert 之前。原实现先 insert 拿到自增 id,再 updateById 回填付费字段,
        // 同一行被写两次;现将套餐定价一次性装配好后单次 insert,预约只落一次盘。
        // C端预约演示:自动标记已付费,金额取套餐定价
        ExamPackage pkg = packageMapper.selectById(packageId);
        Appointment appt = new Appointment();
        appt.setPatientId(patientId);
        appt.setPackageId(packageId);
        appt.setSlotId(slotId);
        appt.setStatus("BOOKED");
        appt.setCreatedAt(LocalDateTime.now());
        if (pkg != null && pkg.getPrice() != null) {
            appt.setPayStatus("PAID");
            appt.setPayAmount(pkg.getPrice());
        }
        appointmentMapper.insert(appt);

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
