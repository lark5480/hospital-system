package com.hospital.core.platform.security;

import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * 统一解析当前登录用户名。
 *
 * <p>{@link JwtAuthFilter} 校验 JWT 后注入 {@link JwtAuthenticationToken}。
 * {@code JwtAuthenticationToken.getName()} 返回 JWT 的 {@code sub},即登录用户名。
 * 患者档案按 username 绑定(见 patient.patient.username 列)。
 *
 * <p>C 端 patient 接口({@code /patient/me}、{@code /dispatch/my-queue} 等)据此识别当前登录用户。
 */
public final class CurrentUserResolver {

    private static final Logger log = LoggerFactory.getLogger(CurrentUserResolver.class);

    private CurrentUserResolver() {}

    /**
     * 解析当前请求的用户名。
     *
     * @param request HTTP 请求(用于在开发态无 Bearer 时读取 X-Username 头)
     * @return 用户名(如 patient01);无法解析时返回 null
     */
    public static String resolveUsername(HttpServletRequest request) {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();

        // 已认证主体(JwtAuthFilter 注入 JwtAuthenticationToken):getName() 即 sub=用户名
        if (auth != null && !(auth instanceof AnonymousAuthenticationToken) && auth.isAuthenticated()) {
            log.debug("[CurrentUserResolver] 已认证主体,取 auth.getName(): {}", auth.getName());
            return auth.getName();
        }

        // 开发态 fallback:前端 Axios 在无 Bearer 令牌时发送 X-Username 头(见 http.ts 拦截器)
        String devUser = request.getHeader("X-Username");
        if (devUser != null && !devUser.isBlank()) {
            log.debug("[CurrentUserResolver] 开发态从 X-Username 头解析到用户名: {}", devUser);
            return devUser;
        }

        log.debug("[CurrentUserResolver] 无有效认证且无 X-Username 头,返回 null");
        return null;
    }
}
