package com.hospital.core.platform.infrastructure;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.List;
import java.util.Map;

/**
 * 自管 JWT 签发/解析服务。
 * 本地开发 + 生产统一走这一套;
 * 未来接外部 IdP 时只需替换 login 环节(校验外部 token → 换签自有 JWT),本服务不动。
 *
 * Payload 结构:
 *  - sub: username
 *  - roles: 岗位列表(如 [DOCTOR])
 *  - authorities: 权限串列表(如 [visit:entry, order:execute])
 */
@Service
public class JwtTokenService {

    /**
     * 签名密钥(HS256)。生产环境应通过环境变量/密钥管理服务注入;
     * 此处给一个默认值保证本地零配置可跑。
     */
    @Value("${app.jwt.secret:hospital-system-dev-secret-key-must-be-at-least-32-bytes-long}")
    private String secret;

    /** token 有效期(毫秒),默认 8 小时 */
    @Value("${app.jwt.ttl:28800000}")
    private long ttlMillis;

    private SecretKey signingKey;

    @PostConstruct
    void init() {
        // 确保密钥长度满足 HS256 最低 256 位要求
        byte[] bytes = secret.getBytes(StandardCharsets.UTF_8);
        if (bytes.length < 32) {
            byte[] padded = new byte[32];
            System.arraycopy(bytes, 0, padded, 0, bytes.length);
            bytes = padded;
        }
        this.signingKey = Keys.hmacShaKeyFor(bytes);
    }

    /** 签发 token */
    public String issue(String username, List<String> roles, List<String> authorities) {
        long now = System.currentTimeMillis();
        return Jwts.builder()
                .subject(username)
                .claim("roles", roles)
                .claim("authorities", authorities)
                .issuedAt(new Date(now))
                .expiration(new Date(now + ttlMillis))
                .signWith(signingKey)
                .compact();
    }

    /** 解析并校验 token;校验失败返回 null */
    public Claims parse(String token) {
        try {
            return Jwts.parser()
                    .verifyWith(signingKey)
                    .build()
                    .parseSignedClaims(token)
                    .getPayload();
        } catch (Exception e) {
            return null;
        }
    }

    /** 从 claims 提取 authorities */
    @SuppressWarnings("unchecked")
    public List<String> authoritiesOf(Claims claims) {
        Object auths = claims.get("authorities");
        if (auths instanceof List<?> list) {
            return list.stream().map(Object::toString).filter(s -> !s.isEmpty()).toList();
        }
        if (auths instanceof String s && !s.isBlank()) {
            return java.util.Arrays.stream(s.split(",")).map(String::trim).filter(t -> !t.isEmpty()).toList();
        }
        return List.of();
    }

    /** 从 claims 提取 roles */
    @SuppressWarnings("unchecked")
    public List<String> rolesOf(Claims claims) {
        Object roles = claims.get("roles");
        if (roles instanceof List<?> list) {
            return list.stream().map(Object::toString).toList();
        }
        return List.of();
    }

    public String usernameOf(Claims claims) {
        return claims.getSubject();
    }

    /** 默认仅作占位(SpEL 表达式引用用) */
    public Map<String, Object> hints() {
        return Map.of();
    }
}
