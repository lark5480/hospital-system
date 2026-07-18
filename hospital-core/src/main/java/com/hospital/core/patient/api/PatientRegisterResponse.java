package com.hospital.core.patient.api;

import com.hospital.core.patient.domain.Patient;
import lombok.Data;

/**
 * 建档响应:返回患者记录 + 登录凭据。
 * - username = 手机号(与登录用户名一致)
 * - tempPassword 已废弃(自管 JWT 不再需要),始终为 null
 */
@Data
public class PatientRegisterResponse {

    private Patient patient;
    private String username;
    private String tempPassword;
}
