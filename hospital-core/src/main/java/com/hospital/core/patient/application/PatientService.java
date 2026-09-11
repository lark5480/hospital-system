package com.hospital.core.patient.application;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.hospital.core.patient.api.PatientApi;
import com.hospital.core.patient.api.PatientRegisterResponse;
import com.hospital.core.patient.domain.Patient;
import com.hospital.core.patient.infrastructure.PatientMapper;
import com.hospital.core.platform.domain.SysUser;
import com.hospital.core.platform.infrastructure.SysUserMapper;
import com.hospital.core.platform.infrastructure.SysUserRoleMapper;
import com.hospital.core.platform.security.CurrentUserResolver;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@Service
@RequiredArgsConstructor
public class PatientService implements PatientApi {

    /** 新建患者默认密码,须与 platform.config.DataInitializer.DEFAULT_PASSWORD 保持一致。 */
    private static final String DEFAULT_PASSWORD = "123456";

    // R-07: 分页兜底上限 —— 防止 pageSize 被放大成事实上的全量拉取
    private static final int MAX_PAGE_SIZE = 500;
    private static final int DEFAULT_PAGE_SIZE = 200;

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

    /**
     * R-07: 分页查询患者列表 —— LIMIT/OFFSET 下推到 SQL,禁止 selectList(null) 全量后内存分页。
     *
     * @param pageNum  页码,从 1 开始(小于等于 0 时按 1 处理)
     * @param pageSize 每页条数(小于等于 0 时取默认 200,超过 500 时截断为 500)
     */
    public List<Patient> list(int pageNum, int pageSize) {
        int safePageNum = Math.max(pageNum, 1);
        int safePageSize = pageSize <= 0 ? DEFAULT_PAGE_SIZE : Math.min(pageSize, MAX_PAGE_SIZE);
        LambdaQueryWrapper<Patient> qw = new LambdaQueryWrapper<Patient>()
                .orderByDesc(Patient::getId)
                .last("LIMIT " + safePageSize + " OFFSET " + (safePageNum - 1) * safePageSize);
        return patientMapper.selectList(qw);
    }

    /**
     * R-07: 批量按 ID 查询患者实体(走 IN 条件)。
     * ids 为空时返回空列表,避免 IN() 退化成全表扫描。
     */
    public List<Patient> listByIds(List<Long> ids) {
        if (ids == null || ids.isEmpty()) {
            return List.of();
        }
        return patientMapper.selectList(new LambdaQueryWrapper<Patient>().in(Patient::getId, ids));
    }

    /** 按姓名或手机号模糊搜索患者(供医生端建档使用)。 */
    public List<Patient> search(String keyword) {
        QueryWrapper<Patient> qw = new QueryWrapper<>();
        qw.like("name", keyword)
          .or().like("phone", keyword);
        return patientMapper.selectList(qw);
    }

    /**
     * R-02/R-07: 身份证号脱敏 —— 保留前 6 后 4,中间以 * 号补齐。
     * 患者列表 / FHIR Patient 出参统一走这里,避免身份证明文批量泄露。
     */
    public static String maskIdCard(String idCard) {
        if (idCard == null || idCard.isBlank()) {
            return idCard;
        }
        String raw = idCard.trim();
        if (raw.length() <= 10) {
            return "*".repeat(raw.length());
        }
        return raw.substring(0, 6)
                + "*".repeat(raw.length() - 10)
                + raw.substring(raw.length() - 4);
    }

    /** R-07: 批量对出参做身份证脱敏(就地修改列表中的实体,返回同一列表便于链式调用)。 */
    public List<Patient> maskIdCardList(List<Patient> patients) {
        if (patients == null) {
            return patients;
        }
        for (Patient p : patients) {
            if (p != null) {
                p.setIdCard(maskIdCard(p.getIdCard()));
            }
        }
        return patients;
    }

    /** R-07: 单个患者出参脱敏(返回同一实例,便于链式调用)。 */
    public Patient maskIdCard(Patient patient) {
        if (patient != null) {
            patient.setIdCard(maskIdCard(patient.getIdCard()));
        }
        return patient;
    }

    /** R-07: 把逗号分隔的 ID 串解析为 ID 列表(非法片段直接跳过)。 */
    public static List<Long> parseIds(String ids) {
        if (ids == null || ids.isBlank()) {
            return List.of();
        }
        List<Long> parsed = new ArrayList<>();
        for (String part : ids.split(",")) {
            String trimmed = part.trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            try {
                parsed.add(Long.valueOf(trimmed));
            } catch (NumberFormatException ignored) {
                // R-07: 非法 ID 片段直接忽略,不暴露内部异常
            }
        }
        return parsed;
    }

    /**
     * R-08: 解析当前登录用户绑定的患者档案 ID。
     * 沿用 BookingController 的方式:JWT sub(手机号)→ patient.username。
     * 当前用户无绑定患者档案(如员工账号)时返回 null,由调用方决定 403。
     */
    public Long currentPatientId() {
        String username = CurrentUserResolver.resolveUsername();
        if (username == null) {
            return null;
        }
        Patient current = findByUsername(username);
        return current == null ? null : current.getId();
    }

    /**
     * 更新患者信息。
     *
     * <p>R-07: <b>phone 与 username 一律不接受入参变更</b>,以库里旧值覆盖入参。
     * 原因:PatientService 会把 phone 同步到 {@code sys_user.phone},而 sys_user 就是登录账号表,
     * 系统又支持"手机号即用户名"登录 —— 一旦允许改档改手机号,拿到改档权限的账号就能把
     * 他人档案的手机号改成自己掌控的号码,从而劫持/抢占他人登录账号(账号接管)。
     * 手机号改绑必须走独立的、带短信验证码的账号安全流程,不混在档案编辑里。
     *
     * <p>R-07: 身份证只接受非掩码值 —— 列表/详情出参身份证是脱敏的(含 *),
     * 编辑表单原样回传掩码时不能覆盖库里真实号码,否则等于把真实身份证擦成掩码。
     */
    /**
     * 更新患者信息。
     *
     * <p>R-07: <b>姓名与身份证属身份标识字段,仅 system:admin 可改</b>。
     * 医生端 PatientsView.vue 的编辑入口只有 visit:entry,若不收敛,持有该权限的账号
     * 就能把患者身份证改成任意合法号码(身份冒用 / 医保欺诈),风险远高于改错姓名。
     * 非管理员提交这两项变更时抛 {@link AccessDeniedException},由 GlobalExceptionHandler 统一返回 403。
     *
     * <p>R-07: <b>phone 仅 system:admin 可改</b>,且会同步 {@code sys_user.phone}(即登录账号),
     * 因此必须留审计;其余角色一律还原为库里旧值(防账号接管)。username 任何角色都不得变更。
     *
     * <p>R-07: 身份证只接受非掩码值 —— 列表/详情出参身份证是脱敏的(含 *),
     * 编辑表单原样回传掩码时不能覆盖库里真实号码,否则等于把真实身份证擦成掩码。
     */
    @Transactional
    public Patient update(Long id, Patient updated) {
        Patient p = patientMapper.selectById(id);
        if (p == null) throw new IllegalArgumentException("患者不存在:" + id);
        boolean admin = hasSystemAdminAuthority();

        if (updated.getName() != null && !updated.getName().equals(p.getName())) {
            if (!admin) {
                throw new AccessDeniedException("修改患者姓名需要管理员权限");
            }
            p.setName(updated.getName());
        }
        if (updated.getGender() != null) p.setGender(updated.getGender());
        if (updated.getBirthday() != null) p.setBirthday(updated.getBirthday());
        // R-07: 身份证 —— 掩码(含 *)不回写;真实值发生变更时仅限管理员
        if (updated.getIdCard() != null && !updated.getIdCard().contains("*")
                && !updated.getIdCard().equals(p.getIdCard())) {
            if (!admin) {
                throw new AccessDeniedException("修改患者身份证号需要管理员权限");
            }
            p.setIdCard(updated.getIdCard());
        }
        // R-07: phone 即登录账号,仅管理员可改绑;其余角色还原为旧值
        if (admin && updated.getPhone() != null && !updated.getPhone().equals(p.getPhone())) {
            p.setPhone(updated.getPhone());
        } else {
            updated.setPhone(p.getPhone());
        }
        // R-07: username 与登录账号绑定,任何角色都不得变更
        updated.setUsername(p.getUsername());
        patientMapper.updateById(p);

        // 同步更新 sys_user 的姓名;手机号仅在管理员改绑时同步
        if (p.getUserId() != null) {
            SysUser sysUser = sysUserMapper.selectById(p.getUserId());
            if (sysUser != null) {
                if (p.getName() != null) {
                    sysUser.setName(p.getName());
                }
                if (admin && p.getPhone() != null && !p.getPhone().equals(sysUser.getPhone())) {
                    sysUser.setPhone(p.getPhone());
                }
                sysUserMapper.updateById(sysUser);
            }
        }

        return p;
    }

    /**
     * R-07: 当前主体是否持有 system:admin。直接读 SecurityContext,
     * 不新增构造参数,保持既有方法签名与其它调用点不变(与 VisitService 同款实现)。
     */
    private boolean hasSystemAdminAuthority() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || auth.getAuthorities() == null) {
            return false;
        }
        for (GrantedAuthority ga : auth.getAuthorities()) {
            if ("system:admin".equals(ga.getAuthority())) {
                return true;
            }
        }
        return false;
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
