package com.hospital.core.clinical.api;

import java.util.List;

import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.hospital.core.clinical.application.VisitService;
import com.hospital.core.clinical.domain.Charge;
import com.hospital.core.clinical.infrastructure.ChargeMapper;
import com.hospital.core.patient.application.PatientService;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.AllArgsConstructor;
import lombok.Data;

@Tag(name = "收费管理", description = "收费、查询待缴/已缴费用")
@RestController
public class ChargeController {

    private final ChargeMapper chargeMapper;
    private final VisitService visitService;
    private final PatientService patientService;

    public ChargeController(ChargeMapper chargeMapper, VisitService visitService, PatientService patientService) {
        this.chargeMapper = chargeMapper;
        this.visitService = visitService;
        this.patientService = patientService;
    }

    @Operation(summary = "查询待缴费列表")
    @GetMapping("/api/core/charges/unpaid")
    @PreAuthorize("hasAuthority('charge:pay')")
    public ResponseEntity<List<ChargeVO>> unpaid() {
        return ResponseEntity.ok(listByStatus("UNPAID"));
    }

    @Operation(summary = "查询已缴费列表")
    @GetMapping("/api/core/charges/paid")
    @PreAuthorize("hasAuthority('charge:pay')")
    public ResponseEntity<List<ChargeVO>> paid() {
        return ResponseEntity.ok(listByStatus("PAID"));
    }

    private List<ChargeVO> listByStatus(String payStatus) {
        List<Charge> all = chargeMapper.selectList(
                new LambdaQueryWrapper<Charge>()
                        .eq(Charge::getPayStatus, payStatus)
                        .orderByDesc(Charge::getId));
        List<ChargeVO> result = new java.util.ArrayList<>();
        for (Charge c : all) {
            com.hospital.core.clinical.domain.Visit visit = null;
            String patientName = null;
            try {
                visit = visitService.get(c.getVisitId());
                if (visit != null) {
                    patientName = patientService.getName(visit.getPatientId());
                }
            } catch (Exception ignored) {}
            // 待收费列表排除尚未确单(CREATED)的就诊:医生未确单前不可收费
            if ("UNPAID".equals(payStatus) && visit != null && "CREATED".equals(visit.getStatus())) {
                continue;
            }
            result.add(new ChargeVO(c, patientName));
        }
        return result;
    }

    @Operation(summary = "就诊缴费")
    @PostMapping("/api/core/charges/{visitId}/pay")
    @PreAuthorize("hasAuthority('charge:pay')")
    public ResponseEntity<?> pay(@Parameter(description = "就诊ID") @PathVariable Long visitId) {
        visitService.pay(visitId);
        return ResponseEntity.ok().build();
    }

    @Data
    @AllArgsConstructor
    public static class ChargeVO {
        private Charge charge;
        private String patientName;
    }

}
