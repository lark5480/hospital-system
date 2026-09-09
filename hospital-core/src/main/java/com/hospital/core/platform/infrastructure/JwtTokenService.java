package com.hospital.core.platform.infrastructure;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import lombok.extern.slf4j.Slf4j;

/**
 * 自管 JWT 签发/解析服务。
 * 本地开发 + 生产统一走这一套;
 * 未来接外部 IdP 时只需替换 login 环节(校验外部 token → 换签自有 JWT),本服务不动。
 *
 * Payload 结构:
 *  - sub: username
 *  - roles: 岗位列表(如 [DOCTOR])
 *  - authorities: 权限串列表(如 [visit:entry, order:execute])
 *
 * TODO(P2 R-34): 增加 jti + Redis 黑名单支持吊销(改密 / 停用账号时主动失效旧 token)。
 */
@Slf4j
@Service
public class JwtTokenService {

    /** R-01: HS256 要求密钥 ≥ 256 bit(32 字节)。 */
    private static final int MIN_SECRET_BYTES = 32;

    /** R-01: 已知弱密钥 / 占位串黑名单(全部小写比对)。 */
    private static final Set<String> WEAK_SECRETS = Set.of(
            "hospital-system-dev-secret-key-must-be-at-least-32-bytes-long",
            "secret",
            "changeme",
            "changeit",
            "hospital-system",
            "password",
            "123456");

    /** R-01: 历史硬编码默认值(即便被拼接 / 包裹也要拦下)。 */
    private static final String LEGACY_DEV_SECRET =
            "hospital-system-dev-secret-key-must-be-at-least-32-bytes-long";

    /**
     * 签名密钥(HS256)。
     * R-01: 取消硬编码默认值 —— 未注入 app.jwt.secret 时按运行环境 fail-fast 或生成临时随机密钥。
     */
    @Value("${app.jwt.secret:}")
    private String secret;

    /** R-01: 当前激活 profile(用于判断是否生产环境,决定是否 fail-fast)。 */
    @Value("${spring.profiles.active:dev}")
    private String activeProfile;

    /** token 有效期(毫秒),默认 8 小时 */
    @Value("${app.jwt.ttl:28800000}")
    private long ttlMillis;

    private SecretKey signingKey;

    @PostConstruct
    void init() {
        this.signingKey = resolveSigningKey();
    }

    /**
     * R-01: 密钥解析 —— 空 / 过短 / 命中弱串时:
     *  - 生产(profile 含 prod / production):直接抛异常,拒绝带病启动;
     *  - 其它环境:生成一次性的 32 字节随机密钥并告警(重启后已签发 token 全部失效)。
     *
     * 注:不再对短密钥"补零"(原实现),补零只会掩盖弱密钥问题,等价于公开密钥。
     */
    private SecretKey resolveSigningKey() {
        String raw = secret == null ? "" : secret.trim();
        boolean empty = raw.isEmpty();
        boolean tooShort = !empty && raw.getBytes(StandardCharsets.UTF_8).length < MIN_SECRET_BYTES;
        boolean weak = isWeakSecret(raw);

        if (empty || tooShort || weak) {
            String reason = empty ? "未注入" : (tooShort ? "长度不足 32 字节" : "命中已知弱密钥");
            if (isProductionProfile()) {
                // R-01: 生产环境必须显式注入,禁止临时密钥 / 弱密钥
                throw new IllegalStateException(
                        "[JwtTokenService] JWT 签名密钥" + reason
                                + ",生产环境拒绝启动;请通过环境变量 APP_JWT_SECRET 注入 ≥32 字节随机密钥。");
            }
            byte[] random = new byte[MIN_SECRET_BYTES];
            new SecureRandom().nextBytes(random);
            String generated = Base64.getEncoder().encodeToString(random);
            log.warn("[JwtTokenService] 未检测到有效 JWT 签名密钥({}),已生成临时随机密钥;"
                            + "这是本地开发兜底行为,进程重启后已签发的 token 将全部失效。"
                            + "请通过 APP_JWT_SECRET 注入固定密钥。",
                    reason);
            return Keys.hmacShaKeyFor(generated.getBytes(StandardCharsets.UTF_8));
        }
        return Keys.hmacShaKeyFor(raw.getBytes(StandardCharsets.UTF_8));
    }

    /** R-01: 弱密钥判定(忽略大小写与首尾空白)。 */
    private boolean isWeakSecret(String raw) {
        if (raw.isEmpty()) return false;
        String normalized = raw.toLowerCase(Locale.ROOT);
        return WEAK_SECRETS.contains(normalized) || normalized.contains(LEGACY_DEV_SECRET);
    }

    /** R-01: 是否生产环境(profile 含 prod / production 均按生产处理)。 */
    private boolean isProductionProfile() {
        if (activeProfile == null) return false;
        String p = activeProfile.toLowerCase(Locale.ROOT);
        return p.contains("prod") || p.contains("production");
    }

    /** 签发 token */
    public String issue(String username, List<String> roles, List<String> authorities) {
        long now = System.currentTimeMillis();
        return Jwts.builder()
                // R-34: jti —— 便于将来按"单个设备/单条 token"吊销与审计追踪
                .id(UUID.randomUUID().toString())
                .subject(username)
                .claim("roles", roles)
                .claim("authorities", authorities)
                .issuedAt(new Date(now))
                .expiration(new Date(now + ttlMillis))
                .signWith(signingKey)
                .compact();
    }

    /** R-34: token 有效期(毫秒),供令牌吊销记录设置与 token 对齐的 TTL。 */
    public long ttlMillis() {
        return ttlMillis;
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
