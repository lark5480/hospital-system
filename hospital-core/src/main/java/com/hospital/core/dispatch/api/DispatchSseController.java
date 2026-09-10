package com.hospital.core.dispatch.api;

import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import com.hospital.core.dispatch.application.BoardUpdateEvent;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;

/**
 * 排队看板SSE推送端点。
 * 前端订阅后，看板数据变更时实时推送，无需轮询。
 *
 * <p>R-13 止血改造：
 * <ul>
 *   <li>连接上限 {@link #MAX_EMITTERS}，超出返回 503，防止无界增长耗尽内存/线程；</li>
 *   <li>有限超时（60s）+ 15s 心跳，死连接可被及时发现回收（原来 0L 永不超时，onTimeout 永不触发）；</li>
 *   <li>广播 catch 全部异常并在 finally 统一清理，单个坏连接不再中断整轮广播；</li>
 *   <li>订阅需认证（@PreAuthorize），避免匿名无限开连接。</li>
 * </ul>
 *
 * <p>R-14 止血改造：按 station 分组投递（原来 station 参数被丢弃，实际上是全量广播）。
 *
 * <p>注意：心跳使用自建守护线程池，未使用 {@code @Scheduled}/{@code @EnableScheduling}，
 * 避免引入新的调度配置影响其他模块。
 */
@Tag(name = "分诊排队", description = "排队看板SSE实时推送")
@Slf4j
@RestController
@RequestMapping("/api/core/dispatch/sse")
public class DispatchSseController {

    /** R-13: 单实例最大 SSE 连接数，超出返回 503。 */
    public static final int MAX_EMITTERS = 500;

    /** R-13: 连接超时（毫秒）。0L 表示永不超时，死连接将永久占用资源，故改为 60s。 */
    private static final long EMITTER_TIMEOUT_MS = 60_000L;

    /** R-13: 心跳间隔（秒），保活并探测死连接。 */
    private static final long HEARTBEAT_INTERVAL_SEC = 15L;

    /** R-14: 未指定 station（即订阅全部工位）的订阅者所在的桶 key。 */
    private static final String ALL_STATIONS_KEY = "__ALL__";

    /** R-14: 按 station 分组保存订阅者；值为并发安全集合，支持遍历时增删。 */
    private final Map<String, Set<SseEmitter>> emittersByStation = new ConcurrentHashMap<>();

    /** R-13: 全局连接计数，与分组集合配合做上限判断（先占位后校验，避免并发突破上限）。 */
    private final AtomicInteger emitterCount = new AtomicInteger(0);

    /** R-13: 心跳调度线程池（守护线程，不阻塞 JVM 退出）。 */
    private final ScheduledThreadPoolExecutor heartbeatExecutor =
            new ScheduledThreadPoolExecutor(1, r -> {
                Thread t = new Thread(r, "dispatch-sse-hb");
                t.setDaemon(true);
                return t;
            });

    /** R-13: 启动心跳（用 @PostConstruct 而非构造器，避免与后续注入构造器冲突）。 */
    @PostConstruct
    public void startHeartbeat() {
        this.heartbeatExecutor.setRemoveOnCancelPolicy(true);
        // R-13: 固定频率心跳；任务内部必须自吞异常，否则一次异常会终止后续调度
        this.heartbeatExecutor.scheduleAtFixedRate(
                this::sendHeartbeat, HEARTBEAT_INTERVAL_SEC, HEARTBEAT_INTERVAL_SEC, TimeUnit.SECONDS);
    }

    @Operation(summary = "订阅看板数据变更推送")
    @GetMapping(value = "/subscribe", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    // R-13: SSE 为长连接资源，必须登录后才允许建立，防止匿名连接耗尽资源（core 已开启 @EnableMethodSecurity）
    // R-34: 认证来源已从"URL 上的长期 JWT"改为"短期 ticket"——前端先 POST /api/core/sse/ticket
    //       取票，再由 SseTicketAuthFilter 解析 ?ticket= 注入 Authentication，因此这里保持
    //       isAuthenticated() 不变即可；本 Controller 不再从 query 读取任何令牌。
    @PreAuthorize("isAuthenticated()")
    public SseEmitter subscribe(
            @Parameter(description = "工位名称，可选；不传则接收全部工位事件")
            @RequestParam(required = false) String station) {
        // R-13: 连接上限保护；updateAndGet 保证并发下不会突破上限
        if (emitterCount.updateAndGet(c -> c >= MAX_EMITTERS ? c : c + 1) >= MAX_EMITTERS) {
            log.warn("[SSE] 订阅数已达上限 {}，拒绝新连接", MAX_EMITTERS);
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "SSE 连接数已达上限，请稍后重试");
        }

        // R-14: 记录该订阅者关注的工位，供广播时路由；为空归入“全部工位”桶
        final String key = normalizeKey(station);
        // R-13: 有限超时，超时/异常/完成均会触发清理
        SseEmitter emitter = new SseEmitter(EMITTER_TIMEOUT_MS);
        emitter.onCompletion(() -> removeEmitter(key, emitter));
        emitter.onTimeout(() -> {
            removeEmitter(key, emitter);
            safeComplete(emitter);
        });
        emitter.onError(e -> {
            removeEmitter(key, emitter);
            safeComplete(emitter);
        });

        emittersByStation.computeIfAbsent(key, k -> new CopyOnWriteArraySet<>()).add(emitter);
        log.debug("[SSE] 新增订阅者 station={}，当前连接数: {}", station, emitterCount.get());

        return emitter;
    }

    /**
     * 推送看板更新事件。
     * 由 DispatchService 在事务提交后调用（R-13：不得在数据库事务内同步推送）。
     *
     * <p>R-14：只推给订阅了 {@code event.station()} 的客户端 + 未指定 station（收全部）的客户端。
     */
    public void broadcastBoardUpdate(BoardUpdateEvent event) {
        if (event == null || emitterCount.get() == 0) {
            return;
        }

        Map<String, Object> data = Map.of(
                "type", "board-update",
                "station", event.station(),
                "taskId", event.taskId(),
                "action", event.action()
        );

        // R-14: 目标 = 该工位订阅者 ∪ 未指定工位的订阅者（保持“不传 station 即收全部”的兼容语义）
        Set<SseEmitter> targets = new LinkedHashSet<>();
        Set<SseEmitter> stationBucket = emittersByStation.get(normalizeKey(event.station()));
        if (stationBucket != null) {
            targets.addAll(stationBucket);
        }
        Set<SseEmitter> allBucket = emittersByStation.get(ALL_STATIONS_KEY);
        if (allBucket != null) {
            targets.addAll(allBucket);
        }
        if (targets.isEmpty()) {
            return;
        }

        Set<SseEmitter> deadEmitters = new LinkedHashSet<>();
        try {
            for (SseEmitter emitter : targets) {
                try {
                    emitter.send(SseEmitter.event()
                            .name("board-update")
                            .data(data, MediaType.APPLICATION_JSON));
                } catch (Exception e) {
                    // R-13: 不能只 catch IOException —— 对已 complete 的 emitter 发送会抛 IllegalStateException，
                    // 若让它冒泡会中断整轮广播，后续订阅者全部收不到推送
                    log.debug("[SSE] 推送失败，标记为死连接: {}", e.toString());
                    deadEmitters.add(emitter);
                }
            }
        } finally {
            // R-13: 统一清理，保证任何情况下死连接都会被移除，不会泄漏
            for (SseEmitter dead : deadEmitters) {
                removeEmitterEverywhere(dead);
            }
        }
    }

    /** R-13: 心跳，发送注释行保活；发送失败视为死连接并移除。 */
    private void sendHeartbeat() {
        for (Map.Entry<String, Set<SseEmitter>> entry : emittersByStation.entrySet()) {
            for (SseEmitter emitter : entry.getValue()) {
                try {
                    emitter.send(SseEmitter.event().comment("hb"));
                } catch (Exception e) {
                    log.debug("[SSE] 心跳失败，移除死连接: {}", e.toString());
                    removeEmitter(entry.getKey(), emitter);
                    safeComplete(emitter);
                }
            }
        }
    }

    /** R-13: 应用关闭时释放心跳线程与所有订阅者。 */
    @PreDestroy
    public void shutdown() {
        heartbeatExecutor.shutdownNow();
        for (Set<SseEmitter> bucket : emittersByStation.values()) {
            for (SseEmitter emitter : bucket) {
                safeComplete(emitter);
            }
        }
        emittersByStation.clear();
        log.info("[SSE] 心跳线程池已关闭，订阅者全部释放");
    }

    /** R-14: 空/空白 station 归一化为“全部工位”桶。 */
    private static String normalizeKey(String station) {
        return (station == null || station.isBlank()) ? ALL_STATIONS_KEY : station.trim();
    }

    /** 从指定桶移除；真正移除成功才回退计数，并顺带回收空桶。 */
    private void removeEmitter(String key, SseEmitter emitter) {
        Set<SseEmitter> bucket = emittersByStation.get(key);
        if (bucket != null && bucket.remove(emitter)) {
            emitterCount.decrementAndGet();
            if (bucket.isEmpty()) {
                // 仅当桶当前仍为空时才移除，避免误删并发新订阅
                emittersByStation.computeIfPresent(key, (k, v) -> v.isEmpty() ? null : v);
            }
        }
    }

    /** 广播清理用：不知道 emitter 归属哪个桶，统一扫描移除。 */
    private void removeEmitterEverywhere(SseEmitter emitter) {
        for (Map.Entry<String, Set<SseEmitter>> entry : emittersByStation.entrySet()) {
            removeEmitter(entry.getKey(), emitter);
        }
        safeComplete(emitter);
    }

    /** 幂等关闭 emitter；已 complete 的连接重复关闭会抛异常，忽略即可。 */
    private void safeComplete(SseEmitter emitter) {
        try {
            emitter.complete();
        } catch (Exception ignored) {
            // R-13: 忽略重复 complete
        }
    }
}
