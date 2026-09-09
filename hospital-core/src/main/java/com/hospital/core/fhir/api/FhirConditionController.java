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
import com.hospital.core.fhir.converter.ConditionConverter;
import com.hospital.core.fhir.domain.FhirCondition;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;

/**
 * FHIR R4 Condition 资源 facade。
 *
 * <p>R-02 收口说明:
 * <ul>
 *   <li>补类级 @PreAuthorize,仅管理员 / 接诊 / 审核角色可读。</li>
 *   <li>搜索接口采用<b>「强制过滤条件」</b>方案:缺少 patient 参数时返回 400,不再回落到
 *       <code>visitService.listAll()</code> 全量返回(原实现一次请求即可拖走全院诊断)。</li>
 * </ul>
 */
@Tag(name = "FHIR R4 标准接口", description = "FHIR Condition 资源接口")
@RestController
@RequiredArgsConstructor
// R-02: FHIR 读接口收口 —— 仅管理员 / 接诊 / 审核角色可访问,患者等低权限账号一律 403
@PreAuthorize("hasAnyAuthority('system:admin','visit:entry','visit:audit')")
public class FhirConditionController {

    private final VisitService visitService;
    private final ConditionConverter conditionConverter;

    @Operation(summary = "按ID获取Condition资源")
    @GetMapping("/fhir/Condition/{id}")
    public ResponseEntity<FhirCondition> getById(@Parameter(description = "就诊单ID") @PathVariable Long id) {
        Visit visit = visitService.get(id);
        if (visit == null) {
            return ResponseEntity.notFound().build();
        }
        FhirCondition condition = conditionConverter.toFhir(visit);
        if (condition == null) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(condition);
    }

    @Operation(summary = "搜索Condition资源(必须提供 patient 过滤条件)")
    @GetMapping("/fhir/Condition")
    public ResponseEntity<?> search(
            @Parameter(description = "患者ID") @RequestParam(required = false) Long patient) {
        // R-02: 禁止无参全量返回 —— 缺少过滤条件时 400,防止一次请求拖走全院诊断
        if (patient == null) {
            return ResponseEntity.badRequest().body(Map.of(
                    "error", "缺少过滤条件",
                    "message", "搜索 Condition 必须提供 patient(患者ID)参数,不支持无参全量查询"));
        }
        List<Visit> visits = visitService.listByPatientId(patient);
        List<FhirCondition> conditions = visits.stream()
                .map(conditionConverter::toFhir)
                .filter(c -> c != null)
                .toList();
        return ResponseEntity.ok(conditions);
    }
}
