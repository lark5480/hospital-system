package com.hospital.core.report.api;

import java.util.List;
import java.util.Map;

import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.hospital.core.org.application.StaffService;
import com.hospital.core.patient.application.PatientService;
import com.hospital.core.platform.annotation.AuditLog;
import com.hospital.core.platform.security.CurrentUserResolver;
import com.hospital.core.report.application.ReportService;
import com.hospital.core.report.domain.Report;
import com.hospital.core.report.domain.ReportDetail;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

/**
 * 报告管理接口。
 *
 * <p>R-09 收口说明:
 * <ul>
 *   <li>create / publish 原先无鉴权,且 doctorId 由前端直传 —— 任意已登录账号可冒名医生发布报告。
 *       现 create 需 visit:entry、publish 需 visit:audit / system:admin,doctorId 一律由服务端解析。</li>
 *   <li>读接口(get / list*)同样补读权限,并对以 patientId 为入参的接口做归属校验。</li>
 * </ul>
 */
@Tag(name = "报告管理", description = "医疗报告的创建、查询、发布")
@RestController
@RequiredArgsConstructor
public class ReportController {

    private final ReportService reportService;
    // R-08/R-09: 归属校验与"当前医生"解析依赖
    private final StaffService staffService;
    private final PatientService patientService;

    @Operation(summary = "按就诊ID查询报告列表")
    // R-08: 读接口对患者(patient:booking)开放 —— 患者可自助查看自己就诊下的报告;
    //       同一就诊的报告归属同一患者,故取首条报告的 patientId 做归属校验,越权直接 403。
    @PreAuthorize("hasAnyAuthority('visit:entry','visit:audit','system:admin','patient:booking')")
    @GetMapping("/api/reports")
    public ResponseEntity<List<Report>> listByVisit(@Parameter(description = "就诊单ID") @RequestParam Long visitId) {
        List<Report> reports = reportService.listByVisit(visitId);
        // R-08: 空列表直接放行(无数据可泄);非空时按首条报告归属校验
        if (!reports.isEmpty() && !canReadPatient(reports.get(0).getPatientId())) {
            return ResponseEntity.status(403).build();
        }
        return ResponseEntity.ok(reports);
    }

    @Operation(summary = "按报告类型查询报告详细列表")
    // R-08: 全院维度的报告列表,无归属维度可校验 —— 患者角色一律不放行,保持医护 / 管理员专用
    @PreAuthorize("hasAnyAuthority('visit:entry','visit:audit','system:admin')")
    @GetMapping("/api/reports/type/{type}")
    public ResponseEntity<List<ReportDetail>> listByType(@Parameter(description = "报告类型") @PathVariable String type) {
        return ResponseEntity.ok(reportService.listByTypeDetail(type));
    }

    @Operation(summary = "查询全部报告详细列表")
    // R-08: 全院全量报告(PHI),无归属维度可校验 —— 患者角色一律不放行,保持医护 / 管理员专用
    @PreAuthorize("hasAnyAuthority('visit:entry','visit:audit','system:admin')")
    @GetMapping("/api/reports/list")
    public ResponseEntity<List<ReportDetail>> listAll() {
        return ResponseEntity.ok(reportService.listAllDetail());
    }

    @Operation(summary = "获取报告详情")
    // R-08: 读接口对患者(patient:booking)开放,仍保留 IDOR 归属校验(报告 ID 由前端直传)
    @PreAuthorize("hasAnyAuthority('visit:entry','visit:audit','system:admin','patient:booking')")
    @GetMapping("/api/reports/{id}")
    public ResponseEntity<ReportDetail> get(@Parameter(description = "报告ID") @PathVariable Long id) {
        ReportDetail r = reportService.getDetail(id);
        if (r == null || r.getId() == null) return ResponseEntity.notFound().build();
        if (!canReadPatient(r.getPatientId())) {
            return ResponseEntity.status(403).build();
        }
        return ResponseEntity.ok(r);
    }

    @Operation(summary = "创建报告")
    @AuditLog(action = "CREATE_REPORT")
    // R-09: 创建报告属医疗文书写入,仅接诊医护可操作
    @PreAuthorize("hasAuthority('visit:entry')")
    @PostMapping("/api/reports")
    public ResponseEntity<Object> create(@Valid @RequestBody CreateReportRequest req) {
        // R-09: doctorId 不再由前端传入,改由服务端按 JWT 主体解析真实医生,解析不到直接 400
        Long doctorId = resolveCurrentDoctorId();
        if (doctorId == null) {
            return ResponseEntity.badRequest().body(Map.of(
                    "error", "无法解析当前医生",
                    "message", "当前登录账号未绑定员工档案,无法创建报告"));
        }
        return ResponseEntity.ok(reportService.create(
                req.getVisitId(), req.getType(), req.getTitle(), req.getContent(), doctorId));
    }

    @Operation(summary = "发布报告")
    @AuditLog(action = "PUBLISH_REPORT")
    // R-09: 发布报告是对外的医疗结论,需审核权限或管理员
    @PreAuthorize("hasAnyAuthority('visit:audit','system:admin')")
    @PostMapping("/api/reports/{id}/publish")
    public ResponseEntity<Report> publish(@Parameter(description = "报告ID") @PathVariable Long id) {
        return ResponseEntity.ok(reportService.publish(id));
    }

    @Operation(summary = "按患者ID查询报告详细列表")
    // R-08: 读接口对患者(patient:booking)开放;入参 patientId 一律经 resolveReadablePatientId 覆盖,
    //       患者角色被强制改写成本人 patientId,不存在"改 id 看他人报告"的绕行路径
    @PreAuthorize("hasAnyAuthority('visit:entry','visit:audit','system:admin','patient:booking')")
    @GetMapping("/api/reports/patient/{patientId}")
    public ResponseEntity<List<ReportDetail>> listByPatient(@Parameter(description = "患者ID") @PathVariable Long patientId) {
        Long effectivePatientId = resolveReadablePatientId(patientId);
        if (effectivePatientId == null) {
            return ResponseEntity.status(403).build();
        }
        return ResponseEntity.ok(reportService.listDetailByPatient(effectivePatientId));
    }

    /** R-09: 解析当前登录员工(医生)ID;未绑定员工档案返回 null。 */
    private Long resolveCurrentDoctorId() {
        String phone = CurrentUserResolver.resolveUsername();
        if (phone == null) return null;
        var staff = staffService.findByPhone(phone);
        return staff == null ? null : staff.getId();
    }

    /**
     * R-08: 归属校验 —— 以 patientId 为入参的读接口统一走这里。
     * 员工(医护 / 管理员)允许按入参查询;患者角色强制覆盖为本人 patientId,解析不到则拒绝。
     *
     * @return 允许查询的患者 ID;null 表示无权(调用方返回 403)
     */
    private Long resolveReadablePatientId(Long requestedPatientId) {
        if (isStaff()) {
            return requestedPatientId;
        }
        return patientService.currentPatientId();
    }

    /** R-08: 当前主体是否有权读取指定患者的数据(患者角色仅可看自己)。 */
    private boolean canReadPatient(Long targetPatientId) {
        if (isStaff()) {
            return true;
        }
        Long own = patientService.currentPatientId();
        return own != null && own.equals(targetPatientId);
    }

    /** R-08: 当前登录主体是否为员工(医护 / 管理员);患者账号返回 false。 */
    private boolean isStaff() {
        String phone = CurrentUserResolver.resolveUsername();
        return phone != null && staffService.findByPhone(phone) != null;
    }
}
