package com.hospital.core.patient.application;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.hospital.core.patient.api.PatientApi;
import com.hospital.core.patient.api.PatientRegisterResponse;
import com.hospital.core.patient.domain.Patient;
import com.hospital.core.patient.infrastructure.PatientMapper;
import com.hospital.core.platform.domain.SysUser;
import com.hospital.core.platform.infrastructure.SysUserMapper;
import com.hospital.core.platform.infrastructure.SysUserRoleMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

@Service
@RequiredArgsConstructor
public class PatientService implements PatientApi {

    /** 新建患者默认密码,须与 platform.config.DataInitializer.DEFAULT_PASSWORD 保持一致。 */
    private static final String DEFAULT_PASSWORD = "123456";

    private final PatientMapper patientMapper;
    private final SysUserMapper sysUserMapper;
    private final SysUserRoleMapper sysUserRoleMapper;
    private final PasswordEncoder passwordEncoder;

    /**
     * 建档/注册统一入口(B 端建患者、C 端自助建档共用):按手机号幂等。
     * 新患者:username 设为手机号(与登录用户名一致),落库后返回临时登录凭据。
     */
    @Transactional
    public PatientRegisterResponse register(Patient patient) {
        String phone = patient.getPhone();
        Patient existing = patientMapper.selectOne(
                new QueryWrapper<Patient>().eq("phone", phone));
        if (existing != null) {
            // 兼容历史数据:补设 username,便于 C 端身份绑定
            if (existing.getUsername() == null) {
                existing.setUsername(phone);
                patientMapper.updateById(existing);
            }
            // 兼容历史数据:确保已建档患者也有统一账号(启动期 DataInitializer 之外的新增路径)
            ensureSysUser(existing.getPhone(), existing.getName());
            PatientRegisterResponse existed = new PatientRegisterResponse();
            existed.setPatient(existing);
            existed.setUsername(existing.getUsername());
            existed.setTempPassword(null); // 已建档不重复开通,不返回密码
            return existed;
        }
        patient.setUsername(phone);
        patient.setCreatedAt(LocalDateTime.now());
        patientMapper.insert(patient);

        // 同步统一账号(默认密码 123456),使 C 端可用手机号直接登录,与启动期迁移保持一致
        ensureSysUser(phone, patient.getName());

        PatientRegisterResponse result = new PatientRegisterResponse();
        result.setPatient(patient);
        result.setUsername(phone);
        result.setTempPassword(null);
        return result;
    }

    /**
     * 幂等建立患者统一账号 + PATIENT 角色(默认密码),保证 C 端登录可用。
     * 与 platform.config.DataInitializer 的迁移逻辑对齐:已存在则跳过,缺角色则补。
     */
    private void ensureSysUser(String phone, String name) {
        SysUser user = sysUserMapper.findByPhone(phone);
        if (user == null) {
            user = new SysUser();
            user.setPhone(phone);
            user.setPassword(passwordEncoder.encode(DEFAULT_PASSWORD));
            user.setName(name);
            user.setStatus("ACTIVE");
            user.setCreatedAt(LocalDateTime.now());
            sysUserMapper.insert(user);
        }
        if (!sysUserRoleMapper.findRoleCodesByUserId(user.getId()).contains("PATIENT")) {
            sysUserRoleMapper.insertRole(user.getId(), "PATIENT");
        }
    }

    public Patient get(Long id) {
        return patientMapper.selectById(id);
    }

    /** 公开读 API:供其他模块(如 dispatch)按 id 取患者姓名,避免跨模块直连 DAO。 */
    public String getName(Long id) {
        Patient p = patientMapper.selectById(id);
        return p == null ? null : p.getName();
    }

    public List<Patient> list() {
        return patientMapper.selectList(null);
    }

    /** 按姓名或手机号模糊搜索患者(供医生端建档使用)。 */
    public List<Patient> search(String keyword) {
        QueryWrapper<Patient> qw = new QueryWrapper<>();
        qw.like("name", keyword)
          .or().like("phone", keyword);
        return patientMapper.selectList(qw);
    }

    /** 更新患者信息。 */
    @Transactional
    public Patient update(Long id, Patient updated) {
        Patient p = patientMapper.selectById(id);
        if (p == null) throw new IllegalArgumentException("患者不存在:" + id);
        if (updated.getName() != null) p.setName(updated.getName());
        if (updated.getGender() != null) p.setGender(updated.getGender());
        if (updated.getBirthday() != null) p.setBirthday(updated.getBirthday());
        if (updated.getPhone() != null) p.setPhone(updated.getPhone());
        if (updated.getIdCard() != null) p.setIdCard(updated.getIdCard());
        if (updated.getUsername() != null) p.setUsername(updated.getUsername());
        patientMapper.updateById(p);

        // 同步更新 sys_user 表的手机号(用于登录)
        if (p.getUserId() != null) {
            SysUser sysUser = sysUserMapper.selectById(p.getUserId());
            if (sysUser != null && updated.getPhone() != null) {
                sysUser.setPhone(updated.getPhone());
                sysUser.setName(updated.getName());
                sysUserMapper.updateById(sysUser);
            }
        }

        return p;
    }

    /** 按用户名精确查找患者(用于 C 端身份绑定)。 */
    public Patient findByUsername(String username) {
        QueryWrapper<Patient> qw = new QueryWrapper<>();
        qw.eq("username", username);
        return patientMapper.selectOne(qw);
    }

    /** 按身份证号查找患者(供 FHIR facade 使用)。 */
    public Patient findByIdCard(String idCard) {
        QueryWrapper<Patient> qw = new QueryWrapper<>();
        qw.eq("id_card", idCard);
        return patientMapper.selectOne(qw);
    }
}
