package com.hospital.core.clinical.application;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
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
import lombok.extern.slf4j.Slf4j;

@Service
@RequiredArgsConstructor
@Slf4j
public class VisitService {
    
    private final VisitMapper visitMapper;
    private final OrderMapper orderMapper;
    private final ChargeMapper chargeMapper;
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

        // R-05: 医嘱/收费仍是逐条 insert(N 条医嘱 = N 次 insert + N 次 charge insert)。
        // TODO(R-05 待办): 后续可改为 MyBatis-Plus 批量插入或 XML foreach 批量落库,
        // 本期为控制改造风险(保持 public 签名与事件时序不变)暂保持逐条,但金额统一走 calcAmount 校验。
        BigDecimal total = BigDecimal.ZERO;
        for (Order order : orders) {
            order.setVisitId(visit.getId());
            order.setStatus("CREATED");
            // R-06: 统一金额计算(校验 + 两位小数),避免 NPE / 负额结算 / scale 漂移
            order.setAmount(calcAmount(order.getUnitPrice(), order.getQuantity()));
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
                // R-06: 合计金额统一两位小数,避免多次累加后 scale 漂移
                .totalAmount(total.setScale(2, RoundingMode.HALF_UP))
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
        // R-06: 统一金额计算(校验 + 两位小数),避免 NPE / 负额结算 / scale 漂移
        order.setAmount(calcAmount(order.getUnitPrice(), order.getQuantity()));
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
        // R-06: 统一金额计算(校验 + 两位小数),避免 NPE / 负额结算 / scale 漂移
        BigDecimal newAmount = calcAmount(updates.getUnitPrice(), updates.getQuantity());
        existing.setType(updates.getType());
        existing.setItemName(updates.getItemName());
        existing.setQuantity(updates.getQuantity());
        existing.setUnitPrice(updates.getUnitPrice());
        existing.setAmount(newAmount);
        orderMapper.updateById(existing);

        // 同步更新对应 charge 项(只改未收费的)
        // R-05: 把 orderId + visitId + payStatus 条件下推到 SQL,不再拉全表后在 Java 里过滤
        chargeMapper.selectList(new LambdaQueryWrapper<Charge>()
                        .eq(Charge::getOrderId, orderId)
                        .eq(Charge::getVisitId, visitId)
                        .eq(Charge::getPayStatus, "UNPAID"))
                .forEach(c -> {
                    c.setItemName(updates.getItemName());
                    c.setAmount(newAmount);
                    chargeMapper.updateById(c);
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
        // R-05: visitId + orderId + payStatus 全部下推到 SQL,不再拉全表后在 Java 里过滤
        chargeMapper.delete(new LambdaQueryWrapper<Charge>()
                .eq(Charge::getVisitId, visitId)
                .eq(Charge::getOrderId, orderId)
                .eq(Charge::getPayStatus, "UNPAID"));

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
        // R-05: visitId + orderId 条件下推到 SQL,不再拉全表后在 Java 里过滤
        List<Charge> related = chargeMapper.selectList(new LambdaQueryWrapper<Charge>()
                .eq(Charge::getVisitId, visitId)
                .eq(Charge::getOrderId, orderId));
        for (Charge c : related) {
            if ("UNPAID".equals(c.getPayStatus())) {
                chargeMapper.deleteById(c.getId());
            } else if ("PAID".equals(c.getPayStatus())) {
                c.setPayStatus("REFUNDED");
                c.setRefundTime(LocalDateTime.now());
                chargeMapper.updateById(c);
            }
        }

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
        // R-05: 原实现先 selectList(null) 拉全表再逐条 updateById(N 次 SQL),
        // 现改为单条批量 UPDATE(条件下推),无论多少条收费都只发 1 次 SQL 且不物化行。
        chargeMapper.update(null, new LambdaUpdateWrapper<Charge>()
                .eq(Charge::getVisitId, visitId)
                .eq(Charge::getPayStatus, "UNPAID")
                .set(Charge::getPayStatus, "PAID")
                .set(Charge::getPayTime, LocalDateTime.now()));
        // 收费完成后,已确单就诊单自动推进为进行中(进入就诊执行阶段)
        if (VisitStatus.of(visit.getStatus()) == VisitStatus.CONFIRMED) {
            visit.transitTo(VisitStatus.IN_PROGRESS);
            visitMapper.updateById(visit);
        }
        readModelService.refresh(visitId);
        // 发布缴费完成通知:仅当有药品医嘱时通知药房
        // R-05: 这里只判"是否存在",改用 selectCount;并把 status='CREATED' 一并下推到 SQL,
        // 不再物化医嘱行后在 Java 里 anyMatch(原实现会拉回该就诊全部药品医嘱再过滤)。
        Long medicationCount = orderMapper.selectCount(new LambdaQueryWrapper<Order>()
                .eq(Order::getVisitId, visitId)
                .eq(Order::getType, "MEDICATION")
                .eq(Order::getStatus, "CREATED"));
        boolean hasMedication = medicationCount != null && medicationCount > 0;
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
        // R-05: 只需判断"是否存在未缴",改用 selectCount 下推(不物化收费行),原实现拉全表再 anyMatch
        Long unpaidCount = chargeMapper.selectCount(new LambdaQueryWrapper<Charge>()
                .eq(Charge::getVisitId, visitId)
                .eq(Charge::getPayStatus, "UNPAID"));
        boolean hasUnpaid = unpaidCount != null && unpaidCount > 0;
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
            // R-05: 批量作废医嘱同样改单条批量 UPDATE,不再 N 次 updateById
            if (!pendingOrders.isEmpty()) {
                orderMapper.update(null, new LambdaUpdateWrapper<Order>()
                        .eq(Order::getVisitId, visitId)
                        .eq(Order::getStatus, "CREATED")
                        .set(Order::getStatus, "CANCELLED"));
            }
            // 作废医嘱对应的已收费记录 → 退费(保审计痕迹)
            // R-05: id 为 null 的脏数据不能进 IN 列表,否则拼出非法 SQL
            List<Long> cancelledIds = pendingOrders.stream()
                    .map(Order::getId)
                    .filter(Objects::nonNull)
                    .toList();
            // R-05: 原来 selectList(null) 拉全表 + Java 过滤,现改为单条批量 UPDATE
            // (in 条件为空会生成非法 SQL,故先判空)
            if (!cancelledIds.isEmpty()) {
                chargeMapper.update(null, new LambdaUpdateWrapper<Charge>()
                        .eq(Charge::getVisitId, visitId)
                        .in(Charge::getOrderId, cancelledIds)
                        .eq(Charge::getPayStatus, "PAID")
                        .set(Charge::getPayStatus, "REFUNDED")
                        .set(Charge::getRefundTime, LocalDateTime.now()));
            }
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
        // R-06: 汇总金额跳过 null(防 NPE)并统一两位小数
        BigDecimal total = sumAmount(charges);
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

        // R-17: 原实现对每条读模型各查 3 次(visit / orders / charges),pageSize=10 即 1+30 次 SQL。
        // 现改为 3 次批量查询(selectBatchIds + 两次 in 查询),再用 groupingBy 在内存里组装,
        // 与页面条数无关的常量级 SQL,同时过滤掉 visit 已不存在的脏数据(原实现会留下 null 元素)。
        List<VisitDetail> items = toDetails(readModels);

        return PageResult.<VisitDetail>builder()
                .items(items)
                .total(total.intValue())
                .pageNum(pageNum)
                .pageSize(pageSize)
                .build();
    }

    /**
     * R-17: 批量把读模型列表组装为 VisitDetail 列表（3 次批量 SQL 取代 3N 次逐行回查）。
     * visit 已被物理删除的读模型（脏数据）直接跳过，结果中不会出现 null 元素。
     */
    private List<VisitDetail> toDetails(List<VisitReadModel> readModels) {
        if (readModels == null || readModels.isEmpty()) {
            return List.of();
        }
        List<Long> visitIds = readModels.stream()
                .map(VisitReadModel::getVisitId)
                .filter(Objects::nonNull)
                .distinct()
                .toList();
        if (visitIds.isEmpty()) {
            return List.of();
        }

        // 1 次查就诊 + 1 次查医嘱 + 1 次查收费
        List<Visit> visits = visitMapper.selectBatchIds(visitIds);
        Map<Long, Visit> visitMap = visits == null ? Map.of()
                : visits.stream().filter(Objects::nonNull)
                        .collect(Collectors.toMap(Visit::getId, Function.identity(), (a, b) -> a));
        Map<Long, List<Order>> ordersByVisit = orderMapper.selectList(
                        new LambdaQueryWrapper<Order>().in(Order::getVisitId, visitIds))
                .stream().filter(o -> o.getVisitId() != null)
                .collect(Collectors.groupingBy(Order::getVisitId));
        Map<Long, List<Charge>> chargesByVisit = chargeMapper.selectList(
                        new LambdaQueryWrapper<Charge>().in(Charge::getVisitId, visitIds))
                .stream().filter(c -> c.getVisitId() != null)
                .collect(Collectors.groupingBy(Charge::getVisitId));

        // 保持读模型原有顺序（即排序/分页顺序）
        return readModels.stream()
                .map(rm -> {
                    Visit visit = visitMap.get(rm.getVisitId());
                    if (visit == null) {
                        return null; // 脏读模型（visit 已删除）→ 过滤掉，不留下 null 元素
                    }
                    List<Order> orders = ordersByVisit.getOrDefault(rm.getVisitId(), List.of());
                    List<Charge> charges = chargesByVisit.getOrDefault(rm.getVisitId(), List.of());
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
                })
                .filter(Objects::nonNull)
                .toList();
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
        // R-08: currentDeptId == null 意味着"看全院"(管理员)或"当前用户不是科室员工"(患者账号)。
        // 原实现在此分支下 selectList(null) 返回全量就诊,而 VisitController.currentDeptId()
        // 对患者账号同样返回 null —— 任意患者一次请求即可拉走全院就诊。
        // 现仅对持有 system:admin 的主体放行全量;其余(含患者 / 无科室员工)直接返回空列表并告警。
        if (currentDeptId == null && !hasSystemAdminAuthority()) {
            log.warn("[R-08] 拒绝无科室上下文的全量就诊查询,疑似越权全量拉取: user={}",
                    CurrentUserResolver.resolveUsername());
            return List.of();
        }
        // R-18: 原实现在循环内对每条 visit 各拉一次全表 orders / charges,复杂度 O(V×(O+C)),
        // 现把两表各查一次(V 的 visitId 集合下推 in 条件),再用 groupingBy 分组,降为 O(V+O+C)。
        List<Visit> allVisits = visitMapper.selectList(null);
        if (allVisits == null || allVisits.isEmpty()) {
            return List.of();
        }
        List<Long> visitIds = allVisits.stream()
                .map(Visit::getId)
                .filter(Objects::nonNull)
                .distinct()
                .toList();
        // in 条件为空会拼出非法 SQL,故判空后再查;过滤语义与 isVisibleToDept 保持一致
        List<Order> allOrders = visitIds.isEmpty() ? List.of()
                : orderMapper.selectList(new LambdaQueryWrapper<Order>().in(Order::getVisitId, visitIds));
        List<Charge> allCharges = visitIds.isEmpty() ? List.of()
                : chargeMapper.selectList(new LambdaQueryWrapper<Charge>().in(Charge::getVisitId, visitIds));
        Map<Long, List<Order>> ordersByVisit = allOrders.stream()
                .filter(o -> o.getVisitId() != null)
                .collect(Collectors.groupingBy(Order::getVisitId));
        Map<Long, List<Charge>> chargesByVisit = allCharges.stream()
                .filter(c -> c.getVisitId() != null)
                .collect(Collectors.groupingBy(Charge::getVisitId));
        List<Visit> visits = allVisits.stream()
                .filter(v -> isVisibleToDept(v, currentDeptId, allOrders))  // 科室过滤(含执行科室)
                .toList();
        return visits.stream().map(v -> {
            List<Order> orders = ordersByVisit.getOrDefault(v.getId(), List.of());
            List<Charge> charges = chargesByVisit.getOrDefault(v.getId(), List.of());
            // R-06: 汇总金额跳过 null(防 NPE)并统一两位小数
            BigDecimal total = sumAmount(charges);
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
     * R-06: 金额统一计算入口 —— 金额 = 单价 × 数量,固定两位小数(HALF_UP)。
     * <p>
     * 原实现直接 {@code unitPrice.multiply(valueOf(quantity))}:
     * 单价为 null → NPE 导致整单建单失败;数量为负 → 负金额,可构造"负额结算";
     * 且全链路无 setScale,多次累加后 scale 漂移。此处统一收口校验与精度。
     *
     * @throws IllegalArgumentException 单价/数量为 null,或数量为负、单价为负
     */
    private BigDecimal calcAmount(BigDecimal unitPrice, Integer quantity) {
        if (unitPrice == null) {
            throw new IllegalArgumentException("医嘱单价不能为空");
        }
        if (quantity == null) {
            throw new IllegalArgumentException("医嘱数量不能为空");
        }
        if (quantity < 0) {
            throw new IllegalArgumentException("医嘱数量不能为负数: " + quantity);
        }
        if (unitPrice.signum() < 0) {
            throw new IllegalArgumentException("医嘱单价不能为负数: " + unitPrice);
        }
        return unitPrice.multiply(BigDecimal.valueOf(quantity)).setScale(2, RoundingMode.HALF_UP);
    }

    /**
     * R-06: 汇总收费金额 —— 跳过 null 金额(数据库脏数据 / 未回填时原实现会 NPE),
     * 结果统一两位小数(HALF_UP),避免 reduce 后 scale 漂移。
     */
    private BigDecimal sumAmount(List<Charge> charges) {
        if (charges == null || charges.isEmpty()) {
            return BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);
        }
        return charges.stream()
                .map(Charge::getAmount)
                .filter(Objects::nonNull)
                .reduce(BigDecimal.ZERO, BigDecimal::add)
                .setScale(2, RoundingMode.HALF_UP);
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
        String phone = CurrentUserResolver.resolveUsername();
        if (phone == null) return null;
        var staff = staffService.findByPhone(phone);
        return staff == null ? null : staff.getId();
    }

    /**
     * R-08: 当前主体是否持有 system:admin(唯一允许"看全院"的角色)。
     * 直接读 SecurityContext,不新增构造参数,保持既有方法签名与其它调用点不变。
     */
    private boolean hasSystemAdminAuthority() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || auth.getAuthorities() == null) {
            return false;
        }
        for (GrantedAuthority authority : auth.getAuthorities()) {
            if ("system:admin".equals(authority.getAuthority())) {
                return true;
            }
        }
        return false;
    }
}