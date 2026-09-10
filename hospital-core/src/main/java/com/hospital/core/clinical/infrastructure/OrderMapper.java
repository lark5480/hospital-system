package com.hospital.core.clinical.infrastructure;

import java.util.List;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.hospital.core.clinical.domain.Order;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface OrderMapper extends BaseMapper<Order> {

    /** 该就诊是否有 PENDING 处方。 */
    @Select("SELECT COUNT(1) FROM pharmacy.prescription WHERE visit_id = #{visitId} AND status = 'PENDING'")
    int countPendingPrescriptions(Long visitId);

    /** 该就诊是否有 PENDING 检验申请。 */
    @Select("SELECT COUNT(1) FROM lab.requisition WHERE visit_id = #{visitId} AND status = 'PENDING'")
    int countPendingRequisitions(Long visitId);

    /**
     * R-21: 批量插入医嘱(单条 {@code INSERT ... VALUES (...),(...)}),把建单时 N 条医嘱的
     * N 次数据库往返降为 1 次;并通过 {@link Options#useGeneratedKeys()} 把自增主键<b>按序回填</b>
     * 到入参每个 {@link Order}(供 {@code VisitService.createWithOrders} 拼装 charge.order_id 使用)。
     *
     * <p><b>回填可行性(MyBatis 3.5.16 + PostgreSQL 驱动,已由
     * {@code VisitServiceCreateOrdersBatchTest} 在真实 PG 上实测通过)</b>:
     * 本方法是"单一 {@code @Param("list")} 参数",MyBatis 的 {@code Jdbc3KeyGenerator} 走
     * {@code assignKeysToParamMap → getAssignerForSingleParam} 分支,按生成键结果集的<b>行序</b>
     * 逐行 {@code setValue("id", ...)} 到 list 的每个元素;因未指定 {@code keyColumn},
     * {@code columnPosition=1},即取生成键结果集的<b>第 1 列(即 id)</b>。
     * 该实现依赖 PostgreSQL 对多值 INSERT 按 VALUES 顺序返回 {@code RETURNING} 行 ——
     * 已在测试中用 "id 升序 ⇔ item_name 升序" 的一一对应关系证明无错位。
     *
     * <p>只写 {@code db/schema.sql} 中 {@code clinical.orders} 的实际列,且均为可空列
     * (visit_id/type/item_name/quantity/unit_price/amount/execution_dept_id/status/finding),
     * 不写死任何带默认值或非空的约束列,避免约束冲突。
     *
     * <p>调用方需保证 list 非空({@code <foreach>} 面对空集合会拼出非法 SQL,由调用方判空)。
     *
     * @param orders 待插入的医嘱(非空);方法返回后每个元素的 id 已被回填
     * @return 受影响行数
     */
    @Insert("<script>"
            + "INSERT INTO clinical.orders "
            + "(visit_id, type, item_name, quantity, unit_price, amount, execution_dept_id, status, finding) VALUES "
            + "<foreach collection='list' item='o' separator=','>"
            + "(#{o.visitId}, #{o.type}, #{o.itemName}, #{o.quantity}, #{o.unitPrice}, #{o.amount}, "
            + "#{o.executionDeptId}, #{o.status}, #{o.finding})"
            + "</foreach>"
            + "</script>")
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insertBatch(@Param("list") List<Order> orders);
}
