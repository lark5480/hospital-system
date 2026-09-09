package com.hospital.core.platform.security;

import java.time.Duration;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import com.hospital.core.platform.infrastructure.JwtTokenService;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * R-34: 令牌吊销 —— 让"改密 / 重置密码 / 登出"之后已签发的旧 token 立即失效。
 *
 * <p>背景:JWT 无状态、服务端不存 session,改密后旧 token 在过期前(原 8 小时)依然可用。
 * 攻击者只要在用旧密码登录期间拿到过 token,受害者改密也踢不掉他。
 * 强制改密(R-10)上线后这个缺口必须补上,否则安全闭环不成立。
 *
 * <p>实现:按<b>用户维度</b>在 Redis 记一条吊销标记,TTL 与 token 有效期对齐 ——
 * 标记自然过期即可,无需清理任务。{@code JwtAuthFilter} 每次请求查一次(仅 1 次 GET),
 * 命中则拒绝;未命中(绝大多数请求)只有一次 Redis 未命中开销。
 *
 * <p>为何不做 jti 黑名单:按 jti 只能吊销"某一台设备"的 token,而改密要吊销的是
 * 该用户<b>全部</b>设备的会话;按用户维度一次写、一次读即可满足,且无需遍历已签发 jti。
 * token 里仍带 jti(见 {@link JwtTokenService#issue}),为将来"只踢当前设备"的登出预留。
 *
 * <p><b>可用性权衡</b>:本服务把 Redis 放到了认证关键路径上。Redis 不可用时默认
 * <b>fail-closed</b>(拒绝请求),可通过 {@code app.jwt.revocation-fail-open=true}
 * 切换为放行 —— 切换意味着攻击者打挂 Redis 就能让吊销失效,请谨慎。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TokenRevocationService {

    /** 用户维度吊销标记的 key 前缀。 */
    private static final String USER_KEY_PREFIX = "auth:revoked:user:";

    private final StringRedisTemplate stringRedisTemplate;
    private final JwtTokenService jwtTokenService;

    /** Redis 不可用时的降级策略,默认 fail-closed(拒绝)。 */
    @Value("${app.jwt.revocation-fail-open:false}")
    private boolean failOpen;

    /**
     * 吊销该用户当前全部已签发 token(改密 / 重置密码 / 登出时调用)。
     *
     * @param username 登录名(手机号)
     */
    public void revokeUser(String username) {
        if (username == null || username.isBlank()) {
            return;
        }
        try {
            // TTL 与 token 有效期对齐;再兜底 60s,避免 ttl 配错导致标记过早消失
            long ttl = Math.max(jwtTokenService.ttlMillis(), 60_000L);
            stringRedisTemplate.opsForValue().set(key(username), "1", Duration.ofMillis(ttl));
        } catch (RuntimeException e) {
            // 写入失败不阻断改密本身,但要留 ERROR 日志:该用户旧 token 在过期前仍可用
            log.error("[R-34] 写入令牌吊销记录失败,该用户旧 token 在过期前仍可使用: user={}", username, e);
        }
    }

    /**
     * 该用户是否已被吊销。由 {@code JwtAuthFilter} 每次请求调用一次。
     *
     * @return true 表示该用户改过密码 / 已登出,当前 token 应视为失效
     */
    public boolean isRevoked(String username) {
        if (username == null || username.isBlank()) {
            return false;
        }
        try {
            return Boolean.TRUE.equals(stringRedisTemplate.hasKey(key(username)));
        } catch (RuntimeException e) {
            log.error("[R-34] 读取令牌吊销记录失败,Redis 已位于认证关键路径上(failOpen={})", failOpen, e);
            // failOpen=true → 当作未吊销放行;false(默认) → 当作已吊销拒绝
            return !failOpen;
        }
    }

    private static String key(String username) {
        return USER_KEY_PREFIX + username;
    }
}
