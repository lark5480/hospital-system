package com.hospital.core.platform.security;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.hospital.core.platform.infrastructure.JwtTokenService;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;

/**
 * R-34: 签发短期 SSE 订阅凭证(ticket)的端点。
 *
 * <p>前端在建立 {@code EventSource} 前先调用本接口(走既有的 Bearer 认证),
 * 拿到一个 60 秒有效、带 {@code scope=sse} 的 ticket,再用 {@code ?ticket=<ticket>}
 * 订阅 SSE。这样 URL 上只出现短期票据,而非有效期数小时的完整 JWT。
 *
 * <p>医护与患者都要能订阅各自的 SSE,故仅要求"已认证"({@code isAuthenticated()}),
 * 细粒度权限仍由订阅端点自己的 {@code @PreAuthorize} 决定。
 *
 * <p>注意:签发<b>不引入任何额外查询</b> —— username 与 authorities 直接取自
 * {@code SecurityContextHolder}(即 {@link com.hospital.core.platform.security.JwtAuthFilter}
 * 已注入的认证),不查库取 roles,避免把一次简单签发放大成数据库往返。
 */
@Tag(name = "平台", description = "SSE 订阅凭证签发")
@RestController
@RequestMapping("/api/core/sse")
@RequiredArgsConstructor
public class SseTicketController {

    private final JwtTokenService jwtTokenService;

    /** R-34: 返回值中的有效期(秒),与 {@link JwtTokenService#SSE_TICKET_TTL_MILLIS} 对齐。 */
    private static final long EXPIRES_IN_SECONDS = JwtTokenService.SSE_TICKET_TTL_MILLIS / 1000;

    @Operation(summary = "签发短期 SSE 订阅凭证(ticket)")
    @PostMapping("/ticket")
    // R-34: 需登录;医护/患者均可申请,各自按 authorities 订阅各自的事件
    @PreAuthorize("isAuthenticated()")
    public Map<String, Object> issueTicket(Authentication authentication) {
        String username = authentication.getName();
        List<String> authorities = authentication.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .toList();
        // R-34: roles 直接置空,SSE 授权只依赖 authorities,避免为签发而查库
        String ticket = jwtTokenService.issueSseTicket(username, List.of(), authorities);

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("ticket", ticket);
        body.put("expiresIn", EXPIRES_IN_SECONDS);
        return body;
    }
}
