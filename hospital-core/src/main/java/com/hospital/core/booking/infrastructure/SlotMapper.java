package com.hospital.core.booking.infrastructure;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.hospital.core.booking.domain.Slot;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Update;

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
}
