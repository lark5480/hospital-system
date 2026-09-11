package com.hospital.core.fhir.api;

import java.util.List;
import java.util.Map;

import org.springframework.beans.BeanUtils;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.hospital.core.fhir.converter.PatientConverter;
import com.hospital.core.fhir.domain.FhirPatient;
import com.hospital.core.patient.application.PatientService;
import com.hospital.core.patient.domain.Patient;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;

/**
 * FHIR R4 Patient 资源 facade。
 *
 * <p>R-02 收口说明:
 * <ul>
 *   <li>原 <code>/fhir/**</code> 在 SecurityConfig 中被 permitAll 匿名放行,且本类无任何 @PreAuthorize ——
 *       任意匿名请求即可拖走全院患者身份证 / 手机号。现端点已收口为必须携带 JWT,本类再补类级 @PreAuthorize
 *       做细粒度授权(仅管理员 / 接诊 / 审核角色可读)。</li>
 *   <li>搜索接口采用<b>「强制过滤条件」</b>方案(而非强制分页):缺少 identifier 时直接返回 400。
 *       理由:FHIR Patient 含身份证等 PII,强制分页仍可被低权限账号逐页遍历拖库;
 *       要求显式过滤条件可把单次响应规模压到单条,且语义更接近 FHIR 的 identifier 精确检索。</li>
 *   <li>出参身份证号一律脱敏(保留前 6 后 4,中间补 *),避免 PatientConverter 把明文写进 identifier。</li>
 * </ul>
 */
@Tag(name = "FHIR R4 标准接口", description = "FHIR Patient 资源接口")
@RestController
@RequiredArgsConstructor
// R-02: FHIR 读接口收口 —— 仅管理员 / 接诊 / 审核角色可访问,患者等低权限账号一律 403
@PreAuthorize("hasAnyAuthority('system:admin','visit:entry','visit:audit')")
public class FhirPatientController {

    private final PatientService patientService;
    private final PatientConverter patientConverter;

    @Operation(summary = "按ID获取Patient资源")
    @GetMapping("/fhir/Patient/{id}")
    public ResponseEntity<FhirPatient> getById(@Parameter(description = "患者ID") @PathVariable Long id) {
        Patient patient = patientService.get(id);
        if (patient == null) {
            return ResponseEntity.notFound().build();
        }
        // R-02: 出参身份证脱敏后再转 FHIR 资源
        return ResponseEntity.ok(patientConverter.toFhir(maskIdCard(patient)));
    }

    @Operation(summary = "搜索Patient资源(必须提供 identifier 过滤条件)")
    @GetMapping("/fhir/Patient")
    public ResponseEntity<?> search(
            @Parameter(description = "身份证号") @RequestParam(required = false) String identifier) {
        // R-02: 禁止无参全量返回 —— 缺少过滤条件时 400,防止一次请求拖走全院患者档案
        if (identifier == null || identifier.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of(
                    "error", "缺少过滤条件",
                    "message", "搜索 Patient 必须提供 identifier(身份证号)参数,不支持无参全量查询"));
        }
        Patient patient = patientService.findByIdCard(identifier);
        if (patient == null) {
            return ResponseEntity.ok(List.of());
        }
        // R-02: 出参身份证脱敏后再转 FHIR 资源
        return ResponseEntity.ok(List.of(patientConverter.toFhir(maskIdCard(patient))));
    }

    /** R-02: 身份证脱敏副本(保留前 6 后 4,中间以 * 号补齐),避免污染入参实体。 */
    private Patient maskIdCard(Patient source) {
        if (source == null) {
            return null;
        }
        Patient copy = new Patient();
        BeanUtils.copyProperties(source, copy);
        copy.setIdCard(PatientService.maskIdCard(source.getIdCard()));
        return copy;
    }
}
