package com.hospital.core.clinical.infrastructure;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.hospital.core.clinical.domain.VisitReadModel;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import java.math.BigDecimal;
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

    /**
     * R-16: 一条聚合 SQL 取读模型所需的全部聚合值 —— 取代原先
     * "selectList(orders) + selectList(charge) 后在 Java 内存里聚合"的 2 次全量查询。
     * 标量子查询在 visit_id 有索引(见 R-04 热路径索引)时可走索引,
     * 单次往返即得 order_count / charge_count / total_amount / unpaid_count。
     * <p>COALESCE(SUM(amount),0) 天然跳过金额为 null 的脏数据,金额口径与原 sumAmount 一致。
     */
    @Select("""
            SELECT
              (SELECT COUNT(*) FROM clinical.orders o WHERE o.visit_id = #{visitId}) AS order_count,
              (SELECT COUNT(*) FROM clinical.charge c WHERE c.visit_id = #{visitId}) AS charge_count,
              (SELECT COALESCE(SUM(c.amount), 0) FROM clinical.charge c WHERE c.visit_id = #{visitId}) AS total_amount,
              (SELECT COUNT(*) FROM clinical.charge c WHERE c.visit_id = #{visitId} AND c.pay_status = 'UNPAID') AS unpaid_count
            """)
    VisitAggregate selectAggregate(@Param("visitId") Long visitId);

    /**
     * R-16: 聚合查询结果载体(包装类型可空,便于 MyBatis 按列名自动映射)。
     * map-underscore-to-camel-case 已开启,order_count → orderCount。
     */
    class VisitAggregate {
        private Integer orderCount;
        private Integer chargeCount;
        private BigDecimal totalAmount;
        private Integer unpaidCount;

        public Integer getOrderCount() {
            return orderCount;
        }

        public void setOrderCount(Integer orderCount) {
            this.orderCount = orderCount;
        }

        public Integer getChargeCount() {
            return chargeCount;
        }

        public void setChargeCount(Integer chargeCount) {
            this.chargeCount = chargeCount;
        }

        public BigDecimal getTotalAmount() {
            return totalAmount;
        }

        public void setTotalAmount(BigDecimal totalAmount) {
            this.totalAmount = totalAmount;
        }

        public Integer getUnpaidCount() {
            return unpaidCount;
        }

        public void setUnpaidCount(Integer unpaidCount) {
            this.unpaidCount = unpaidCount;
        }
    }
}
