package com.hospital.core.booking.infrastructure;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.hospital.core.booking.domain.ExamItem;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface ExamItemMapper extends BaseMapper<ExamItem> {
}
