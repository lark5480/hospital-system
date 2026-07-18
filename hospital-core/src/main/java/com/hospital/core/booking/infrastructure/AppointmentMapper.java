package com.hospital.core.booking.infrastructure;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.hospital.core.booking.domain.Appointment;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;

import java.time.LocalDate;
import java.util.List;

@Mapper
public interface AppointmentMapper extends BaseMapper<Appointment> {

    /** 查询已过期(检查日期 < today)的 BOOKED 预约,用于定时清理。 */
    @Select("SELECT a.* FROM booking.appointment a "
            + "JOIN booking.slot s ON a.slot_id = s.id "
            + "WHERE a.status = 'BOOKED' AND s.exam_date < #{date}")
    List<Appointment> selectExpiredBooked(LocalDate date);
}
