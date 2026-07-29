package com.hospital.core.report.api;

import java.util.List;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.hospital.core.platform.annotation.AuditLog;
import com.hospital.core.report.application.ReportService;
import com.hospital.core.report.domain.Report;
import com.hospital.core.report.domain.ReportDetail;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

@Tag(name = "报告管理", description = "医疗报告的创建、查询、发布")
@RestController
@RequiredArgsConstructor
public class ReportController {

    private final ReportService reportService;

    @Operation(summary = "按就诊ID查询报告列表")
    @GetMapping("/api/reports")
    public ResponseEntity<List<Report>> listByVisit(@Parameter(description = "就诊单ID") @RequestParam Long visitId) {
        return ResponseEntity.ok(reportService.listByVisit(visitId));
    }

    @Operation(summary = "按报告类型查询报告详细列表")
    @GetMapping("/api/reports/type/{type}")
    public ResponseEntity<List<ReportDetail>> listByType(@Parameter(description = "报告类型") @PathVariable String type) {
        return ResponseEntity.ok(reportService.listByTypeDetail(type));
    }

    @Operation(summary = "查询全部报告详细列表")
    @GetMapping("/api/reports/list")
    public ResponseEntity<List<ReportDetail>> listAll() {
        return ResponseEntity.ok(reportService.listAllDetail());
    }

    @Operation(summary = "获取报告详情")
    @GetMapping("/api/reports/{id}")
    public ResponseEntity<ReportDetail> get(@Parameter(description = "报告ID") @PathVariable Long id) {
        ReportDetail r = reportService.getDetail(id);
        if (r == null || r.getId() == null) return ResponseEntity.notFound().build();
        return ResponseEntity.ok(r);
    }

    @Operation(summary = "创建报告")
    @AuditLog(action = "CREATE_REPORT")
    @PostMapping("/api/reports")
    public ResponseEntity<Report> create(@Valid @RequestBody CreateReportRequest req) {
        return ResponseEntity.ok(reportService.create(
                req.getVisitId(), req.getType(), req.getTitle(),
                req.getContent(), req.getDoctorId()));
    }

    @Operation(summary = "发布报告")
    @AuditLog(action = "PUBLISH_REPORT")
    @PostMapping("/api/reports/{id}/publish")
    public ResponseEntity<Report> publish(@Parameter(description = "报告ID") @PathVariable Long id) {
        return ResponseEntity.ok(reportService.publish(id));
    }

    @Operation(summary = "按患者ID查询报告详细列表")
    @GetMapping("/api/reports/patient/{patientId}")
    public ResponseEntity<List<ReportDetail>> listByPatient(@Parameter(description = "患者ID") @PathVariable Long patientId) {
        return ResponseEntity.ok(reportService.listDetailByPatient(patientId));
    }
}
