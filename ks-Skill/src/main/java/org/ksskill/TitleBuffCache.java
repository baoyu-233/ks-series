package org.ksskill;

import org.bukkit.entity.Player;

import java.lang.reflect.Method;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 玩家当前生效 buff 称号ID 的短 TTL 缓存 —— 让受伤热路径不必每次反射查 ks-Title（可能落 DB）。
 *
 * <p>全程反射接入 {@code org.kstitle.api.KsTitleApi.getEquippedTitleId(UUID)}（替代旧的
 * PlayerTitle），缺 ks-Title 时 {@link #current} 恒返回 null（称号绑定来源静默失效，不崩）。</p>
 *
 * <p>TTL 兼顾正确性与开销：玩家换/摘称号后最多 {@value #TTL_MS}ms 内感知；处于战斗中的玩家
 * 每 {@value #TTL_MS}ms 最多触发一次 API 查询。</p>
 */
public final class TitleBuffCache {

    private static final long TTL_MS = 3000L;

    private record Entry(Integer titleId, long ts) {}

    private final ConcurrentHashMap<UUID, Entry> cache = new ConcurrentHashMap<>();
    private volatile Boolean apiAvailable = null;
    private volatile Method getEquippedMethod;

    /** 当前佩戴称号ID；无佩戴或 ks-Title 不可用返回 null。 */
    public Integer current(Player p) {
        UUID id = p.getUniqueId();
        long now = System.currentTimeMillis();
        Entry e = cache.get(id);
        if (e != null && now - e.ts() < TTL_MS) return e.titleId();
        Integer fresh = query(id);
        cache.put(id, new Entry(fresh, now));
        return fresh;
    }

    public void invalidate(UUID id) {
        cache.remove(id);
    }

    private Integer query(UUID uuid) {
        if (Boolean.FALSE.equals(apiAvailable)) return null;
        try {
            Method m = getEquippedMethod;
            if (m == null) {
                Class<?> api = Class.forName("org.kstitle.api.KsTitleApi");
                m = api.getMethod("getEquippedTitleId", UUID.class);
                getEquippedMethod = m;
            }
            Object result = m.invoke(null, uuid);
            apiAvailable = true;
            return result instanceof Integer i ? i : null;
        } catch (Throwable t) {
            apiAvailable = false;
            return null;
        }
    }
}
