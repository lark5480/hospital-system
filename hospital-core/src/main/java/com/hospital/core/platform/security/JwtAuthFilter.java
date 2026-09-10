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
import org.springframework.http.MediaType;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Date;
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
    /** R-34: 改密 / 重置密码 / 登出后让旧 token 立即失效。 */
    private final TokenRevocationService tokenRevocationService;

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String token = resolveToken(request);
        if (StringUtils.hasText(token)) {
            Claims claims = jwtTokenService.parse(token);
            if (claims != null) {
                String username = jwtTokenService.usernameOf(claims);
                // R-34: 该 token 签发于"改密 / 重置密码 / 登出"之前 → 失效,要求重新登录
                // 用毫秒级 iatMs(标准 iat 只有秒级精度,不足以区分同秒内的新旧 token)
                if (tokenRevocationService.isRevoked(username, issuedAtMillisOf(claims))) {
                    SecurityContextHolder.clearContext();
                    response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
                    response.setContentType(MediaType.APPLICATION_JSON_VALUE);
                    response.setCharacterEncoding(StandardCharsets.UTF_8.name());
                    response.getWriter().write("{\"error\":\"令牌已失效,请重新登录\"}");
                    return;
                }
                List<String> authorities = jwtTokenService.authoritiesOf(claims);
                var auth = new UsernamePasswordAuthenticationToken(
                        username,
                        null,
                        authorities.stream().map(SimpleGrantedAuthority::new).toList());
                auth.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));
                SecurityContextHolder.getContext().setAuthentication(auth);
            }
        }
        chain.doFilter(request, response);
    }

    /**
     * R-34: 取 token 的毫秒级签发时间。优先自定义声明 iatMs;
     * 老版本签发的 token 没有该声明时,退化为标准 iat(秒级,精度略差但不会误放行新 token)。
     */
    private static long issuedAtMillisOf(Claims claims) {
        Object raw = claims.get("iatMs");
        if (raw instanceof Number number) {
            return number.longValue();
        }
        Date issuedAt = claims.getIssuedAt();
        return issuedAt == null ? 0L : issuedAt.getTime();
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
