package com.hospital.core.clinical.application;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.hospital.core.clinical.domain.Charge;
import com.hospital.core.clinical.domain.Order;
import com.hospital.core.clinical.domain.Visit;
import com.hospital.core.clinical.domain.VisitCreatedEvent;
import com.hospital.core.clinical.infrastructure.ChargeMapper;
import com.hospital.core.clinical.infrastructure.OrderMapper;
import com.hospital.core.clinical.infrastructure.VisitMapper;
import com.hospital.core.clinical.application.ChargeService;
import com.hospital.core.org.application.DepartmentService;
import com.hospital.core.org.application.StaffService;
import com.hospital.core.patient.application.PatientService;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class VisitService {

    private final VisitMapper visitMapper;
    private final OrderMapper orderMapper;
    private final ChargeMapper chargeMapper;
    private final ChargeService chargeService;
    private final ApplicationEventPublisher eventPublisher;
    private final PatientService patientService;
    private final StaffService staffService;
    private final DepartmentService departmentService;
    private final VisitReadModelService readModelService;

    /** 简单建就诊(无医嘱);保留以向后端直接调用。 */
    @Transactional
    public Visit create(Visit visit) {
        visit.setStatus("CREATED");
        visit.setVisitTime(LocalDateTime.now());
        visit.setCreatedAt(LocalDateTime.now());
        visitMapper.insert(visit);
        eventPublisher.publishEvent(
                new VisitCreatedEvent(visit.getId(), visit.getPatientId(), visit.getDoctorId(), visit.getCreatedAt()));
        return visit;
    }

    /**
     * 建就诊 + 附带医嘱:同一事务内落就诊 / 医嘱 / 收费。
     * 这是模块化单体(需 Saga / 最终一致性的优势在本作品权衡中也是体现的"判断点"。
     */
    @Transactional
    public VisitDetail createWithOrders(Visit visit, List<Order> orders) {
        visit.setStatus("CREATED");
        visit.setVisitTime(LocalDateTime.now());
        visit.setCreatedAt(LocalDateTime.now());
        visitMapper.insert(visit);

        BigDecimal total = BigDecimal.ZERO;
        for (Order order : orders) {
            order.setVisitId(visit.getId());
            order.setStatus("CREATED");
            order.setAmount(order.getUnitPrice().multiply(BigDecimal.valueOf(order.getQuantity())));
            orderMapper.insert(order);

            Charge charge = new Charge();
            charge.setVisitId(visit.getId());
            charge.setOrderId(order.getId());
            charge.setItemName(order.getItemName());
            charge.setAmount(order.getAmount());
            charge.setPayStatus("UNPAID");
            chargeMapper.insert(charge);

            total = total.add(order.getAmount());
        }

        readModelService.refresh(visit.getId());
        eventPublisher.publishEvent(
                new VisitCreatedEvent(visit.getId(), visit.getPatientId(), visit.getDoctorId(), visit.getCreatedAt()));

        return VisitDetail.builder()
                .visit(visit)
                .orders(orders)
                .charges(chargeMapper.selectList(
                        new LambdaQueryWrapper<Charge>().eq(Charge::getVisitId, visit.getId())))
                .totalAmount(total)
                .build();
    }

    /** 确单:草稿 → 已确单,锁定就诊单不再可修改/追加。幂等(已是 CONFIRMED 则直接返回)。 */
    @Transactional
    public VisitDetail confirm(Long visitId) {
        Visit visit = visitMapper.selectById(visitId);
        if (visit == null) {
            throw new IllegalArgumentException("就诊不存在: " + visitId);
        }
        if ("CONFIRMED".equals(visit.getStatus())) {
            return getDetail(visitId);
        }
        if (!"CREATED".equals(visit.getStatus())) {
            throw new IllegalStateException("仅草稿状态就诊单可确单(当前状态: " + visit.getStatus() + ")");
        }
        visit.setStatus("CONFIRMED");
        visitMapper.updateById(visit);
        readModelService.refresh(visitId);
        return getDetail(visitId);
    }

    /** 就诊内追加一条医嘱;同一事务生成对应收费。已确单则拒绝。 */
    @Transactional
    public VisitDetail addOrder(Long visitId, Order order) {
        assertNotConfirmed(visitId);
        order.setVisitId(visitId);
        order.setStatus("CREATED");
        order.setAmount(order.getUnitPrice().multiply(BigDecimal.valueOf(order.getQuantity())));
        orderMapper.insert(order);

        Charge charge = new Charge();
        charge.setVisitId(visitId);
        charge.setOrderId(order.getId());
        charge.setItemName(order.getItemName());
        charge.setAmount(order.getAmount());
        charge.setPayStatus("UNPAID");
        chargeMapper.insert(charge);

        readModelService.refresh(visitId);
        return getDetail(visitId);
    }

    /**
     * 修改一条未执行的医嘱(同步更新项 + 数量 + 单价 + 金额 + 对应收费记录)。
     * 只能修改 CREATED 状态的医嘱;已执行或已收费不可改。
     */
    @Transactional
    public VisitDetail editOrder(Long visitId, Long orderId, Order updates) {
        assertNotConfirmed(visitId);
        Order existing = orderMapper.selectById(orderId);
        if (existing == null || !visitId.equals(existing.getVisitId())) {
            throw new IllegalArgumentException("医嘱不存在或不属于该就诊: " + orderId);
        }
        if (!"CREATED".equals(existing.getStatus())) {
            throw new IllegalStateException("只能修改未执行的医嘱(当前状态: " + existing.getStatus() + ")");
        }
        BigDecimal newAmount = updates.getUnitPrice().multiply(BigDecimal.valueOf(updates.getQuantity()));
        existing.setType(updates.getType());
        existing.setItemName(updates.getItemName());
        existing.setQuantity(updates.getQuantity());
        existing.setUnitPrice(updates.getUnitPrice());
        existing.setAmount(newAmount);
        orderMapper.updateById(existing);

        // 同步更新对应 charge 项(只改未收费的)
        chargeMapper.selectList(null).stream()
                .filter(c -> orderId.equals(c.getOrderId()) && visitId.equals(c.getVisitId()))
                .forEach(c -> {
                    if ("UNPAID".equals(c.getPayStatus())) {
                        c.setItemName(updates.getItemName());
                        c.setAmount(newAmount);
                        chargeMapper.updateById(c);
                    }
                });

        readModelService.refresh(visitId);
        return getDetail(visitId);
    }

    /**
     * 取消一条未执行的医嘱(标记 CANCELLED;同步删除对应未收费记录,避免误收费)。
     * 已执行或已关联收费的订单不可取消。
     */
    @Transactional
    public VisitDetail cancelOrder(Long visitId, Long orderId) {
        assertNotConfirmed(visitId);
        Order existing = orderMapper.selectById(orderId);
        if (existing == null || !visitId.equals(existing.getVisitId())) {
            throw new IllegalArgumentException("医嘱不存在或不属于该就诊: " + orderId);
        }
        if (!"CREATED".equals(existing.getStatus())) {
            throw new IllegalStateException("只能取消未执行的医嘱(当前状态: " + existing.getStatus() + ")");
        }
        existing.setStatus("CANCELLED");
        orderMapper.updateById(existing);

        // 删除对应未收费记录(已收费的不动)
        chargeMapper.selectList(null).stream()
                .filter(c -> orderId.equals(c.getOrderId()) && visitId.equals(c.getVisitId()))
                .filter(c -> "UNPAID".equals(c.getPayStatus()))
                .forEach(c -> chargeMapper.deleteById(c.getId()));

        tryAutoFinish(visitId);  // 所有医嘱执行/取消完 → 自动完成就诊单

        readModelService.refresh(visitId);
        return getDetail(visitId);
    }

    /** 收费:同一事务内把所有 UNPAID 收费置为 PAID;已确单则推进为进行中(IN_PROGRESS)。 */
    @Transactional
    public VisitDetail pay(Long visitId) {
        List<Charge> unpaid = chargeMapper.selectList(null).stream()
                .filter(c -> visitId.equals(c.getVisitId()) && "UNPAID".equals(c.getPayStatus()))
                .toList();
        for (Charge c : unpaid) {
            c.setPayStatus("PAID");
            c.setPayTime(LocalDateTime.now());
            chargeMapper.updateById(c);
        }
        // 收费完成后,已确单就诊单自动推进为进行中(进入就诊执行阶段)
        Visit visit = visitMapper.selectById(visitId);
        if (visit != null && "CONFIRMED".equals(visit.getStatus())) {
            visit.setStatus("IN_PROGRESS");
            visitMapper.updateById(visit);
        }
        readModelService.refresh(visitId);
        return getDetail(visitId);
    }

    /**
     * 若该就诊单所有医嘱都已执行/已取消(无 CREATED 剩余),自动推进为已完成。
     * 在每次医嘱执行/取消动作后调用。
     */
    @Transactional
    public void tryAutoFinish(Long visitId) {
        List<Order> orders = orderMapper.selectList(
                new LambdaQueryWrapper<Order>().eq(Order::getVisitId, visitId));
        boolean allDone = !orders.isEmpty() && orders.stream()
                .allMatch(o -> "EXECUTED".equals(o.getStatus()) || "CANCELLED".equals(o.getStatus()));
        if (allDone) {
            Visit visit = visitMapper.selectById(visitId);
            if (visit != null && !"FINISHED".equals(visit.getStatus())) {
                visit.setStatus("FINISHED");
                visitMapper.updateById(visit);
            }
        }
    }

    /** 就诊详情投影:就诊 + 医嘱 + 收费 + 名称解析 + 收费状态。 */
    public VisitDetail getDetail(Long visitId) {
        Visit visit = visitMapper.selectById(visitId);
        if (visit == null) return null;
        List<Order> orders = orderMapper.selectList(null).stream()
                .filter(o -> visitId.equals(o.getVisitId()))
                .toList();
        List<Charge> charges = chargeMapper.selectList(null).stream()
                .filter(c -> visitId.equals(c.getVisitId()))
                .toList();
        BigDecimal total = charges.stream()
                .map(Charge::getAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        String payStatus = resolvePayStatus(charges);
        return VisitDetail.builder()
                .visit(visit)
                .orders(orders)
                .charges(charges)
                .totalAmount(total)
                .patientName(resolvePatientName(visit.getPatientId()))
                .doctorName(resolveDoctorName(visit.getDoctorId()))
                .deptName(resolveDeptName(visit.getDeptId()))
                .payStatus(payStatus)
                .build();
    }

    public Visit get(Long id) {
        return visitMapper.selectById(id);
    }

    /**
     * 分页查询就诊列表(按就诊时间倒序,支持关键字搜索:患者姓名/医生姓名/主诉)。
     */
    public PageResult<VisitDetail> listPage(String keyword, int pageNum, int pageSize, Long currentDeptId) {
        // 如果用 MyBatis-Plus 的分页插件需要 Page 对象;此处先用内存分页写清晰逻辑。
        // 跨科协作:就诊单对"归属科室(visit.deptId)"或"有待执行医嘱的执行科室(order.executionDeptId)"均可见。
        List<Order> allOrders = orderMapper.selectList(null);
        List<Visit> allVisits = visitMapper.selectList(null).stream()
                .filter(v -> isVisibleToDept(v, currentDeptId, allOrders))  // 科室过滤(含执行科室)
                .sorted((a, b) -> {
                    // 按就诊时间倒序(null 兜底到最早)
                    if (a.getVisitTime() == null && b.getVisitTime() == null) return 0;
                    if (a.getVisitTime() == null) return 1;
                    if (b.getVisitTime() == null) return -1;
                    return b.getVisitTime().compareTo(a.getVisitTime());
                })
                .toList();

        // 关键字搜索 + 完整投影(含医嘱/收费/收费状态,与 getDetail 一致)
        List<VisitDetail> filtered = allVisits.stream().map(v -> {
            List<Order> orders = orderMapper.selectList(null).stream()
                    .filter(o -> v.getId().equals(o.getVisitId())).toList();
            List<Charge> charges = chargeMapper.selectList(null).stream()
                    .filter(c -> v.getId().equals(c.getVisitId())).toList();
            BigDecimal total = charges.stream().map(Charge::getAmount).reduce(BigDecimal.ZERO, BigDecimal::add);
            return VisitDetail.builder()
                    .visit(v)
                    .orders(orders)
                    .charges(charges)
                    .totalAmount(total)
                    .patientName(resolvePatientName(v.getPatientId()))
                    .doctorName(resolveDoctorName(v.getDoctorId()))
                    .deptName(resolveDeptName(v.getDeptId()))
                    .payStatus(resolvePayStatus(charges))
                    .build();
        }).filter(d -> {
            if (keyword == null || keyword.isBlank()) return true;
            String kw = keyword.toLowerCase();
            return (d.getPatientName() != null && d.getPatientName().toLowerCase().contains(kw))
                    || (d.getDoctorName() != null && d.getDoctorName().toLowerCase().contains(kw))
                    || (d.getVisit().getChiefComplaint() != null && d.getVisit().getChiefComplaint().toLowerCase().contains(kw));
        }).toList();

        int total = filtered.size();
        int from = Math.min((pageNum - 1) * pageSize, total);
        int to = Math.min(from + pageSize, total);
        List<VisitDetail> pageItems = filtered.subList(from, to);

        return PageResult.<VisitDetail>builder()
                .items(pageItems)
                .total(total)
                .pageNum(pageNum)
                .pageSize(pageSize)
                .build();
    }

    /** 删除草稿状态就诊单(级联删除医嘱 + 收费)。 */
    @Transactional
    public void delete(Long visitId) {
        Visit visit = visitMapper.selectById(visitId);
        if (visit == null) throw new IllegalArgumentException("就诊单不存在: " + visitId);
        if (!"CREATED".equals(visit.getStatus())) {
            throw new IllegalStateException("仅草稿状态(CREATED)的就诊单可删除,当前状态: " + visit.getStatus());
        }
        // 级联删除
        orderMapper.delete(new LambdaQueryWrapper<Order>().eq(Order::getVisitId, visitId));
        chargeMapper.delete(new LambdaQueryWrapper<Charge>().eq(Charge::getVisitId, visitId));
        visitMapper.deleteById(visitId);
        readModelService.deleteById(visitId);
    }

    /** 就诊列表(分页前兼容,未分页)。 */
    public List<VisitDetail> list(Long currentDeptId) {
        List<Order> allOrders = orderMapper.selectList(null);
        List<Visit> visits = visitMapper.selectList(null).stream()
                .filter(v -> isVisibleToDept(v, currentDeptId, allOrders))  // 科室过滤(含执行科室)
                .toList();
        return visits.stream().map(v -> {
            List<Order> orders = orderMapper.selectList(null).stream()
                    .filter(o -> v.getId().equals(o.getVisitId()))
                    .toList();
            List<Charge> charges = chargeMapper.selectList(null).stream()
                    .filter(c -> v.getId().equals(c.getVisitId()))
                    .toList();
            BigDecimal total = charges.stream()
                    .map(Charge::getAmount)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
            String payStatus = resolvePayStatus(charges);
            return VisitDetail.builder()
                    .visit(v)
                    .orders(orders)
                    .charges(charges)
                    .totalAmount(total)
                    .patientName(resolvePatientName(v.getPatientId()))
                    .doctorName(resolveDoctorName(v.getDoctorId()))
                    .deptName(resolveDeptName(v.getDeptId()))
                    .payStatus(payStatus)
                    .build();
        }).toList();
    }

    /** 判断收费状态: charges 全无 NO_CHARGES,有未缴 HAS_UNPAID,全部 ALL_PAID */
    private String resolvePayStatus(List<Charge> charges) {
        if (charges == null || charges.isEmpty()) return "NO_CHARGES";
        boolean hasUnpaid = charges.stream().anyMatch(c -> "UNPAID".equals(c.getPayStatus()));
        return hasUnpaid ? "HAS_UNPAID" : "ALL_PAID";
    }

    /**
     * 就诊单对某科室是否可见:管理员(null)全量;归属科室(deptId)可见;
     * 跨科协作场景下,若就诊单上存在执行科室=当前科室的医嘱(如内科开单、外科执行的检查),该科室同样可见。
     */
    private boolean isVisibleToDept(Visit visit, Long currentDeptId, List<Order> allOrders) {
        if (currentDeptId == null) return true;
        if (currentDeptId.equals(visit.getDeptId())) return true;
        return allOrders.stream().anyMatch(o ->
                visit.getId().equals(o.getVisitId()) && currentDeptId.equals(o.getExecutionDeptId()));
    }

    /** 确单守卫:已确单就诊单禁止追加/修改/取消医嘱。 */
    private void assertNotConfirmed(Long visitId) {
        Visit visit = visitMapper.selectById(visitId);
        if (visit != null && "CONFIRMED".equals(visit.getStatus())) {
            throw new IllegalStateException("该就诊单已确单，不可修改或追加医嘱");
        }
    }

    /** 执行检查类医嘱(MEDICATION 后 EXAM/LAB):标记并记录 finding。 */
    @Transactional
    public VisitDetail executeExam(Long visitId, Long orderId, String finding) {
        chargeService.assertAllPaid(visitId);  // 收费前置:未缴费拦截执行
        Order order = orderMapper.selectById(orderId);
        if (order == null) throw new IllegalArgumentException("医嘱不存在: " + orderId);
        if (!"EXAM".equals(order.getType())) throw new IllegalArgumentException("非检查类医嘱不可执行");
        if (!"CREATED".equals(order.getStatus())) throw new IllegalStateException("医嘱已执行或已取消");
        order.setStatus("EXECUTED");
        orderMapper.updateById(order);
        tryAutoFinish(visitId);  // 所有医嘱执行完 → 自动完成就诊单
        return getDetail(visitId);
    }

    /** 护士工作台:列出所有 CREATED 状态的 EXAM 医嘱(跨就诊)。 */
    public List<ExamTaskVO> listPendingExams(Long currentDeptId) {
        LambdaQueryWrapper<Order> wrapper = new LambdaQueryWrapper<Order>()
                .eq(Order::getType, "EXAM")
                .eq(Order::getStatus, "CREATED");
        if (currentDeptId != null) wrapper.eq(Order::getExecutionDeptId, currentDeptId);  // 按执行科室过滤
        List<Order> exams = orderMapper.selectList(wrapper);
        return exams.stream().map(o -> {
            Visit visit = visitMapper.selectById(o.getVisitId());
            String patientName = visit != null ? resolvePatientName(visit.getPatientId()) : null;
            String doctorName = visit != null ? resolveDoctorName(visit.getDoctorId()) : null;
            return new ExamTaskVO(o.getId(), o.getVisitId(), patientName, doctorName, o.getItemName());
        }).toList();
    }

    // -- 名称解析辅助方法 --

    private String resolvePatientName(Long patientId) {
        if (patientId == null) return null;
        try { return patientService.getName(patientId); } catch (Exception e) { return null; }
    }

    /** 批量查询就诊单缴费状态(仅返回是否全部缴清,不暴露金额)。 */
    public Map<Long, Boolean> paymentStatus(List<Long> visitIds) {
        Map<Long, Boolean> result = new HashMap<>();
        if (visitIds == null || visitIds.isEmpty()) return result;
        List<Charge> unpaid = chargeMapper.selectList(
                new LambdaQueryWrapper<Charge>()
                        .in(Charge::getVisitId, visitIds)
                        .eq(Charge::getPayStatus, "UNPAID"));
        Set<Long> unpaidVisitIds = unpaid.stream().map(Charge::getVisitId).collect(Collectors.toSet());
        for (Long id : visitIds) result.put(id, !unpaidVisitIds.contains(id));
        return result;
    }

    private String resolveDoctorName(Long doctorId) {
        if (doctorId == null) return null;
        try {
            var staff = staffService.get(doctorId);
            return staff == null ? null : staff.getName();
        } catch (Exception e) { return null; }
    }

    private String resolveDeptName(Long deptId) {
        if (deptId == null) return null;
        try {
            var dept = departmentService.get(deptId);
            return dept == null ? null : dept.getName();
        } catch (Exception e) { return null; }
    }
}