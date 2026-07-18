package com.hospital.core.clinical.infrastructure;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.hospital.core.clinical.domain.Charge;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface ChargeMapper extends BaseMapper<Charge> {
}
