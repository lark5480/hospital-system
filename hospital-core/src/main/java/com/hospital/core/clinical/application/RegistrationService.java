package com.hospital.core.clinical.application;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.hospital.core.clinical.domain.Registration;
import com.hospital.core.clinical.domain.Visit;
import com.hospital.core.clinical.domain.VisitStatusEvent;
import com.hospital.core.clinical.infrastructure.RegistrationMapper;
import com.hospital.core.patient.application.PatientService;

import lombok.RequiredArgsConstructor;

/**
 * 门诊挂号 + 分诊排队 + 叫号。
 * <p>
 * 流程:挂号(WAITING) → 叫号(CALLED,自动创建就诊单) → 就诊;或取消(CANCELLED)。
 * 排队号按科室 + 当日自增,轻量实现(单用户学习项目,无并发竞态问题)。
 */
@Service
@RequiredArgsConstructor
public class RegistrationService {

    private final RegistrationMapper registrationMapper;
    private final VisitService visitService;
    private final PatientService patientService;

    /** 挂号:患者 + 科室(+ 可选医生) → 进入候诊队列,返回排队号。 */
    @Transactional
    public Registration register(Long patientId, Long deptId, Long doctorId) {
        if (patientService.get(patientId) == null) {
            throw new IllegalArgumentException("患者不存在: " + patientId);
        }
        Long todayCount = registrationMapper.selectCount(
                new LambdaQueryWrapper<Registration>()
                        .eq(Registration::getDeptId, deptId)
                        .ge(Registration::getCreatedAt, LocalDate.now().atStartOfDay()));
        Registration reg = new Registration();
        reg.setPatientId(patientId);
        reg.setDeptId(deptId);
        reg.setDoctorId(doctorId);
        reg.setQueueNo(todayCount.intValue() + 1);
        reg.setStatus("WAITING");
        reg.setCreatedAt(LocalDateTime.now());
        registrationMapper.insert(reg);
        return reg;
    }

    /** 叫号:取该科室最前面的 WAITING 记录,自动创建就诊单并关联。 */
    @Transactional
    public Registration callNext(Long deptId) {
        Registration reg = registrationMapper.selectOne(
                new LambdaQueryWrapper<Registration>()
                        .eq(Registration::getDeptId, deptId)
                        .eq(Registration::getStatus, "WAITING")
                        .orderByAsc(Registration::getQueueNo)
                        .last("LIMIT 1"));
        if (reg == null) {
            throw new IllegalStateException("当前无候诊患者");
        }
        // 自动创建就诊单(医生后续补充主诉/医嘱)
        Visit visit = new Visit();
        visit.setPatientId(reg.getPatientId());
        visit.setDeptId(deptId);
        visit.setDoctorId(reg.getDoctorId());
        visit.setChiefComplaint("门诊就诊");
        Visit created = visitService.create(visit);

        reg.setStatus("CALLED");
        reg.setVisitId(created.getId());
        reg.setCalledAt(LocalDateTime.now());
        registrationMapper.updateById(reg);
        return reg;
    }

    /** 就诊结束 → 关联挂号置 COMPLETED,候诊队列/大屏的"当前就诊"随之指向最新叫号者。 */
    @EventListener
    public void onVisitFinished(VisitStatusEvent event) {
        if (!"FINISHED".equals(event.status())) {
            return;
        }
        Registration reg = registrationMapper.selectOne(
                new LambdaQueryWrapper<Registration>()
                        .eq(Registration::getVisitId, event.visitId())
                        .eq(Registration::getStatus, "CALLED"));
        if (reg != null) {
            reg.setStatus("COMPLETED");
            registrationMapper.updateById(reg);
        }
    }

    /** 取消挂号:仅 WAITING 可取消。 */
    @Transactional
    public Registration cancel(Long id) {
        Registration reg = registrationMapper.selectById(id);
        if (reg == null) {
            throw new IllegalArgumentException("挂号记录不存在: " + id);
        }
        if (!"WAITING".equals(reg.getStatus())) {
            throw new IllegalStateException("仅候诊状态可取消(当前: " + reg.getStatus() + ")");
        }
        reg.setStatus("CANCELLED");
        registrationMapper.updateById(reg);
        return reg;
    }

    /** 查询某科室当日排队列表(含所有状态,按排队号排序)。 */
    public List<Registration> listByDept(Long deptId) {
        return registrationMapper.selectList(
                new LambdaQueryWrapper<Registration>()
                        .eq(Registration::getDeptId, deptId)
                        .ge(Registration::getCreatedAt, LocalDate.now().atStartOfDay())
                        .orderByAsc(Registration::getQueueNo));
    }

    /** 查询某科室当前候诊 + 已叫号(大屏用)。 */
    public List<Registration> activeQueue(Long deptId) {
        return registrationMapper.selectList(
                new LambdaQueryWrapper<Registration>()
                        .eq(Registration::getDeptId, deptId)
                        .ge(Registration::getCreatedAt, LocalDate.now().atStartOfDay())
                        .in(Registration::getStatus, "WAITING", "CALLED")
                        .orderByAsc(Registration::getQueueNo));
    }
}
