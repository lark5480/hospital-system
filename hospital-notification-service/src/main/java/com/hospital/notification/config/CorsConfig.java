package com.hospital.notification.config;

import java.util.Arrays;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * 全局 CORS:允许前端(Vite dev server, 5173)及 Gateway 跨域访问通知服务 API。
 *
 * <p><b>R-35</b>:原先是 {@code allowedOriginPatterns("*")} 搭配
 * {@code allowCredentials(true)} —— 这是危险组合:任意站点都能带着用户凭据
 * 跨域读取/订阅通知数据(含患者姓名、叫号、就诊状态)。
 * 现改为默认只放行本机开发源(与 hospital-core 的 SecurityConfig 口径一致),
 * 生产通过 {@code app.cors.allowed-origins} 显式配置。
 */
@Configuration
public class CorsConfig {

    /** 允许的跨域来源,逗号分隔;默认只放行本机开发端口。 */
    @Value("${app.cors.allowed-origins:http://localhost:*}")
    private String allowedOrigins;

    @Bean
    public WebMvcConfigurer corsConfigurer() {
        return new WebMvcConfigurer() {
            @Override
            public void addCorsMappings(CorsRegistry registry) {
                String[] origins = Arrays.stream(allowedOrigins.split(","))
                        .map(String::trim)
                        .filter(s -> !s.isEmpty())
                        .toArray(String[]::new);

                registry.addMapping("/api/**")
                        // R-35: 不得使用 "*"(尤其不能与 allowCredentials(true) 组合)
                        .allowedOriginPatterns(origins)
                        .allowedMethods("GET", "POST", "PUT", "DELETE", "OPTIONS")
                        .allowedHeaders("*")
                        .allowCredentials(true);
            }
        };
    }
}
