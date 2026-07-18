package com.hospital.file.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.web.SecurityFilterChain;

/**
 * 文件服务安全配置:内部 MinIO 薄封装,文件 API 对网关爱址,患者归属守卫由调用方(core)强制。
 * 默认放行全部请求;生产态若需本服务独立验签,可在此补 oauth2ResourceServer。
 */
@Configuration
public class SecurityConfig {

    @Bean
    public SecurityFilterChain devFilterChain(HttpSecurity http) throws Exception {
        http
            .csrf(AbstractHttpConfigurer::disable)
            .authorizeHttpRequests(auth -> auth.anyRequest().permitAll());
        return http.build();
    }
}
