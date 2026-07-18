package com.hospital.core.platform.infrastructure;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.hospital.core.platform.domain.Role;
import org.apache.ibatis.annotations.Mapper;

/**
 * 角色 Mapper。
 */
@Mapper
public interface RoleMapper extends BaseMapper<Role> {
}
