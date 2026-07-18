package com.hospital.core.org.infrastructure;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.hospital.core.org.domain.Staff;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/**
 * 员工 Mapper。
 */
public interface StaffMapper extends BaseMapper<Staff> {

    @Select("SELECT * FROM org.staff WHERE username = #{username}")
    Staff findByUsername(@Param("username") String username);

    @Select("SELECT * FROM org.staff WHERE phone = #{phone}")
    Staff findByPhone(@Param("phone") String phone);
}
