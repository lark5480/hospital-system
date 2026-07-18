package com.hospital.core.pharmacy.infrastructure;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.hospital.core.pharmacy.domain.PrescriptionItem;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface PrescriptionItemMapper extends BaseMapper<PrescriptionItem> {
}
