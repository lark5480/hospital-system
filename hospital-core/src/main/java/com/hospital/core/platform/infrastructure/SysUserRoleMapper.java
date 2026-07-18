package com.hospital.core.platform.infrastructure;

import java.util.Set;

import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.hospital.core.platform.domain.SysUserRole;

/**
 * 用户-角色 Mapper(多对多)。
 */
@Mapper
public interface SysUserRoleMapper extends BaseMapper<SysUserRole> {
    
    @Select("SELECT role_code FROM platform.sys_user_role WHERE user_id = #{userId}")
    Set<String> findRoleCodesByUserId(@Param("userId") Long userId);

    @Insert("INSERT INTO platform.sys_user_role (user_id, role_code) VALUES (#{userId}, #{roleCode})")
    void insertRole(@Param("userId") Long userId, @Param("roleCode") String roleCode);

    @Delete("DELETE FROM platform.sys_user_role WHERE user_id = #{userId}")
    void deleteByUserId(@Param("userId") Long userId);
}
