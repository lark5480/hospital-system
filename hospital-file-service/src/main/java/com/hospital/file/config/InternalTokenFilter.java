package com.hospital.file.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 内部调用令牌过滤器(R-03):文件服务没有用户体系,不能做"用户鉴权",
 * 至少要挡住"任何人直连 8103 就能拖库"的匿名访问。
 *
 * <p>策略:调用方(core / gateway)必须在请求头携带 {@code X-Internal-Token},
 * 且值等于配置 {@code file.internal-token}。
 *
 * <ul>
 *   <li>令牌一致 → 放行(内部可信调用)。</li>
 *   <li>令牌未配置(默认空)→ 放行并 WARN,保证本地/演示零配置可跑;
 *       生产必须注入 {@code FILE_INTERNAL_TOKEN},否则本过滤器形同虚设。</li>
 *   <li>已配置但不匹配 → 401 JSON,不进入 Controller。</li>
 *   <li>OPTIONS 预检与 /actuator/health、/api/files/health 直接放行(探活/注册中心依赖)。</li>
 * </ul>
 *
 * <p>TODO(P1):本过滤器只是"应用层口令",令牌一旦泄露即失效。生产还需叠加
 * 8103 不对外映射 + 网关统一鉴权(见 SecurityConfig 类注释)。
 */
@Slf4j
public class InternalTokenFilter extends OncePerRequestFilter {

    /** 内部调用令牌请求头。 */
    public static final String TOKEN_HEADER = "X-Internal-Token";

    /** 无需令牌的探活端点(网关/容器/K8s 探针依赖)。 */
    private static final List<String> SKIP_PATHS = List.of("/actuator/health", "/api/files/health");

    /** 配置的内部调用令牌;为空表示未启用(放行 + 告警)。 */
    private final String internalToken;

    /** 未配置令牌的告警只打印一次,避免每个请求刷日志。 */
    private final AtomicBoolean warnOnce = new AtomicBoolean(false);

    /**
     * @param internalToken 内部调用令牌,取自 {@code file.internal-token}(默认空串)
     * @param activeProfile 当前激活 profile,用于生产环境 fail-fast 校验
     */
    public InternalTokenFilter(@Value("${file.internal-token:}") String internalToken,
                               @Value("${spring.profiles.active:dev}") String activeProfile) {
        this.internalToken = internalToken == null ? "" : internalToken;
        // R-03: 与 JwtTokenService 的密钥校验对称 —— prod 环境未配置令牌直接拒绝启动。
        // 否则"内部令牌"形同虚设:任何人直连 8103 仍可匿名拖走全部患者报告。
        if (this.internalToken.isBlank() && isProduction(activeProfile)) {
            throw new IllegalStateException(
                    "R-03 安全启动检查失败:file.internal-token 未配置。"
                            + "生产环境必须通过环境变量 FILE_INTERNAL_TOKEN 注入内部调用令牌,否则文件服务对外无防护。");
        }
    }

    /** 当前 profile 是否为生产(与 JwtTokenService 的判定口径一致)。 */
    private static boolean isProduction(String activeProfile) {
        if (activeProfile == null || activeProfile.isBlank()) {
            return false;
        }
        for (String p : activeProfile.split(",")) {
            String t = p.trim();
            if ("prod".equalsIgnoreCase(t) || "production".equalsIgnoreCase(t)) {
                return true;
            }
        }
        return false;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        // R-03: CORS 预检不带自定义头,直接放行,由 CorsFilter 处理
        String method = request.getMethod();
        if (method != null && HttpMethod.OPTIONS.matches(method)) {
            chain.doFilter(request, response);
            return;
        }

        // R-03: 探活端点放行,保证容器探针/网关健康检查不被令牌挡住
        if (isSkipPath(request)) {
            chain.doFilter(request, response);
            return;
        }

        // R-03: 未配置令牌 → 放行但告警(本地零配置可跑,生产必须配置)
        if (internalToken.isEmpty()) {
            if (warnOnce.compareAndSet(false, true)) {
                log.warn("file.internal-token 未配置,文件服务处于无防护状态,生产必须配置");
            }
            chain.doFilter(request, response);
            return;
        }

        String provided = request.getHeader(TOKEN_HEADER);
        // R-03: 用定长时间比较,降低基于响应耗时的口令爆破可行性
        if (provided != null && MessageDigest.isEqual(
                internalToken.getBytes(StandardCharsets.UTF_8),
                provided.getBytes(StandardCharsets.UTF_8))) {
            chain.doFilter(request, response);
            return;
        }

        // R-03: 不匹配 → 401,不落任何文件元数据/对象名到响应里
        log.warn("[file] 拒绝未授权访问: {} {} (缺少或不匹配的 {})",
                method, request.getRequestURI(), TOKEN_HEADER);
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.setHeader(HttpHeaders.CACHE_CONTROL, "no-store");
        response.getWriter().write("{\"error\":\"未授权\"}");
    }

    /** 是否为免鉴权的探活路径(兼容 context-path 部署)。 */
    private boolean isSkipPath(HttpServletRequest request) {
        String path = request.getRequestURI();
        String contextPath = request.getContextPath();
        if (contextPath != null && !contextPath.isEmpty() && path.startsWith(contextPath)) {
            path = path.substring(contextPath.length());
        }
        for (String skip : SKIP_PATHS) {
            if (skip.equals(path)) {
                return true;
            }
        }
        return false;
    }
}
