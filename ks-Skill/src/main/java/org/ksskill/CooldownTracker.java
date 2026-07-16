package org.ksskill;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * per-player per-skill 的内部冷却记录（内存）。ON_INTERVAL 复用它做“每 N 秒一次”的节流。
 */
public final class CooldownTracker {

    private final Map<UUID, Map<String, Long>> last = new ConcurrentHashMap<>();

    /** 距上次触发是否已过 cooldownSeconds。cooldownSeconds<=0 视为无冷却，恒 true。 */
    public boolean ready(UUID uuid, String skillId, long cooldownSeconds) {
        if (cooldownSeconds <= 0) return true;
        Map<String, Long> m = last.get(uuid);
        if (m == null) return true;
        Long t = m.get(skillId);
        if (t == null) return true;
        return System.currentTimeMillis() - t >= cooldownSeconds * 1000L;
    }

    public void mark(UUID uuid, String skillId) {
        last.computeIfAbsent(uuid, k -> new ConcurrentHashMap<>()).put(skillId, System.currentTimeMillis());
    }

    public void clear(UUID uuid) {
        last.remove(uuid);
    }
}
