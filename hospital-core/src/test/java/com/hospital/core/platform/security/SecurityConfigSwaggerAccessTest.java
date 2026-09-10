package com.hospital.core.platform.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * R-33: Swagger / OpenAPI 生产环境"不再匿名放行"的回归。
 *
 * <p>{@link SecurityConfig#swaggerPermitMatchers()} 是 {@code authorizeHttpRequests} 中
 * swagger permitAll 的唯一来源(见 {@code filterChain}),因此直接断言它即可精确守护:
 * <ul>
 *   <li>非生产:返回全部 swagger 路径(本地/演示匿名可访问);</li>
 *   <li>生产(prod / production,大小写不敏感,支持逗号分隔多 profile):返回空数组,
 *       即 swagger 路径不在 permitAll 中,改走 {@code authenticated()}。</li>
 * </ul>
 *
 * <p>用纯 Mockito 构造 {@link SecurityConfig}(不启 Spring 上下文),避免为了断言一个
 * matcher 决策去启动整条上下文。
 */
class SecurityConfigSwaggerAccessTest {

    private static SecurityConfig configWithProfile(String activeProfile) {
        SecurityConfig config = new SecurityConfig(
                mock(JwtAuthFilter.class), mock(SseTicketAuthFilter.class), new ObjectMapper());
        ReflectionTestUtils.setField(config, "activeProfile", activeProfile);
        return config;
    }

    @Test
    @DisplayName("R-33:prod profile 下 swagger 路径不在 permitAll 中")
    void swaggerIsNotPermittedInProd() {
        assertThat(configWithProfile("prod").swaggerPermitMatchers())
                .as("生产环境不得匿名放行任何 swagger 文档路径")
                .isEmpty();
    }

    @Test
    @DisplayName("R-33:production profile 同样关闭匿名文档")
    void swaggerIsNotPermittedInProduction() {
        assertThat(configWithProfile("production").swaggerPermitMatchers()).isEmpty();
    }

    @Test
    @DisplayName("R-33:大小写不敏感、逗号分隔多 profile 也能识别生产")
    void swaggerIsNotPermittedWhenProdAmongMultipleProfiles() {
        assertThat(configWithProfile("dev,PROD").swaggerPermitMatchers()).isEmpty();
        assertThat(configWithProfile("PRODUCTION").swaggerPermitMatchers()).isEmpty();
    }

    @Test
    @DisplayName("R-33:非生产(dev / 为空)保持 swagger 匿名放行")
    void swaggerIsPermittedInNonProd() {
        for (String profile : new String[]{"dev", "test", "", null, "local"}) {
            assertThat(configWithProfile(profile).swaggerPermitMatchers())
                    .as("profile=%s 应保持匿名放行以支持本地开发/演示", profile)
                    .containsExactlyInAnyOrder(
                            "/swagger-ui/**", "/swagger-ui.html", "/api-docs/**", "/v3/api-docs/**");
        }
    }
}
