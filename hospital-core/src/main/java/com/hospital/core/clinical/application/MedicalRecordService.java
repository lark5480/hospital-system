package com.hospital.core.clinical.application;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.hospital.core.clinical.domain.MedicalRecord;
import com.hospital.core.clinical.domain.Visit;
import com.hospital.core.clinical.infrastructure.MedicalRecordMapper;
import com.hospital.core.clinical.infrastructure.VisitMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class MedicalRecordService {

    private final MedicalRecordMapper medicalRecordMapper;
    private final VisitMapper visitMapper;

    /**
     * 获取病历（按 visitId）
     */
    public MedicalRecord get(Long visitId) {
        return medicalRecordMapper.selectByVisitId(visitId);
    }

    /**
     * 获取病历（按 id）
     */
    public MedicalRecord getById(Long id) {
        return medicalRecordMapper.selectById(id);
    }

    /**
     * 创建或更新病历
     */
    @Transactional
    public MedicalRecord save(Long visitId, MedicalRecord record) {
        Visit visit = visitMapper.selectById(visitId);
        if (visit == null) {
            throw new IllegalArgumentException("就诊不存在: " + visitId);
        }

        MedicalRecord existing = medicalRecordMapper.selectByVisitId(visitId);
        
        if (existing != null) {
            // 更新现有病历
            record.setId(existing.getId());
            record.setVisitId(visitId);
            record.setPatientId(visit.getPatientId());
            record.setDoctorId(visit.getDoctorId());
            record.setDeptId(visit.getDeptId());
            record.setUpdatedAt(LocalDateTime.now());
            medicalRecordMapper.updateById(record);
            log.info("Updated medical record for visit {}", visitId);
        } else {
            // 创建新病历
            record.setVisitId(visitId);
            record.setPatientId(visit.getPatientId());
            record.setDoctorId(visit.getDoctorId());
            record.setDeptId(visit.getDeptId());
            record.setStatus("DRAFT");
            record.setCreatedAt(LocalDateTime.now());
            record.setUpdatedAt(LocalDateTime.now());
            medicalRecordMapper.insert(record);
            log.info("Created medical record for visit {}", visitId);
        }
        
        return record;
    }

    /**
     * 终诊（DRAFT → FINAL）
     */
    @Transactional
    public MedicalRecord finalize(Long visitId) {
        MedicalRecord record = medicalRecordMapper.selectByVisitId(visitId);
        if (record == null) {
            throw new IllegalArgumentException("病历不存在: visitId=" + visitId);
        }
        if ("FINAL".equals(record.getStatus())) {
            return record;  // 幂等
        }
        
        record.setStatus("FINAL");
        record.setFinalizedAt(LocalDateTime.now());
        record.setUpdatedAt(LocalDateTime.now());
        medicalRecordMapper.updateById(record);
        log.info("Finalized medical record for visit {}", visitId);
        
        return record;
    }

    /**
     * 按患者查询病历历史
     */
    public List<MedicalRecord> listByPatientId(Long patientId) {
        return medicalRecordMapper.selectList(
                new LambdaQueryWrapper<MedicalRecord>()
                        .eq(MedicalRecord::getPatientId, patientId)
                        .orderByDesc(MedicalRecord::getCreatedAt));
    }

    /**
     * 删除病历
     */
    @Transactional
    public void delete(Long visitId) {
        medicalRecordMapper.delete(
                new LambdaQueryWrapper<MedicalRecord>()
                        .eq(MedicalRecord::getVisitId, visitId));
    }
}
