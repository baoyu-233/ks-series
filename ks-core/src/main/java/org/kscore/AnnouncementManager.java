package org.kscore;

import java.sql.*;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 公告 / 公示栏管理（ks-core 本体）。
 *
 * 任何 ks-* 插件都可通过 {@code core.bridge().postAnnouncement(...)} 发布公告，
 * 公告在 /announce 页面对所有人公开展示（读取无需 token）。
 *
 * refKey：可选的去重键。传入 refKey 时按其 upsert（先删同 key 再插），
 * 便于"动态公告"（如某提案表决中）随状态变化更新 / 撤下。
 *
 * category 约定（仅用于前端分组，可自定义）：
 *   GENERAL  普通公告（管理员手写）
 *   SYSTEM   系统通知
 *   VOTING   元老院表决中（政治扩展推送）
 *   LAW      已颁布法案（政治扩展推送）
 */
public final class AnnouncementManager {

    private final KsCore core;

    public AnnouncementManager(KsCore core) {
        this.core = core;
    }

    public void init() {
        try (Connection c = core.dataStore().getConnection()) {
            if (c == null) return;
            try (Statement s = c.createStatement()) {
                KsDataStore.DatabaseDialect dialect = core.dataStore().dialect();
                s.execute(createTableSql(dialect));
                if ((dialect != KsDataStore.DatabaseDialect.MYSQL
                        && dialect != KsDataStore.DatabaseDialect.MARIADB)
                        || !indexExists(c, "ks_announcements", "idx_ann_ref")) {
                    try {
                        s.execute(createIndexSql(dialect));
                    } catch (SQLException failure) {
                        if (failure.getErrorCode() != 1061
                                && !indexExists(c, "ks_announcements", "idx_ann_ref")) {
                            throw failure;
                        }
                    }
                }
            }
        } catch (SQLException e) {
            core.getLogger().warning("[公告] 建表失败: " + e.getMessage());
        }
    }

    static String createTableSql(KsDataStore.DatabaseDialect dialect) {
        String identity = switch (dialect) {
            case SQLITE -> "INTEGER PRIMARY KEY AUTOINCREMENT";
            case MYSQL, MARIADB -> "BIGINT AUTO_INCREMENT PRIMARY KEY";
            case POSTGRESQL -> "BIGSERIAL PRIMARY KEY";
        };
        return "CREATE TABLE IF NOT EXISTS ks_announcements (" +
                "id " + identity + ", " +
                "ref_key VARCHAR(191), " +
                "category VARCHAR(64) NOT NULL DEFAULT 'GENERAL', " +
                "title VARCHAR(255) NOT NULL, " +
                "body TEXT NOT NULL, " +
                "author VARCHAR(128) DEFAULT '', " +
                "priority INTEGER DEFAULT 0, " +
                "created_at BIGINT NOT NULL, " +
                "expires_at BIGINT NOT NULL DEFAULT 0)";
    }

    static String createIndexSql(KsDataStore.DatabaseDialect dialect) {
        String ifMissing = dialect == KsDataStore.DatabaseDialect.MYSQL
                || dialect == KsDataStore.DatabaseDialect.MARIADB ? "" : " IF NOT EXISTS";
        return "CREATE INDEX" + ifMissing + " idx_ann_ref ON ks_announcements(ref_key)";
    }

    private static boolean indexExists(Connection connection, String tableName, String indexName)
            throws SQLException {
        DatabaseMetaData metadata = connection.getMetaData();
        Set<String> candidates = new LinkedHashSet<>();
        candidates.add(tableName);
        candidates.add(tableName.toUpperCase(Locale.ROOT));
        candidates.add(tableName.toLowerCase(Locale.ROOT));
        for (String candidate : candidates) {
            try (ResultSet indexes = metadata.getIndexInfo(connection.getCatalog(), connection.getSchema(),
                    candidate, false, false)) {
                while (indexes.next()) {
                    if (indexName.equalsIgnoreCase(indexes.getString("INDEX_NAME"))) return true;
                }
            }
        }
        return false;
    }

    /** 发布公告；refKey 非空时按其覆盖（upsert）。expiresAt=0 表示永久。返回 id 或 -1。 */
    public long post(String category, String refKey, String title, String body,
                     String author, int priority, long expiresAt) {
        if (title == null || title.isEmpty()) return -1;
        long now = System.currentTimeMillis() / 1000;
        try (Connection c = core.dataStore().getConnection()) {
            if (c == null) return -1;
            if (refKey != null && !refKey.isEmpty()) {
                try (PreparedStatement ps = c.prepareStatement("DELETE FROM ks_announcements WHERE ref_key=?")) {
                    ps.setString(1, refKey);
                    ps.executeUpdate();
                }
            }
            try (PreparedStatement ps = c.prepareStatement(
                    "INSERT INTO ks_announcements (ref_key, category, title, body, author, priority, created_at, expires_at) " +
                    "VALUES (?,?,?,?,?,?,?,?)", Statement.RETURN_GENERATED_KEYS)) {
                ps.setString(1, refKey);
                ps.setString(2, category != null ? category : "GENERAL");
                ps.setString(3, title);
                ps.setString(4, body != null ? body : "");
                ps.setString(5, author != null ? author : "");
                ps.setInt(6, priority);
                ps.setLong(7, now);
                ps.setLong(8, expiresAt);
                ps.executeUpdate();
                try (ResultSet rs = ps.getGeneratedKeys()) {
                    if (rs.next()) return rs.getLong(1);
                }
            }
        } catch (SQLException e) {
            core.getLogger().warning("[公告] 发布失败: " + e.getMessage());
        }
        return -1;
    }

    public boolean removeByRef(String refKey) {
        if (refKey == null || refKey.isEmpty()) return false;
        try (Connection c = core.dataStore().getConnection()) {
            if (c == null) return false;
            try (PreparedStatement ps = c.prepareStatement("DELETE FROM ks_announcements WHERE ref_key=?")) {
                ps.setString(1, refKey);
                return ps.executeUpdate() > 0;
            }
        } catch (SQLException e) {
            return false;
        }
    }

    public boolean removeById(long id) {
        try (Connection c = core.dataStore().getConnection()) {
            if (c == null) return false;
            try (PreparedStatement ps = c.prepareStatement("DELETE FROM ks_announcements WHERE id=?")) {
                ps.setLong(1, id);
                return ps.executeUpdate() > 0;
            }
        } catch (SQLException e) {
            return false;
        }
    }

    /** 列出未过期公告（category 为空=全部），priority 降序 + 时间降序。 */
    public List<Map<String, Object>> list(String category) {
        List<Map<String, Object>> out = new ArrayList<>();
        long now = System.currentTimeMillis() / 1000;
        boolean filter = category != null && !category.isEmpty();
        try (Connection c = core.dataStore().getConnection()) {
            if (c == null) return out;
            String sql = "SELECT * FROM ks_announcements WHERE (expires_at=0 OR expires_at>?)" +
                    (filter ? " AND category=?" : "") +
                    " ORDER BY priority DESC, created_at DESC LIMIT 200";
            try (PreparedStatement ps = c.prepareStatement(sql)) {
                ps.setLong(1, now);
                if (filter) ps.setString(2, category);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        Map<String, Object> m = new LinkedHashMap<>();
                        m.put("id", rs.getLong("id"));
                        m.put("refKey", rs.getString("ref_key"));
                        m.put("category", rs.getString("category"));
                        m.put("title", rs.getString("title"));
                        m.put("body", rs.getString("body"));
                        m.put("author", rs.getString("author"));
                        m.put("priority", rs.getInt("priority"));
                        m.put("createdAt", rs.getLong("created_at"));
                        m.put("expiresAt", rs.getLong("expires_at"));
                        out.add(m);
                    }
                }
            }
        } catch (SQLException e) {
            core.getLogger().warning("[公告] 查询失败: " + e.getMessage());
        }
        return out;
    }
}
