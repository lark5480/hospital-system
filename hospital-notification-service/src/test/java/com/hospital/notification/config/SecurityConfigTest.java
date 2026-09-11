package com.hospital.notification.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.test.web.servlet.MockMvc;

import jakarta.servlet.DispatcherType;

/**
 * R-11:通知服务安全配置守护测试。
 *
 * <p>【本测试的语义已从"守护 permitAll 现状"翻转为"守护新的鉴权策略"】:
 * 原先本服务为 {@code anyRequest().permitAll()},任何人(含患者)都能订阅/读取全院通知,
 * 属已登记的 P1 越权缺口。R-11 落地后,策略改为:
 * <ul>
 *   <li>{@code /api/notify/subscribe}:短期 ticket({@code scope=sse})+ 员工权限;</li>
 *   <li>{@code /api/notify/events*} 等 REST 端点:需认证(未认证 → 401);</li>
 *   <li>{@code /api/notify/health}、{@code /actuator/health}:匿名放行。</li>
 * </ul>
 * 因此原先"未认证访问 XX 应<b>不</b>为 401"的断言,现已全部改为断言 401(或健康检查放行)。
 *
 * <p>RabbitMQ:测试通过 {@code spring.rabbitmq.listener.simple.auto-startup=false}
 * 禁止监听容器自启动,因此无需 RabbitMQ 即可启动上下文。
 */
@SpringBootTest(properties = {
        "spring.rabbitmq.listener.simple.auto-startup=false",
        "spring.rabbitmq.listener.direct.auto-startup=false",
        // R-11: 配置密钥以启用严格鉴权,从而可以断言"未认证 → 401"
        "app.jwt.secret=" + SseTestTokens.SECRET
})
@AutoConfigureMockMvc
class SecurityConfigTest {

    @Autowired
    private MockMvc mockMvc;

    // R-11: 现在有两条过滤链(SSE 订阅链 + 其余端点链),故按集合注入
    @Autowired
    private List<SecurityFilterChain> securityFilterChains;

    @Test
    @DisplayName("R-11 用例A:未认证访问 /api/notify/events 应返回 401(原 permitAll 已收紧)")
    void unauthenticatedEventsRequestShouldBeUnauthorized() throws Exception {
        var result = mockMvc.perform(get("/api/notify/events")).andReturn();

        assertThat(result.getResponse().getStatus()).isEqualTo(401);
    }

    @Test
    @DisplayName("R-11 用例A2:探活端点 /api/notify/health 仍匿名放行(容器/网关探针依赖)")
    void unauthenticatedHealthRequestShouldBePermitted() throws Exception {
        var result = mockMvc.perform(get("/api/notify/health")).andReturn();

        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        assertThat(result.getResponse().getContentAsString()).contains("notification");
    }

    @Test
    @DisplayName("R-11 用例A3:按角色/科室查询端点同样需认证(证明是全局收紧,不是单点豁免)")
    void unauthenticatedRoleAndDeptQueriesShouldBeUnauthorized() throws Exception {
        assertThat(mockMvc.perform(get("/api/notify/events/by-role").param("role", "DOCTOR"))
                .andReturn().getResponse().getStatus()).isEqualTo(401);
        assertThat(mockMvc.perform(get("/api/notify/events/by-dept").param("deptId", "1"))
                .andReturn().getResponse().getStatus()).isEqualTo(401);
    }

    @Test
    @DisplayName("R-11:SecurityFilterChain 必须存在且非空(配置被删则此用例失败)")
    void securityFilterChainShouldBePresent() {
        assertThat(securityFilterChains).isNotEmpty();
        assertThat(securityFilterChains).allSatisfy(chain ->
                assertThat(chain.getFilters()).isNotEmpty());
    }

    @Test
    @DisplayName("R-11:CSRF 必须在所有过滤链上关闭,否则前端 SSE / POST 会被 403 打断")
    void csrfShouldBeDisabled() {
        for (SecurityFilterChain chain : securityFilterChains) {
            boolean hasCsrfFilter = chain.getFilters().stream()
                    .anyMatch(f -> f.getClass().getName().contains("CsrfFilter"));
            assertThat(hasCsrfFilter).as("过滤链不应包含 CsrfFilter").isFalse();
        }
    }

    @Test
    @DisplayName("R-11 用例B(原 P1 目标契约):未认证访问 /api/notify/subscribe 应返回 401")
    void unauthenticatedSubscribeShouldBeUnauthorized() throws Exception {
        // 未带 ticket 时,查询参数提升不生效,授权规则应判为未认证 → 401
        var result = mockMvc.perform(get("/api/notify/subscribe")).andReturn();

        assertThat(result.getResponse().getStatus()).isEqualTo(401);
    }

    @Test
    @DisplayName("R-65:未认证的 ASYNC 派发必须放行(SSE 60s 超时后容器会自动发起,响应已提交)")
    void asyncDispatchWithoutAuthenticationShouldNotBeReauthorized() throws Exception {
        // 与 hospital-security 同一缺陷:ASYNC 派发已无 SecurityContext,若再判 401/403,
        // 真实环境下响应已提交 → "Unable to handle the Spring Security Exception" 刷 ERROR。
        // 探针路径刻意不存在:放行后授权通过、以 404 收场,不会真的建立 SSE 连接。
        var result = mockMvc.perform(get("/api/notify/__async-dispatch-probe__").with(request -> {
            request.setDispatcherType(DispatcherType.ASYNC);
            return request;
        })).andReturn();

        assertThat(result.getResponse().getStatus())
                .as("ASYNC 派发是已授权请求的延续,不得被再次鉴权")
                .isNotIn(401, 403);
    }
}
