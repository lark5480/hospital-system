package com.hospital.core.platform.security;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.springframework.beans.factory.annotation.Value;
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

import jakarta.servlet.DispatcherType;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 统一 JWT 安全配置(无外部 IdP 依赖,自管 token)。
 * - 登录接口 /api/auth/** 与 swagger 公开,其余需 JWT 认证。
 * - 无状态会话(STATELESS),服务端不存 session。
 * - 方法级鉴权 @PreAuthorize 启用,接口鉴权走 token 中的 authorities。
 *
 * R-02: /fhir/** 已从 permitAll 移除(原先匿名放行会泄露全院患者身份证 / 手机号 / 诊断),
 *       现需携带有效 JWT,并由 Controller 上的 @PreAuthorize 做细粒度授权(由另一子代理补齐)。
 * R-33: /actuator/** 同样不再匿名放行,避免暴露健康检查 / 环境信息;
 *       此外 Swagger / OpenAPI 文档在生产环境不再匿名放行(见 {@link #swaggerPermitMatchers()})。
 * R-34: 新增 {@link SseTicketAuthFilter},把 SSE 订阅凭证从"长期 JWT(query token)"改为
 *       "60 秒短期 ticket(query ticket)",并注册在原 {@link JwtAuthFilter} 之后。
 * R-65: ASYNC / ERROR 派发放行(见 {@code authorizeHttpRequests} 首条规则)。SSE 的 60s 超时会让容器
 *       以 ASYNC 派发重新进入过滤器链,而该次派发已无 SecurityContext、自定义过滤器又默认跳过 ASYNC,
 *       授权会把它当匿名拒绝 —— 修复前表现为响应已提交后刷 "Unable to handle the Spring Security
 *       Exception"。守护测试:{@code SecurityConfigAsyncDispatchTest}。
 *
 * 未来接外部 IdP 时,只需把 login 接口改为"校验外部 token → 换签自有 JWT",
 * 过滤器与 SecurityConfig 不动。
 */
@Slf4j
@Configuration
@EnableMethodSecurity
@RequiredArgsConstructor
public class SecurityConfig {

    /**
     * R-33: Swagger / OpenAPI 相关路径。
     * <p>为什么必须在生产移除:这些路径会暴露完整的接口清单、参数与数据模型,
     * 相当于免费给攻击者一份"系统地图"。本地/演示保留匿名放行以方便联调。
     */
    private static final String[] SWAGGER_PATHS = {
            "/swagger-ui/**", "/swagger-ui.html", "/api-docs/**", "/v3/api-docs/**"
    };

    private final JwtAuthFilter jwtAuthFilter;
    private final SseTicketAuthFilter sseTicketAuthFilter;
    private final ObjectMapper objectMapper;

    /** R-33: 当前激活 profile,用于判断是否生产环境。 */
    @Value("${spring.profiles.active:dev}")
    private String activeProfile;

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

    /**
     * R-33: 依据运行环境返回 swagger/OpenAPI 的匿名放行 matcher。
     *
     * <ul>
     *   <li>非 prod(本地/演示):返回全部 swagger 路径,保持匿名可访问;</li>
     *   <li>prod:返回空数组 —— swagger 相关路径从 permitAll 中移除,改走 authenticated()。</li>
     * </ul>
     *
     * <p>提取为独立方法,既避免在 {@code filterChain} 里复制两份 {@code authorizeHttpRequests} 代码块,
     * 也让该安全决策可被单元测试直接断言(见 {@code SecurityConfigSwaggerAccessTest})。
     */
    String[] swaggerPermitMatchers() {
        return isProductionProfile() ? new String[0] : SWAGGER_PATHS.clone();
    }

    /** R-33: 是否生产环境(与 {@code JwtTokenService} / file-service 的判定口径一致:支持逗号分隔多 profile,忽略大小写)。 */
    private boolean isProductionProfile() {
        if (activeProfile == null || activeProfile.isBlank()) {
            return false;
        }
        for (String p : activeProfile.split(",")) {
            String t = p.trim().toLowerCase(Locale.ROOT);
            if ("prod".equals(t) || "production".equals(t)) {
                return true;
            }
        }
        return false;
    }

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        // R-33: 生产环境关闭匿名文档访问。条件化构造 matcher 数组,不复制两份 authorizeHttpRequests。
        String[] swaggerPaths = swaggerPermitMatchers();
        if (swaggerPaths.length == 0) {
            log.warn("[R-33] 检测到生产 profile({}),已关闭 Swagger / OpenAPI 文档的匿名访问;"
                            + "如需查看 /api-docs 或 /swagger-ui /**,请携带有效 JWT,或显式放开对应路径。",
                    activeProfile);
        }

        http
            .cors(cors -> cors.configurationSource(corsConfigurationSource()))
            .csrf(AbstractHttpConfigurer::disable)
            .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .authorizeHttpRequests(auth -> {
                // R-65: ASYNC / ERROR 派发放行。
                // 为什么必须放行:SSE(SseEmitter)超时后,容器会以 DispatcherType.ASYNC 把这次请求
                // 重新派发回过滤器链,而此刻 SecurityContextHolder 已随首次 REQUEST 派发结束而清空,
                // JwtAuthFilter / SseTicketAuthFilter 又是 OncePerRequestFilter(默认跳过 ASYNC),
                // 于是这次派发在授权看来是匿名 → AuthorizationFilter 判 AccessDeniedException,
                // 响应却已提交 → "Unable to handle the Spring Security Exception because the response
                // is already committed" 刷 ERROR,并连带把 /error 的 ERROR 派发也判死。
                // 为什么安全:ASYNC 派发无法由外部凭空发起,只能是"已通过 REQUEST 授权的那次请求"的延续
                // (控制器方法不会被重新调用),再次鉴权只会重复评估、不新增保护;ERROR 派发放行则是为了让
                // 错误页不被 401/403 掩盖(仅对 ERROR 派发生效,直接以 REQUEST 访问 /error 仍需认证)。
                auth.dispatcherTypeMatchers(DispatcherType.ASYNC, DispatcherType.ERROR).permitAll();
                // R-02/R-33: 已移除 "/fhir/**" 与 "/actuator/**" 的匿名放行
                auth.requestMatchers("/api/auth/**").permitAll();
                // R-33: 仅非生产环境匿名放行 swagger 文档
                if (swaggerPaths.length > 0) {
                    auth.requestMatchers(swaggerPaths).permitAll();
                }
                auth.anyRequest().authenticated();
            })
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
            .addFilterBefore(jwtAuthFilter, UsernamePasswordAuthenticationFilter.class)
            // R-34: SSE ticket 过滤器必须排在 JwtAuthFilter 之后,确保 ?ticket= 不会被当作普通令牌
            .addFilterAfter(sseTicketAuthFilter, JwtAuthFilter.class);
        return http.build();
    }
}
