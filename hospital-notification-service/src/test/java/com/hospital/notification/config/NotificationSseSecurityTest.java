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
import org.springframework.test.web.servlet.MvcResult;

/**
 * R-11:SSE 订阅端点的鉴权契约测试。
 *
 * <p>覆盖"任何人可订阅全部通知"越权缺口的修复:
 * <ul>
 *   <li>缺少 / 非法 / 已过期 ticket → 401;</li>
 *   <li>{@code scope} 非 {@code sse} 的普通长效 JWT(塞进 {@code ?ticket=})→ 401
 *       —— 证明 URL 上的凭证只能是短期 ticket;</li>
 *   <li>患者权限 ticket(仅 {@code patient:booking})→ 403 —— 证明订阅是员工专属;</li>
 *   <li>员工权限 ticket → 通过鉴权(进入 SSE 异步流)。</li>
 * </ul>
 *
 * <p>ticket 由 {@link SseTestTokens} 按与 core 一致的声明形状自行签发(见其类注释的维护约定)。
 */
@SpringBootTest(properties = {
        "spring.rabbitmq.listener.simple.auto-startup=false",
        "spring.rabbitmq.listener.direct.auto-startup=false",
        "app.jwt.secret=" + SseTestTokens.SECRET
})
@AutoConfigureMockMvc
class NotificationSseSecurityTest {

    private static final String SUBSCRIBE = "/api/notify/subscribe";

    @Autowired
    private MockMvc mockMvc;

    @Test
    @DisplayName("R-11:缺少 ticket → 401")
    void missingTicketShouldBeUnauthorized() throws Exception {
        assertThat(mockMvc.perform(get(SUBSCRIBE)).andReturn().getResponse().getStatus())
                .isEqualTo(401);
    }

    @Test
    @DisplayName("R-11:非法 ticket(非 JWT)→ 401")
    void invalidTicketShouldBeUnauthorized() throws Exception {
        assertThat(mockMvc.perform(get(SUBSCRIBE).queryParam("ticket", "not-a-jwt"))
                .andReturn().getResponse().getStatus()).isEqualTo(401);
    }

    @Test
    @DisplayName("R-11:已过期 ticket → 401")
    void expiredTicketShouldBeUnauthorized() throws Exception {
        String ticket = SseTestTokens.expiredTicket("doctor", List.of("visit:entry"));
        assertThat(mockMvc.perform(get(SUBSCRIBE).queryParam("ticket", ticket))
                .andReturn().getResponse().getStatus()).isEqualTo(401);
    }

    @Test
    @DisplayName("R-11:scope 非 sse 的普通长效 JWT(当 ticket 用)→ 401")
    void plainJwtWithoutSseScopeShouldBeUnauthorized() throws Exception {
        // 该 JWT 签名合法、未过期,但缺少 scope=sse 声明,必须被拒
        String token = SseTestTokens.plainJwt("doctor", List.of("visit:entry"));
        assertThat(mockMvc.perform(get(SUBSCRIBE).queryParam("ticket", token))
                .andReturn().getResponse().getStatus()).isEqualTo(401);
    }

    @Test
    @DisplayName("R-11:患者权限 ticket(仅 patient:booking)→ 403")
    void patientTicketShouldBeForbidden() throws Exception {
        String ticket = SseTestTokens.ticket("patient-1", List.of("patient:booking"));
        assertThat(mockMvc.perform(get(SUBSCRIBE).queryParam("ticket", ticket))
                .andReturn().getResponse().getStatus()).isEqualTo(403);
    }

    @Test
    @DisplayName("R-11:员工权限 ticket → 通过鉴权(进入 SSE 异步流)")
    void employeeTicketShouldPassAuthentication() throws Exception {
        String ticket = SseTestTokens.ticket("doctor-1", List.of("visit:entry"));
        MvcResult result = mockMvc.perform(get(SUBSCRIBE).queryParam("ticket", ticket)).andReturn();

        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        assertThat(result.getRequest().isAsyncStarted()).isTrue();
    }
}
