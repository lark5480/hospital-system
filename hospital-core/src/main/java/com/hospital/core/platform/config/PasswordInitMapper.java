package com.hospital.core.platform.config;

import java.util.List;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface PasswordInitMapper {
    @Select("SELECT username FROM org.staff WHERE password IS NULL")
    List<String> findUsernamesWithNullPassword();

    @Update("UPDATE org.staff SET password = #{hash} WHERE username = #{username}")
    void updatePassword(@Param("username") String username, @Param("hash") String hash);
}