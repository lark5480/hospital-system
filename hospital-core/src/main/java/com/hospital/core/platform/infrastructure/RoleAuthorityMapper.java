package com.hospital.core.platform.infrastructure;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.hospital.core.platform.domain.RoleAuthority;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;
import java.util.Set;

/**
 * 角色↔权限 Mapper。
 */
@Mapper
public interface RoleAuthorityMapper extends BaseMapper<RoleAuthority> {

    /** 查单个角色持有的全部 authority */
    @Select("SELECT authority FROM platform.role_authority WHERE role_code = #{roleCode}")
    Set<String> findAuthoritiesByRole(@Param("roleCode") String roleCode);

    /** 查多个角色持有的全部 authority 并集 */
    @Select("<script>" +
            "SELECT DISTINCT authority FROM platform.role_authority WHERE role_code IN " +
            "<foreach item='c' collection='roles' open='(' separator=',' close=')'>#{c}</foreach>" +
            "</script>")
    Set<String> findAuthoritiesByRoles(@Param("roles") List<String> roles);
}
