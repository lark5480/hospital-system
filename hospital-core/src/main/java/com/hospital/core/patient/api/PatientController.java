package com.hospital.core.patient.api;

import java.util.List;
import java.util.Objects;

import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.hospital.core.patient.application.PatientService;
import com.hospital.core.patient.domain.Patient;
import com.hospital.core.platform.annotation.AuditLog;
import com.hospital.core.platform.security.CurrentUserResolver;
import com.hospital.core.report.application.ReportPdfGenerator;
import com.hospital.core.report.application.ReportService;
import com.hospital.core.report.domain.Report;
import com.hospital.core.report.infrastructure.FileServiceClient;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 患者管理接口。
 *
 * <p>R-07 收口说明:本类原先全类无 @PreAuthorize,任意已登录账号(含仅 patient:booking 的患者)
 * 都能 <code>GET /api/patient</code> 拉走全院患者身份证 / 手机号,并 <code>PUT /api/patient/{id}</code>
 * 改他人档案。现按读写分离补鉴权,并对出参身份证脱敏。
 */
@Tag(name = "患者管理", description = "患者信息查询、注册与报告下载")
@RestController
@RequiredArgsConstructor
@Slf4j
public class PatientController {

    private final PatientService patientService;
    private final ReportService reportService;
    private final ReportPdfGenerator reportPdfGenerator;
    private final FileServiceClient fileServiceClient;

    @Operation(summary = "查询当前患者报告列表")
    @GetMapping("/api/patient/reports")
    // R-07: C 端自助接口 —— 患者本人(patient:booking)或医护 / 管理员可读,未认证由 SecurityConfig 拦 401
    @PreAuthorize("hasAnyAuthority('visit:entry','system:admin','patient:booking')")
    public ResponseEntity<List<Report>> myReports() {
        String username = CurrentUserResolver.resolveUsername();
        Patient patient = patientService.findByUsername(username);
        if (patient == null) return ResponseEntity.notFound().build();
        return ResponseEntity.ok(reportService.listByPatient(patient.getId()));
    }

    @Operation(summary = "下载报告PDF")
    @GetMapping("/api/patient/reports/{id}/download")
    // R-07: C 端自助接口 —— 患者本人(patient:booking)或医护 / 管理员可读;下载前仍做归属校验
    @PreAuthorize("hasAnyAuthority('visit:entry','system:admin','patient:booking')")
    public ResponseEntity<byte[]> downloadReport(@Parameter(description = "报告ID") @PathVariable Long id) {
        String username = CurrentUserResolver.resolveUsername();
        Patient patient = patientService.findByUsername(username);
        if (patient == null) return ResponseEntity.notFound().build();

        var report = reportService.getDetail(id);
        if (report == null || report.getId() == null) return ResponseEntity.notFound().build();
        if (!Objects.equals(report.getPatientId(), patient.getId())) {
            return ResponseEntity.status(403).build();
        }
        log.info("[download] 报告 {} 归属患者 {} 校验通过, fileId={}, pdfStatus={}", id, patient.getId(), report.getFileId(), report.getPdfStatus());

        if (report.getFileId() == null || "FAILED".equals(report.getPdfStatus())) {
            reportPdfGenerator.generate(report.getId(), patient.getId(), report.getTitle(), report.getContent());
            report = reportService.getDetail(id);
        }
        if (report.getFileId() == null) {
            return ResponseEntity.status(409).body(null);
        }

        try {
            byte[] pdf = fileServiceClient.download(report.getFileId(), patient.getId());
            return ResponseEntity.ok()
                    .contentType(MediaType.APPLICATION_PDF)
                    .header(HttpHeaders.CONTENT_DISPOSITION,
                            "attachment; filename=\"report-" + id + ".pdf\"")
                    .body(pdf);
        } catch (IllegalStateException ex) {
            log.warn("[download] 报告 {} 文件对象 {} 取回失败,尝试重新生成: {}", id, report.getFileId(), ex.getMessage());
            reportPdfGenerator.generate(report.getId(), patient.getId(), report.getTitle(), report.getContent());
            report = reportService.getDetail(id);
            if (report.getFileId() == null) {
                return ResponseEntity.status(409).body(null);
            }
            try {
                byte[] pdf = fileServiceClient.download(report.getFileId(), patient.getId());
                return ResponseEntity.ok()
                        .contentType(MediaType.APPLICATION_PDF)
                        .header(HttpHeaders.CONTENT_DISPOSITION,
                                "attachment; filename=\"report-" + id + ".pdf\"")
                        .body(pdf);
            } catch (IllegalStateException e2) {
                return ResponseEntity.status(404).body(null);
            }
        }
    }

    @Operation(summary = "患者注册")
    @PostMapping("/api/patient/register")
    // R-07: 建档属于写操作 —— 医护(visit:entry)、管理员或患者本人自助建档
    @PreAuthorize("hasAnyAuthority('visit:entry','system:admin','patient:booking')")
    public ResponseEntity<PatientRegisterResponse> register(@Valid @RequestBody PatientRegisterRequest request) {
        return ResponseEntity.ok(patientService.register(request.toDomain()));
    }

    @Operation(summary = "分页查询患者列表")
    @GetMapping("/api/patient")
    // R-07: 患者列表含身份证 / 手机号等 PII,仅接诊 / 管理员可读
    @PreAuthorize("hasAnyAuthority('visit:entry','system:admin')")
    public ResponseEntity<List<Patient>> list(
            @Parameter(description = "页码,从 1 开始") @RequestParam(defaultValue = "1") int pageNum,
            @Parameter(description = "每页条数,默认 200,上限 500") @RequestParam(defaultValue = "200") int pageSize) {
        // R-07: 分页下推到 SQL(LIMIT/OFFSET),不再 selectList(null) 全量后内存分页;
        //      出参身份证统一脱敏,避免低权限账号批量拉取 PII
        return ResponseEntity.ok(patientService.maskIdCardList(patientService.list(pageNum, pageSize)));
    }

    /**
     * R-07: 患者姓名投影(仅 id + name),供门诊大屏 / 下拉框使用,替代全量拉取患者列表。
     * 不返回身份证、手机号等任何 PII。
     */
    @Operation(summary = "按ID批量查询患者姓名(仅返回 id + name)")
    @GetMapping("/api/patient/names")
    @PreAuthorize("hasAnyAuthority('visit:entry','system:admin')")
    public ResponseEntity<List<PatientNameView>> names(
            @Parameter(description = "患者ID列表,逗号分隔,如 1,2,3") @RequestParam(required = false) String ids) {
        List<Long> idList = PatientService.parseIds(ids);
        if (idList.isEmpty()) {
            return ResponseEntity.ok(List.of());
        }
        return ResponseEntity.ok(patientService.listByIds(idList).stream()
                .filter(Objects::nonNull)
                .map(p -> new PatientNameView(p.getId(), p.getName()))
                .toList());
    }

    @Operation(summary = "获取当前患者信息")
    @GetMapping("/api/patient/me")
    // R-07: 本人档案 —— 患者本人(patient:booking)或医护 / 管理员可读
    @PreAuthorize("hasAnyAuthority('visit:entry','system:admin','patient:booking')")
    public ResponseEntity<Patient> me() {
        String username = CurrentUserResolver.resolveUsername();
        Patient patient = patientService.findByUsername(username);
        if (patient == null) return ResponseEntity.notFound().build();
        return ResponseEntity.ok(patient);
    }

    @Operation(summary = "按关键字搜索患者")
    @GetMapping("/api/patient/search")
    // R-07: 搜索同样会返回身份证等 PII,仅接诊 / 管理员可用
    @PreAuthorize("hasAnyAuthority('visit:entry','system:admin')")
    public ResponseEntity<List<Patient>> search(@Parameter(description = "搜索关键字") @RequestParam String keyword) {
        return ResponseEntity.ok(patientService.maskIdCardList(patientService.search(keyword)));
    }

    @Operation(summary = "获取患者详情")
    @GetMapping("/api/patient/{id}")
    @PreAuthorize("hasAnyAuthority('visit:entry','system:admin')")
    public ResponseEntity<Patient> get(@Parameter(description = "患者ID") @PathVariable Long id) {
        // R-07: 出参身份证脱敏
        return ResponseEntity.ok(patientService.maskIdCard(patientService.get(id)));
    }

    @Operation(summary = "更新患者信息")
    @PutMapping("/api/patient/{id}")
    // R-07: 改档属写操作,接诊医生(visit:entry)与管理员可改 —— 医生端 PatientsView.vue 有编辑入口;
    //       但姓名 / 身份证 / 手机号属身份标识字段,仅 system:admin 可改(见 PatientService#update),
    //       非管理员提交变更返回 403;username 任何角色都不得变更。
    // R-07: 管理员改手机号会同步 sys_user.phone(即登录账号),故本接口必须留审计。
    @PreAuthorize("hasAnyAuthority('visit:entry','system:admin')")
    @AuditLog(action = "UPDATE_PATIENT")
    public ResponseEntity<Patient> update(@Parameter(description = "患者ID") @PathVariable Long id, @RequestBody Patient patient) {
        return ResponseEntity.ok(patientService.update(id, patient));
    }

    /** R-07: 患者姓名投影,只含 id 与 name,供门诊大屏等对外的非敏感场景使用。 */
    @Data
    public static class PatientNameView {
        private Long id;
        private String name;

        public PatientNameView() {
        }

        public PatientNameView(Long id, String name) {
            this.id = id;
            this.name = name;
        }
    }
}
