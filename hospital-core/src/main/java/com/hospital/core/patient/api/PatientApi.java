package com.hospital.core.patient.api;

import com.hospital.core.patient.domain.Patient;

/**
 * Patient 模块对外暴露的公开查询接口。
 * 供 booking、dispatch 等模块按 id 获取患者基本信息，避免跨模块直连 DAO。
 */
public interface PatientApi {

    /** 按患者 id 查询姓名，不存在返回 null。 */
    String getName(Long id);

    /** 按登录用户名(手机号)精确查找患者档案，用于 C 端接口绑定当前登录用户，不存在返回 null。 */
    Patient findByUsername(String username);
}
