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
import com.hospital.core.clinical.domain.OrderCreatedEvent;
import com.hospital.core.clinical.domain.OrderUpdatedEvent;
import com.hospital.core.clinical.domain.Visit;
import com.hospital.core.clinical.domain.VisitCreatedEvent;
import com.hospital.core.clinical.domain.VisitReadModel;
import com.hospital.core.clinical.domain.VisitStatus;
import com.hospital.core.clinical.domain.VisitStatusEvent;
import com.hospital.core.clinical.infrastructure.ChargeMapper;
import com.hospital.core.clinical.infrastructure.OrderMapper;
import com.hospital.core.clinical.infrastructure.VisitMapper;
import com.hospital.core.clinical.infrastructure.VisitReadModelMapper;
import com.hospital.core.org.application.DepartmentService;
import com.hospital.core.org.application.StaffService;
import com.hospital.core.patient.application.PatientService;
import com.hospital.core.platform.security.CurrentUserResolver;

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
    private final VisitReadModelMapper readModelMapper;

    /** 简单建就诊(无医嘱);保留以向后端直接调用。 */
    @Transactional
    public Visit create(Visit visit) {
        visit.setStatus("CREATED");
        visit.setVisitTime(LocalDateTime.now());
        visit.setCreatedAt(LocalDateTime.now());
        visitMapper.insert(visit);
        readModelService.refresh(visit.getId());
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
        if (VisitStatus.of(visit.getStatus()) == VisitStatus.CONFIRMED) {
            return getDetail(visitId);
        }
        // 确单时若尚未指定医生,默认归属当前操作医生(确单者即接诊者)
        if (visit.getDoctorId() == null) {
            visit.setDoctorId(resolveCurrentDoctorId());
        }
        visit.transitTo(VisitStatus.CONFIRMED);
        visitMapper.updateById(visit);
        readModelService.refresh(visitId);
        return getDetail(visitId);
    }

    /** 就诊内追加一条医嘱;同一事务生成对应收费。FINISHED 状态拒绝追加;其余状态均可(含回诊追加)。 */
    @Transactional
    public VisitDetail addOrder(Long visitId, Order order) {
        Visit orderVisit = visitMapper.selectById(visitId);
        if (orderVisit != null && VisitStatus.of(orderVisit.getStatus()).isTerminal()) {
            throw new IllegalStateException("就诊已结束,不可追加医嘱");
        }
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

        // 发送医嘱创建通知
        Visit visit = visitMapper.selectById(visitId);
        if (visit != null) {
            var patient = patientService.get(visit.getPatientId());
            String patientName = patient != null ? patient.getName() : "患者";
            
            // 根据医嘱类型确定通知对象
            String targetRole;
            String message;
            if ("EXAM".equals(order.getType())) {
                // 检查医嘱 → 通知目标科室医生
                targetRole = "DOCTOR";
                message = "新检查医嘱: " + order.getItemName() + "，请安排检查";
            } else if ("LAB".equals(order.getType())) {
                // 检验医嘱 → 通知护士执行
                targetRole = "NURSE";
                message = "新检验医嘱: " + order.getItemName() + "，请采集标本";
            } else {
                // 药品医嘱 → 不需要特殊通知
                targetRole = "DOCTOR";
                message = "新药品医嘱: " + order.getItemName();
            }
            
            eventPublisher.publishEvent(new OrderCreatedEvent(
                    visitId, visit.getPatientId(), patientName,
                    order.getType(), order.getItemName(), targetRole,
                    order.getExecutionDeptId()));
        }

        readModelService.refresh(visitId);
        return getDetail(visitId);
    }

    /**
     * 修改一条未执行的医嘱(同步更新项 + 数量 + 单价 + 金额 + 对应收费记录)。
     * 只能修改 CREATED 状态的医嘱;已执行或已收费不可改。
     */
    @Transactional
    public VisitDetail editOrder(Long visitId, Long orderId, Order updates) {
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

        // 通知下游模块同步各自快照明细(处方/检验申请),避免修改后仍显示旧名称
        eventPublisher.publishEvent(new OrderUpdatedEvent(
                orderId, existing.getItemName(), existing.getQuantity(), existing.getUnitPrice()));

        readModelService.refresh(visitId);
        return getDetail(visitId);
    }

    /**
     * 取消一条未执行的医嘱(标记 CANCELLED;同步删除对应未收费记录,避免误收费)。
     * 已执行或已关联收费的订单不可取消。
     */
    @Transactional
    public VisitDetail cancelOrder(Long visitId, Long orderId) {
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

        readModelService.refresh(visitId);
        return getDetail(visitId);
    }

    /**
     * 退费:作废一条未执行(CREATED)的医嘱,并处理其对应收费。
     * 未收费(UNPAID)记录直接删除;已收费(PAID)记录置为 REFUNDED 并记录退费时间,保留审计痕迹。
     * 已执行(EXECUTED)的医嘱不可退费(需走线下冲红,不在本期)。
     */
    @Transactional
    public VisitDetail refundOrder(Long visitId, Long orderId) {
        Order existing = orderMapper.selectById(orderId);
        if (existing == null || !visitId.equals(existing.getVisitId())) {
            throw new IllegalArgumentException("医嘱不存在或不属于该就诊: " + orderId);
        }
        if (!"CREATED".equals(existing.getStatus())) {
            throw new IllegalStateException("只能退费未执行的医嘱(当前状态: " + existing.getStatus() + ")");
        }
        existing.setStatus("CANCELLED");
        orderMapper.updateById(existing);

        // 未收费 → 直接删除;已收费 → 置 REFUNDED + 记录退费时间(不物理删除,保审计)
        chargeMapper.selectList(null).stream()
                .filter(c -> orderId.equals(c.getOrderId()) && visitId.equals(c.getVisitId()))
                .forEach(c -> {
                    if ("UNPAID".equals(c.getPayStatus())) {
                        chargeMapper.deleteById(c.getId());
                    } else if ("PAID".equals(c.getPayStatus())) {
                        c.setPayStatus("REFUNDED");
                        c.setRefundTime(LocalDateTime.now());
                        chargeMapper.updateById(c);
                    }
                });

        readModelService.refresh(visitId);
        return getDetail(visitId);
    }

    /** 收费:同一事务内把所有 UNPAID 收费置为 PAID;已确单则推进为进行中(IN_PROGRESS)。 */
    @Transactional
    public VisitDetail pay(Long visitId) {
        // 前置校验:草稿(未确单)就诊单不可结算,需医生先确单
        Visit visit = visitMapper.selectById(visitId);
        if (visit == null) {
            throw new IllegalArgumentException("就诊不存在: " + visitId);
        }
        if (VisitStatus.of(visit.getStatus()) == VisitStatus.CREATED) {
            throw new IllegalStateException("就诊单尚未确单,不可结算,请先由医生确单");
        }
        List<Charge> unpaid = chargeMapper.selectList(null).stream()
                .filter(c -> visitId.equals(c.getVisitId()) && "UNPAID".equals(c.getPayStatus()))
                .toList();
        for (Charge c : unpaid) {
            c.setPayStatus("PAID");
            c.setPayTime(LocalDateTime.now());
            chargeMapper.updateById(c);
        }
        // 收费完成后,已确单就诊单自动推进为进行中(进入就诊执行阶段)
        if (VisitStatus.of(visit.getStatus()) == VisitStatus.CONFIRMED) {
            visit.transitTo(VisitStatus.IN_PROGRESS);
            visitMapper.updateById(visit);
        }
        readModelService.refresh(visitId);
        // 发布缴费完成通知:仅当有药品医嘱时通知药房
        boolean hasMedication = orderMapper.selectList(
                new LambdaQueryWrapper<Order>()
                        .eq(Order::getVisitId, visitId)
                        .eq(Order::getType, "MEDICATION"))
                .stream().anyMatch(o -> "CREATED".equals(o.getStatus()));
        if (hasMedication) {
            var patient = patientService.get(visit.getPatientId());
            String patientName = patient != null ? patient.getName() : "患者";
            eventPublisher.publishEvent(new VisitStatusEvent(
                    visitId, visit.getPatientId(), patientName,
                    "PAID", "就诊单已缴费成功，请发药"));
        }
        return getDetail(visitId);
    }

    /**
     * 手动结束就诊:CONFIRMED / IN_PROGRESS → FINISHED,由医生显式触发。
     * 前置校验:存在未缴(UNPAID)费用 → 拒绝。
     * 已缴费但尚未执行的医嘱属下游(药房/检验)职责,默认保留不作废,患者仍可继续取药/检验;
     * 仅 forceCancelOrders=true(如患者放弃)时才批量作废这些医嘱并退费。
     * 幂等(已 FINISHED 直接返回)。
     */
    @Transactional
    public VisitDetail finishVisit(Long visitId, boolean forceCancelOrders) {
        Visit visit = visitMapper.selectById(visitId);
        if (visit == null) {
            throw new IllegalArgumentException("就诊不存在: " + visitId);
        }
        VisitStatus curStatus = VisitStatus.of(visit.getStatus());
        if (curStatus.isTerminal()) {
            return getDetail(visitId);
        }
        if (curStatus != VisitStatus.CONFIRMED && curStatus != VisitStatus.IN_PROGRESS) {
            throw new IllegalStateException("仅已确单/进行中就诊单可结束(当前状态: " + visit.getStatus() + ")");
        }
        boolean hasUnpaid = chargeMapper.selectList(null).stream()
                .anyMatch(c -> visitId.equals(c.getVisitId()) && "UNPAID".equals(c.getPayStatus()));
        if (hasUnpaid) {
            throw new IllegalStateException("存在未缴费用,请先缴费或退费后再结束就诊");
        }

        // 已缴费但尚未执行的医嘱属下游(药房发药/检验执行)职责:
        // 默认结束就诊时保留,不作废、不拦截,患者仍可继续取药/检验;
        // 仅当 forceCancelOrders=true(如患者放弃)时才批量作废并退费。
        if (forceCancelOrders) {
            List<Order> pendingOrders = orderMapper.selectList(
                    new LambdaQueryWrapper<Order>()
                            .eq(Order::getVisitId, visitId)
                            .eq(Order::getStatus, "CREATED"));
            for (Order o : pendingOrders) {
                o.setStatus("CANCELLED");
                orderMapper.updateById(o);
            }
            // 作废医嘱对应的已收费记录 → 退费(保审计痕迹)
            List<Long> cancelledIds = pendingOrders.stream().map(Order::getId).toList();
            chargeMapper.selectList(null).stream()
                    .filter(c -> visitId.equals(c.getVisitId()) && cancelledIds.contains(c.getOrderId()))
                    .forEach(c -> {
                        if ("PAID".equals(c.getPayStatus())) {
                            c.setPayStatus("REFUNDED");
                            c.setRefundTime(LocalDateTime.now());
                            chargeMapper.updateById(c);
                        }
                    });
        }

        visit.transitTo(VisitStatus.FINISHED);
        visitMapper.updateById(visit);
        readModelService.refresh(visitId);
        var patient = patientService.get(visit.getPatientId());
        String patientName = patient != null ? patient.getName() : "患者";
        eventPublisher.publishEvent(new VisitStatusEvent(
                visitId, visit.getPatientId(), patientName,
                "FINISHED", "就诊已完成，报告已生成"));
        return getDetail(visitId);
    }

    /** 就诊详情投影:就诊 + 医嘱 + 收费 + 名称解析 + 收费状态。 */
    public VisitDetail getDetail(Long visitId) {
        Visit visit = visitMapper.selectById(visitId);
        if (visit == null) return null;
        List<Order> orders = orderMapper.selectList(
                new LambdaQueryWrapper<Order>().eq(Order::getVisitId, visitId));
        List<Charge> charges = chargeMapper.selectList(
                new LambdaQueryWrapper<Charge>().eq(Charge::getVisitId, visitId));
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
     * 分页查询就诊列表（走读模型，O(1) 复杂度）
     */
    public PageResult<VisitDetail> listPage(String keyword, int pageNum, int pageSize, Long currentDeptId) {
        // 构建查询条件
        LambdaQueryWrapper<VisitReadModel> wrapper = new LambdaQueryWrapper<>();

        // 科室过滤(跨科协作):归属科室(dept_id) 或 有待执行医嘱的执行科室(execution_dept_id) 均可看见。
        // 用子查询保持单条 SQL + O(1) 分页,total 与 items 一致。
        if (currentDeptId != null) {
            wrapper.and(w -> w
                    .eq(VisitReadModel::getDeptId, currentDeptId)
                    .or().inSql(VisitReadModel::getVisitId,
                            "SELECT visit_id FROM clinical.orders WHERE execution_dept_id = " + currentDeptId));
        }

        // 关键字搜索
        if (keyword != null && !keyword.isBlank()) {
            String kw = keyword.toLowerCase();
            wrapper.and(w -> w
                    .like(VisitReadModel::getPatientName, kw)
                    .or().like(VisitReadModel::getDoctorName, kw)
                    .or().like(VisitReadModel::getChiefComplaint, kw));
        }

        // 查询总数（ORDER BY 不能用于 COUNT 查询）
        Long total = readModelMapper.selectCount(wrapper);

        // 排序（在 COUNT 之后追加，避免污染 count SQL）
        wrapper.orderByDesc(VisitReadModel::getVisitTime);

        // 分页查询
        int offset = (pageNum - 1) * pageSize;
        wrapper.last("LIMIT " + pageSize + " OFFSET " + offset);
        List<VisitReadModel> readModels = readModelMapper.selectList(wrapper);

        // 转换为 VisitDetail（详情仍走写模型，保证实时性）
        List<VisitDetail> items = readModels.stream()
                .map(rm -> convertToDetail(rm))
                .toList();

        return PageResult.<VisitDetail>builder()
                .items(items)
                .total(total.intValue())
                .pageNum(pageNum)
                .pageSize(pageSize)
                .build();
    }

    /**
     * 从读模型转换为 VisitDetail
     */
    private VisitDetail convertToDetail(VisitReadModel rm) {
        Visit visit = visitMapper.selectById(rm.getVisitId());
        if (visit == null) return null;

        List<Order> orders = orderMapper.selectList(
                new LambdaQueryWrapper<Order>().eq(Order::getVisitId, rm.getVisitId()));
        List<Charge> charges = chargeMapper.selectList(
                new LambdaQueryWrapper<Charge>().eq(Charge::getVisitId, rm.getVisitId()));

        return VisitDetail.builder()
                .visit(visit)
                .orders(orders)
                .charges(charges)
                .totalAmount(rm.getTotalAmount())
                .patientName(rm.getPatientName())
                .doctorName(rm.getDoctorName())
                .deptName(rm.getDeptName())
                .payStatus(rm.getPayStatus())
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

    /** 按患者 ID 查询就诊列表(供 FHIR facade 使用)。 */
    public List<Visit> listByPatientId(Long patientId) {
        return visitMapper.selectList(
                new LambdaQueryWrapper<Visit>().eq(Visit::getPatientId, patientId));
    }

    /** 全量就诊列表(仅返回 Visit 实体,供 FHIR facade 使用)。 */
    public List<Visit> listAll() {
        return visitMapper.selectList(null);
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

    /** 执行检查类医嘱(EXAM):标记并记录 finding。 */
    @Transactional
    public VisitDetail executeExam(Long visitId, Long orderId, String finding) {
        Order order = orderMapper.selectById(orderId);
        if (order == null) throw new IllegalArgumentException("医嘱不存在: " + orderId);
        if (!"EXAM".equals(order.getType())) throw new IllegalArgumentException("非检查类医嘱不可执行");
        if (!"CREATED".equals(order.getStatus())) throw new IllegalStateException("医嘱已执行或已取消");
        order.setStatus("EXECUTED");
        if (finding != null && !finding.isBlank()) {
            order.setFinding(finding);
        }
        orderMapper.updateById(order);
        return getDetail(visitId);
    }

    /** 列出检查医嘱(可按状态过滤,按科室过滤)。 */
    public List<ExamTaskVO> listExams(String status, Long currentDeptId) {
        LambdaQueryWrapper<Order> wrapper = new LambdaQueryWrapper<Order>()
                .eq(Order::getType, "EXAM")
                .orderByDesc(Order::getId);
        if (status != null && !status.isBlank()) {
            wrapper.eq(Order::getStatus, status);
        }
        if (currentDeptId != null) wrapper.eq(Order::getExecutionDeptId, currentDeptId);
        List<Order> exams = orderMapper.selectList(wrapper);
        return exams.stream().map(o -> {
            Visit visit = visitMapper.selectById(o.getVisitId());
            String patientName = visit != null ? resolvePatientName(visit.getPatientId()) : null;
            String doctorName = visit != null ? resolveDoctorName(visit.getDoctorId()) : null;
            return new ExamTaskVO(o.getId(), o.getVisitId(), patientName, doctorName, o.getItemName(),
                    o.getStatus(), o.getFinding());
        }).toList();
    }

    /** 兼容旧调用:仅待执行。 */
    public List<ExamTaskVO> listPendingExams(Long currentDeptId) {
        return listExams("CREATED", currentDeptId);
    }

    /** 获取患者历史就诊记录(含医嘱),供新建就诊时医生参考。 */
    public List<PatientVisitHistoryVO> getPatientHistory(Long patientId) {
        List<Visit> visits = visitMapper.selectList(
                new LambdaQueryWrapper<Visit>()
                        .eq(Visit::getPatientId, patientId)
                        .orderByDesc(Visit::getCreatedAt));
        return visits.stream().map(v -> {
            String doctorName = resolveDoctorName(v.getDoctorId());
            String deptName = resolveDeptName(v.getDeptId());
            List<Order> orders = orderMapper.selectList(
                    new LambdaQueryWrapper<Order>().eq(Order::getVisitId, v.getId()));
            List<PatientVisitHistoryVO.OrderSummary> orderSummaries = orders.stream()
                    .map(o -> new PatientVisitHistoryVO.OrderSummary(
                            o.getId(), o.getType(), o.getItemName(), o.getStatus(), o.getFinding()))
                    .toList();
            return new PatientVisitHistoryVO(
                    v.getId(), v.getVisitTime() != null ? v.getVisitTime().toString() : null,
                    v.getStatus(), v.getChiefComplaint(), doctorName, deptName, orderSummaries);
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

    /** 解析当前登录医生 ID(确单时回填接诊医生用);无法解析返回 null。 */
    private Long resolveCurrentDoctorId() {
        String phone = CurrentUserResolver.resolveUsername(null);
        if (phone == null) return null;
        var staff = staffService.findByPhone(phone);
        return staff == null ? null : staff.getId();
    }
}