package com.hospital.core.clinical.infrastructure;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.hospital.core.clinical.domain.Visit;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface VisitMapper extends BaseMapper<Visit> {
}
