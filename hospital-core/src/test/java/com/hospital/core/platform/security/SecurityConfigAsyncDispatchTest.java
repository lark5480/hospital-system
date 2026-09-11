package com.hospital.core.platform.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import jakarta.servlet.DispatcherType;

/**
 * R-65:SSE 长连接超时后的 <b>ASYNC 派发</b>不得被再次鉴权。
 *
 * <p><b>修复前的真实故障链</b>(本地实测复现):
 * <ol>
 *   <li>前端凭 ticket 订阅 {@code /api/core/dispatch/sse/subscribe},拿到 {@code SseEmitter}(超时 60s);</li>
 *   <li>60s 后容器触发超时 → {@code WebAsyncManager} "Performing async dispatch",以
 *       {@link DispatcherType#ASYNC} 重新进入 Servlet 过滤器链;</li>
 *   <li>此时 {@code SecurityContextHolder} 已随首次 REQUEST 派发结束而清空,自定义过滤器
 *       ({@link JwtAuthFilter} / {@link SseTicketAuthFilter})又是 {@code OncePerRequestFilter},
 *       默认跳过 ASYNC 派发 → 这次派发在授权看来是<b>匿名</b>;</li>
 *   <li>{@code AuthorizationFilter} 于是判 {@code AccessDeniedException},而响应此时已提交,
 *       {@code ExceptionTranslationFilter} 只能抛 "Unable to handle the Spring Security Exception
 *       because the response is already committed",日志刷 ERROR,并连带把 {@code /error} 的 ERROR 派发也判死。</li>
 * </ol>
 *
 * <p><b>为什么 ASYNC 派发放行是安全的</b>:ASYNC 派发无法被外部凭空发起,它只能是"已经通过 REQUEST
 * 派发授权的那次请求"的延续(控制器方法不会被重新调用),因此再次要求认证只会重复评估、不会新增
 * 任何保护;ERROR 派发同理 —— 放行的目的是让错误页本身不被 401/403 掩盖。
 *
 * <p>本用例用真实安全过滤链断言"未认证的 ASYNC 派发不返回 401/403"。路径刻意选一个不存在的探针
 * 路径:授权一旦放行,请求会走到 DispatcherServlet 并以 404 收场,不会真的建立 SSE 连接、留下副作用。
 */
@SpringBootTest
@AutoConfigureMockMvc
class SecurityConfigAsyncDispatchTest {

    /** 不存在的探针路径:授权放行后必然 404,避免用例真的订阅到一个 SseEmitter。 */
    private static final String PROBE_PATH = "/api/core/dispatch/sse/__async-dispatch-probe__";

    @Autowired
    private MockMvc mockMvc;

    @Test
    @DisplayName("R-65:未认证的 ASYNC 派发必须放行(否则 SSE 超时后响应已提交 → Unable to handle the Spring Security Exception)")
    void asyncDispatchWithoutAuthenticationShouldNotBeReauthorized() throws Exception {
        MvcResult result = mockMvc.perform(get(PROBE_PATH).with(request -> {
            request.setDispatcherType(DispatcherType.ASYNC);
            return request;
        })).andReturn();

        int status = result.getResponse().getStatus();
        assertThat(status)
                .as("ASYNC 派发是已授权请求的延续,若这里再判 401/403,真实环境下会因响应已提交而刷 ERROR;实际状态码=%d", status)
                .isNotIn(401, 403);
    }
}
