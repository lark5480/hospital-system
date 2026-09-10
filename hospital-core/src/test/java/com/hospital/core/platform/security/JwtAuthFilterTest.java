package com.hospital.core.platform.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;

import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.util.ReflectionTestUtils;

import com.hospital.core.platform.infrastructure.JwtTokenService;

/**
 * R-34: {@link JwtAuthFilter} 的收口回归 —— 纯 Mockito,不启 Spring 上下文。
 *
 * <p>守护两条关键语义:
 * <ol>
 *   <li><b>ticket 不能当普通令牌用</b>:带 {@code scope=sse} 的凭证走普通请求必须 401
 *       且不注入 Authentication。这是"ticket 泄漏也换不到全量 API 访问"的核心护栏。</li>
 *   <li><b>?token= 回退已删除</b>:仅凭 query 参数 token(无 Authorization 头)不得被认证,
 *       否则长期 JWT 会再次进入 URL(历史/日志/Referer 泄漏)。</li>
 * </ol>
 */
@ExtendWith(MockitoExtension.class)
class JwtAuthFilterTest {

    private JwtTokenService jwtTokenService;

    @Mock
    private TokenRevocationService tokenRevocationService;

    private JwtAuthFilter filter;

    @BeforeEach
    void setUp() {
        jwtTokenService = newJwtTokenService();
        filter = new JwtAuthFilter(jwtTokenService, tokenRevocationService);
        // R-34: 默认不被吊销(部分用例不会走到吊销检查,故用 lenient)
        lenient().when(tokenRevocationService.isRevoked(anyString(), anyLong())).thenReturn(false);
    }

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    @DisplayName("R-34:带 scope=sse 的 ticket 当普通令牌使用 → 401 且不注入 Authentication")
    void sseTicketCannotBeUsedAsNormalToken() throws Exception {
        String ticket = jwtTokenService.issueSseTicket(
                "13800000001", List.of(), List.of("visit:entry"));

        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("Authorization", "Bearer " + ticket);
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request, response, chain);

        assertThat(response.getStatus()).as("ticket 当普通令牌用必须 401").isEqualTo(401);
        assertThat(SecurityContextHolder.getContext().getAuthentication())
                .as("不得为 ticket 注入认证")
                .isNull();
        assertThat(chain.getRequest()).as("被拒绝后不应继续过滤器链").isNull();
    }

    @Test
    @DisplayName("R-34:仅 ?token=<合法普通JWT>、无 Authorization 头 → 不再认证(回退已删除)")
    void queryTokenParameterIsNoLongerAccepted() throws Exception {
        String normalToken = jwtTokenService.issue(
                "13800000001", List.of("DOCTOR"), List.of("visit:entry"));

        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setParameter("token", normalToken);
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request, response, chain);

        assertThat(SecurityContextHolder.getContext().getAuthentication())
                .as("query 参数中的 token 不得被接受")
                .isNull();
        // 未认证会交给后续 authenticated() 统一返回 401;本过滤器本身不报错、放行
        assertThat(response.getStatus()).isNotEqualTo(401);
        assertThat(chain.getRequest()).as("过滤器应放行,由 SecurityConfig 兜底 401").isNotNull();
    }

    @Test
    @DisplayName("R-34 对照:Authorization 头中的普通 JWT 仍能正常认证")
    void normalBearerTokenStillAuthenticates() throws Exception {
        String normalToken = jwtTokenService.issue(
                "13800000001", List.of("DOCTOR"), List.of("visit:entry", "order:execute"));

        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("Authorization", "Bearer " + normalToken);
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request, response, chain);

        var authentication = SecurityContextHolder.getContext().getAuthentication();
        assertThat(authentication).as("普通 JWT 应正常认证").isNotNull();
        assertThat(authentication.getName()).isEqualTo("13800000001");
        assertThat(authentication.getAuthorities())
                .extracting(Object::toString)
                .containsExactlyInAnyOrder("visit:entry", "order:execute");
    }

    /** R-34: 用真实密钥构造 JwtTokenService(测试密钥固定,避免走随机兜底)。 */
    private static JwtTokenService newJwtTokenService() {
        JwtTokenService service = new JwtTokenService();
        ReflectionTestUtils.setField(service, "secret", "unit-test-secret-key-must-be-at-least-32-bytes");
        ReflectionTestUtils.setField(service, "activeProfile", "dev");
        ReflectionTestUtils.setField(service, "ttlMillis", 14_400_000L);
        ReflectionTestUtils.invokeMethod(service, "init");
        return service;
    }
}
