package com.hospital.core.dispatch.infrastructure;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.hospital.core.dispatch.domain.ExamTask;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface ExamTaskMapper extends BaseMapper<ExamTask> {
}
