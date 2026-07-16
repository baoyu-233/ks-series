package org.itemedit;

import java.security.SecureRandom;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 临时 token 会话管理。
 * 玩家输入 /design 后生成一个 token，浏览器通过 token 验证身份。
 * token 有过期时间，过期后自动失效。
 */
public final class WebSessionManager {

    private static final SecureRandom RNG = new SecureRandom();
    private static final Map<String, Session> TOKENS = new ConcurrentHashMap<>();

    public static class Session {
        public final String token;
        public final UUID playerUuid;
        public final String playerName;
        public final boolean isAdmin;
        long createdAt;  // non-final — touch() 续期用

        Session(String token, UUID playerUuid, String playerName, boolean isAdmin) {
            this.token = token;
            this.playerUuid = playerUuid;
            this.playerName = playerName;
            this.isAdmin = isAdmin;
            this.createdAt = System.currentTimeMillis();
        }

        public boolean isExpired(int timeoutSeconds) {
            return System.currentTimeMillis() - createdAt > timeoutSeconds * 1000L;
        }
    }

    /**
     * 生成一个新 token 并注册会话。
     */
    public static Session create(UUID playerUuid, String playerName, boolean isAdmin) {
        String token = generateToken();
        Session session = new Session(token, playerUuid, playerName, isAdmin);
        TOKENS.put(token, session);
        return session;
    }

    /**
     * 验证 token 是否有效且未过期。
     * @return 会话对象，无效返回 null
     */
    public static Session validate(String token, int timeoutSeconds) {
        Session session = TOKENS.get(token);
        if (session == null) return null;
        if (session.isExpired(timeoutSeconds)) {
            TOKENS.remove(token);
            return null;
        }
        return session;
    }

    /**
     * 延长会话有效期（保持原 token 不变）。
     * 用于网页保活 ping，避免每次保活换 token 导致前端 token 失效。
     * @return true 如果成功续期
     */
    public static boolean touch(String token, int timeoutSeconds) {
        Session session = TOKENS.get(token);
        if (session == null) return false;
        if (session.isExpired(timeoutSeconds)) {
            TOKENS.remove(token);
            return false;
        }
        session.createdAt = System.currentTimeMillis();
        return true;
    }

    /**
     * 刷新 token —— 用新 token 替换旧 token（旧 token 失效）。
     */
    public static Session refresh(String oldToken, int timeoutSeconds) {
        Session old = TOKENS.remove(oldToken);
        if (old == null || old.isExpired(timeoutSeconds)) return null;
        return create(old.playerUuid, old.playerName, old.isAdmin);
    }

    /**
     * 移除 token。
     */
    public static void remove(String token) {
        TOKENS.remove(token);
    }

    /**
     * 清理所有过期会话。
     */
    public static void cleanup(int timeoutSeconds) {
        long now = System.currentTimeMillis();
        TOKENS.entrySet().removeIf(e ->
                now - e.getValue().createdAt > timeoutSeconds * 1000L);
    }

    /**
     * 当前活跃会话数。
     */
    public static int activeCount() {
        return TOKENS.size();
    }

    private static String generateToken() {
        byte[] bytes = new byte[24]; // 48 hex chars
        RNG.nextBytes(bytes);
        StringBuilder sb = new StringBuilder(48);
        for (byte b : bytes) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }
}
