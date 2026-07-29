package com.hospital.core.patient.api;

import java.util.List;
import java.util.Objects;

import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.hospital.core.patient.application.PatientService;
import com.hospital.core.patient.domain.Patient;
import com.hospital.core.platform.security.CurrentUserResolver;
import com.hospital.core.report.application.ReportPdfGenerator;
import com.hospital.core.report.application.ReportService;
import com.hospital.core.report.domain.Report;
import com.hospital.core.report.infrastructure.FileServiceClient;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

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
    public ResponseEntity<List<Report>> myReports() {
        String username = CurrentUserResolver.resolveUsername();
        Patient patient = patientService.findByUsername(username);
        if (patient == null) return ResponseEntity.notFound().build();
        return ResponseEntity.ok(reportService.listByPatient(patient.getId()));
    }

    @Operation(summary = "下载报告PDF")
    @GetMapping("/api/patient/reports/{id}/download")
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
    public ResponseEntity<PatientRegisterResponse> register(@Valid @RequestBody PatientRegisterRequest request) {
        return ResponseEntity.ok(patientService.register(request.toDomain()));
    }

    @Operation(summary = "查询患者列表")
    @GetMapping("/api/patient")
    public ResponseEntity<List<Patient>> list() {
        return ResponseEntity.ok(patientService.list());
    }

    @Operation(summary = "获取当前患者信息")
    @GetMapping("/api/patient/me")
    public ResponseEntity<Patient> me() {
        String username = CurrentUserResolver.resolveUsername();
        Patient patient = patientService.findByUsername(username);
        if (patient == null) return ResponseEntity.notFound().build();
        return ResponseEntity.ok(patient);
    }

    @Operation(summary = "按关键字搜索患者")
    @GetMapping("/api/patient/search")
    public ResponseEntity<List<Patient>> search(@Parameter(description = "搜索关键字") @RequestParam String keyword) {
        return ResponseEntity.ok(patientService.search(keyword));
    }

    @Operation(summary = "获取患者详情")
    @GetMapping("/api/patient/{id}")
    public ResponseEntity<Patient> get(@Parameter(description = "患者ID") @PathVariable Long id) {
        return ResponseEntity.ok(patientService.get(id));
    }

    @Operation(summary = "更新患者信息")
    @PutMapping("/api/patient/{id}")
    public ResponseEntity<Patient> update(@Parameter(description = "患者ID") @PathVariable Long id, @RequestBody Patient patient) {
        return ResponseEntity.ok(patientService.update(id, patient));
    }
}
