package com.hospital.core.clinical.infrastructure;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.hospital.core.clinical.domain.MedicalRecord;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface MedicalRecordMapper extends BaseMapper<MedicalRecord> {
    
    @Select("SELECT * FROM clinical.medical_record WHERE visit_id = #{visitId}")
    MedicalRecord selectByVisitId(@Param("visitId") Long visitId);
}
