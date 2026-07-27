package com.hospital.core.report.application;

import java.time.LocalDateTime;
import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.hospital.core.clinical.domain.Visit;
import com.hospital.core.clinical.infrastructure.VisitMapper;
import com.hospital.core.org.application.DepartmentService;
import com.hospital.core.org.application.StaffService;
import com.hospital.core.patient.application.PatientService;
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
        return reports.stream().map(this::toDetail).toList();
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

    /** 实体 → 读模型,解析患者姓名/性别/电话 + 就诊单医生/科室/主诉。 */
    private ReportDetail toDetail(Report r) {
        ReportDetail d = new ReportDetail();
        if (r == null) return d;
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
        if (r.getPatientId() != null) {
            var p = patientService.get(r.getPatientId());
            if (p != null) {
                d.setPatientName(p.getName());
                d.setPatientGender(p.getGender());
                d.setPatientPhone(p.getPhone());
            }
        }

        // 就诊单信息
        if (r.getVisitId() != null) {
            Visit v = visitMapper.selectById(r.getVisitId());
            if (v != null) {
                d.setVisitChiefComplaint(v.getChiefComplaint());
                if (v.getDoctorId() != null) {
                    var staff = staffService.get(v.getDoctorId());
                    d.setDoctorName(staff == null ? null : staff.getName());
                }
                if (v.getDeptId() != null) {
                    var dept = departmentService.get(v.getDeptId());
                    d.setDeptName(dept == null ? null : dept.getName());
                }
            }
        }
        return d;
    }

    private List<ReportDetail> toDetailList(List<Report> reports) {
        return reports.stream().map(this::toDetail).toList();
    }

    /** 全部报告(解析关联名称),倒序。 */
    public List<ReportDetail> listAllDetail() {
        return toDetailList(listAll());
    }

    /** 按类型查报告(解析关联名称),倒序。 */
    public List<ReportDetail> listByTypeDetail(String type) {
        return toDetailList(listByType(type));
    }
}
