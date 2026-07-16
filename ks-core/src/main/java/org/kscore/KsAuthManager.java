package org.kscore;

import java.security.SecureRandom;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;

/**
 * 统一 Token 鉴权管理。
 * Token 生成、验证、续期、角色管理（玩家/管理员）。
 * 设计上可被子插件通过 KsPluginBridge 调用。
 */
public final class KsAuthManager {

    private static final SecureRandom RNG = new SecureRandom();
    private final Map<String, Session> tokens = new ConcurrentHashMap<>();
    private final int timeoutSeconds;

    public KsAuthManager(int timeoutSeconds) {
        this.timeoutSeconds = timeoutSeconds;
    }

    /**
     * 会话对象。
     */
    public static class Session {
        public final String token;
        public final UUID playerUuid;
        public final String playerName;
        public final boolean isAdmin;
        long createdAt;

        Session(String token, UUID playerUuid, String playerName, boolean isAdmin) {
            this.token = token;
            this.playerUuid = playerUuid;
            this.playerName = playerName;
            this.isAdmin = isAdmin;
            this.createdAt = System.currentTimeMillis();
        }

        public boolean isExpired(int timeoutSec) {
            return System.currentTimeMillis() - createdAt > timeoutSec * 1000L;
        }

        public long ageSeconds() {
            return (System.currentTimeMillis() - createdAt) / 1000;
        }
    }

    /**
     * 创建会话（生成新 token）。
     */
    public Session create(UUID playerUuid, String playerName, boolean isAdmin) {
        String token = generateToken();
        Session session = new Session(token, playerUuid, playerName, isAdmin);
        tokens.put(token, session);
        return session;
    }

    /**
     * 验证 token 是否有效且未过期。
     * @return 会话对象，无效返回 null
     */
    public Session validate(String token) {
        if (token == null || token.isEmpty()) return null;
        Session session = tokens.get(token);
        if (session == null) return null;
        if (session.isExpired(timeoutSeconds)) {
            tokens.remove(token);
            return null;
        }
        return session;
    }

    /**
     * 续期会话（保持原 token）。
     */
    public boolean touch(String token) {
        Session session = tokens.get(token);
        if (session == null || session.isExpired(timeoutSeconds)) {
            if (session != null) tokens.remove(token);
            return false;
        }
        session.createdAt = System.currentTimeMillis();
        return true;
    }

    /**
     * 刷新 token（用新 token 替换旧 token）。
     */
    public Session refresh(String oldToken) {
        Session old = tokens.remove(oldToken);
        if (old == null || old.isExpired(timeoutSeconds)) return null;
        return create(old.playerUuid, old.playerName, old.isAdmin);
    }

    /**
     * 移除 token。
     */
    public void remove(String token) {
        tokens.remove(token);
    }

    /**
     * 清理所有过期会话。
     */
    public void cleanup() {
        long now = System.currentTimeMillis();
        tokens.entrySet().removeIf(e ->
                now - e.getValue().createdAt > timeoutSeconds * 1000L);
    }

    /**
     * 当前活跃会话数。
     */
    public int activeCount() {
        return tokens.size();
    }

    /**
     * 获取 Token 超时时间（供子插件参考）。
     */
    public int getTimeoutSeconds() {
        return timeoutSeconds;
    }

    // ---- 内部 ----

    private static String generateToken() {
        byte[] bytes = new byte[32]; // 64 hex chars
        RNG.nextBytes(bytes);
        StringBuilder sb = new StringBuilder(64);
        for (byte b : bytes) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }
}
