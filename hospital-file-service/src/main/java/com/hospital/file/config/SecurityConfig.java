package com.hospital.file.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

/**
 * 文件服务安全配置:内部 MinIO 薄封装,文件 API 对网关暴露,患者归属守卫由调用方(core)强制。
 *
 * <p>R-03:原先是 {@code anyRequest().permitAll()} 且没有任何过滤器,
 * 任何人直连 8103(或经网关 /api/files/**)即可匿名下载任意患者报告 PDF。
 * 本服务没有用户体系,不能直接用 {@code .anyRequest().authenticated()}
 * (没有 Authentication 时会被 Security 的授权过滤器全部判 401,连健康检查都挂),
 * 因此这里保留 {@code permitAll()},把鉴权下沉到 {@link InternalTokenFilter}:
 * 由过滤器校验 {@code X-Internal-Token},通过才放行,否则直接 401。
 *
 * <p>TODO(P1):这只是"应用层口令 + 纵深防御"的最小止血,生产必须再叠加网络隔离:
 * 8103 不映射宿主机端口、不接受公网流量,仅允许 core / gateway 所在安全组访问;
 * 长期方案是接入统一鉴权(oauth2ResourceServer().jwt())或由网关下发内部令牌。
 */
@Configuration
public class SecurityConfig {

    /** 内部调用令牌,未配置时为空串(过滤器放行并告警,见 InternalTokenFilter)。 */
    @Value("${file.internal-token:}")
    private String internalToken;

    /** R-03: 当前激活 profile,prod 下未配置令牌由 InternalTokenFilter 直接拒绝启动。 */
    @Value("${spring.profiles.active:dev}")
    private String activeProfile;

    @Bean
    public SecurityFilterChain devFilterChain(HttpSecurity http) throws Exception {
        http
            // R-03: 无会话、无表单登录,CSRF 对本内部文件 API 无意义
            .csrf(AbstractHttpConfigurer::disable)
            // R-03: 保持无状态,不创建 HttpSession
            .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            // R-03: 内部服务无用户上下文,授权交由 InternalTokenFilter 承担,
            // 这里若改 authenticated() 会因无 Authentication 导致全部请求 401
            .authorizeHttpRequests(auth -> auth.anyRequest().permitAll())
            // R-03: 令牌校验必须早于任何业务 Controller,挂在认证过滤器之前
            .addFilterBefore(new InternalTokenFilter(internalToken, activeProfile), UsernamePasswordAuthenticationFilter.class);
        return http.build();
    }
}
