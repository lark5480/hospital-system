package com.hospital.notification.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import jakarta.servlet.ServletRequest;
import jakarta.servlet.http.HttpServletRequest;

/**
 * R-11:{@link QueryTicketBearerFilter} 的单元测试。
 *
 * <p>只验证"头提升"这一件事:有 {@code ?ticket=} 且<b>无</b> {@code Authorization} 时,
 * 下游看到的是 {@code Authorization: Bearer <ticket>};已有 {@code Authorization} 时<b>不覆盖</b>;
 * 无 ticket 时<b>不注入</b>。合法性/scope/权限的判定不在此层,由标准 JWT 链路负责。
 */
class QueryTicketBearerFilterTest {

    private final QueryTicketBearerFilter filter = new QueryTicketBearerFilter();

    /** R-11: 从过滤链下游看到的请求里取头值(chain 回调收到的是 ServletRequest,向下转型)。 */
    private static String headerOf(ServletRequest request, String name) {
        return ((HttpServletRequest) request).getHeader(name);
    }

    @Test
    @DisplayName("R-11:?ticket= 会被提升为 Authorization: Bearer <ticket>")
    void shouldPromoteTicketToAuthorization() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/notify/subscribe");
        request.setQueryString("ticket=abc.def.ghi");

        AtomicReference<String> seen = new AtomicReference<>();
        filter.doFilter(request, new MockHttpServletResponse(),
                (req, res) -> seen.set(headerOf(req, "Authorization")));

        assertThat(seen.get()).isEqualTo("Bearer abc.def.ghi");
    }

    @Test
    @DisplayName("R-11:已有 Authorization 头时不被覆盖")
    void shouldNotOverrideExistingAuthorization() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/notify/subscribe");
        request.setQueryString("ticket=abc.def.ghi");
        request.addHeader("Authorization", "Bearer real-long-lived-jwt");

        AtomicReference<String> seen = new AtomicReference<>();
        filter.doFilter(request, new MockHttpServletResponse(),
                (req, res) -> seen.set(headerOf(req, "Authorization")));

        assertThat(seen.get()).isEqualTo("Bearer real-long-lived-jwt");
    }

    @Test
    @DisplayName("R-11:无 ticket 时不注入 Authorization")
    void shouldNotInjectWhenNoTicket() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/notify/subscribe");

        AtomicReference<String> seen = new AtomicReference<>("__unset__");
        filter.doFilter(request, new MockHttpServletResponse(),
                (req, res) -> seen.set(headerOf(req, "Authorization")));

        assertThat(seen.get()).isNull();
    }

    @Test
    @DisplayName("R-11:只提升 Authorization,不影响其它请求头")
    void shouldNotTouchOtherHeaders() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/notify/subscribe");
        request.setQueryString("ticket=abc");
        request.addHeader("X-Trace-Id", "trace-1");

        AtomicReference<String> trace = new AtomicReference<>();
        filter.doFilter(request, new MockHttpServletResponse(),
                (req, res) -> trace.set(headerOf(req, "X-Trace-Id")));

        assertThat(trace.get()).isEqualTo("trace-1");
    }

    @Test
    @DisplayName("R-11:ticket 值会被 URL 解码")
    void shouldUrlDecodeTicketValue() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/notify/subscribe");
        request.setQueryString("other=1&ticket=a%2Bb%3Dc");

        AtomicReference<String> seen = new AtomicReference<>();
        filter.doFilter(request, new MockHttpServletResponse(),
                (req, res) -> seen.set(headerOf(req, "Authorization")));

        assertThat(seen.get()).isEqualTo("Bearer a+b=c");
    }

    @Test
    @DisplayName("R-11:query string 解析 —— ticket 不存在 / 空值 的边界")
    void extractTicketEdgeCases() {
        assertThat(QueryTicketBearerFilter.extractTicketFromQuery(null)).isNull();
        assertThat(QueryTicketBearerFilter.extractTicketFromQuery("")).isNull();
        assertThat(QueryTicketBearerFilter.extractTicketFromQuery("foo=bar")).isNull();
        assertThat(QueryTicketBearerFilter.extractTicketFromQuery("ticket=")).isEmpty();
        assertThat(QueryTicketBearerFilter.extractTicketFromQuery("ticket=x&y=z")).isEqualTo("x");
    }
}
