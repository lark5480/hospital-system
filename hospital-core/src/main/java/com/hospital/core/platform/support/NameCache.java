package com.hospital.core.platform.support;

import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

import org.springframework.stereotype.Service;

/**
 * 名称解析缓存(R-19 基础设施)。
 *
 * <p><b>背景</b>:就诊单、检验申请、处方、报告等列表接口需要把 {@code patient_id} /
 * {@code doctor_id} / {@code dept_id} 解析为姓名展示。原先逐条回查(每条记录 1~3 次 SQL),
 * 在列表页形成 N+1;同一批记录里还会反复查同一个 id。
 *
 * <p><b>设计</b>:患者/医生/科室姓名属<b>低频变更</b>数据,列表展示对 5 分钟内的不一致
 * 可接受,因此这里用进程内 {@link ConcurrentHashMap} 做带 TTL 的轻量缓存,命中即零查询。
 * <ul>
 *   <li>key 形如 {@code patient:12} / {@code staff:7} / {@code dept:3};</li>
 *   <li>TTL 5 分钟,惰性清理(读写时顺带清理过期项);</li>
 *   <li>容量上限 5000,达到上限时先清过期项,仍超限则整体清空重建(低频数据,代价可忽略);</li>
 * </ul>
 *
 * <p><b>一致性</b>:多实例部署时各实例各自持有独立缓存,彼此无共享、无一致性问题;
 * 单实例内同一 5 分钟窗口内名称统一,不会出现同一响应里新旧名称混杂。
 */
@Service
public class NameCache {

    /** 缓存有效期:5 分钟。 */
    private static final long TTL_MILLIS = 5L * 60 * 1000;

    /** 容量上限:达到该规模后触发惰性清理。 */
    private static final int MAX_SIZE = 5000;

    private final ConcurrentHashMap<String, Entry> cache = new ConcurrentHashMap<>();

    /** 缓存条目:值 + 过期时间戳(绝对毫秒)。 */
    private record Entry(String value, long expireAt) {
    }

    /**
     * 单个 id 的名称解析:命中且未过期直接返回;否则调用 loader 回源并写入缓存。
     * loader 返回 null 时不缓存(允许后续重试)。
     */
    public String getOrLoad(String type, Long id, Function<Long, String> loader) {
        if (id == null) {
            return null;
        }
        long now = System.currentTimeMillis();
        String key = key(type, id);
        Entry entry = cache.get(key);
        if (entry != null && entry.expireAt() > now) {
            return entry.value();
        }
        lazyEvict(now);
        String value = loader.apply(id);
        if (value != null) {
            cache.put(key, new Entry(value, now + TTL_MILLIS));
        }
        return value;
    }

    /**
     * 批量 id 的名称解析:<b>只对缓存缺失的 id</b> 调用一次 batchLoader
     * (底层一条 {@code WHERE id IN (...)}),已命中的直接复用,避免 N+1。
     *
     * @return 仅包含成功解析到的 id;缺失(loader 未返回)的 id 不入结果
     */
    public Map<Long, String> getOrLoadAll(String type, Collection<Long> ids,
                                          Function<Collection<Long>, Map<Long, String>> batchLoader) {
        Map<Long, String> result = new HashMap<>();
        if (ids == null || ids.isEmpty()) {
            return result;
        }
        long now = System.currentTimeMillis();
        lazyEvict(now);

        // 1) 先摘出命中的,并把缺失的 id 去重收集
        Set<Long> missing = new LinkedHashSet<>();
        for (Long id : ids) {
            if (id == null) {
                continue;
            }
            Entry entry = cache.get(key(type, id));
            if (entry != null && entry.expireAt() > now) {
                result.put(id, entry.value());
            } else {
                missing.add(id);
            }
        }

        // 2) 只对缺失的 id 批量回源(一次 IN 查询),再回填缓存与结果
        if (!missing.isEmpty()) {
            Map<Long, String> loaded = batchLoader.apply(missing);
            if (loaded != null) {
                for (Map.Entry<Long, String> e : loaded.entrySet()) {
                    Long id = e.getKey();
                    String value = e.getValue();
                    if (id == null || value == null) {
                        continue;
                    }
                    cache.put(key(type, id), new Entry(value, now + TTL_MILLIS));
                    result.put(id, value);
                }
            }
        }
        return result;
    }

    private static String key(String type, Long id) {
        return type + ":" + id;
    }

    /** 惰性清理:仅在规模达到上限时清过期项,仍超限则整体清空重建。 */
    private void lazyEvict(long now) {
        if (cache.size() < MAX_SIZE) {
            return;
        }
        cache.entrySet().removeIf(e -> e.getValue().expireAt() <= now);
        if (cache.size() >= MAX_SIZE) {
            cache.clear();
        }
    }
}
