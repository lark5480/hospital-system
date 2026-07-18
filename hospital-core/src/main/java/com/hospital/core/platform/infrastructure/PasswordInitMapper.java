package com.hospital.core.platform.infrastructure;

import java.util.List;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/**
 * 密码初始化 Mapper:启动时给无密码员工填默认密码。
 */
@Mapper
public interface PasswordInitMapper {
    @Select("SELECT username FROM org.staff WHERE password IS NULL")
    List<String> findUsernamesWithNullPassword();

    @Update("UPDATE org.staff SET password = #{hash} WHERE username = #{username}")
    void updatePassword(@Param("username") String username, @Param("hash") String hash);
}
