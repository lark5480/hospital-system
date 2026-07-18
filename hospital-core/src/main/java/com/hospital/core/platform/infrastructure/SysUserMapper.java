package com.hospital.core.platform.infrastructure;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.hospital.core.platform.domain.SysUser;

/**
 * 统一账号 Mapper。
 */
@Mapper
public interface SysUserMapper extends BaseMapper<SysUser> {

    @Select("SELECT * FROM platform.sys_user WHERE phone = #{phone}")
    SysUser findByPhone(@Param("phone") String phone);
}
