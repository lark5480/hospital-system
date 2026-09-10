package com.hospital.notification.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.test.web.servlet.MockMvc;

/**
 * R-27:通知服务安全配置守护测试。
 *
 * <p>当前事实(2026 审查口径):本服务 SecurityConfig 为 {@code anyRequest().permitAll()},
 * 且模块内没有 JWT 基础设施(oauth2-resource-server 依赖在,但没有 jwt decoder 配置),
 * 因此 SSE 端点 {@code /api/notify/subscribe} 与查询端点 {@code /api/notify/events} 均匿名可达。
 * 这是已登记的 <b>P1 缺口</b>("任何人可订阅全部通知"的越权问题)。
 *
 * <p>本测试的作用是<b>把这个现状钉死</b>:
 * <ul>
 *   <li>只要有人"以为已经加了鉴权"而实际上没加,或者把 permitAll 悄悄改成别的语义,
 *       用例 A 会立刻失败,不会无声漂移;</li>
 *   <li>用例 B 是 P1 完成后的目标契约占位(当前 @Disabled)。</li>
 * </ul>
 *
 * <p>【维护约定】P1(接入 JWT 验签或网关统一鉴权)落地时,必须:
 * ① 把用例 A 的断言从 {@code isNotEqualTo(401)} 改为 {@code isEqualTo(401)};
 * ② 删除用例 B 的 @Disabled 并让它变绿。
 * 只改 SecurityConfig 而不改本测试,会让鉴权被无声回退。
 *
 * <p>RabbitMQ:测试通过 {@code spring.rabbitmq.listener.simple.auto-startup=false}
 * 禁止监听容器自启动,因此无需 RabbitMQ 即可启动上下文。
 */
@SpringBootTest(properties = {
        "spring.rabbitmq.listener.simple.auto-startup=false",
        "spring.rabbitmq.listener.direct.auto-startup=false"
})
@AutoConfigureMockMvc
class SecurityConfigTest {

    @Autowired
    private MockMvc mockMvc;

    // R-27: 退化守卫 —— SecurityFilterChain 必须可注入,配置被删/被改坏时此用例失败
    @Autowired
    private SecurityFilterChain securityFilterChain;

    @Test
    @DisplayName("R-27 用例A(守护现状):未认证访问 /api/notify/events 不是 401 —— 当前确为 permitAll")
    void unauthenticatedEventsRequestShouldNotBeUnauthorized() throws Exception {
        var result = mockMvc.perform(get("/api/notify/events")).andReturn();

        assertThat(result.getResponse().getStatus()).isNotEqualTo(401);
        // 必须真的抵达了 Controller(permitAll 语义),而不是被重定向/异常短路
        assertThat(result.getResponse().getContentAsString()).contains("total");
    }

    @Test
    @DisplayName("R-27 用例A2(守护现状):未认证访问 /api/notify/health 不是 401")
    void unauthenticatedHealthRequestShouldNotBeUnauthorized() throws Exception {
        var result = mockMvc.perform(get("/api/notify/health")).andReturn();

        assertThat(result.getResponse().getStatus()).isNotEqualTo(401);
        assertThat(result.getResponse().getContentAsString()).contains("notification");
    }

    @Test
    @DisplayName("R-27 用例A3(守护现状):按角色/科室查询端点同样匿名可达(证明是全局 permitAll,不是单点豁免)")
    void unauthenticatedRoleAndDeptQueriesShouldNotBeUnauthorized() throws Exception {
        assertThat(mockMvc.perform(get("/api/notify/events/by-role").param("role", "DOCTOR"))
                .andReturn().getResponse().getStatus()).isNotEqualTo(401);
        assertThat(mockMvc.perform(get("/api/notify/events/by-dept").param("deptId", "1"))
                .andReturn().getResponse().getStatus()).isNotEqualTo(401);
    }

    @Test
    @DisplayName("R-27:SecurityFilterChain 必须可注入且非空(配置被删则此用例失败)")
    void securityFilterChainShouldBePresent() {
        assertThat(securityFilterChain).isNotNull();
        assertThat(securityFilterChain.getFilters()).isNotEmpty();
    }

    @Test
    @DisplayName("R-27:CSRF 必须关闭,否则前端 SSE / POST 会被 403 打断")
    void csrfShouldBeDisabled() {
        boolean hasCsrfFilter = securityFilterChain.getFilters().stream()
                .anyMatch(f -> f.getClass().getName().contains("CsrfFilter"));

        assertThat(hasCsrfFilter).isFalse();
    }

    /**
     * R-27 用例B:P1 目标契约占位 —— 未认证访问通知端点应返回 401。
     *
     * <p>当前禁用:本服务尚未接入 JWT 鉴权(见 SecurityConfig 的 P1 待办),
     * 一旦启用断言会立即失败。P1 完成后删除本注解。
     */
    @Test
    @Disabled("P1: 接入 JWT 鉴权后,该断言应改为期望 401(同时把用例 A 的 isNotEqualTo(401) 改成 isEqualTo(401))")
    @DisplayName("R-27 用例B(P1 目标契约):未认证访问 /api/notify/events 应返回 401")
    void unauthenticatedRequestShouldBeUnauthorizedAfterP1() throws Exception {
        var result = mockMvc.perform(get("/api/notify/events")).andReturn();

        assertThat(result.getResponse().getStatus()).isEqualTo(401);
    }
}
