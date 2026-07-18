package com.hospital.core.lab.infrastructure;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.hospital.core.lab.domain.LabRequisition;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface LabRequisitionMapper extends BaseMapper<LabRequisition> {
}
