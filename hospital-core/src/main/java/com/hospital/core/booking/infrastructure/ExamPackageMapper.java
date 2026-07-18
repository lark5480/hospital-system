package com.hospital.core.booking.infrastructure;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.hospital.core.booking.domain.ExamPackage;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface ExamPackageMapper extends BaseMapper<ExamPackage> {
}
