package com.hospital.core.platform.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.util.Date;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import com.hospital.core.platform.infrastructure.JwtTokenService;

/**
 * R-34:令牌吊销语义测试。
 *
 * <p><b>守护的核心回归</b>:吊销必须是"作废某个时间点<b>之前</b>签发的 token",
 * 而不能是"该用户永久拉黑"。早期实现只写一个存在性标记,导致管理员重置密码后
 * 该用户<b>重新登录签发的 token 也被判失效</b> —— 重置一次密码等于把用户锁死一个 token 周期。
 *
 * <p>{@code newTokenAfterRevocationShouldBeValid} 就是守住该缺陷的关键用例。
 */
@ExtendWith(MockitoExtension.class)
class TokenRevocationServiceTest {

    private static final String PHONE = "13800000000";
    private static final String KEY = "auth:revoked:user:" + PHONE;

    @Mock
    private StringRedisTemplate stringRedisTemplate;

    @Mock
    private ValueOperations<String, String> valueOperations;

    @Mock
    private JwtTokenService jwtTokenService;

    private TokenRevocationService service;

    @BeforeEach
    void setUp() {
        service = new TokenRevocationService(stringRedisTemplate, jwtTokenService);
    }

    @Test
    @DisplayName("R-34:吊销时写入的是时间戳,且 TTL 与 token 有效期对齐")
    void revokeShouldWriteTimestampWithTtl() {
        when(stringRedisTemplate.opsForValue()).thenReturn(valueOperations);
        when(jwtTokenService.ttlMillis()).thenReturn(14_400_000L);

        long before = System.currentTimeMillis();
        service.revokeUser(PHONE);

        verify(valueOperations).set(eq(KEY), anyString(), any(Duration.class));
        // 写入的值必须是可解析的时间戳,而不是 "1" 之类的存在性标记
        org.mockito.ArgumentCaptor<String> captor = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(valueOperations).set(eq(KEY), captor.capture(), any(Duration.class));
        long written = Long.parseLong(captor.getValue());
        assertThat(written).isGreaterThanOrEqualTo(before);
    }

    @Test
    @DisplayName("R-34:签发时间早于吊销时间点的旧 token → 失效")
    void oldTokenShouldBeRevoked() {
        when(stringRedisTemplate.opsForValue()).thenReturn(valueOperations);
        long revokedAt = 1_700_000_000_000L;
        when(valueOperations.get(KEY)).thenReturn(String.valueOf(revokedAt));

        assertThat(service.isRevoked(PHONE, new Date(revokedAt - 10_000L)))
                .as("改密之前签发的 token 必须失效")
                .isTrue();
        assertThat(service.isRevoked(PHONE, new Date(revokedAt)))
                .as("与吊销时刻同毫秒签发的 token 也按失效处理")
                .isTrue();
    }

    @Test
    @DisplayName("R-34 回归:吊销之后重新登录签发的新 token 必须仍然有效")
    void newTokenAfterRevocationShouldBeValid() {
        when(stringRedisTemplate.opsForValue()).thenReturn(valueOperations);
        long revokedAt = 1_700_000_000_000L;
        when(valueOperations.get(KEY)).thenReturn(String.valueOf(revokedAt));

        assertThat(service.isRevoked(PHONE, new Date(revokedAt + 5_000L)))
                .as("管理员重置密码后,用户重新登录签发的 token 不能被误判为失效")
                .isFalse();
    }

    @Test
    @DisplayName("R-34:无吊销记录 → 有效")
    void noRecordShouldBeValid() {
        when(stringRedisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.get(KEY)).thenReturn(null);

        assertThat(service.isRevoked(PHONE, new Date())).isFalse();
    }

    @Test
    @DisplayName("R-34:Redis 不可用且 fail-closed(默认) → 按已吊销拒绝")
    void redisFailureShouldDenyByDefault() {
        when(stringRedisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.get(KEY)).thenThrow(new RuntimeException("redis down"));

        assertThat(service.isRevoked(PHONE, new Date()))
                .as("默认 fail-closed,Redis 故障时拒绝请求")
                .isTrue();
    }

    @Test
    @DisplayName("R-10:手机号为空时既不写入也不判定吊销")
    void blankPhoneShouldBeIgnored() {
        service.revokeUser(null);
        service.revokeUser("   ");

        verify(valueOperations, never()).set(anyString(), anyString(), any(Duration.class));
        assertThat(service.isRevoked(null, new Date())).isFalse();
    }
}
