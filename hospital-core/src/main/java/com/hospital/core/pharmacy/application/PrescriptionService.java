package com.hospital.core.pharmacy.application;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.hospital.core.clinical.application.VisitService;
import com.hospital.core.clinical.domain.Charge;
import com.hospital.core.clinical.domain.Order;
import com.hospital.core.clinical.domain.Visit;
import com.hospital.core.clinical.infrastructure.ChargeMapper;
import com.hospital.core.clinical.infrastructure.OrderMapper;
import com.hospital.core.clinical.infrastructure.VisitMapper;
import com.hospital.core.org.application.StaffService;
import com.hospital.core.patient.application.PatientService;
import com.hospital.core.pharmacy.domain.Prescription;
import com.hospital.core.pharmacy.domain.PrescriptionItem;
import com.hospital.core.pharmacy.infrastructure.PrescriptionItemMapper;
import com.hospital.core.pharmacy.infrastructure.PrescriptionMapper;
import com.hospital.core.report.application.ReportService;
import com.hospital.core.report.domain.Report;
import com.hospital.core.report.domain.ReportPdfEvent;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 药事应用服务:处方创建 → 发药闭环。
 * <p>
 * 与 clinical 模块的 Order 衔接:创建处方时查出就诊下所有 MEDICATION 类型医嘱,
 * 逐条生成 PrescriptionItem;发药时同步回写 Order.status = EXECUTED。
 */
@Service
@RequiredArgsConstructor
public class PrescriptionService {

    private final PrescriptionMapper prescriptionMapper;
    private final PrescriptionItemMapper itemMapper;
    private final OrderMapper orderMapper;
    private final VisitMapper visitMapper;
    private final ChargeMapper chargeMapper;
    private final VisitService visitService;
    private final ReportService reportService;
    private final PatientService patientService;
    private final StaffService staffService;
    private final ApplicationEventPublisher eventPublisher;

    /** 从就诊的药品医嘱创建处方(只取 status=CREATED 的医嘱)。创建后同时确单锁定就诊单。 */
    @Transactional
    public Prescription createFromVisit(Long visitId, Long doctorId) {
        Visit visit = visitMapper.selectById(visitId);
        if (visit == null) throw new IllegalArgumentException("就诊不存在:" + visitId);

        // 防重复:该就诊已有 PENDING 处方则拒绝
        Long existCount = prescriptionMapper.selectCount(
                new LambdaQueryWrapper<Prescription>()
                        .eq(Prescription::getVisitId, visitId)
                        .eq(Prescription::getStatus, "PENDING"));
        if (existCount > 0) {
            throw new IllegalStateException("该就诊已有待处理的处方，请勿重复创建");
        }

        List<Order> medOrders = orderMapper.selectList(
                new LambdaQueryWrapper<Order>()
                        .eq(Order::getVisitId, visitId)
                        .eq(Order::getType, "MEDICATION")
                        .eq(Order::getStatus, "CREATED"));

        if (medOrders.isEmpty()) {
            throw new IllegalStateException("该就诊无可创建的药品医嘱");
        }

        // 确单:锁定就诊单,此后不可再追加/修改/取消医嘱
        visitService.confirm(visitId);

        Prescription p = new Prescription();
        p.setVisitId(visitId);
        p.setPatientId(visit.getPatientId());
        p.setDoctorId(doctorId);
        p.setStatus("PENDING");
        p.setCreatedAt(LocalDateTime.now());
        prescriptionMapper.insert(p);

        for (Order o : medOrders) {
            PrescriptionItem item = new PrescriptionItem();
            item.setPrescriptionId(p.getId());
            item.setOrderId(o.getId());
            item.setItemName(o.getItemName());
            item.setQuantity(o.getQuantity());
            item.setUnitPrice(o.getUnitPrice());
            item.setStatus("PENDING");
            itemMapper.insert(item);
        }
        return p;
    }

    /** 发药:标记处方已发药,同步回写关联医嘱为 EXECUTED。 */
    @Transactional
    public Prescription dispense(Long prescriptionId, Long pharmacistId) {
        Prescription p = prescriptionMapper.selectById(prescriptionId);
        if (p == null) throw new IllegalArgumentException("处方不存在:" + prescriptionId);
        if (!"PENDING".equals(p.getStatus())) {
            throw new IllegalStateException("仅 PENDING 状态的处方可发药,当前=" + p.getStatus());
        }

        // 校验该就诊是否已全部缴费
        List<Charge> charges = chargeMapper.selectList(
                new LambdaQueryWrapper<Charge>().eq(Charge::getVisitId, p.getVisitId()));
        boolean hasUnpaid = charges.stream().anyMatch(c -> "UNPAID".equals(c.getPayStatus()));
        if (hasUnpaid) {
            throw new IllegalStateException("该就诊尚有未缴费用，请先完成缴费");
        }

        // 更新处方
        p.setPharmacistId(pharmacistId);
        p.setStatus("DISPENSED");
        p.setDispensedAt(LocalDateTime.now());
        prescriptionMapper.updateById(p);

        // 更新所有明细
        List<PrescriptionItem> items = itemMapper.selectList(
                new LambdaQueryWrapper<PrescriptionItem>()
                        .eq(PrescriptionItem::getPrescriptionId, prescriptionId));
        for (PrescriptionItem item : items) {
            item.setStatus("DISPENSED");
            itemMapper.updateById(item);

            // 同步回写临床医嘱状态
            if (item.getOrderId() != null) {
                Order order = orderMapper.selectById(item.getOrderId());
                if (order != null && "CREATED".equals(order.getStatus())) {
                    order.setStatus("EXECUTED");
                    orderMapper.updateById(order);
                }
            }
        }

        // 发药完成后自动生成报告(并异步生成 PDF,与 C 端体检报告一致)
        String itemsSummary = items.stream().map(PrescriptionItem::getItemName)
                .reduce((a, b) -> a + "、" + b).orElse("");
        Report report = reportService.createAndPublish(p.getVisitId(), p.getPatientId(), "CLINICAL",
                "发药报告-" + p.getId(),
                "处方 #" + p.getId() + " 已发药，药品：" + itemsSummary,
                p.getPharmacistId());
        eventPublisher.publishEvent(new ReportPdfEvent(report.getId(), report.getPatientId(), report.getTitle(), report.getContent()));

        // 该就诊所有处方都已发完 → 就诊单完结(FINISHED)
        Long visitId = p.getVisitId();
        Long pendingCount = prescriptionMapper.selectCount(
                new LambdaQueryWrapper<Prescription>()
                        .eq(Prescription::getVisitId, visitId)
                        .eq(Prescription::getStatus, "PENDING"));
        if (pendingCount == 0) {
            Visit visit = visitMapper.selectById(visitId);
            if (visit != null) {
                visit.setStatus("FINISHED");
                visitMapper.updateById(visit);
            }
        }
        return p;
    }

    /** 取消处方。 */
    @Transactional
    public Prescription cancel(Long prescriptionId) {
        Prescription p = prescriptionMapper.selectById(prescriptionId);
        if (p == null) throw new IllegalArgumentException("处方不存在:" + prescriptionId);
        if (!"PENDING".equals(p.getStatus())) {
            throw new IllegalStateException("仅 PENDING 状态的处方可取消");
        }
        p.setStatus("CANCELLED");
        prescriptionMapper.updateById(p);

        List<PrescriptionItem> items = itemMapper.selectList(
                new LambdaQueryWrapper<PrescriptionItem>()
                        .eq(PrescriptionItem::getPrescriptionId, prescriptionId));
        for (PrescriptionItem item : items) {
            item.setStatus("CANCELLED");
            itemMapper.updateById(item);
        }
        return p;
    }

    /** 查询所有处方(按创建时间倒序)。 */
    public List<Prescription> list(String status) {
        LambdaQueryWrapper<Prescription> q = new LambdaQueryWrapper<Prescription>()
                .orderByDesc(Prescription::getCreatedAt);
        if (status != null && !status.isBlank()) {
            q.eq(Prescription::getStatus, status);
        }
        return prescriptionMapper.selectList(q);
    }

    /** 查询单个处方(含明细 + 名称)。 */
    public PrescriptionDetail getDetail(Long id) {
        Prescription p = prescriptionMapper.selectById(id);
        if (p == null) return null;
        List<PrescriptionItem> items = itemMapper.selectList(
                new LambdaQueryWrapper<PrescriptionItem>()
                        .eq(PrescriptionItem::getPrescriptionId, id));
        String patientName = resolvePatientName(p.getPatientId());
        String doctorName = resolveStaffName(p.getDoctorId());
        String pharmacistName = resolveStaffName(p.getPharmacistId());
        return new PrescriptionDetail(p, items, patientName, doctorName, pharmacistName);
    }

    private String resolvePatientName(Long patientId) {
        if (patientId == null) return null;
        try { return patientService.getName(patientId); } catch (Exception e) { return null; }
    }

    private String resolveStaffName(Long staffId) {
        if (staffId == null) return null;
        try {
            var s = staffService.get(staffId);
            return s == null ? null : s.getName();
        } catch (Exception e) { return null; }
    }

    /** 查询处方列表(含患者和医生名称),可按状态筛选、关键字(患者/医生)搜索。 */
    public List<PrescriptionDetail> listWithDetail(String status, String keyword) {
        List<Prescription> list = list(status);
        return list.stream().map(p -> {
            List<PrescriptionItem> items = itemMapper.selectList(
                    new LambdaQueryWrapper<PrescriptionItem>()
                            .eq(PrescriptionItem::getPrescriptionId, p.getId()));
            return new PrescriptionDetail(p, items,
                    resolvePatientName(p.getPatientId()),
                    resolveStaffName(p.getDoctorId()),
                    resolveStaffName(p.getPharmacistId()));
        }).filter(d -> {
            if (keyword == null || keyword.isBlank()) return true;
            String kw = keyword.toLowerCase();
            return (d.getPatientName() != null && d.getPatientName().toLowerCase().contains(kw))
                    || (d.getDoctorName() != null && d.getDoctorName().toLowerCase().contains(kw));
        }).toList();
    }

    /** @deprecated 改用 {@link #listWithDetail(String, String)}。 */
    public List<PrescriptionDetail> listWithDetail(String status) {
        return listWithDetail(status, null);
    }
}
