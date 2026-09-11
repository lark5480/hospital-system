package com.hospital.core.pharmacy.infrastructure;

import java.util.List;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.hospital.core.pharmacy.domain.PrescriptionItem;

@Mapper
public interface PrescriptionItemMapper extends BaseMapper<PrescriptionItem> {

    /**
     * R-64 对账:找出「就诊已确单、但仍有药品医嘱未被任何处方覆盖」的就诊单 ID。
     *
     * <p>与 {@code LabResultItemMapper#selectVisitIdsWithUncoveredLabOrders} 同构,
     * 口径即领域不变式「已确单 ⇒ 每条 MEDICATION 医嘱都被某个处方的明细覆盖」。
     * 具体判据说明见该方法的注释,此处不重复。
     */
    @Select("""
            SELECT DISTINCT o.visit_id
            FROM clinical.orders o
            JOIN clinical.visit v ON v.id = o.visit_id
            LEFT JOIN pharmacy.prescription_item pi ON pi.order_id = o.id
            WHERE o.type = 'MEDICATION'
              AND o.status = 'CREATED'
              AND v.status <> 'CREATED'
              AND pi.id IS NULL
            """)
    List<Long> selectVisitIdsWithUncoveredMedicationOrders();
}
