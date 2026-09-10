package com.hospital.core.platform.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.util.ReflectionTestUtils;

import com.hospital.core.platform.infrastructure.JwtTokenService;

/**
 * R-34: {@link SseTicketAuthFilter} 行为测试(纯 Mockito,无 Spring 上下文)。
 *
 * <p>校验:无 ticket 放行(交 SecurityConfig 兜 401)、非法 ticket → 401、
 * 普通 JWT 当 ticket → 401、合法 ticket → 注入正确的 Authentication、非 SSE 路径被忽略。
 */
class SseTicketAuthFilterTest {

    private static final String SSE_PATH = "/api/core/dispatch/sse/subscribe";

    private JwtTokenService jwtTokenService;
    private SseTicketAuthFilter filter;

    @BeforeEach
    void setUp() {
        jwtTokenService = newJwtTokenService();
        filter = new SseTicketAuthFilter(jwtTokenService);
    }

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    @DisplayName("R-34:SSE 路径但无 ticket → 放行(不注入),由 SecurityConfig 统一 401")
    void noTicketPassesThroughWithoutAuthentication() throws Exception {
        MockHttpServletRequest request = sseRequest();
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request, response, chain);

        assertThat(response.getStatus()).isNotEqualTo(401);
        assertThat(chain.getRequest()).as("应放行,由 authenticated() 兜底").isNotNull();
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
    }

    @Test
    @DisplayName("R-34:非法 ticket → 401,不进入过滤器链")
    void invalidTicketIsUnauthorized() throws Exception {
        MockHttpServletRequest request = sseRequest();
        request.setParameter("ticket", "not-a-jwt");
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request, response, chain);

        assertThat(response.getStatus()).isEqualTo(401);
        assertThat(chain.getRequest()).isNull();
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
    }

    @Test
    @DisplayName("R-34:把普通 JWT 塞进 ?ticket= → 401(scope 非 sse)")
    void normalJwtAsTicketIsUnauthorized() throws Exception {
        String normalToken = jwtTokenService.issue(
                "13800000001", List.of("DOCTOR"), List.of("visit:entry"));
        MockHttpServletRequest request = sseRequest();
        request.setParameter("ticket", normalToken);
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request, response, chain);

        assertThat(response.getStatus()).as("普通 JWT 不得当作 SSE ticket 使用").isEqualTo(401);
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
    }

    @Test
    @DisplayName("R-34:合法 ticket → 注入 Authentication,authorities 正确")
    void validTicketInjectsAuthentication() throws Exception {
        String ticket = jwtTokenService.issueSseTicket(
                "13800000001", List.of(), List.of("visit:entry", "dispatch:board"));
        MockHttpServletRequest request = sseRequest();
        request.setParameter("ticket", ticket);
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request, response, chain);

        var authentication = SecurityContextHolder.getContext().getAuthentication();
        assertThat(authentication).as("合法 ticket 应注入认证").isNotNull();
        assertThat(authentication.getName()).isEqualTo("13800000001");
        assertThat(authentication.getAuthorities())
                .extracting(Object::toString)
                .containsExactlyInAnyOrder("visit:entry", "dispatch:board");
        assertThat(chain.getRequest()).as("应继续过滤器链").isNotNull();
    }

    @Test
    @DisplayName("R-34:非 SSE 路径即使带 ticket 也不注入认证")
    void nonSsePathIsIgnored() throws Exception {
        String ticket = jwtTokenService.issueSseTicket(
                "13800000001", List.of(), List.of("visit:entry"));
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRequestURI("/api/core/visits");
        request.setParameter("ticket", ticket);
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request, response, chain);

        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
        assertThat(chain.getRequest()).isNotNull();
    }

    private static MockHttpServletRequest sseRequest() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRequestURI(SSE_PATH);
        return request;
    }

    private static JwtTokenService newJwtTokenService() {
        JwtTokenService service = new JwtTokenService();
        ReflectionTestUtils.setField(service, "secret", "unit-test-secret-key-must-be-at-least-32-bytes");
        ReflectionTestUtils.setField(service, "activeProfile", "dev");
        ReflectionTestUtils.setField(service, "ttlMillis", 14_400_000L);
        ReflectionTestUtils.invokeMethod(service, "init");
        return service;
    }
}
