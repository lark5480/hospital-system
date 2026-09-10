package com.hospital.notification.config;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;

import javax.crypto.spec.SecretKeySpec;

import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;

import com.nimbusds.jose.jwk.source.ImmutableSecret;
import com.nimbusds.jose.proc.SecurityContext;

/**
 * R-11: 测试用 JWT / ticket 签发工具。
 *
 * <p><b>【维护约定】</b>本类刻意<b>复刻</b> hospital-core 的
 * {@code JwtTokenService.issueSseTicket} 的声明形状:sub / authorities / roles / iatMs /
 * 以及 ticket 特有的 {@code scope=sse};过期时间用 {@code exp}。
 * 通知服务只做"校验",不能反向依赖 core 的签发实现,所以这里用 {@link NimbusJwtEncoder}
 * 自行签发。<b>一旦 core 改了声明名、scope 值或密钥派生方式,必须同步修改本类</b>,
 * 否则本类签出的 ticket 会被通知服务拒绝,用例会以"签名/声明不匹配"的形式失败。
 *
 * <p>密钥派生与 core 一致:secret 字符串的 <b>UTF-8 原始字节</b>作为 HMAC-SHA256 密钥,
 * 不做 Base64 解码。
 */
final class SseTestTokens {

    /** R-11: 测试用密钥,需 ≥32 字节。测试类通过 {@code app.jwt.secret} 把它配进上下文。 */
    static final String SECRET = "notification-sse-test-secret-key-0123456789-abcdefghij";

    /** R-11: ticket 的 scope 声明值,与 core 的 JwtTokenService.SSE_TICKET_SCOPE 一致。 */
    static final String SSE_SCOPE = "sse";

    private SseTestTokens() {
    }

    /** R-11: 签发一个合法的 {@code scope=sse} ticket(60 秒有效)。 */
    static String ticket(String subject, List<String> authorities) {
        Instant now = Instant.now();
        return encode(subject, authorities, SSE_SCOPE, now, now.plusSeconds(60));
    }

    /**
     * R-11: 签发一个已过期的 {@code scope=sse} ticket,用于验证 401。
     *
     * <p>注意:过期时间取"10 分钟前",而不是几秒前 —— 因为 {@code JwtValidators.createDefault()}
     * 默认带 60 秒的时钟偏移容差(clock skew),仅过期几秒的票据仍会被判为有效。
     */
    static String expiredTicket(String subject, List<String> authorities) {
        // issuedAt 必须早于 expiresAt,否则 JwtClaimsSet.Builder 会直接抛异常
        Instant now = Instant.now();
        return encode(subject, authorities, SSE_SCOPE, now.minusSeconds(900), now.minusSeconds(600));
    }

    /** R-11: 签发一个<b>不带 scope</b> 的普通长效 JWT,用于验证"长期 JWT 不能当 ticket"。 */
    static String plainJwt(String subject, List<String> authorities) {
        Instant now = Instant.now();
        return encode(subject, authorities, null, now, now.plusSeconds(3600));
    }

    private static String encode(String subject, List<String> authorities, String scope,
                                 Instant issuedAt, Instant expiration) {
        SecretKeySpec key = new SecretKeySpec(SECRET.getBytes(StandardCharsets.UTF_8), "HmacSHA256");
        NimbusJwtEncoder encoder = new NimbusJwtEncoder(new ImmutableSecret<SecurityContext>(key));

        JwtClaimsSet.Builder claims = JwtClaimsSet.builder()
                .subject(subject)
                .claim("authorities", authorities)
                .claim("roles", List.of())
                .claim("iatMs", issuedAt.toEpochMilli())
                .issuedAt(issuedAt)
                .expiresAt(expiration);
        if (scope != null) {
            claims.claim("scope", scope);
        }

        JwsHeader header = JwsHeader.with(MacAlgorithm.HS256).build();
        return encoder.encode(JwtEncoderParameters.from(header, claims.build())).getTokenValue();
    }
}
