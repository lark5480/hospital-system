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

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@RestController
@RequiredArgsConstructor
@Slf4j
public class PatientController {

    private final PatientService patientService;
    private final ReportService reportService;
    private final ReportPdfGenerator reportPdfGenerator;
    private final FileServiceClient fileServiceClient;

    @GetMapping("/api/patient/reports")
    public ResponseEntity<List<Report>> myReports() {
        String username = CurrentUserResolver.resolveUsername();
        Patient patient = patientService.findByUsername(username);
        if (patient == null) return ResponseEntity.notFound().build();
        return ResponseEntity.ok(reportService.listByPatient(patient.getId()));
    }

    @GetMapping("/api/patient/reports/{id}/download")
    public ResponseEntity<byte[]> downloadReport(@PathVariable Long id) {
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

    @PostMapping("/api/patient/register")
    public ResponseEntity<PatientRegisterResponse> register(@RequestBody PatientRegisterRequest request) {
        return ResponseEntity.ok(patientService.register(request.toDomain()));
    }

    @GetMapping("/api/patient")
    public ResponseEntity<List<Patient>> list() {
        return ResponseEntity.ok(patientService.list());
    }

    @GetMapping("/api/patient/me")
    public ResponseEntity<Patient> me() {
        String username = CurrentUserResolver.resolveUsername();
        Patient patient = patientService.findByUsername(username);
        if (patient == null) return ResponseEntity.notFound().build();
        return ResponseEntity.ok(patient);
    }

    @GetMapping("/api/patient/search")
    public ResponseEntity<List<Patient>> search(@RequestParam String keyword) {
        return ResponseEntity.ok(patientService.search(keyword));
    }

    @GetMapping("/api/patient/{id}")
    public ResponseEntity<Patient> get(@PathVariable Long id) {
        return ResponseEntity.ok(patientService.get(id));
    }

    @PutMapping("/api/patient/{id}")
    public ResponseEntity<Patient> update(@PathVariable Long id, @RequestBody Patient patient) {
        return ResponseEntity.ok(patientService.update(id, patient));
    }
}
