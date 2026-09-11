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
import com.hospital.core.booking.domain.AppointmentCancelledEvent;
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

        // R-25: 重复预约幂等校验(占号之前)。同一患者 + 同一号源若已存在 BOOKED 预约,
        // 直接拒绝,避免前端双击 / 网络重试导致重复落单并多扣号源。
        // 抛 IllegalStateException,由 GlobalExceptionHandler 映射为 409。
        // 说明:这是应用层校验,并发双写下仍有竞态窗口(两个请求可同时通过校验)。
        // 彻底解决需要数据库侧"部分唯一索引"(booking.appointment(patient_id, slot_id) WHERE status='BOOKED')
        // + 存量重复数据清洗;但 spring.sql.init.mode=always 每次启动都跑 schema.sql,
        // 若存量已有重复行,CREATE UNIQUE INDEX 会直接让应用启动失败,故本期不做,列为 P3。
        Long duplicate = appointmentMapper.selectCount(new QueryWrapper<Appointment>()
                .eq("patient_id", patientId)
                .eq("slot_id", slotId)
                .eq("status", "BOOKED"));
        if (duplicate != null && duplicate > 0) {
            throw new IllegalStateException("您已预约该时段,请勿重复提交");
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

    /**
     * C 端患者自助取消预约:置 CANCELLED + 释放号源 + 发布取消事件。
     *
     * <p><b>为什么只有 BOOKED 可取消</b>:
     * <ul>
     *   <li>{@code CHECKED_IN}(已到院)/ {@code DONE}(已完成)属于<b>线下流程</b>——患者人已经在院、
     *       检查可能已经开始甚至已出报告,这时让 C 端一键取消会把现场排队、收费、报告全部打成悬空数据,
     *       应走前台/医生端的线下作废流程,而不是自助取消;</li>
     *   <li>{@code CANCELLED}(已取消)必须<b>显式报错而不是静默成功</b>:静默成功会让用户以为
     *       "这次操作生效了",从而掩盖真实状态(例如其实是别人/定时任务取消的),也可能让人误以为
     *       号源被再次释放。重复取消返回 409 语义,是幂等保护的常规做法(拒绝而非吞掉)。</li>
     * </ul>
     *
     * <p><b>为什么必须联动 dispatch</b>:见 {@link AppointmentCancelledEvent} 的类注释——
     * 预约在 dispatch 侧已展开成 N 条 {@code ExamTask} 与 {@code queue_board} 投影,
     * 不清理的话患者会继续留在排队队列与看板上。本方法只负责发事件,
     * 由 dispatch 侧监听并清理,保持模块单向依赖(booking 不依赖 dispatch)。
     *
     * <p><b>号源释放</b>:复用 {@code cleanupExpiredAppointments()} 的批量释放路径
     * {@code SlotMapper.releaseBookedBatch}(一次 UPDATE 完成),而不是
     * {@code decrementBooked}——后者是 R-42 明确治理掉的写放大写法。
     * 取消单条预约时传单元素列表即可,语义与批量清理完全一致。
     *
     * @throws IllegalArgumentException 预约不存在(GlobalExceptionHandler 映射为 404)
     * @throws IllegalStateException    当前状态不允许取消(映射为 409)
     */
    @Transactional
    public void cancelAppointment(Long appointmentId) {
        Appointment appt = appointmentMapper.selectById(appointmentId);
        if (appt == null) {
            // 沿用既有约定:IllegalArgumentException → 全局异常处理器映射 404(见 GlobalExceptionHandler)
            throw new IllegalArgumentException("预约不存在: " + appointmentId);
        }
        // 状态校验:仅 BOOKED 可自助取消,其余一律 409(理由见方法注释)
        if (!"BOOKED".equals(appt.getStatus())) {
            throw new IllegalStateException(cancelRejectReason(appt.getStatus()));
        }

        // 付费状态同步:建单时没有支付网关,有价套餐被直接标记为 PAID(见 book())。
        // 若不处理,C 端「我的预约」会显示"已支付 + 已取消" —— 用户付了钱却没有可做的检查,
        // 是本次新增页面上肉眼可见的不一致。
        // 注意:**这里没有发生真实退款**。当前既无支付网关也无退款流水,REFUNDED 只是把状态
        // 改成与"预约已取消"自洽的终态。将来接入真实支付时必须改为
        // "调用退款网关成功后再置 REFUNDED",否则会出现"标记已退款但钱没退"。
        if ("PAID".equals(appt.getPayStatus())) {
            appt.setPayStatus("REFUNDED");
        }
        appt.setStatus("CANCELLED");
        appointmentMapper.updateById(appt);

        // 释放号源:单元素批量更新,与 cleanupExpiredAppointments 走同一条 SQL 路径(R-42)。
        // slotId 为 null(历史无号源预约)时跳过,避免拼出空参数列表导致 SQL 语法错误。
        if (appt.getSlotId() != null) {
            List<Map<String, Object>> decrements = new ArrayList<>(1);
            decrements.add(Map.of("slotId", appt.getSlotId(), "cnt", 1));
            slotMapper.releaseBookedBatch(decrements);
        }

        // 发布取消事件 → dispatch 清理已生成的检查任务与看板投影(提交后消费,失败不影响本事务)
        publisher.publishEvent(new AppointmentCancelledEvent(appt.getId(), appt.getPatientId()));
    }

    /** 不可取消状态的人话原因(直接透出给 C 端,避免只回一个干巴巴的状态码)。 */
    private static String cancelRejectReason(String status) {
        if ("CHECKED_IN".equals(status)) {
            return "您已到院签到,不可自助取消,请联系前台办理";
        }
        if ("DONE".equals(status)) {
            return "本次体检已完成,不可取消";
        }
        if ("CANCELLED".equals(status)) {
            return "该预约已取消,请勿重复操作";
        }
        return "当前状态(" + status + ")不支持取消";
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
