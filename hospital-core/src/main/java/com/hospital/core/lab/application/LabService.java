package com.hospital.core.lab.application;

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
import com.hospital.core.clinical.application.ChargeService;
import com.hospital.core.clinical.application.VisitService;
import com.hospital.core.clinical.domain.Order;
import com.hospital.core.clinical.domain.OrderUpdatedEvent;
import com.hospital.core.clinical.domain.Visit;
import com.hospital.core.clinical.infrastructure.OrderMapper;
import com.hospital.core.clinical.infrastructure.VisitMapper;
import com.hospital.core.lab.domain.LabRequisition;
import com.hospital.core.lab.domain.LabResultItem;
import com.hospital.core.lab.infrastructure.LabRequisitionMapper;
import com.hospital.core.lab.infrastructure.LabResultItemMapper;
import com.hospital.core.org.application.StaffService;
import com.hospital.core.org.domain.Staff;
import com.hospital.core.org.infrastructure.StaffMapper;
import com.hospital.core.patient.application.PatientService;
import com.hospital.core.patient.domain.Patient;
import com.hospital.core.platform.support.NameCache;
import com.hospital.core.report.application.ReportService;
import com.hospital.core.report.domain.Report;
import com.hospital.core.report.domain.ReportPdfEvent;

import lombok.Data;
import lombok.RequiredArgsConstructor;

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
    private final PatientService patientService;
    private final StaffService staffService;

    // R-19: 名称解析缓存 + 员工批量查询(字段注入,不改构造签名,兼容既有单测直接 new)
    @Autowired(required = false)
    private NameCache nameCache;
    @Autowired(required = false)
    private StaffMapper staffMapper;

    /**
     * 监听临床医嘱修改事件:同步更新对应 PENDING 检验明细的项目名称快照。
     * 医生二次修改检验医嘱后,检验申请明细随之更新,避免录入结果时仍显示旧名称。
     * 同步执行,与 editOrder 处于同一事务;已完成(COMPLETED)/已取消明细不受影响。
     */
    @EventListener
    public void onOrderUpdated(OrderUpdatedEvent event) {
        List<LabResultItem> items = resultItemMapper.selectList(
                new LambdaQueryWrapper<LabResultItem>()
                        .eq(LabResultItem::getOrderId, event.orderId())
                        .eq(LabResultItem::getStatus, "PENDING"));
        for (LabResultItem item : items) {
            item.setItemName(event.itemName());
            resultItemMapper.updateById(item);
        }
    }

    @Transactional
    public LabRequisition createFromVisit(Long visitId, Long doctorId, List<Long> orderIds) {
        Visit visit = visitMapper.selectById(visitId);
        if (visit == null) throw new IllegalArgumentException("就诊不存在:" + visitId);

        LambdaQueryWrapper<Order> q = new LambdaQueryWrapper<Order>()
                .eq(Order::getVisitId, visitId)
                .eq(Order::getType, "LAB")
                .eq(Order::getStatus, "CREATED");
        if (orderIds != null && !orderIds.isEmpty()) {
            q.in(Order::getId, orderIds);
        }
        List<Order> labOrders = orderMapper.selectList(q);

        // 查找已有的 PENDING 检验申请
        LabRequisition existingPending = requisitionMapper.selectOne(
                new LambdaQueryWrapper<LabRequisition>()
                        .eq(LabRequisition::getVisitId, visitId)
                        .eq(LabRequisition::getStatus, "PENDING"));

        if (existingPending != null) {
            // 已有 PENDING 申请:找出尚未纳入的新检验医嘱,追加到现有申请
            List<Long> existingOrderIds = resultItemMapper.selectList(
                    new LambdaQueryWrapper<LabResultItem>()
                            .eq(LabResultItem::getRequisitionId, existingPending.getId()))
                    .stream().map(LabResultItem::getOrderId).toList();

            List<Order> newOrders = labOrders.stream()
                    .filter(o -> !existingOrderIds.contains(o.getId()))
                    .toList();

            if (newOrders.isEmpty()) {
                throw new IllegalStateException("该就诊无可追加的检验医嘱");
            }

            for (Order o : newOrders) {
                LabResultItem item = new LabResultItem();
                item.setRequisitionId(existingPending.getId());
                item.setOrderId(o.getId());
                item.setItemName(o.getItemName());
                item.setStatus("PENDING");
                resultItemMapper.insert(item);
            }
            return existingPending;
        }

        if (labOrders.isEmpty()) {
            throw new IllegalStateException("该就诊无可创建的检验医嘱");
        }

        // 确单:锁定就诊单,此后不可再追加/修改/取消医嘱
        visitService.confirm(visitId);

        // 医生以就诊单为准(确单时若未指定会回填当前操作医生),传参仅兜底
        Visit confirmedVisit = visitMapper.selectById(visitId);
        Long effectiveDoctorId = (confirmedVisit != null && confirmedVisit.getDoctorId() != null)
                ? confirmedVisit.getDoctorId() : doctorId;

        LabRequisition req = new LabRequisition();
        req.setVisitId(visitId);
        req.setPatientId(visit.getPatientId());
        req.setDoctorId(effectiveDoctorId);
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
            // 自动判定异常方向,忽略前端传入的 abnormalFlag
            item.setAbnormalFlag(autoDetectAbnormalFlag(entry.getResultValue(), entry.getRefRange()));
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
        StringBuilder content = new StringBuilder();
        content.append("检验申请 #").append(req.getId()).append("\n");
        content.append("━━━━━━━━━━━━━━━━━━━━━━━━━━━━\n");
        for (ResultEntry entry : entries) {
            LabResultItem item = resultItemMapper.selectById(entry.getItemId());
            if (item == null) continue;
            content.append("【").append(item.getItemName()).append("】\n");
            content.append("  结果: ").append(entry.getResultValue());
            if (entry.getUnit() != null && !entry.getUnit().isBlank()) {
                content.append(" ").append(entry.getUnit());
            }
            content.append("\n");
            if (entry.getRefRange() != null && !entry.getRefRange().isBlank()) {
                content.append("  参考范围: ").append(entry.getRefRange()).append("\n");
            }
            String flag = item.getAbnormalFlag();
            String symbol = "HIGH".equals(flag) ? "偏高 ↑" : "LOW".equals(flag) ? "偏低 ↓" : "正常";
            content.append("  判定: ").append(symbol).append("\n");
        }
        Report report = reportService.createAndPublish(req.getVisitId(), req.getPatientId(), "LAB",
                "检验报告-" + req.getId(),
                content.toString(),
                req.getTechnicianId());
        eventPublisher.publishEvent(new ReportPdfEvent(report.getId(), report.getPatientId(), report.getTitle(), report.getContent()));

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

    /** 检查当前用户科室是否有权访问该检验申请(申请中任一医嘱的执行科室匹配即可) */
    public boolean hasAccessToRequisition(Long requisitionId, Long deptId) {
        List<LabResultItem> items = resultItemMapper.selectList(
                new LambdaQueryWrapper<LabResultItem>()
                        .eq(LabResultItem::getRequisitionId, requisitionId));
        // R-19: 原对每个 item 再 orderMapper.selectById(单条回查,O(items) 次 SQL);
        // 改为一次 selectBatchIds 批量取回后内存匹配。
        List<Long> orderIds = items.stream()
                .map(LabResultItem::getOrderId)
                .filter(Objects::nonNull)
                .distinct()
                .toList();
        if (orderIds.isEmpty()) return false;
        Map<Long, Order> orderMap = orderMapper.selectBatchIds(orderIds).stream()
                .filter(Objects::nonNull)
                .collect(Collectors.toMap(Order::getId, Function.identity(), (a, b) -> a));
        return items.stream().anyMatch(item -> {
            if (item.getOrderId() == null) return false;
            Order order = orderMap.get(item.getOrderId());
            return order != null && deptId.equals(order.getExecutionDeptId());
        });
    }

    /**
     * 检验申请列表(含患者/医生名称),可按状态筛选、按科室过滤。
     *
     * <p>R-19: 消除名称解析与科室判定的 N+1 ——
     * 原实现"每条申请查 1 次明细 + 每个明细再查 1 次医嘱 + 每条申请解析患者/医生名",
     * 现改为常量级 SQL:1 次批量查明细 + 1 次批量查医嘱(判科室) + 1 次批量查回退就诊单
     * + NameCache 批量解析姓名(命中零查询)。出参结构与字段保持不变。
     */
    public List<LabRequisitionListItem> listWithDetail(String status, Long deptId) {
        List<LabRequisition> requisitions = list(status);
        if (requisitions.isEmpty()) {
            return List.of();
        }

        List<Long> requisitionIds = requisitions.stream()
                .map(LabRequisition::getId)
                .filter(Objects::nonNull)
                .distinct()
                .toList();
        // 1 次批量查明细(原:每条申请各查一次)
        List<LabResultItem> allItems = requisitionIds.isEmpty() ? List.of()
                : resultItemMapper.selectList(new LambdaQueryWrapper<LabResultItem>()
                        .in(LabResultItem::getRequisitionId, requisitionIds));
        Map<Long, List<LabResultItem>> itemsByReq = allItems.stream()
                .filter(i -> i.getRequisitionId() != null)
                .collect(Collectors.groupingBy(LabResultItem::getRequisitionId));

        // 1 次批量查医嘱(原:对每个明细再 orderMapper.selectById,用于判定执行科室)
        List<Long> orderIds = allItems.stream()
                .map(LabResultItem::getOrderId)
                .filter(Objects::nonNull)
                .distinct()
                .toList();
        Map<Long, Order> orderMap = orderIds.isEmpty() ? Map.of()
                : orderMapper.selectBatchIds(orderIds).stream()
                        .filter(Objects::nonNull)
                        .collect(Collectors.toMap(Order::getId, Function.identity(), (a, b) -> a));

        // 科室过滤:申请下任一明细的执行科室匹配即可(内存匹配,不再逐条回查)
        if (deptId != null) {
            requisitions = requisitions.stream().filter(req -> {
                List<LabResultItem> items = itemsByReq.getOrDefault(req.getId(), List.of());
                return items.stream().anyMatch(item -> {
                    Order order = item.getOrderId() == null ? null : orderMap.get(item.getOrderId());
                    return order != null && deptId.equals(order.getExecutionDeptId());
                });
            }).toList();
        }
        if (requisitions.isEmpty()) {
            return List.of();
        }

        // 申请未记录医生时回退到就诊单医生(历史数据兜底):1 次批量查就诊单
        Set<Long> fallbackVisitIds = requisitions.stream()
                .filter(r -> r.getDoctorId() == null)
                .map(LabRequisition::getVisitId)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
        Map<Long, Visit> visitMap = fallbackVisitIds.isEmpty() ? Map.of()
                : visitMapper.selectBatchIds(fallbackVisitIds).stream()
                        .filter(Objects::nonNull)
                        .collect(Collectors.toMap(Visit::getId, Function.identity(), (a, b) -> a));

        // 批量解析姓名:患者一次 IN 查询 + 医生一次 IN 查询(仅查缓存缺失的 id)
        Set<Long> patientIds = requisitions.stream()
                .map(LabRequisition::getPatientId)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
        Set<Long> doctorIds = new HashSet<>();
        for (LabRequisition req : requisitions) {
            Long doctorId = resolveDoctorId(req, visitMap);
            if (doctorId != null) {
                doctorIds.add(doctorId);
            }
        }
        Map<Long, String> patientNames = loadNames("patient", patientIds, this::batchLoadPatientNames);
        Map<Long, String> doctorNames = loadNames("staff", doctorIds, this::batchLoadStaffNames);

        return requisitions.stream().map(req -> {
            LabRequisitionListItem item = new LabRequisitionListItem();
            item.setId(req.getId());
            item.setVisitId(req.getVisitId());
            item.setPatientId(req.getPatientId());
            item.setDoctorId(req.getDoctorId());
            item.setTechnicianId(req.getTechnicianId());
            item.setStatus(req.getStatus());
            item.setRemark(req.getRemark());
            item.setCreatedAt(req.getCreatedAt());
            item.setSampledAt(req.getSampledAt());
            item.setReportedAt(req.getReportedAt());

            item.setPatientName(patientNames.get(req.getPatientId()));

            Long doctorId = resolveDoctorId(req, visitMap);
            item.setDoctorId(doctorId);
            item.setDoctorName(doctorNames.get(doctorId));
            return item;
        }).toList();
    }

    public LabRequisitionDetail getDetail(Long id) {
        LabRequisition req = requisitionMapper.selectById(id);
        if (req == null) return null;
        List<LabResultItem> items = resultItemMapper.selectList(
                new LambdaQueryWrapper<LabResultItem>()
                        .eq(LabResultItem::getRequisitionId, id));
        return new LabRequisitionDetail(req, items);
    }

    /** R-19: 解析医生 ID:申请未记录时回退就诊单医生(visitMap 已批量加载)。 */
    private Long resolveDoctorId(LabRequisition req, Map<Long, Visit> visitMap) {
        Long doctorId = req.getDoctorId();
        if (doctorId == null && req.getVisitId() != null) {
            Visit v = visitMap.get(req.getVisitId());
            if (v != null) {
                doctorId = v.getDoctorId();
            }
        }
        return doctorId;
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

    @Data
    public static class ResultEntry {
        private Long itemId;
        private String resultValue;
        private String unit;
        private String refRange;
        private String abnormalFlag;
    }

    /**
     * 根据结果值和参考范围自动判定异常方向。
     * 参考范围格式: "120-160" 或 "4.0-10.0"
     * @return NORMAL / HIGH / LOW
     */
    static String autoDetectAbnormalFlag(String resultValue, String refRange) {
        if (resultValue == null || resultValue.isBlank() || refRange == null || refRange.isBlank()) {
            return "NORMAL";
        }
        try {
            double value = Double.parseDouble(resultValue.trim());
            // 支持 "120-160" 和 "120~160" 两种分隔符
            String[] parts = refRange.split("[-~]");
            if (parts.length != 2) return "NORMAL";
            double min = Double.parseDouble(parts[0].trim());
            double max = Double.parseDouble(parts[1].trim());
            if (value > max) return "HIGH";
            if (value < min) return "LOW";
            return "NORMAL";
        } catch (NumberFormatException e) {
            return "NORMAL";
        }
    }
}
