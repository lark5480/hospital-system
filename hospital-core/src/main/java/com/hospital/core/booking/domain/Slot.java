package com.hospital.core.booking.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDate;

/**
 * 号源(某套餐在某日期/时段的可预约额度)。
 * booked 为已约数量;占号时必须满足 booked < capacity,见 SlotMapper.incrementBooked。
 */
@Data
@TableName("booking.slot")
public class Slot {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long packageId;

    private LocalDate examDate;

    /** AM / PM / FULL 等时段标识 */
    private String period;

    private Integer capacity;

    private Integer booked;
}
