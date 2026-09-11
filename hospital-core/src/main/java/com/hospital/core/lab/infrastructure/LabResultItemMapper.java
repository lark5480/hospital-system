package com.hospital.core.lab.infrastructure;

import java.util.List;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.hospital.core.lab.domain.LabResultItem;

@Mapper
public interface LabResultItemMapper extends BaseMapper<LabResultItem> {

    /**
     * R-64 对账:找出「就诊已确单、但仍有检验医嘱未被任何申请覆盖」的就诊单 ID。
     *
     * <p>对账口径即领域不变式「已确单 ⇒ 每条 LAB 医嘱都被某个申请的 result_item 覆盖」,
     * 故用 {@code LEFT JOIN ... IS NULL} 直接找出反例,而不是"先拉全量再在内存里比对":
     * <ul>
     *   <li>{@code o.status = 'CREATED'} —— 医嘱未执行;执行后会被 {@code submitResults} 置为 EXECUTED;</li>
     *   <li>{@code v.status <> 'CREATED'} —— 草稿就诊单本就不生成申请,不在对账范围内;</li>
     *   <li>{@code ri.id IS NULL} —— 该医嘱尚未被任何申请纳入,即需要补建的部分。</li>
     * </ul>
     *
     * <p>本查询是"跨 schema 只读 join"(同库内读 {@code clinical.orders}/{@code clinical.visit}),
     * 与 lab 模块已通过 {@code OrderMapper} 读取订单是同一份数据,不新增模块耦合。
     */
    @Select("""
            SELECT DISTINCT o.visit_id
            FROM clinical.orders o
            JOIN clinical.visit v ON v.id = o.visit_id
            LEFT JOIN lab.result_item ri ON ri.order_id = o.id
            WHERE o.type = 'LAB'
              AND o.status = 'CREATED'
              AND v.status <> 'CREATED'
              AND ri.id IS NULL
            """)
    List<Long> selectVisitIdsWithUncoveredLabOrders();
}
