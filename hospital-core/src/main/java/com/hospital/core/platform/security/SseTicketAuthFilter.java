package com.hospital.core.platform.security;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;

import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;

import com.hospital.core.platform.infrastructure.JwtTokenService;

import io.jsonwebtoken.Claims;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;

/**
 * R-34: SSE 订阅凭证过滤器 —— 从 query 参数 {@code ticket} 解析短期 SSE ticket 并注入认证。
 *
 * <p>背景:浏览器原生 {@code EventSource} 无法自定义请求头,SSE 订阅只能把凭证放在 URL 上。
 * {@link JwtAuthFilter} 已删除 {@code ?token=} 回退(长期 JWT 进 URL 会泄漏到历史/日志/Referer),
 * 改由本过滤器消费短期 ticket:{@code /api/core/dispatch/sse/subscribe?ticket=<60s票据>}。
 *
 * <p>与 {@link JwtAuthFilter} 的分工:
 * <ul>
 *   <li>{@link JwtAuthFilter} 只认 {@code Authorization: Bearer},且拒绝把 ticket 当普通令牌用;</li>
 *   <li>本过滤器只认 SSE 路径上的 {@code ?ticket=},并校验其 {@code scope=sse} 声明。</li>
 * </ul>
 * 两者职责互斥,确保"ticket 泄漏也换不到全量 API 访问"。
 *
 * <p>关键行为:
 * <ul>
 *   <li>非 SSE 路径 → 直接放行(交其它过滤器处理);</li>
 *   <li>SSE 路径但无 {@code ticket} → 放行,交给 SecurityConfig 的 {@code authenticated()}
 *       拦下,保持 401 语义统一(避免"未带凭证"与"凭证非法"混淆);</li>
 *   <li>{@code ticket} 非法/过期、或不是 SSE ticket → 401 JSON,不进入 Controller;</li>
 *   <li>{@code ticket} 合法 → 注入 {@code UsernamePasswordAuthenticationToken}。</li>
 * </ul>
 */
@Component
@RequiredArgsConstructor
public class SseTicketAuthFilter extends OncePerRequestFilter {

    /** R-34: SSE ticket 的 query 参数名。 */
    public static final String TICKET_PARAM = "ticket";

    /** R-34: 当前已知的 SSE 订阅前缀。 */
    private static final String DISPATCH_SSE_PREFIX = "/api/core/dispatch/sse";

    /** R-34: 签发 ticket 的端点本身不参与本过滤器的 ticket 校验(它走 Bearer + 方法鉴权)。 */
    private static final String TICKET_ISSUE_ENDPOINT = "/api/core/sse/ticket";

    private final JwtTokenService jwtTokenService;

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String path = pathWithinApplication(request);
        // R-34: 仅对 SSE 订阅路径生效,其余请求一律放行
        if (!isSseSubscribePath(path)) {
            chain.doFilter(request, response);
            return;
        }

        String ticket = request.getParameter(TICKET_PARAM);
        // R-34: 没带 ticket 时不在这里报错,放行后由 SecurityConfig 的 authenticated() 统一返回 401,
        // 避免破坏"未认证 → 401"的语义一致性。
        if (!StringUtils.hasText(ticket)) {
            chain.doFilter(request, response);
            return;
        }

        Claims claims = jwtTokenService.parse(ticket);
        // R-34: 非法/过期,或不是 SSE ticket(把普通 JWT 塞进 ?ticket= 也在此拦下)→ 401
        if (claims == null || !jwtTokenService.isSseTicket(claims)) {
            reject(response);
            return;
        }

        String username = jwtTokenService.usernameOf(claims);
        List<String> authorities = jwtTokenService.authoritiesOf(claims);
        var auth = new UsernamePasswordAuthenticationToken(
                username,
                null,
                authorities.stream().map(SimpleGrantedAuthority::new).toList());
        auth.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));
        SecurityContextHolder.getContext().setAuthentication(auth);

        chain.doFilter(request, response);
    }

    /** R-34: 统一 401 JSON 响应,不注入任何认证。 */
    private static void reject(HttpServletResponse response) throws IOException {
        SecurityContextHolder.clearContext();
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.getWriter().write("{\"error\":\"SSE 订阅凭证无效或已过期\"}");
    }

    /**
     * R-34: 判断是否为 SSE 订阅路径。同时覆盖:
     * <ul>
     *   <li>已知的 {@code /api/core/dispatch/sse/**};</li>
     *   <li>后续可能新增的 {@code /api/core/**&#47;sse/**} 通用形式;</li>
     *   <li>排除签发端点 {@code /api/core/sse/ticket}(它不属于订阅路径)。</li>
     * </ul>
     */
    private static boolean isSseSubscribePath(String path) {
        if (path == null || path.isEmpty()) {
            return false;
        }
        if (TICKET_ISSUE_ENDPOINT.equals(path)) {
            return false;
        }
        if (path.startsWith(DISPATCH_SSE_PREFIX)) {
            return true;
        }
        // R-34: 通用形式 /api/core/.../sse/...
        return path.startsWith("/api/core/") && path.contains("/sse/");
    }

    /** R-34: 去掉 context-path,得到应用内路径(兼容将来挂 context-path 部署)。 */
    private static String pathWithinApplication(HttpServletRequest request) {
        String path = request.getRequestURI();
        String contextPath = request.getContextPath();
        if (contextPath != null && !contextPath.isEmpty() && path.startsWith(contextPath)) {
            path = path.substring(contextPath.length());
        }
        return path;
    }
}
