package com.hospital.notification.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;

/**
 * R-11:通知服务 REST 端点的鉴权契约测试(订阅链之外的"另一个入口")。
 *
 * <p>为什么单独一个类:收紧 {@code /api/notify/subscribe} 只堵住了"订阅"这条口子。
 * 通知内容本身是<b>全院级</b>的,只要 {@code /api/notify/events*} 仍是"认证即可读",
 * 患者账号照样能把全院通知拉走 —— 同一个横向泄露,换了个入口而已。
 *
 * <p>本类锁住两条不变式:
 * <ol>
 *   <li><b>读取端点与订阅同权限</b>:员工 token 可读,患者 token → 403;</li>
 *   <li><b>ticket 不得当普通令牌用</b>:把 {@code scope=sse} 的 ticket 塞进
 *       {@code Authorization: Bearer} 头调 REST 端点 → 401。
 *       这是与 hospital-core 的 {@code JwtAuthFilter} 对齐的同一不变式 ——
 *       "能放在 URL 上的凭证"与"请求头里的凭证"边界必须清晰。</li>
 * </ol>
 *
 * <p>注意:ticket 走 {@code ?ticket=} 而非请求头时,本链不注册 {@code QueryTicketBearerFilter},
 * 因此根本不会被提升为凭证(由 {@code NotificationSseSecurityTest} 从订阅侧覆盖)。
 */
@SpringBootTest(properties = {
        "spring.rabbitmq.listener.simple.auto-startup=false",
        "spring.rabbitmq.listener.direct.auto-startup=false",
        "app.jwt.secret=" + SseTestTokens.SECRET
})
@AutoConfigureMockMvc
class NotificationRestSecurityTest {

    private static final String EVENTS = "/api/notify/events";
    private static final String EVENTS_BY_ROLE = "/api/notify/events/by-role";
    private static final String HEALTH = "/api/notify/health";

    @Autowired
    private MockMvc mockMvc;

    @Test
    @DisplayName("R-11:未带凭证读取通知列表 → 401")
    void eventsWithoutCredentialShouldBeUnauthorized() throws Exception {
        assertThat(statusOfGet(EVENTS)).isEqualTo(401);
    }

    @Test
    @DisplayName("R-11:患者 token 读取全院通知 → 403(读取端点与订阅同权限)")
    void eventsWithPatientTokenShouldBeForbidden() throws Exception {
        String token = SseTestTokens.plainJwt("patient-1", List.of("patient:booking"));

        assertThat(statusOfGetWithBearer(EVENTS, token)).isEqualTo(403);
    }

    @Test
    @DisplayName("R-11:患者 token 读取按角色通知 → 403(子路径同样受约束)")
    void eventsByRoleWithPatientTokenShouldBeForbidden() throws Exception {
        String token = SseTestTokens.plainJwt("patient-1", List.of("patient:booking"));

        assertThat(mockMvc.perform(get(EVENTS_BY_ROLE).param("role", "DOCTOR")
                        .header("Authorization", "Bearer " + token))
                .andReturn().getResponse().getStatus()).isEqualTo(403);
    }

    @Test
    @DisplayName("R-11:员工 token 读取通知 → 放行(不是 401/403)")
    void eventsWithEmployeeTokenShouldPass() throws Exception {
        String token = SseTestTokens.plainJwt("doctor-1", List.of("visit:entry"));

        int status = statusOfGetWithBearer(EVENTS, token);

        assertThat(status)
                .as("员工持合法长期 JWT 应可读取通知(状态码取决于业务实现,这里只断言未被鉴权拦截)")
                .isNotIn(401, 403);
    }

    @Test
    @DisplayName("R-11:把 SSE ticket 当普通令牌调 REST → 401(URL 凭证不得越界使用)")
    void sseTicketMustNotBeAcceptedByRestEndpoints() throws Exception {
        String ticket = SseTestTokens.ticket("doctor-1", List.of("visit:entry"));

        assertThat(statusOfGetWithBearer(EVENTS, ticket))
                .as("ticket 的 scope=sse:即便被塞进 Authorization 头,REST 链也必须拒绝")
                .isEqualTo(401);
    }

    @Test
    @DisplayName("R-11:探活端点免鉴权(容器/网关探针依赖)")
    void healthEndpointShouldNotRequireCredential() throws Exception {
        assertThat(statusOfGet(HEALTH)).isNotEqualTo(401);
    }

    private int statusOfGet(String path) throws Exception {
        return mockMvc.perform(get(path)).andReturn().getResponse().getStatus();
    }

    private int statusOfGetWithBearer(String path, String token) throws Exception {
        return mockMvc.perform(get(path).header("Authorization", "Bearer " + token))
                .andReturn().getResponse().getStatus();
    }
}
