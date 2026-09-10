package com.hospital.core.pharmacy.application;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.hospital.core.clinical.application.VisitService;
import com.hospital.core.clinical.domain.Charge;
import com.hospital.core.clinical.domain.Order;
import com.hospital.core.clinical.domain.OrderUpdatedEvent;
import com.hospital.core.clinical.domain.Visit;
import com.hospital.core.clinical.domain.VisitStatusEvent;
import com.hospital.core.clinical.infrastructure.ChargeMapper;
import com.hospital.core.clinical.infrastructure.OrderMapper;
import com.hospital.core.org.application.StaffService;
import com.hospital.core.org.domain.Staff;
import com.hospital.core.org.infrastructure.StaffMapper;
import com.hospital.core.patient.application.PatientService;
import com.hospital.core.patient.domain.Patient;
import com.hospital.core.platform.support.NameCache;
import com.hospital.core.pharmacy.domain.Prescription;
import com.hospital.core.pharmacy.domain.PrescriptionItem;
import com.hospital.core.pharmacy.infrastructure.PrescriptionItemMapper;
import com.hospital.core.pharmacy.infrastructure.PrescriptionMapper;
import com.hospital.core.report.application.ReportService;
import com.hospital.core.report.domain.Report;
import com.hospital.core.report.domain.ReportPdfEvent;

import lombok.RequiredArgsConstructor;

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
    private final ChargeMapper chargeMapper;
    private final VisitService visitService;
    private final ReportService reportService;
    private final PatientService patientService;
    private final StaffService staffService;
    private final ApplicationEventPublisher eventPublisher;

    // R-19: 名称解析缓存 + 员工批量查询(字段注入,不改构造签名,兼容既有单测直接 new)
    @Autowired(required = false)
    private NameCache nameCache;
    @Autowired(required = false)
    private StaffMapper staffMapper;

    /**
     * 监听临床医嘱修改事件:同步更新对应 PENDING 处方明细的快照(名称/数量/单价)。
     * 医生二次修改药品医嘱后,处方明细随之更新,避免发药时仍显示旧药品名称。
     * 同步执行,与 editOrder 处于同一事务;已发药(DISPENSED)/已取消明细不受影响。
     */
    @EventListener
    public void onOrderUpdated(OrderUpdatedEvent event) {
        List<PrescriptionItem> items = itemMapper.selectList(
                new LambdaQueryWrapper<PrescriptionItem>()
                        .eq(PrescriptionItem::getOrderId, event.orderId())
                        .eq(PrescriptionItem::getStatus, "PENDING"));
        for (PrescriptionItem item : items) {
            item.setItemName(event.itemName());
            item.setQuantity(event.quantity());
            item.setUnitPrice(event.unitPrice());
            itemMapper.updateById(item);
        }
    }

    /** 从就诊的药品医嘱创建处方(只取 status=CREATED 的医嘱)。创建后同时确单锁定就诊单。
     *  若已有 PENDING 处方,将新药品医嘱追加到现有处方(支持二次诊断追加药品)。 */
    @Transactional
    public Prescription createFromVisit(Long visitId, Long doctorId) {
        Visit visit = visitService.get(visitId);
        if (visit == null) throw new IllegalArgumentException("就诊不存在:" + visitId);

        List<Order> medOrders = orderMapper.selectList(
                new LambdaQueryWrapper<Order>()
                        .eq(Order::getVisitId, visitId)
                        .eq(Order::getType, "MEDICATION")
                        .eq(Order::getStatus, "CREATED"));

        // 查找已有的 PENDING 处方
        Prescription existingPending = prescriptionMapper.selectOne(
                new LambdaQueryWrapper<Prescription>()
                        .eq(Prescription::getVisitId, visitId)
                        .eq(Prescription::getStatus, "PENDING"));

        if (existingPending != null) {
            // 已有 PENDING 处方:找出尚未纳入的新药品医嘱,追加到现有处方
            List<Long> existingOrderIds = itemMapper.selectList(
                    new LambdaQueryWrapper<PrescriptionItem>()
                            .eq(PrescriptionItem::getPrescriptionId, existingPending.getId()))
                    .stream().map(PrescriptionItem::getOrderId).toList();

            List<Order> newOrders = medOrders.stream()
                    .filter(o -> !existingOrderIds.contains(o.getId()))
                    .toList();

            if (newOrders.isEmpty()) {
                throw new IllegalStateException("该就诊无可追加的药品医嘱");
            }

            for (Order o : newOrders) {
                PrescriptionItem item = new PrescriptionItem();
                item.setPrescriptionId(existingPending.getId());
                item.setOrderId(o.getId());
                item.setItemName(o.getItemName());
                item.setQuantity(o.getQuantity());
                item.setUnitPrice(o.getUnitPrice());
                item.setStatus("PENDING");
                itemMapper.insert(item);
            }
            return existingPending;
        }

        if (medOrders.isEmpty()) {
            throw new IllegalStateException("该就诊无可创建的药品医嘱");
        }

        // 首次从草稿创建处方时确单锁定;若就诊已确单/进行中(回诊做完检查后追加药品),
        // 就诊单已锁定,跳过确单直接建方,避免 confirm() 对非草稿状态抛异常导致处方回滚。
        if ("CREATED".equals(visit.getStatus())) {
            visitService.confirm(visitId);
        }

        // 医生以就诊单为准(确单时若未指定会回填当前操作医生),传参仅兜底
        Visit freshVisit = visitService.get(visitId);
        Long effectiveDoctorId = (freshVisit != null && freshVisit.getDoctorId() != null)
                ? freshVisit.getDoctorId() : doctorId;

        Prescription p = new Prescription();
        p.setVisitId(visitId);
        p.setPatientId(visit.getPatientId());
        p.setDoctorId(effectiveDoctorId);
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
        // 通知收费处：有新处方待收费
        var patient = patientService.get(visit.getPatientId());
        String patientName = patient != null ? patient.getName() : "患者";
        eventPublisher.publishEvent(new VisitStatusEvent(
                visitId, visit.getPatientId(), patientName,
                "PRESCRIPTION_CREATED", "新处方已创建，处方号 #" + p.getId() + "，请通知患者缴费"));
        return p;
    }

    /** 发药:标记处方已发药,同步回写关联医嘱为 EXECUTED。 */
    @Transactional
    public Prescription dispense(Long prescriptionId, Long pharmacistId, String remark) {
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
        if (remark != null && !remark.isBlank()) {
            p.setRemark(remark);
        }
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
        String doctorName = resolveDoctorName(p);
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

    /** 处方医生名称:处方未记录医生时回退到就诊单医生(历史数据兜底)。 */
    private String resolveDoctorName(Prescription p) {
        Long doctorId = p.getDoctorId();
        if (doctorId == null && p.getVisitId() != null) {
            var v = visitService.get(p.getVisitId());
            if (v != null) {
                doctorId = v.getDoctorId();
            }
        }
        return resolveStaffName(doctorId);
    }

    /**
     * 查询处方列表(含患者和医生名称),可按状态筛选、关键字(患者/医生)搜索。
     *
     * <p>R-19: 消除名称解析 N+1 —— 原实现"每条处方查 1 次明细 + 各解析 1 次患者/医生/药师名",
     * 现改为:1 次批量查明细 + NameCache 批量解析患者/员工姓名(命中零查询)。出参与字段不变。
     */
    public List<PrescriptionDetail> listWithDetail(String status, String keyword) {
        List<Prescription> list = list(status);
        if (list.isEmpty()) {
            return List.of();
        }

        List<Long> prescriptionIds = list.stream()
                .map(Prescription::getId)
                .filter(Objects::nonNull)
                .distinct()
                .toList();
        // 1 次批量查明细(原:每条处方各查一次)
        List<PrescriptionItem> allItems = prescriptionIds.isEmpty() ? List.of()
                : itemMapper.selectList(new LambdaQueryWrapper<PrescriptionItem>()
                        .in(PrescriptionItem::getPrescriptionId, prescriptionIds));
        Map<Long, List<PrescriptionItem>> itemsByRx = allItems.stream()
                .filter(i -> i.getPrescriptionId() != null)
                .collect(Collectors.groupingBy(PrescriptionItem::getPrescriptionId));

        // 批量解析患者姓名(仅查缓存缺失的 id)
        Set<Long> patientIds = list.stream()
                .map(Prescription::getPatientId)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
        Map<Long, String> patientNames = loadNames("patient", patientIds, this::batchLoadPatientNames);

        // 医生 ID:处方未记录时回退就诊单医生(历史数据,罕见);医生与药师同属 org.staff,合并一次批量解析
        Map<Long, Long> doctorIdByRx = new HashMap<>();
        Set<Long> staffIds = new HashSet<>();
        for (Prescription p : list) {
            Long doctorId = p.getDoctorId();
            if (doctorId == null && p.getVisitId() != null) {
                var v = visitService.get(p.getVisitId());
                if (v != null) {
                    doctorId = v.getDoctorId();
                }
            }
            doctorIdByRx.put(p.getId(), doctorId);
            if (doctorId != null) {
                staffIds.add(doctorId);
            }
            if (p.getPharmacistId() != null) {
                staffIds.add(p.getPharmacistId());
            }
        }
        Map<Long, String> staffNames = loadNames("staff", staffIds, this::batchLoadStaffNames);

        return list.stream().map(p -> new PrescriptionDetail(p,
                        itemsByRx.getOrDefault(p.getId(), List.of()),
                        patientNames.get(p.getPatientId()),
                        staffNames.get(doctorIdByRx.get(p.getId())),
                        staffNames.get(p.getPharmacistId())))
                .filter(d -> {
                    if (keyword == null || keyword.isBlank()) return true;
                    String kw = keyword.toLowerCase();
                    return (d.getPatientName() != null && d.getPatientName().toLowerCase().contains(kw))
                            || (d.getDoctorName() != null && d.getDoctorName().toLowerCase().contains(kw));
                }).toList();
    }

    /** R-19: 名称批量解析 —— 命中缓存零查询,缺失的 id 走底层一次 IN 查询。 */
    private Map<Long, String> loadNames(String type, Collection<Long> ids,
                                        Function<Collection<Long>, Map<Long, String>> loader) {
        if (ids == null || ids.isEmpty()) {
            return Map.of();
        }
        try {
            if (nameCache != null) {
                return nameCache.getOrLoadAll(type, ids, loader);
            }
            return loader.apply(ids);   // 无上下文(纯单测)时退化为直接批量加载
        } catch (Exception e) {
            return Map.of();
        }
    }

    /** R-19: 批量解析患者姓名(patientService.listByIds 底层一条 IN 查询)。 */
    private Map<Long, String> batchLoadPatientNames(Collection<Long> ids) {
        Map<Long, String> result = new HashMap<>();
        for (Patient p : patientService.listByIds(new ArrayList<>(ids))) {
            if (p != null && p.getId() != null && p.getName() != null) {
                result.put(p.getId(), p.getName());
            }
        }
        return result;
    }

    /** R-19: 批量解析员工姓名(StaffMapper.selectBatchIds 底层一条 IN 查询)。 */
    private Map<Long, String> batchLoadStaffNames(Collection<Long> ids) {
        Map<Long, String> result = new HashMap<>();
        if (staffMapper != null) {
            for (Staff s : staffMapper.selectBatchIds(new ArrayList<>(ids))) {
                if (s != null && s.getId() != null && s.getName() != null) {
                    result.put(s.getId(), s.getName());
                }
            }
            return result;
        }
        // 无 Mapper(纯单测)时回退到 service 层逐条查询,保证功能不缺失
        for (Long id : ids) {
            Staff s = staffService.get(id);
            if (s != null && s.getName() != null) {
                result.put(id, s.getName());
            }
        }
        return result;
    }
}
