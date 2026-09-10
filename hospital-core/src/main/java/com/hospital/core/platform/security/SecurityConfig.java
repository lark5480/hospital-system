package com.hospital.core.platform.security;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.MediaType;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import com.fasterxml.jackson.databind.ObjectMapper;

import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;

/**
 * 统一 JWT 安全配置(无外部 IdP 依赖,自管 token)。
 * - 登录接口 /api/auth/** 与 swagger 公开,其余需 JWT 认证。
 * - 无状态会话(STATELESS),服务端不存 session。
 * - 方法级鉴权 @PreAuthorize 启用,接口鉴权走 token 中的 authorities。
 *
 * R-02: /fhir/** 已从 permitAll 移除(原先匿名放行会泄露全院患者身份证 / 手机号 / 诊断),
 *       现需携带有效 JWT,并由 Controller 上的 @PreAuthorize 做细粒度授权(由另一子代理补齐)。
 * R-33: /actuator/** 同样不再匿名放行,避免暴露健康检查 / 环境信息。
 *
 * 未来接外部 IdP 时,只需把 login 接口改为"校验外部 token → 换签自有 JWT",
 * 过滤器与 SecurityConfig 不动。
 */
@Configuration
@EnableMethodSecurity
@RequiredArgsConstructor
public class SecurityConfig {

    private final JwtAuthFilter jwtAuthFilter;
    private final ObjectMapper objectMapper;

    /**
     * 密码加密器(BCrypt)。
     *
     * <p>R-45: 强度由默认 10 提升到 12。前置条件是登录失败锁定与 IP 限流已落地
     * (见 {@code LoginAttemptService}),否则暴力破解的性价比会随 cost 提升而上升。
     *
     * <p>关于性能:BCrypt 的校验代价由<b>哈希里记录的 cost</b>决定,而不是由本 Bean 的强度决定,
     * 因此既有 cost=10 的账号登录速度不变;只有"新建/修改密码"时的编码会变慢
     * (单次约 250~400ms),属可接受的一次性开销。多实例部署时各实例强度需保持一致。
     */
    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder(12);
    }

    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration config = new CorsConfiguration();
        config.setAllowedOriginPatterns(List.of("http://localhost:*"));
        config.setAllowedMethods(List.of("GET", "POST", "PUT", "DELETE", "OPTIONS"));
        config.setAllowedHeaders(List.of("*"));
        config.setAllowCredentials(true);
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", config);
        return source;
    }

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http
            .cors(cors -> cors.configurationSource(corsConfigurationSource()))
            .csrf(AbstractHttpConfigurer::disable)
            .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .authorizeHttpRequests(auth -> auth
                // R-02/R-33: 已移除 "/fhir/**" 与 "/actuator/**" 的匿名放行
                .requestMatchers("/api/auth/**",
                        "/swagger-ui/**", "/swagger-ui.html", "/api-docs/**", "/v3/api-docs/**").permitAll()
                .anyRequest().authenticated())
            .exceptionHandling(ex -> ex
                .authenticationEntryPoint((request, response, authException) -> {
                    response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
                    response.setContentType(MediaType.APPLICATION_JSON_VALUE);
                    Map<String, Object> body = new LinkedHashMap<>();
                    body.put("timestamp", LocalDateTime.now().toString());
                    body.put("status", 401);
                    body.put("error", "未认证");
                    body.put("message", "请先登录");
                    objectMapper.writeValue(response.getOutputStream(), body);
                })
                .accessDeniedHandler((request, response, accessDeniedException) -> {
                    response.setStatus(HttpServletResponse.SC_FORBIDDEN);
                    response.setContentType(MediaType.APPLICATION_JSON_VALUE);
                    Map<String, Object> body = new LinkedHashMap<>();
                    body.put("timestamp", LocalDateTime.now().toString());
                    body.put("status", 403);
                    body.put("error", "权限不足");
                    body.put("message", accessDeniedException.getMessage());
                    objectMapper.writeValue(response.getOutputStream(), body);
                }))
            .addFilterBefore(jwtAuthFilter, UsernamePasswordAuthenticationFilter.class);
        return http.build();
    }
}
