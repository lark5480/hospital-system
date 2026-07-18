package com.hospital.core.clinical.infrastructure;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.hospital.core.clinical.domain.Order;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface OrderMapper extends BaseMapper<Order> {

    /** 该就诊是否有 PENDING 处方。 */
    @Select("SELECT COUNT(1) FROM pharmacy.prescription WHERE visit_id = #{visitId} AND status = 'PENDING'")
    int countPendingPrescriptions(Long visitId);

    /** 该就诊是否有 PENDING 检验申请。 */
    @Select("SELECT COUNT(1) FROM lab.requisition WHERE visit_id = #{visitId} AND status = 'PENDING'")
    int countPendingRequisitions(Long visitId);
}
