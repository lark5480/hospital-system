package com.hospital.core.clinical.infrastructure;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.hospital.core.clinical.domain.VisitReadModel;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import java.util.List;

@Mapper
public interface VisitReadModelMapper extends BaseMapper<VisitReadModel> {
    
    /**
     * 按 visitId 查询（用于刷新读模型）
     */
    @Select("SELECT * FROM clinical.visit_read_model WHERE visit_id = #{visitId}")
    VisitReadModel selectByVisitId(@Param("visitId") Long visitId);
    
    /**
     * 按科室过滤（支持跨科协作：归属科室 或 执行科室）
     */
    @Select("SELECT * FROM clinical.visit_read_model WHERE dept_id = #{deptId} ORDER BY visit_time DESC")
    List<VisitReadModel> selectByDeptId(@Param("deptId") Long deptId);
}
