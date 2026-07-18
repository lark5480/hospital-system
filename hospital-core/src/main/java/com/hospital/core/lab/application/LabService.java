package com.hospital.core.lab.application;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.hospital.core.clinical.application.ChargeService;
import com.hospital.core.clinical.application.VisitService;
import com.hospital.core.clinical.domain.Order;
import com.hospital.core.clinical.infrastructure.OrderMapper;
import com.hospital.core.lab.domain.LabRequisition;
import com.hospital.core.lab.domain.LabResultItem;
import com.hospital.core.lab.infrastructure.LabRequisitionMapper;
import com.hospital.core.lab.infrastructure.LabResultItemMapper;
import com.hospital.core.clinical.domain.Visit;
import com.hospital.core.clinical.infrastructure.VisitMapper;
import com.hospital.core.report.application.ReportService;
import com.hospital.core.report.domain.Report;
import com.hospital.core.report.domain.ReportPdfEvent;
import lombok.RequiredArgsConstructor;
import lombok.Data;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 医技应用服务:检验申请创建 → 结果录入闭环。
 * 与 clinical 模块的 Order 衔接:创建申请时查出就诊下所有 LAB+CREATED 医嘱,
 * 逐条生成 LabResultItem;提交结果时同步回写 Order.status = EXECUTED。
 */
@Service
@RequiredArgsConstructor
public class LabService {

    private final LabRequisitionMapper requisitionMapper;
    private final LabResultItemMapper resultItemMapper;
    private final OrderMapper orderMapper;
    private final VisitMapper visitMapper;
    private final VisitService visitService;
    private final ChargeService chargeService;
    private final ReportService reportService;
    private final ApplicationEventPublisher eventPublisher;

    @Transactional
    public LabRequisition createFromVisit(Long visitId, Long doctorId, List<Long> orderIds) {
        Visit visit = visitMapper.selectById(visitId);
        if (visit == null) throw new IllegalArgumentException("就诊不存在:" + visitId);

        // 防重复:该就诊已有 PENDING 检验申请则拒绝
        Long existCount = requisitionMapper.selectCount(
                new LambdaQueryWrapper<LabRequisition>()
                        .eq(LabRequisition::getVisitId, visitId)
                        .eq(LabRequisition::getStatus, "PENDING"));
        if (existCount > 0) {
            throw new IllegalStateException("该就诊已有待处理的检验申请，请勿重复创建");
        }

        LambdaQueryWrapper<Order> q = new LambdaQueryWrapper<Order>()
                .eq(Order::getVisitId, visitId)
                .eq(Order::getType, "LAB")
                .eq(Order::getStatus, "CREATED");
        if (orderIds != null && !orderIds.isEmpty()) {
            q.in(Order::getId, orderIds);
        }
        List<Order> labOrders = orderMapper.selectList(q);
        if (labOrders.isEmpty()) {
            throw new IllegalStateException("该就诊无可创建的检验医嘱");
        }

        // 确单:锁定就诊单,此后不可再追加/修改/取消医嘱
        visitService.confirm(visitId);

        LabRequisition req = new LabRequisition();
        req.setVisitId(visitId);
        req.setPatientId(visit.getPatientId());
        req.setDoctorId(doctorId);
        req.setStatus("PENDING");
        req.setCreatedAt(LocalDateTime.now());
        requisitionMapper.insert(req);

        for (Order o : labOrders) {
            LabResultItem item = new LabResultItem();
            item.setRequisitionId(req.getId());
            item.setOrderId(o.getId());
            item.setItemName(o.getItemName());
            item.setStatus("PENDING");
            resultItemMapper.insert(item);
        }
        return req;
    }

    @Transactional
    public LabRequisition submitResults(Long requisitionId, Long technicianId, List<ResultEntry> entries) {
        LabRequisition req = requisitionMapper.selectById(requisitionId);
        if (req == null) throw new IllegalArgumentException("申请不存在:" + requisitionId);
        if (!"PENDING".equals(req.getStatus())) {
            throw new IllegalStateException("仅 PENDING 状态的申请可录入结果");
        }

        chargeService.assertAllPaid(req.getVisitId());  // 收费前置:未缴费拦截执行

        req.setTechnicianId(technicianId);
        req.setReportedAt(LocalDateTime.now());

        for (ResultEntry entry : entries) {
            LabResultItem item = resultItemMapper.selectById(entry.getItemId());
            if (item == null || !"PENDING".equals(item.getStatus())) continue;

            item.setResultValue(entry.getResultValue());
            item.setUnit(entry.getUnit());
            item.setRefRange(entry.getRefRange());
            item.setAbnormalFlag(entry.getAbnormalFlag());
            item.setStatus("COMPLETED");
            resultItemMapper.updateById(item);

            if (item.getOrderId() != null) {
                Order order = orderMapper.selectById(item.getOrderId());
                if (order != null && "CREATED".equals(order.getStatus())) {
                    order.setStatus("EXECUTED");
                    orderMapper.updateById(order);
                }
            }
        }

        boolean allDone = resultItemMapper.selectList(
                new LambdaQueryWrapper<LabResultItem>()
                        .eq(LabResultItem::getRequisitionId, requisitionId))
                .stream().allMatch(i -> "COMPLETED".equals(i.getStatus()));
        if (allDone) req.setStatus("EXECUTED");
        requisitionMapper.updateById(req);

        // 结果录入完成后自动生成报告(并异步生成 PDF,与 C 端体检报告一致)
        String itemsSummary = entries.stream().map(e -> {
            LabResultItem item = resultItemMapper.selectById(e.getItemId());
            return item != null ? item.getItemName() + "=" + e.getResultValue() : "";
        }).filter(s -> !s.isEmpty()).reduce((a, b) -> a + "; " + b).orElse("");
        Report report = reportService.createAndPublish(req.getVisitId(), req.getPatientId(), "LAB",
                "检验报告-" + req.getId(),
                "申请 #" + req.getId() + " 检验结果：" + itemsSummary,
                req.getTechnicianId());
        eventPublisher.publishEvent(new ReportPdfEvent(report.getId(), report.getPatientId(), report.getTitle(), report.getContent()));

        // 所有医嘱执行完 → 自动完成就诊单
        visitService.tryAutoFinish(req.getVisitId());

        return req;
    }

    @Transactional
    public LabRequisition cancel(Long requisitionId) {
        LabRequisition req = requisitionMapper.selectById(requisitionId);
        if (req == null) throw new IllegalArgumentException("申请不存在:" + requisitionId);
        if (!"PENDING".equals(req.getStatus())) {
            throw new IllegalStateException("仅 PENDING 状态的申请可取消");
        }
        req.setStatus("CANCELLED");
        requisitionMapper.updateById(req);

        List<LabResultItem> items = resultItemMapper.selectList(
                new LambdaQueryWrapper<LabResultItem>()
                        .eq(LabResultItem::getRequisitionId, requisitionId));
        for (LabResultItem item : items) {
            item.setStatus("CANCELLED");
            resultItemMapper.updateById(item);
        }
        return req;
    }

    public List<LabRequisition> list(String status) {
        LambdaQueryWrapper<LabRequisition> q = new LambdaQueryWrapper<LabRequisition>()
                .orderByDesc(LabRequisition::getCreatedAt);
        if (status != null && !status.isBlank()) q.eq(LabRequisition::getStatus, status);
        return requisitionMapper.selectList(q);
    }

    public LabRequisitionDetail getDetail(Long id) {
        LabRequisition req = requisitionMapper.selectById(id);
        if (req == null) return null;
        List<LabResultItem> items = resultItemMapper.selectList(
                new LambdaQueryWrapper<LabResultItem>()
                        .eq(LabResultItem::getRequisitionId, id));
        return new LabRequisitionDetail(req, items);
    }

    @Data
    public static class ResultEntry {
        private Long itemId;
        private String resultValue;
        private String unit;
        private String refRange;
        private String abnormalFlag;
    }
}
