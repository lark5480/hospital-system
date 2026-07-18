package com.hospital.core.iam.infrastructure;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.hospital.core.iam.domain.MenuAuthority;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface MenuAuthorityMapper extends BaseMapper<MenuAuthority> {

    @Select("SELECT authority FROM platform.menu_authority WHERE menu_id = #{menuId}")
    List<String> findAuthoritiesByMenuId(@Param("menuId") Long menuId);
}
