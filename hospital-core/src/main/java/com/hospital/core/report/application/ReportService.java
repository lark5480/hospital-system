package com.hospital.core.report.application;

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
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.hospital.core.clinical.domain.Visit;
import com.hospital.core.clinical.infrastructure.VisitMapper;
import com.hospital.core.org.application.DepartmentService;
import com.hospital.core.org.application.StaffService;
import com.hospital.core.org.domain.Department;
import com.hospital.core.org.domain.Staff;
import com.hospital.core.org.infrastructure.DepartmentMapper;
import com.hospital.core.org.infrastructure.StaffMapper;
import com.hospital.core.patient.application.PatientService;
import com.hospital.core.patient.domain.Patient;
import com.hospital.core.platform.support.NameCache;
import com.hospital.core.report.domain.Report;
import com.hospital.core.report.domain.ReportDetail;
import com.hospital.core.report.infrastructure.ReportMapper;

import lombok.RequiredArgsConstructor;

/**
 * 报告应用服务:报告创建/发布/查询。
 * 报告作为就诊各环节(临床/检验/检查)的统一输出载体。
 */
@Service
@RequiredArgsConstructor
public class ReportService {

    private final ReportMapper reportMapper;
    private final VisitMapper visitMapper;
    private final PatientService patientService;
    private final StaffService staffService;
    private final DepartmentService departmentService;

    // R-19: 名称解析缓存 + 员工/科室批量查询(字段注入,不改构造签名,兼容既有单测直接 new)
    @Autowired(required = false)
    private NameCache nameCache;
    @Autowired(required = false)
    private StaffMapper staffMapper;
    @Autowired(required = false)
    private DepartmentMapper departmentMapper;

    @Transactional
    public Report create(Long visitId, String type, String title, String content, Long doctorId) {
        return create(visitId, null, type, title, content, doctorId, "DRAFT");
    }

    /** 创建并直接发布(用于发药/检验等自动出报告场景)。 */
    @Transactional
    public Report createAndPublish(Long visitId, Long patientId, String type, String title, String content, Long doctorId) {
        return create(visitId, patientId, type, title, content, doctorId, "PUBLISHED");
    }

    private Report create(Long visitId, Long patientId, String type, String title, String content, Long doctorId, String status) {
        Report r = new Report();
        r.setVisitId(visitId);
        r.setPatientId(patientId);
        r.setType(type);
        r.setTitle(title);
        r.setContent(content);
        r.setDoctorId(doctorId);
        r.setStatus(status);
        r.setCreatedAt(LocalDateTime.now());
        if ("PUBLISHED".equals(status)) {
            r.setPublishedAt(LocalDateTime.now());
        }
        reportMapper.insert(r);
        return r;
    }

    /** C端报告:创建并直接发布(供排队检查全部完成后自动出报告用),关联预约ID以便溯源/防重。 */
    @Transactional
    public Report createPatientReport(Long patientId, Long appointmentId, String title, String content) {
        Report r = new Report();
        r.setPatientId(patientId);
        r.setAppointmentId(appointmentId);
        r.setType("EXAM");
        r.setTitle(title);
        r.setContent(content);
        r.setDoctorId(1L);
        r.setStatus("PUBLISHED");
        r.setCreatedAt(LocalDateTime.now());
        r.setPublishedAt(LocalDateTime.now());
        reportMapper.insert(r);
        return r;
    }

    /** 某体检预约是否已出具报告(requeue 护栏:以真实报告记录为准,不靠任务状态推断)。 */
    public boolean existsForAppointment(Long appointmentId) {
        return reportMapper.selectCount(
                new LambdaQueryWrapper<Report>().eq(Report::getAppointmentId, appointmentId)) > 0;
    }

    @Transactional
    public Report publish(Long id) {
        Report r = reportMapper.selectById(id);
        if (r == null) throw new IllegalArgumentException("报告不存在:" + id);
        r.setStatus("PUBLISHED");
        r.setPublishedAt(LocalDateTime.now());
        reportMapper.updateById(r);
        return r;
    }

    /** C端:按患者ID查询已发布的报告(实体)。 */
    public List<Report> listByPatient(Long patientId) {
        return reportMapper.selectList(
                new LambdaQueryWrapper<Report>()
                        .eq(Report::getPatientId, patientId)
                        .eq(Report::getStatus, "PUBLISHED")
                        .orderByDesc(Report::getPublishedAt));
    }

    /** 按患者ID查询已发布的检验/检查报告(解析关联名称),倒序。供新建就诊时查阅历史。 */
    public List<ReportDetail> listDetailByPatient(Long patientId) {
        List<Report> reports = reportMapper.selectList(
                new LambdaQueryWrapper<Report>()
                        .eq(Report::getPatientId, patientId)
                        .eq(Report::getStatus, "PUBLISHED")
                        .in(Report::getType, "LAB", "EXAM")
                        .orderByDesc(Report::getPublishedAt));
        // R-19: 批量解析关联名称(原逐条回查 patient + visit + staff + department,形成 N+1)
        return toDetails(reports);
    }

    public List<Report> listByVisit(Long visitId) {
        return reportMapper.selectList(
                new LambdaQueryWrapper<Report>()
                        .eq(Report::getVisitId, visitId)
                        .orderByDesc(Report::getCreatedAt));
    }

    /** 全部报告(按创建时间倒序)。 */
    public List<Report> listAll() {
        return reportMapper.selectList(
                new LambdaQueryWrapper<Report>()
                        .orderByDesc(Report::getCreatedAt));
    }

    public List<Report> listByType(String type) {
        return reportMapper.selectList(
                new LambdaQueryWrapper<Report>()
                        .eq(Report::getType, type)
                        .orderByDesc(Report::getCreatedAt));
    }

    /** 报告详情(解析关联名称)。 */
    public ReportDetail getDetail(Long id) {
        return toDetail(reportMapper.selectById(id));
    }

    /**
     * 单条:实体 → 读模型,解析患者姓名/性别/电话 + 就诊单医生/科室/主诉。
     * 供详情类接口使用(逐条回源,nameCache 命中时员工/科室名零查询)。
     */
    private ReportDetail toDetail(Report r) {
        if (r == null) return new ReportDetail();
        Patient p = r.getPatientId() != null ? patientService.get(r.getPatientId()) : null;
        Visit v = r.getVisitId() != null ? visitMapper.selectById(r.getVisitId()) : null;
        String doctorName = null;
        String deptName = null;
        if (v != null) {
            if (v.getDoctorId() != null) {
                doctorName = loadName("staff", v.getDoctorId(), id -> {
                    Staff s = staffService.get(id);
                    return s == null ? null : s.getName();
                });
            }
            if (v.getDeptId() != null) {
                deptName = loadName("dept", v.getDeptId(), id -> {
                    Department d = departmentService.get(id);
                    return d == null ? null : d.getName();
                });
            }
        }
        return build(r, p, v, doctorName, deptName);
    }

    /**
     * R-19: 批量:实体列表 → 读模型列表,常量级 SQL 解析全部关联名称。
     * 原逐条回查 patient/visit/staff/department(N 条报告 4N 次 SQL);
     * 现改为 1 次批量查患者 + 1 次批量查就诊单 + NameCache 批量解析医生/科室姓名。出参与字段不变。
     */
    private List<ReportDetail> toDetails(List<Report> reports) {
        if (reports == null || reports.isEmpty()) {
            return List.of();
        }

        // 1 次批量查患者(patientService.listByIds 底层一条 IN 查询)
        Set<Long> patientIds = reports.stream()
                .map(Report::getPatientId)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
        Map<Long, Patient> patientMap = new HashMap<>();
        if (!patientIds.isEmpty()) {
            for (Patient p : patientService.listByIds(new ArrayList<>(patientIds))) {
                if (p != null && p.getId() != null) {
                    patientMap.put(p.getId(), p);
                }
            }
        }

        // 1 次批量查就诊单(selectBatchIds 底层一条 IN 查询)
        Set<Long> visitIds = reports.stream()
                .map(Report::getVisitId)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
        Map<Long, Visit> visitMap = visitIds.isEmpty() ? Map.of()
                : visitMapper.selectBatchIds(visitIds).stream()
                        .filter(Objects::nonNull)
                        .collect(Collectors.toMap(Visit::getId, Function.identity(), (a, b) -> a));

        // 批量解析医生/科室姓名(仅查缓存缺失的 id)
        Set<Long> doctorIds = new HashSet<>();
        Set<Long> deptIds = new HashSet<>();
        for (Visit v : visitMap.values()) {
            if (v.getDoctorId() != null) {
                doctorIds.add(v.getDoctorId());
            }
            if (v.getDeptId() != null) {
                deptIds.add(v.getDeptId());
            }
        }
        Map<Long, String> doctorNames = loadNames("staff", doctorIds, this::batchLoadStaffNames);
        Map<Long, String> deptNames = loadNames("dept", deptIds, this::batchLoadDeptNames);

        return reports.stream().map(r -> {
            Visit v = r.getVisitId() == null ? null : visitMap.get(r.getVisitId());
            String doctorName = v == null ? null : doctorNames.get(v.getDoctorId());
            String deptName = v == null ? null : deptNames.get(v.getDeptId());
            return build(r, patientMap.get(r.getPatientId()), v, doctorName, deptName);
        }).toList();
    }

    /** 把已解析好的患者/就诊单/名称填入读模型(字段与顺序与原 toDetail 完全一致)。 */
    private ReportDetail build(Report r, Patient p, Visit v, String doctorName, String deptName) {
        ReportDetail d = new ReportDetail();
        if (r == null) {
            return d;
        }
        d.setId(r.getId());
        d.setVisitId(r.getVisitId());
        d.setPatientId(r.getPatientId());
        d.setType(r.getType());
        d.setTitle(r.getTitle());
        d.setContent(r.getContent());
        d.setDoctorId(r.getDoctorId());
        d.setStatus(r.getStatus());
        d.setCreatedAt(r.getCreatedAt());
        d.setPublishedAt(r.getPublishedAt());
        d.setFileId(r.getFileId());
        d.setPdfStatus(r.getPdfStatus());

        // 患者信息
        if (p != null) {
            d.setPatientName(p.getName());
            d.setPatientGender(p.getGender());
            d.setPatientPhone(p.getPhone());
        }
        // 就诊单信息
        if (v != null) {
            d.setVisitChiefComplaint(v.getChiefComplaint());
            d.setDoctorName(doctorName);
            d.setDeptName(deptName);
        }
        return d;
    }

    /** 全部报告(解析关联名称),倒序。 */
    public List<ReportDetail> listAllDetail() {
        return toDetails(listAll());
    }

    /** 按类型查报告(解析关联名称),倒序。 */
    public List<ReportDetail> listByTypeDetail(String type) {
        return toDetails(listByType(type));
    }

    /** R-19: 单个名称解析(优先 NameCache,无上下文时直接回源;异常吞掉返回 null)。 */
    private String loadName(String type, Long id, Function<Long, String> loader) {
        if (id == null) {
            return null;
        }
        try {
            if (nameCache != null) {
                return nameCache.getOrLoad(type, id, loader);
            }
            return loader.apply(id);
        } catch (Exception e) {
            return null;
        }
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

    /** R-19: 批量解析员工姓名(StaffMapper.selectBatchIds 底层一条 IN 查询)。 */
    private Map<Long, String> batchLoadStaffNames(Collection<Long> ids) {
        Map<Long, String> result = new HashMap<>();
        if (staffMapper == null) {
            return result;
        }
        for (Staff s : staffMapper.selectBatchIds(new ArrayList<>(ids))) {
            if (s != null && s.getId() != null && s.getName() != null) {
                result.put(s.getId(), s.getName());
            }
        }
        return result;
    }

    /** R-19: 批量解析科室名称(DepartmentMapper.selectBatchIds 底层一条 IN 查询)。 */
    private Map<Long, String> batchLoadDeptNames(Collection<Long> ids) {
        Map<Long, String> result = new HashMap<>();
        if (departmentMapper == null) {
            return result;
        }
        for (Department d : departmentMapper.selectBatchIds(new ArrayList<>(ids))) {
            if (d != null && d.getId() != null && d.getName() != null) {
                result.put(d.getId(), d.getName());
            }
        }
        return result;
    }
}
