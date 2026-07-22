package com.hospital.core.platform.security;

import com.hospital.core.platform.infrastructure.JwtTokenService;
import io.jsonwebtoken.Claims;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;

/**
 * JWT 认证过滤器:从 Authorization: Bearer <token> 头解析 token,
 * 校验通过后把 username/authorities 注入 SecurityContext,供 @PreAuthorize 使用。
 */
@Component
@RequiredArgsConstructor
public class JwtAuthFilter extends OncePerRequestFilter {

    private static final String BEARER_PREFIX = "Bearer ";
    private final JwtTokenService jwtTokenService;

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String token = resolveToken(request);
        if (StringUtils.hasText(token)) {
            Claims claims = jwtTokenService.parse(token);
            if (claims != null) {
                List<String> authorities = jwtTokenService.authoritiesOf(claims);
                var auth = new UsernamePasswordAuthenticationToken(
                        jwtTokenService.usernameOf(claims),
                        null,
                        authorities.stream().map(SimpleGrantedAuthority::new).toList());
                auth.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));
                SecurityContextHolder.getContext().setAuthentication(auth);
            }
        }
        chain.doFilter(request, response);
    }

    /**
     * 解析 JWT:优先 Authorization: Bearer 头;
     * 回退 query 参数 token——SSE 订阅走浏览器原生 EventSource,无法自定义请求头,
     * 只能把令牌放在 URL query 上携带。
     */
    private String resolveToken(HttpServletRequest request) {
        String header = request.getHeader("Authorization");
        if (StringUtils.hasText(header) && header.startsWith(BEARER_PREFIX)) {
            return header.substring(BEARER_PREFIX.length());
        }
        return request.getParameter("token");
    }
}
