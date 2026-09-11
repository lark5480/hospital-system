package com.hospital.core.platform.security;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import org.springframework.stereotype.Service;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;

/**
 * 登录失败计数 / 账号锁定 / IP 限流(R-10、R-12)。
 *
 * 规则:
 *  - 同一手机号连续失败 5 次 → 锁定 15 分钟;登录成功或改密成功即清零。
 *  - 同一来源 IP 在 10 分钟内失败 30 次 → 粗粒度限流(撞库防护)。
 *  - 用户不存在时只计 IP、不按手机号计数,否则撞库会把他人账号锁死。
 *
 * 惰性清理:写操作后若条目数超过阈值,顺带扫描并剔除已过期条目,避免 map 无界增长
 * (不依赖 @EnableScheduling,也不新增任何配置类)。
 *
 * TODO(P1): 当前为单实例内存计数,多实例部署时计数不共享、锁定可绕过,
 *           需替换为 Redis(INCR + EXPIRE)实现。
 */
@Slf4j
@Service
public class LoginAttemptService {

    /** 同一手机号允许的最大连续失败次数。 */
    private static final int MAX_PHONE_FAILURES = 5;

    /** 手机号锁定分钟数。 */
    private static final int PHONE_LOCK_MINUTES = 15;

    /** 同一 IP 的统计窗口(分钟)。 */
    private static final int IP_WINDOW_MINUTES = 10;

    /** 窗口内同一 IP 允许的最大失败次数。 */
    private static final int MAX_IP_FAILURES = 30;

    /** 触发惰性清理的条目数阈值(手机号表 / IP 表分别统计)。 */
    private static final int CLEANUP_THRESHOLD = 512;

    /** 非 Web 环境(单测 / 定时任务)下的 IP 占位值。 */
    private static final String UNKNOWN_IP = "unknown";

    /** 手机号 → 失败计数(含锁定时点)。 */
    private final ConcurrentHashMap<String, PhoneAttempt> phoneAttempts = new ConcurrentHashMap<>();

    /** IP → 滑动窗口失败计数。 */
    private final ConcurrentHashMap<String, IpAttempt> ipAttempts = new ConcurrentHashMap<>();

    /** 累计写操作次数,用于分摊惰性清理成本。 */
    private final AtomicInteger writesSinceCleanup = new AtomicInteger();

    /** 手机号维度的失败记录。 */
    private static final class PhoneAttempt {
        private int failures;
        private long lastFailureAt;
        private long lockedUntil;
    }

    /** IP 维度的失败记录(固定窗口)。 */
    private static final class IpAttempt {
        private int failures;
        private long windowStart;
    }

    /**
     * 手机号是否处于锁定中。
     *
     * <p><b>注意(踩过的坑)</b>:本方法<b>只</b>在"锁定已过期"时清理记录,
     * 绝不能因为"记录存在但未锁定"就顺手删除 —— 调用方(AuthController)会在
     * 登录失败后再查一次本方法以判断是否刚触发锁定,若此处把未达阈值的计数抹掉,
     * 每次失败都会被清零,连续失败永远攒不到上限,锁定功能将完全失效。
     */
    public boolean isLocked(String phone) {
        if (phone == null || phone.isBlank()) return false;
        PhoneAttempt attempt = phoneAttempts.get(phone);
        if (attempt == null) return false;
        long now = System.currentTimeMillis();
        if (attempt.lockedUntil > now) {
            return true;
        }
        // 仅当"锁定期已过"才清除,重新开始计数;未达阈值的失败计数必须保留
        if (attempt.lockedUntil > 0) {
            phoneAttempts.remove(phone, attempt);
        }
        return false;
    }

    /** 来源 IP 是否已被限流。 */
    public boolean isIpBlocked(String ip) {
        String key = normalizeIp(ip);
        if (UNKNOWN_IP.equals(key)) return false;   // 取不到 IP 时不做限流,避免误伤合法内网调用
        IpAttempt attempt = ipAttempts.get(key);
        if (attempt == null) return false;
        long now = System.currentTimeMillis();
        if (now - attempt.windowStart > Duration.ofMinutes(IP_WINDOW_MINUTES).toMillis()) {
            // 窗口已过期 → 重置
            ipAttempts.remove(key, attempt);
            return false;
        }
        return attempt.failures >= MAX_IP_FAILURES;
    }

    /** 记录一次手机号维度的失败(不区分来源)。 */
    public void onFailure(String phone) {
        if (phone == null || phone.isBlank()) return;
        long now = System.currentTimeMillis();
        long lockMillis = Duration.ofMinutes(PHONE_LOCK_MINUTES).toMillis();
        phoneAttempts.compute(phone, (key, old) -> {
            PhoneAttempt attempt = old == null ? new PhoneAttempt() : old;
            // 上一次失败距现在已超过锁定时长 → 视为新的连续失败序列
            if (now - attempt.lastFailureAt > lockMillis) {
                attempt.failures = 0;
                attempt.lockedUntil = 0L;
            }
            attempt.failures++;
            attempt.lastFailureAt = now;
            if (attempt.failures >= MAX_PHONE_FAILURES) {
                attempt.lockedUntil = now + lockMillis;
                attempt.failures = 0;   // 锁定后重新计数,解锁后再错 5 次才会再次锁定
                log.warn("[LoginAttemptService] 手机号连续登录失败达上限,已锁定 {} 分钟", PHONE_LOCK_MINUTES);
            }
            return attempt;
        });
        scheduleCleanup();
    }

    /** 记录一次失败(手机号 + 来源 IP);phone 为 null 时只计 IP。 */
    public void onFailure(String phone, String ip) {
        onFailure(phone);
        onIpFailure(ip);
    }

    /** R-12: 仅按来源 IP 计数(用于"用户不存在"场景,避免撞库锁死他人账号)。 */
    public void onIpFailure(String ip) {
        String key = normalizeIp(ip);
        if (UNKNOWN_IP.equals(key)) return;
        long now = System.currentTimeMillis();
        long windowMillis = Duration.ofMinutes(IP_WINDOW_MINUTES).toMillis();
        ipAttempts.compute(key, (k, old) -> {
            IpAttempt attempt = old == null ? new IpAttempt() : old;
            if (now - attempt.windowStart > windowMillis) {
                attempt.failures = 0;
                attempt.windowStart = now;
            }
            attempt.failures++;
            return attempt;
        });
        scheduleCleanup();
    }

    /** 登录成功 / 改密成功 → 清空该手机号的失败计数与锁定状态。 */
    public void onSuccess(String phone) {
        if (phone == null || phone.isBlank()) return;
        phoneAttempts.remove(phone);
    }

    /** 当前请求来源 IP:X-Forwarded-For 首跳 → X-Real-IP → remoteAddr;非 Web 环境返回 unknown。 */
    public String currentClientIp() {
        ServletRequestAttributes attrs =
                (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
        if (attrs == null) return UNKNOWN_IP;
        HttpServletRequest request = attrs.getRequest();
        String xff = request.getHeader("X-Forwarded-For");
        if (xff != null && !xff.isBlank()) {
            return xff.split(",")[0].trim();
        }
        String realIp = request.getHeader("X-Real-IP");
        if (realIp != null && !realIp.isBlank()) {
            return realIp.trim();
        }
        String remote = request.getRemoteAddr();
        return remote == null || remote.isBlank() ? UNKNOWN_IP : remote.trim();
    }

    private String normalizeIp(String ip) {
        return ip == null || ip.isBlank() ? UNKNOWN_IP : ip.trim();
    }

    /** R-10: 惰性清理 —— 每累计 64 次写操作且条目超阈值时,扫描剔除过期条目。 */
    private void scheduleCleanup() {
        if (writesSinceCleanup.incrementAndGet() < 64) return;
        writesSinceCleanup.set(0);
        if (phoneAttempts.size() > CLEANUP_THRESHOLD) {
            evictExpiredPhones();
        }
        if (ipAttempts.size() > CLEANUP_THRESHOLD) {
            evictExpiredIps();
        }
    }

    private void evictExpiredPhones() {
        long now = System.currentTimeMillis();
        long staleMillis = Duration.ofMinutes(PHONE_LOCK_MINUTES).toMillis();
        int removed = 0;
        for (Map.Entry<String, PhoneAttempt> entry : phoneAttempts.entrySet()) {
            PhoneAttempt attempt = entry.getValue();
            boolean unlocked = attempt.lockedUntil <= now;
            boolean stale = now - attempt.lastFailureAt > staleMillis;
            if (unlocked && stale) {
                phoneAttempts.remove(entry.getKey(), attempt);
                removed++;
            }
        }
        if (removed > 0) {
            log.debug("[LoginAttemptService] 惰性清理过期手机号失败记录 {} 条", removed);
        }
    }

    private void evictExpiredIps() {
        long now = System.currentTimeMillis();
        long windowMillis = Duration.ofMinutes(IP_WINDOW_MINUTES).toMillis();
        int removed = 0;
        for (Map.Entry<String, IpAttempt> entry : ipAttempts.entrySet()) {
            IpAttempt attempt = entry.getValue();
            if (now - attempt.windowStart > windowMillis) {
                ipAttempts.remove(entry.getKey(), attempt);
                removed++;
            }
        }
        if (removed > 0) {
            log.debug("[LoginAttemptService] 惰性清理过期 IP 失败记录 {} 条", removed);
        }
    }
}
