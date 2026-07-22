package com.hospital.core.clinical.infrastructure;

import org.apache.ibatis.annotations.Mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.hospital.core.clinical.domain.Registration;

@Mapper
public interface RegistrationMapper extends BaseMapper<Registration> {
}
