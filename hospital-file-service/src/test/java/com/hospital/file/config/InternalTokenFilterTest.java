package com.hospital.file.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

/**
 * R-27:InternalTokenFilter 安全语义守护测试(纯单元测试,不启 Spring 上下文)。
 *
 * <p>守护目标:文件服务没有用户体系,唯一挡住"任何人直连 8103 就能拖走患者报告"的
 * 防线就是这个过滤器(见 R-03)。一旦有人把它删掉、改成 permitAll、或把
 * "未配置令牌"分支的语义改反,下面这些用例必须失败。
 *
 * <p>这里用 MockHttpServletRequest/Response/FilterChain 直接驱动过滤器,
 * 不依赖 MinIO,可在无任何外部中间件的环境下稳定运行。
 */
class InternalTokenFilterTest {

    /** 测试中使用的内部调用令牌。 */
    private static final String TOKEN = "test-token";

    // R-27: 该路径既不是探活端点也不是 OPTIONS,必须被令牌拦住
    private static final String BIZ_PATH = "/api/files/reports/1001.pdf";

    @Test
    @DisplayName("R-27 用例A:已配置令牌但请求不带 X-Internal-Token → 401,且不进入后续过滤器链")
    void missingTokenShouldBeRejected() throws Exception {
        FilterResult result = filter(TOKEN, "GET", BIZ_PATH, null);

        assertThat(result.response().getStatus()).isEqualTo(401);
        assertThat(result.chainCalled()).isFalse();
        assertThat(result.response().getContentAsString()).contains("未授权");
    }

    @Test
    @DisplayName("R-27 用例B:已配置令牌但 X-Internal-Token 不匹配 → 401")
    void wrongTokenShouldBeRejected() throws Exception {
        FilterResult result = filter(TOKEN, "GET", BIZ_PATH, "wrong-token");

        assertThat(result.response().getStatus()).isEqualTo(401);
        assertThat(result.chainCalled()).isFalse();
    }

    @Test
    @DisplayName("R-27 用例B2:令牌为空串/大小写不一致也不能蒙混过关")
    void blankOrCaseMismatchedTokenShouldBeRejected() throws Exception {
        assertThat(filter(TOKEN, "GET", BIZ_PATH, "").response().getStatus()).isEqualTo(401);
        assertThat(filter(TOKEN, "GET", BIZ_PATH, TOKEN.toUpperCase()).response().getStatus()).isEqualTo(401);
    }

    @Test
    @DisplayName("R-27 用例C:令牌匹配 → 放行(状态码保持 200 且过滤器链被继续调用)")
    void matchedTokenShouldPass() throws Exception {
        FilterResult result = filter(TOKEN, "GET", BIZ_PATH, TOKEN);

        assertThat(result.response().getStatus()).isEqualTo(200);
        assertThat(result.chainCalled()).isTrue();
    }

    @Test
    @DisplayName("R-27 用例D:未配置令牌(默认空)→ 放行并告警,保证本地零配置仍可跑")
    void notConfiguredTokenShouldPass() throws Exception {
        FilterResult result = filter("", "GET", BIZ_PATH, null);

        assertThat(result.response().getStatus()).isEqualTo(200);
        assertThat(result.chainCalled()).isTrue();
    }

    @Test
    @DisplayName("R-27 用例E:/api/files/health 探活端点不带令牌也不被 401")
    void fileHealthEndpointShouldPassWithoutToken() throws Exception {
        FilterResult result = filter(TOKEN, "GET", "/api/files/health", null);

        assertThat(result.response().getStatus()).isNotEqualTo(401);
        assertThat(result.chainCalled()).isTrue();
    }

    @Test
    @DisplayName("R-27 用例E:/actuator/health 探活端点不带令牌也不被 401")
    void actuatorHealthEndpointShouldPassWithoutToken() throws Exception {
        FilterResult result = filter(TOKEN, "GET", "/actuator/health", null);

        assertThat(result.response().getStatus()).isNotEqualTo(401);
        assertThat(result.chainCalled()).isTrue();
    }

    @Test
    @DisplayName("R-27:OPTIONS 预检请求不带自定义头,必须放行")
    void optionsPreflightShouldPass() throws Exception {
        FilterResult result = filter(TOKEN, "OPTIONS", BIZ_PATH, null);

        assertThat(result.response().getStatus()).isNotEqualTo(401);
        assertThat(result.chainCalled()).isTrue();
    }

    @Test
    @DisplayName("R-27:context-path 部署时探活路径仍能被识别(不等于白名单则会被 401)")
    void skipPathShouldBeRecognizedUnderContextPath() throws Exception {
        InternalTokenFilter filter = new InternalTokenFilter(TOKEN, "dev");
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/file/api/files/health");
        request.setRequestURI("/file/api/files/health");
        request.setContextPath("/file");
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request, response, chain);

        assertThat(response.getStatus()).isNotEqualTo(401);
        assertThat(chain.getRequest()).isNotNull();
    }

    @Test
    @DisplayName("R-27:401 响应必须是 JSON 且禁止缓存(不落任何对象名/元数据)")
    void unauthorizedResponseShouldBeJsonAndNoStore() throws Exception {
        FilterResult result = filter(TOKEN, "GET", BIZ_PATH, null);

        assertThat(result.response().getContentType()).contains("application/json");
        assertThat(result.response().getHeader("Cache-Control")).isEqualTo("no-store");
    }

    @Test
    @DisplayName("R-27:prod profile 且未配置令牌 → 构造即抛异常(生产 fail-fast,不许裸奔)")
    void productionWithoutTokenShouldFailFast() {
        assertThatThrownBy(() -> new InternalTokenFilter("", "prod"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("file.internal-token");
    }

    @Test
    @DisplayName("R-27:非 prod profile(如 dev/test)未配置令牌不抛异常,保证本地可跑")
    void nonProductionWithoutTokenShouldNotFailFast() {
        assertThat(new InternalTokenFilter("", "dev")).isNotNull();
        assertThat(new InternalTokenFilter("", "test,dev")).isNotNull();
    }

    /**
     * 驱动一次过滤。
     *
     * @param configuredToken 过滤器配置的内部令牌(模拟 {@code file.internal-token})
     * @param method HTTP 方法
     * @param uri 请求 URI
     * @param providedToken 请求携带的 {@code X-Internal-Token},{@code null} 表示不带该头
     */
    private static FilterResult filter(String configuredToken, String method, String uri, String providedToken)
            throws Exception {
        InternalTokenFilter filter = new InternalTokenFilter(configuredToken, "dev");
        MockHttpServletRequest request = new MockHttpServletRequest(method, uri);
        request.setRequestURI(uri);
        if (providedToken != null) {
            request.addHeader(InternalTokenFilter.TOKEN_HEADER, providedToken);
        }
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request, response, chain);

        return new FilterResult(response, chain.getRequest() != null);
    }

    /** 一次过滤的结果快照。 */
    private record FilterResult(MockHttpServletResponse response, boolean chainCalled) {
    }
}
