package com.hospital.core.clinical.infrastructure;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.hospital.core.clinical.domain.MedicalRecord;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface MedicalRecordMapper extends BaseMapper<MedicalRecord> {

    default MedicalRecord selectByVisitId(Long visitId) {
        return selectOne(new LambdaQueryWrapper<MedicalRecord>()
                .eq(MedicalRecord::getVisitId, visitId));
    }
}
