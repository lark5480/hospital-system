package com.hospital.core.clinical.api;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.hospital.core.clinical.application.VisitService;
import com.hospital.core.clinical.domain.Charge;
import com.hospital.core.clinical.infrastructure.ChargeMapper;
import com.hospital.core.patient.application.PatientService;
import lombok.AllArgsConstructor;
import lombok.Data;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

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

    @GetMapping("/api/core/charges/unpaid")
    @PreAuthorize("hasAuthority('charge:pay')")
    public ResponseEntity<List<ChargeVO>> unpaid() {
        return ResponseEntity.ok(listByStatus("UNPAID"));
    }

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

    @PostMapping("/api/core/charges/{visitId}/pay")
    @PreAuthorize("hasAuthority('charge:pay')")
    public ResponseEntity<?> pay(@PathVariable Long visitId) {
        visitService.pay(visitId);
        return ResponseEntity.ok().build();
    }

    @Data
    @AllArgsConstructor
    public static class ChargeVO {
        private Charge charge;
        private String patientName;
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, String>> handleNotFound(IllegalArgumentException ex) {
        return ResponseEntity.status(404).body(Map.of("message", ex.getMessage()));
    }

    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<Map<String, String>> handleConflict(IllegalStateException ex) {
        return ResponseEntity.status(409).body(Map.of("message", ex.getMessage()));
    }
}
