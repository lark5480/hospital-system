package com.hospital.core.platform.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import io.jsonwebtoken.Claims;

/**
 * R-34: {@link JwtTokenService} 的 SSE ticket 签发语义测试(无 Spring 上下文)。
 *
 * <p>守护:ticket 必须带 {@code scope=sse}、有效期固定 ~60 秒(不受 {@code app.jwt.ttl} 影响)、
 * 保留 {@code iatMs}(否则会被令牌吊销逻辑误伤)、且普通 token 不会被误判为 ticket。
 */
class JwtTokenServiceSseTicketTest {

    private static final long CONFIGURED_TTL_MILLIS = 14_400_000L;

    private JwtTokenService jwtTokenService;

    @BeforeEach
    void setUp() {
        jwtTokenService = new JwtTokenService();
        ReflectionTestUtils.setField(jwtTokenService, "secret", "unit-test-secret-key-must-be-at-least-32-bytes");
        ReflectionTestUtils.setField(jwtTokenService, "activeProfile", "dev");
        ReflectionTestUtils.setField(jwtTokenService, "ttlMillis", CONFIGURED_TTL_MILLIS);
        ReflectionTestUtils.invokeMethod(jwtTokenService, "init");
    }

    @Test
    @DisplayName("R-34:ticket 带 scope=sse,且有效期为固定 ~60 秒(不受 app.jwt.ttl 影响)")
    void sseTicketHasScopeAndFixedShortTtl() {
        String ticket = jwtTokenService.issueSseTicket(
                "13800000001", List.of("DOCTOR"), List.of("visit:entry"));

        Claims claims = jwtTokenService.parse(ticket);
        assertThat(claims).as("ticket 应可被同一套 parse 解析").isNotNull();
        assertThat(claims.get("scope")).isEqualTo("sse");
        assertThat(jwtTokenService.isSseTicket(claims)).isTrue();

        long ttlSeconds = (claims.getExpiration().getTime() - claims.getIssuedAt().getTime()) / 1000;
        assertThat(ttlSeconds)
                .as("ticket 有效期必须约 60 秒,而不是配置的 %d 秒", CONFIGURED_TTL_MILLIS / 1000)
                .isBetween(55L, 65L);
    }

    @Test
    @DisplayName("R-34:ticket 保留 iatMs 声明(否则会被令牌吊销逻辑误判)")
    void sseTicketKeepsIatMsClaim() {
        String ticket = jwtTokenService.issueSseTicket(
                "13800000001", List.of(), List.of("visit:entry"));

        Claims claims = jwtTokenService.parse(ticket);
        assertThat(claims).isNotNull();
        assertThat(claims.get("iatMs")).as("必须保留毫秒级签发时间").isNotNull();
    }

    @Test
    @DisplayName("R-34:普通 token 不带 scope,isSseTicket 返回 false")
    void normalTokenIsNotSseTicket() {
        String normalToken = jwtTokenService.issue(
                "13800000001", List.of("DOCTOR"), List.of("visit:entry"));

        Claims claims = jwtTokenService.parse(normalToken);
        assertThat(claims).isNotNull();
        assertThat(claims.get("scope")).as("普通 token 不应带 scope 声明").isNull();
        assertThat(jwtTokenService.isSseTicket(claims)).isFalse();
        // 普通 token 的有效期仍是配置值(4 小时),证明 ticket 的 60 秒是独立的
        long ttlSeconds = (claims.getExpiration().getTime() - claims.getIssuedAt().getTime()) / 1000;
        assertThat(ttlSeconds).isGreaterThan(3600L);
    }

    @Test
    @DisplayName("R-34:isSseTicket 对 null 返回 false(防御式)")
    void isSseTicketNullSafe() {
        assertThat(jwtTokenService.isSseTicket(null)).isFalse();
    }

    @Test
    @DisplayName("R-34:ticket 的 authorities 可被同一套解析方法读出")
    void sseTicketAuthoritiesReusable() {
        String ticket = jwtTokenService.issueSseTicket(
                "13800000001", List.of(), List.of("visit:entry", "dispatch:board"));

        Claims claims = jwtTokenService.parse(ticket);
        assertThat(claims).isNotNull();
        assertThat(jwtTokenService.usernameOf(claims)).isEqualTo("13800000001");
        assertThat(jwtTokenService.authoritiesOf(claims))
                .containsExactlyInAnyOrder("visit:entry", "dispatch:board");
    }
}
