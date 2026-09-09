package com.hospital.core.fhir.api;

import java.util.List;
import java.util.Map;

import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.hospital.core.clinical.application.VisitService;
import com.hospital.core.clinical.domain.Visit;
import com.hospital.core.fhir.converter.EncounterConverter;
import com.hospital.core.fhir.domain.FhirEncounter;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;

/**
 * FHIR R4 Encounter 资源 facade。
 *
 * <p>R-02 收口说明:
 * <ul>
 *   <li>补类级 @PreAuthorize,仅管理员 / 接诊 / 审核角色可读。</li>
 *   <li>搜索接口采用<b>「强制过滤条件」</b>方案:缺少 patient 参数时返回 400,不再回落到
 *       <code>visitService.listAll()</code> 全量返回。</li>
 * </ul>
 */
@Tag(name = "FHIR R4 标准接口", description = "FHIR Encounter 资源接口")
@RestController
@RequiredArgsConstructor
// R-02: FHIR 读接口收口 —— 仅管理员 / 接诊 / 审核角色可访问,患者等低权限账号一律 403
@PreAuthorize("hasAnyAuthority('system:admin','visit:entry','visit:audit')")
public class FhirEncounterController {

    private final VisitService visitService;
    private final EncounterConverter encounterConverter;

    @Operation(summary = "按ID获取Encounter资源")
    @GetMapping("/fhir/Encounter/{id}")
    public ResponseEntity<FhirEncounter> getById(@Parameter(description = "就诊单ID") @PathVariable Long id) {
        Visit visit = visitService.get(id);
        if (visit == null) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(encounterConverter.toFhir(visit));
    }

    @Operation(summary = "搜索Encounter资源(必须提供 patient 过滤条件)")
    @GetMapping("/fhir/Encounter")
    public ResponseEntity<?> search(
            @Parameter(description = "患者ID") @RequestParam(required = false) Long patient) {
        // R-02: 禁止无参全量返回 —— 缺少过滤条件时 400,防止一次请求拖走全院就诊记录
        if (patient == null) {
            return ResponseEntity.badRequest().body(Map.of(
                    "error", "缺少过滤条件",
                    "message", "搜索 Encounter 必须提供 patient(患者ID)参数,不支持无参全量查询"));
        }
        List<Visit> visits = visitService.listByPatientId(patient);
        List<FhirEncounter> fhirEncounters = visits.stream()
                .map(encounterConverter::toFhir)
                .toList();
        return ResponseEntity.ok(fhirEncounters);
    }
}
