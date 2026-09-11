package com.hospital.core.booking.infrastructure;

import java.util.List;
import java.util.Map;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.hospital.core.booking.domain.Slot;

@Mapper
public interface SlotMapper extends BaseMapper<Slot> {

    /**
     * 原子占号:仅当 booked < capacity 时才把 booked +1,返回受影响行数。
     * 并发下最多一个事务能成功,从数据库层面彻底杜绝号源超卖——
     * 不依赖应用层先查后改(存在竞态)或乐观锁版本号(需额外字段)。
     * 返回 0 即代表号源已满。
     */
    @Update("UPDATE booking.slot SET booked = booked + 1 WHERE id = #{id} AND booked < capacity")
    int incrementBooked(Long id);

    /** 释放号源:取消预约时 booked -1。 */
    @Update("UPDATE booking.slot SET booked = GREATEST(booked - 1, 0) WHERE id = #{id} AND booked > 0")
    int decrementBooked(Long id);

    /**
     * R-42: 批量插入号源(单条 {@code INSERT INTO ... VALUES (...),(...)}),
     * 替代 {@code ensureSlotsExist} 中 7 天 × 2 时段 = 14 次逐条 insert 的写放大。
     * 仅新增方法,不改动既有方法签名。
     */
    @Insert("<script>INSERT INTO booking.slot (package_id, exam_date, period, capacity, booked) VALUES "
            + "<foreach collection='list' item='s' separator=','>"
            + "(#{s.packageId}, #{s.examDate}, #{s.period}, #{s.capacity}, #{s.booked})"
            + "</foreach></script>")
    int batchInsert(@Param("list") List<Slot> slots);

    /**
     * R-42: 批量释放号源。list 每项为 (slotId, cnt),用 {@code VALUES} 派生表承载"每个 slot 的取消数量",
     * 一次 UPDATE join 完成,替代 {@code cleanupExpiredAppointments} 中逐条 {@code decrementBooked}。
     * 显式 ::bigint / ::int 转型,规避 JDBC 参数在 VALUES 中类型未知导致的 PG 报错。
     */
    @Update("<script>UPDATE booking.slot s SET booked = GREATEST(s.booked - v.cnt, 0) "
            + "FROM (VALUES "
            + "<foreach collection='list' item='e' separator=','>(#{e.slotId}::bigint, #{e.cnt}::int)</foreach>"
            + ") AS v(slot_id, cnt) "
            + "WHERE s.id = v.slot_id</script>")
    int releaseBookedBatch(@Param("list") List<Map<String, Object>> decrements);
}
