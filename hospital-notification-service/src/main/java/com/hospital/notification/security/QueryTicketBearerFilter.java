package com.hospital.notification.security;

import java.io.IOException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;

import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;

/**
 * R-11: 把 query 参数 {@code ?ticket=} 提升为 {@code Authorization: Bearer <ticket>} 的过滤器。
 *
 * <p>背景:浏览器原生 {@code EventSource} 无法自定义请求头,SSE 订阅凭证只能放在 URL query 上。
 * 为了让后续校验完全复用 Spring Security 的 {@code oauth2ResourceServer().jwt()} 标准链路
 * (而不必在本服务手写 JWT 解析),这里用一个 {@link HttpServletRequestWrapper} 只改写
 * {@code Authorization} 头的取值:当原请求<b>没有</b> {@code Authorization} 且 query 上有
 * {@code ticket} 时,对外暴露为 {@code "Bearer " + ticket}。
 *
 * <p>边界(刻意的克制):
 * <ul>
 *   <li>只提升 {@code Authorization} 头,<b>不</b>修改其它任何请求头;</li>
 *   <li>原请求已带 {@code Authorization} 时一律不覆盖,尊重调用方显式携带的凭证;</li>
 *   <li>无 {@code ticket} 时不注入任何头,保持"未带凭证 → 401"的语义统一;</li>
 *   <li>直接解析原始 query string 而非 {@code getParameter},避免在非 GET 请求上触发请求体解析。</li>
 * </ul>
 *
 * <p>注意:本过滤器<b>只</b>注册在 SSE 订阅链路({@code /api/notify/subscribe})上,
 * 因此 {@code ?ticket=} 无法用于 REST 端点;且它不做任何"格式/合法性"判断 ——
 * 是否合法、{@code scope} 是否为 {@code sse}、权限是否足够,全部交给标准链路与授权规则裁决。
 */
public class QueryTicketBearerFilter extends OncePerRequestFilter {

    /** R-11: SSE ticket 的 query 参数名(与 core 的 SseTicketAuthFilter.TICKET_PARAM 保持一致)。 */
    public static final String TICKET_PARAM = "ticket";

    private static final String AUTHORIZATION = "Authorization";
    private static final String BEARER_PREFIX = "Bearer ";

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        // R-11: 已有 Authorization 头 → 不干预,透传
        if (StringUtils.hasText(request.getHeader(AUTHORIZATION))) {
            chain.doFilter(request, response);
            return;
        }

        String ticket = extractTicketFromQuery(request.getQueryString());
        // R-11: 没带 ticket(或为空)→ 不注入,交由授权规则返回 401
        if (!StringUtils.hasText(ticket)) {
            chain.doFilter(request, response);
            return;
        }

        chain.doFilter(new TicketBearerRequestWrapper(request, ticket), response);
    }

    /**
     * R-11: 从原始 query string 中提取 {@code ticket} 值(URL 解码)。
     * 直接解析字符串的好处是不会触碰请求体,可在任意方法上安全调用。
     */
    static String extractTicketFromQuery(String queryString) {
        if (!StringUtils.hasText(queryString)) {
            return null;
        }
        for (String pair : queryString.split("&")) {
            int eq = pair.indexOf('=');
            String name = eq >= 0 ? pair.substring(0, eq) : pair;
            if (TICKET_PARAM.equals(name)) {
                String rawValue = eq >= 0 ? pair.substring(eq + 1) : "";
                try {
                    return URLDecoder.decode(rawValue, StandardCharsets.UTF_8);
                } catch (Exception e) {
                    // 解码失败时退回原始值:交由后续 JWT 校验判定其是否合法
                    return rawValue;
                }
            }
        }
        return null;
    }

    /** R-11: 只改写 {@code Authorization} 头的请求包装器,其余方法/头全部委托给原请求。 */
    private static final class TicketBearerRequestWrapper extends HttpServletRequestWrapper {

        private final String authorization;

        TicketBearerRequestWrapper(HttpServletRequest request, String ticket) {
            super(request);
            this.authorization = BEARER_PREFIX + ticket;
        }

        @Override
        public String getHeader(String name) {
            if (AUTHORIZATION.equalsIgnoreCase(name)) {
                return authorization;
            }
            return super.getHeader(name);
        }

        @Override
        public java.util.Enumeration<String> getHeaders(String name) {
            if (AUTHORIZATION.equalsIgnoreCase(name)) {
                return java.util.Collections.enumeration(java.util.List.of(authorization));
            }
            return super.getHeaders(name);
        }
    }
}
