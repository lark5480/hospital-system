package com.hospital.notification.api;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.springframework.context.event.EventListener;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import com.hospital.notification.model.NotificationBroadcastEvent;
import com.hospital.notification.model.NotificationRecord;
import com.hospital.notification.store.NotificationStore;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 通知服务 REST + SSE 端点。
 *
 * <p>TODO(P1): 鉴权缺失 —— 本服务当前 SecurityConfig 为 anyRequest().permitAll()，
 * 且没有 JWT 基础设施，因此这里没有加 @PreAuthorize（加了会全量 401，前端 notifySSE.ts 直连会全部失效）。
 * 生产必须补齐：接入与 core 相同的 JWT 验签，或由网关统一鉴权，并在网络层隔离 8102 端口。
 *
 * <p>R-13 止血改造（针对匿名可无限开连接的资源耗尽型 DoS）：
 * <ul>
 *   <li>连接上限 {@link #MAX_EMITTERS}，超出返回 503；</li>
 *   <li>有限超时（60s）+ 15s 心跳，死连接可被及时回收（原来 0L 永不超时）；</li>
 *   <li>广播 catch 全部异常并在 finally 统一清理，单个坏连接不再中断整轮广播；</li>
 *   <li>@PreDestroy 关闭心跳线程池并释放所有订阅者。</li>
 * </ul>
 * 注意：心跳使用自建守护线程池，未使用 @Scheduled/@EnableScheduling，避免引入新的调度配置。
 */
@Slf4j
@RestController
@RequestMapping("/api/notify")
@RequiredArgsConstructor
public class NotificationController {

    /** R-13: 单实例最大 SSE 连接数，超出返回 503。 */
    public static final int MAX_EMITTERS = 300;

    /** R-13: 连接超时（毫秒），0L 永不超时会导致死连接永久占用资源。 */
    private static final long EMITTER_TIMEOUT_MS = 60_000L;

    /** R-13: 心跳间隔（秒），保活并探测死连接。 */
    private static final long HEARTBEAT_INTERVAL_SEC = 15L;

    private final NotificationStore store;

    /** R-13: 订阅者集合 + 全局连接计数（先占位后校验，避免并发突破上限）。 */
    private final Set<SseEmitter> emitters = new CopyOnWriteArraySet<>();
    private final AtomicInteger emitterCount = new AtomicInteger(0);

    /** R-13: 心跳调度线程池（守护线程，不阻塞 JVM 退出）。 */
    private final ScheduledThreadPoolExecutor heartbeatExecutor =
            new ScheduledThreadPoolExecutor(1, r -> {
                Thread t = new Thread(r, "notify-sse-hb");
                t.setDaemon(true);
                return t;
            });

    /** R-13: 启动心跳（用 @PostConstruct 而非构造器，避免与 @RequiredArgsConstructor 生成的构造器冲突）。 */
    @PostConstruct
    public void startHeartbeat() {
        this.heartbeatExecutor.setRemoveOnCancelPolicy(true);
        // R-13: 固定频率心跳；任务内部必须自吞异常，否则一次异常会终止后续调度
        this.heartbeatExecutor.scheduleAtFixedRate(
                this::sendHeartbeat, HEARTBEAT_INTERVAL_SEC, HEARTBEAT_INTERVAL_SEC, TimeUnit.SECONDS);
    }

    @GetMapping("/events")
    public Map<String, Object> events() {
        List<NotificationRecord> list = store.all();
        return Map.of(
                "total", list.size(),
                "items", list
        );
    }

    @GetMapping("/events/by-role")
    public Map<String, Object> eventsByRole(@RequestParam String role) {
        List<NotificationRecord> list = store.findByRole(role);
        return Map.of(
                "total", list.size(),
                "items", list
        );
    }

    @GetMapping("/events/by-dept")
    public Map<String, Object> eventsByDept(@RequestParam Long deptId) {
        List<NotificationRecord> list = store.findByDeptId(deptId);
        return Map.of(
                "total", list.size(),
                "items", list
        );
    }

    // TODO(P1): 待本服务接入 JWT 后补 @PreAuthorize("isAuthenticated()")
    @GetMapping(value = "/subscribe", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter subscribe() {
        // R-13: 连接上限保护，防止匿名无限开连接耗尽资源
        if (emitterCount.updateAndGet(c -> c >= MAX_EMITTERS ? c : c + 1) >= MAX_EMITTERS) {
            log.warn("[SSE] 通知订阅数已达上限 {}，拒绝新连接", MAX_EMITTERS);
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "SSE 连接数已达上限，请稍后重试");
        }

        // R-13: 有限超时，超时/异常/完成均会触发清理
        SseEmitter emitter = new SseEmitter(EMITTER_TIMEOUT_MS);
        emitter.onCompletion(() -> removeEmitter(emitter));
        emitter.onTimeout(() -> {
            removeEmitter(emitter);
            safeComplete(emitter);
        });
        emitter.onError(e -> {
            removeEmitter(emitter);
            safeComplete(emitter);
        });
        emitters.add(emitter);
        log.debug("[SSE] 新增通知订阅者，当前连接数: {}", emitterCount.get());
        return emitter;
    }

    @EventListener
    public void onNotificationBroadcast(NotificationBroadcastEvent event) {
        broadcast(event.record());
    }

    private void broadcast(NotificationRecord record) {
        if (record == null || emitters.isEmpty()) {
            return;
        }
        Set<SseEmitter> dead = new LinkedHashSet<>();
        try {
            for (SseEmitter emitter : emitters) {
                try {
                    emitter.send(SseEmitter.event()
                            .name("notification")
                            .data(record, MediaType.APPLICATION_JSON));
                } catch (Exception e) {
                    // R-13: 不能只 catch IOException —— 对已 complete 的 emitter 发送会抛 IllegalStateException，
                    // 若让它冒泡会中断整轮广播，后续订阅者全部收不到推送
                    log.debug("[SSE] 通知推送失败，标记为死连接: {}", e.toString());
                    dead.add(emitter);
                }
            }
        } finally {
            // R-13: 统一清理，保证任何情况下死连接都会被移除
            for (SseEmitter emitter : dead) {
                removeEmitter(emitter);
                safeComplete(emitter);
            }
        }
    }

    /** R-13: 心跳，发送注释行保活；发送失败视为死连接并移除。 */
    private void sendHeartbeat() {
        for (SseEmitter emitter : emitters) {
            try {
                emitter.send(SseEmitter.event().comment("hb"));
            } catch (Exception e) {
                log.debug("[SSE] 通知心跳失败，移除死连接: {}", e.toString());
                removeEmitter(emitter);
                safeComplete(emitter);
            }
        }
    }

    /** R-13: 应用关闭时释放心跳线程与所有订阅者。 */
    @PreDestroy
    public void shutdown() {
        heartbeatExecutor.shutdownNow();
        for (SseEmitter emitter : emitters) {
            safeComplete(emitter);
        }
        emitters.clear();
        log.info("[SSE] 通知心跳线程池已关闭，订阅者全部释放");
    }

    /** 移除订阅者；真正移除成功才回退计数。 */
    private void removeEmitter(SseEmitter emitter) {
        if (emitters.remove(emitter)) {
            emitterCount.decrementAndGet();
        }
    }

    /** 幂等关闭 emitter；已 complete 的连接重复关闭会抛异常，忽略即可。 */
    private void safeComplete(SseEmitter emitter) {
        try {
            emitter.complete();
        } catch (Exception ignored) {
            // R-13: 忽略重复 complete
        }
    }

    @GetMapping("/health")
    public Map<String, String> health() {
        return Map.of("service", "notification", "status", "up");
    }
}
