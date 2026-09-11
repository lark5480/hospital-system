package com.hospital.core.clinical.infrastructure;

import java.util.List;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.hospital.core.clinical.domain.Charge;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface ChargeMapper extends BaseMapper<Charge> {

    /**
     * R-21: 批量插入收费记录(单条 {@code INSERT INTO ... VALUES (...),(...)}),把建单时
     * N 条 charge 的 N 次往返降为 1 次。
     *
     * <p>只写实际用到的列(visit_id/order_id/item_name/amount/pay_status):
     * 建单场景 charge 的 pay_time / refund_time 必为 null,故不写死这些列,交由数据库默认值处理。
     *
     * <p>调用方需保证 list 非空(<foreach> 面对空集合会拼出非法 SQL,由 {@code VisitService} 侧判空)。
     *
     * @param charges 待插入的收费记录(非空)
     * @return 受影响行数
     */
    @Insert("<script>"
            + "INSERT INTO clinical.charge (visit_id, order_id, item_name, amount, pay_status) VALUES "
            + "<foreach collection='list' item='c' separator=','>"
            + "(#{c.visitId}, #{c.orderId}, #{c.itemName}, #{c.amount}, #{c.payStatus})"
            + "</foreach>"
            + "</script>")
    int insertBatch(@Param("list") List<Charge> charges);
}
