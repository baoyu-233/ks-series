package org.kstitle;

import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.kstitle.model.IaBinding;
import org.kstitle.model.TitleAttribute;
import org.kstitle.model.TitleDef;
import org.kstitle.model.TitleFrame;

import java.sql.*;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.logging.Level;

/** 称号定义/持有/佩戴的 CRUD 与购买业务逻辑。 */
public final class TitleManager {

    public static final double MAX_ABS_ATTRIBUTE_AMOUNT = 1_000_000.0;
    public static final int MIN_FRAME_INTERVAL_MS = 50;
    public static final int MAX_FRAME_INTERVAL_MS = 60_000;
    public static final int MAX_IA_FRAME_COUNT = 256;

    private final KsTitle plugin;

    public record GrantTempResult(boolean success, String message, long expiresAt) {}

    private record OwnershipInfo(boolean exists, boolean active, long expiresAt) {
        boolean permanent() { return active && expiresAt <= 0L; }
        boolean temporary() { return active && expiresAt > 0L; }
    }

    public TitleManager(KsTitle plugin) {
        this.plugin = plugin;
    }

    private Connection conn() { return plugin.getConnection(); }

    // ==================== 称号定义 ====================

    /** 管理员创建称号（自增ID）。 */
    public int createTitle(String displayName, String description, String category, String rarity,
                            double price, String unlockType, String conditionType, String conditionValue) {
        if (displayName == null || displayName.isBlank() || !validPrice(price)) return -1;
        String sql = "INSERT INTO ks_title_defs (display_name, description, category, rarity, price, " +
            "unlock_type, condition_type, condition_value, visible, enabled, created_at) VALUES (?,?,?,?,?,?,?,?,1,1,?)";
        try (PreparedStatement ps = conn().prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            ps.setString(1, displayName);
            ps.setString(2, description == null ? "" : description);
            ps.setString(3, category == null || category.isBlank() ? "general" : category);
            ps.setString(4, rarity == null || rarity.isBlank() ? "COMMON" : rarity);
            ps.setDouble(5, price);
            ps.setString(6, unlockType == null ? "ADMIN_GRANT" : unlockType);
            ps.setString(7, conditionType == null ? "" : conditionType);
            ps.setString(8, conditionValue == null ? "" : conditionValue);
            ps.setLong(9, System.currentTimeMillis());
            ps.executeUpdate();
            try (ResultSet rs = ps.getGeneratedKeys()) {
                if (rs.next()) return rs.getInt(1);
            }
        } catch (SQLException e) {
            plugin.getLogger().log(Level.WARNING, "创建称号失败", e);
        }
        return -1;
    }

    /** 迁移工具专用：插入指定 ID 的称号定义（保留 PlayerTitle 原 ID）。 */
    public boolean insertDefWithId(int id, String displayName, String description, String category,
                                    boolean visible, String unlockType, String conditionType, String conditionValue,
                                    long createdAt) {
        String sql = "INSERT OR IGNORE INTO ks_title_defs (id, display_name, description, category, rarity, " +
            "price, unlock_type, condition_type, condition_value, visible, enabled, created_at) " +
            "VALUES (?,?,?,?, 'COMMON', 0, ?,?,?,?,1,?)";
        try (PreparedStatement ps = conn().prepareStatement(sql)) {
            ps.setInt(1, id);
            ps.setString(2, displayName);
            ps.setString(3, description == null ? "" : description);
            ps.setString(4, category == null ? "general" : category);
            ps.setString(5, unlockType);
            ps.setString(6, conditionType == null ? "" : conditionType);
            ps.setString(7, conditionValue == null ? "" : conditionValue);
            ps.setInt(8, visible ? 1 : 0);
            ps.setLong(9, createdAt);
            return ps.executeUpdate() > 0;
        } catch (SQLException e) {
            plugin.getLogger().log(Level.WARNING, "迁移插入称号定义失败 id=" + id, e);
            return false;
        }
    }

    public boolean updateTitle(int id, String displayName, String description, String category, String rarity,
                                double price, String unlockType, String conditionType, String conditionValue,
                                boolean visible, boolean enabled) {
        if (id <= 0 || displayName == null || displayName.isBlank() || !validPrice(price)) return false;
        String sql = "UPDATE ks_title_defs SET display_name=?, description=?, category=?, rarity=?, price=?, " +
            "unlock_type=?, condition_type=?, condition_value=?, visible=?, enabled=? WHERE id=?";
        try (PreparedStatement ps = conn().prepareStatement(sql)) {
            ps.setString(1, displayName);
            ps.setString(2, description);
            ps.setString(3, category);
            ps.setString(4, rarity);
            ps.setDouble(5, price);
            ps.setString(6, unlockType);
            ps.setString(7, conditionType);
            ps.setString(8, conditionValue);
            ps.setInt(9, visible ? 1 : 0);
            ps.setInt(10, enabled ? 1 : 0);
            ps.setInt(11, id);
            return ps.executeUpdate() > 0;
        } catch (SQLException e) {
            plugin.getLogger().log(Level.WARNING, "更新称号失败 id=" + id, e);
            return false;
        }
    }

    /** Updates only old command-generated wrappers; custom/IA title formats are left untouched. */
    public int normalizeLegacyCommandTitleWrappers() {
        int changed = 0;
        for (TitleDef def : listDefs()) {
            String normalized = TitleCommand.normalizeCommandBracketWrapper(def.displayName());
            if (normalized.equals(def.displayName())) continue;
            try (PreparedStatement ps = conn().prepareStatement(
                    "UPDATE ks_title_defs SET display_name=? WHERE id=? AND display_name=?")) {
                ps.setString(1, normalized);
                ps.setInt(2, def.id());
                ps.setString(3, def.displayName());
                changed += ps.executeUpdate();
            } catch (SQLException e) {
                plugin.getLogger().log(Level.WARNING, "Failed to normalize title wrapper id=" + def.id(), e);
            }
        }
        return changed;
    }

    public boolean deleteTitle(int id) {
        try {
            conn().setAutoCommit(false);
            try (PreparedStatement p1 = conn().prepareStatement("DELETE FROM ks_title_attributes WHERE title_id=?");
                 PreparedStatement p2 = conn().prepareStatement("DELETE FROM ks_title_frames WHERE title_id=?");
                 PreparedStatement p3 = conn().prepareStatement("DELETE FROM ks_title_ownership WHERE title_id=?");
                 PreparedStatement p4 = conn().prepareStatement("DELETE FROM ks_title_equipped WHERE title_id=?");
                 PreparedStatement p5 = conn().prepareStatement("DELETE FROM ks_title_defs WHERE id=?")) {
                p1.setInt(1, id); p1.executeUpdate();
                p2.setInt(1, id); p2.executeUpdate();
                p3.setInt(1, id); p3.executeUpdate();
                p4.setInt(1, id); p4.executeUpdate();
                p5.setInt(1, id); int n = p5.executeUpdate();
                conn().commit();
                return n > 0;
            }
        } catch (SQLException e) {
            plugin.getLogger().log(Level.WARNING, "删除称号失败 id=" + id, e);
            try { conn().rollback(); } catch (SQLException ignored) {}
            return false;
        } finally {
            try { conn().setAutoCommit(true); } catch (SQLException ignored) {}
        }
    }

    public TitleDef getDef(int id) {
        try (PreparedStatement ps = conn().prepareStatement("SELECT * FROM ks_title_defs WHERE id=?")) {
            ps.setInt(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) return mapDef(rs);
            }
        } catch (SQLException e) {
            plugin.getLogger().log(Level.WARNING, "查询称号失败 id=" + id, e);
        }
        return null;
    }

    public List<TitleDef> listDefs() {
        List<TitleDef> out = new ArrayList<>();
        try (Statement stmt = conn().createStatement();
             ResultSet rs = stmt.executeQuery("SELECT * FROM ks_title_defs ORDER BY id")) {
            while (rs.next()) out.add(mapDef(rs));
        } catch (SQLException e) {
            plugin.getLogger().log(Level.WARNING, "列出称号失败", e);
        }
        return out;
    }

    public int countDefs() {
        try (Statement stmt = conn().createStatement();
             ResultSet rs = stmt.executeQuery("SELECT COUNT(*) FROM ks_title_defs")) {
            if (rs.next()) return rs.getInt(1);
        } catch (SQLException e) { /* ignore, db not ready */ }
        return 0;
    }

    public int countOwnership() {
        try (PreparedStatement ps = conn().prepareStatement(
                "SELECT COUNT(*) FROM ks_title_ownership WHERE expires_at=0 OR expires_at>?")) {
            ps.setLong(1, System.currentTimeMillis());
            try (ResultSet rs = ps.executeQuery()) {
            if (rs.next()) return rs.getInt(1);
            }
        } catch (SQLException e) { /* ignore */ }
        return 0;
    }

    public int countEquipped() {
        try (Statement stmt = conn().createStatement();
             ResultSet rs = stmt.executeQuery("SELECT COUNT(*) FROM ks_title_equipped")) {
            if (rs.next()) return rs.getInt(1);
        } catch (SQLException e) { /* ignore */ }
        return 0;
    }

    public String getMeta(String key) {
        try (PreparedStatement ps = conn().prepareStatement("SELECT value FROM ks_title_meta WHERE key=?")) {
            ps.setString(1, key);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) return rs.getString(1);
            }
        } catch (SQLException e) { /* ignore */ }
        return null;
    }

    private TitleDef mapDef(ResultSet rs) throws SQLException {
        return new TitleDef(
            rs.getInt("id"), rs.getString("display_name"), rs.getString("description"),
            rs.getString("category"), rs.getString("rarity"), rs.getDouble("price"),
            rs.getString("unlock_type"), rs.getString("condition_type"), rs.getString("condition_value"),
            rs.getInt("visible") != 0, rs.getInt("enabled") != 0, rs.getLong("created_at")
        );
    }

    // ==================== 属性/技能加成 ====================

    public int addAttribute(int titleId, String buffType, String buffKey, double amount, String extra) {
        if (titleId <= 0 || buffType == null || buffType.isBlank() || buffKey == null || buffKey.isBlank()
                || !validAttributeAmount(amount)) return -1;
        String sql = "INSERT INTO ks_title_attributes (title_id, buff_type, buff_key, amount, extra) VALUES (?,?,?,?,?)";
        try (PreparedStatement ps = conn().prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            ps.setInt(1, titleId);
            ps.setString(2, buffType);
            ps.setString(3, buffKey);
            ps.setDouble(4, amount);
            ps.setString(5, extra == null ? "" : extra);
            ps.executeUpdate();
            try (ResultSet rs = ps.getGeneratedKeys()) {
                if (rs.next()) return rs.getInt(1);
            }
        } catch (SQLException e) {
            plugin.getLogger().log(Level.WARNING, "添加称号属性失败", e);
        }
        return -1;
    }

    public boolean updateAttributeAmount(int attrId, double amount) {
        if (attrId <= 0 || !validAttributeAmount(amount)) return false;
        try (PreparedStatement ps = conn().prepareStatement("UPDATE ks_title_attributes SET amount=? WHERE id=?")) {
            ps.setDouble(1, amount);
            ps.setInt(2, attrId);
            return ps.executeUpdate() > 0;
        } catch (SQLException e) {
            plugin.getLogger().log(Level.WARNING, "更新称号属性数值失败 attrId=" + attrId, e);
            return false;
        }
    }

    public boolean removeAttribute(int attrId) {
        try (PreparedStatement ps = conn().prepareStatement("DELETE FROM ks_title_attributes WHERE id=?")) {
            ps.setInt(1, attrId);
            return ps.executeUpdate() > 0;
        } catch (SQLException e) { return false; }
    }

    public List<TitleAttribute> listAttributes(int titleId) {
        List<TitleAttribute> out = new ArrayList<>();
        try (PreparedStatement ps = conn().prepareStatement("SELECT * FROM ks_title_attributes WHERE title_id=?")) {
            ps.setInt(1, titleId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.add(new TitleAttribute(rs.getInt("id"), rs.getInt("title_id"),
                        rs.getString("buff_type"), rs.getString("buff_key"), rs.getDouble("amount"), rs.getString("extra")));
                }
            }
        } catch (SQLException e) {
            plugin.getLogger().log(Level.WARNING, "查询称号属性失败 titleId=" + titleId, e);
        }
        return out;
    }

    // ==================== 动画帧 ====================

    public void clearFrames(int titleId) {
        try (PreparedStatement ps = conn().prepareStatement("DELETE FROM ks_title_frames WHERE title_id=?")) {
            ps.setInt(1, titleId);
            ps.executeUpdate();
        } catch (SQLException e) {
            plugin.getLogger().log(Level.WARNING, "清空称号帧失败 titleId=" + titleId, e);
        }
    }

    public boolean addFrame(int titleId, int frameIndex, String frameText, int intervalMs) {
        if (titleId <= 0 || frameIndex < 0 || frameText == null || frameText.isBlank()
                || intervalMs < MIN_FRAME_INTERVAL_MS || intervalMs > MAX_FRAME_INTERVAL_MS) return false;
        String sql = "INSERT INTO ks_title_frames (title_id, frame_index, frame_text, interval_ms) VALUES (?,?,?,?)";
        try (PreparedStatement ps = conn().prepareStatement(sql)) {
            ps.setInt(1, titleId);
            ps.setInt(2, frameIndex);
            ps.setString(3, frameText);
            ps.setInt(4, intervalMs);
            return ps.executeUpdate() > 0;
        } catch (SQLException e) {
            plugin.getLogger().log(Level.WARNING, "添加称号帧失败 titleId=" + titleId, e);
            return false;
        }
    }

    public boolean removeFrame(int frameId) {
        try (PreparedStatement ps = conn().prepareStatement("DELETE FROM ks_title_frames WHERE id=?")) {
            ps.setInt(1, frameId);
            return ps.executeUpdate() > 0;
        } catch (SQLException e) {
            plugin.getLogger().log(Level.WARNING, "删除称号帧失败 frameId=" + frameId, e);
            return false;
        }
    }

    public List<TitleFrame> listFrames(int titleId) {
        List<TitleFrame> out = new ArrayList<>();
        try (PreparedStatement ps = conn().prepareStatement(
                "SELECT * FROM ks_title_frames WHERE title_id=? ORDER BY frame_index")) {
            ps.setInt(1, titleId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.add(new TitleFrame(rs.getInt("id"), rs.getInt("title_id"), rs.getInt("frame_index"),
                        rs.getString("frame_text"), rs.getInt("interval_ms")));
                }
            }
        } catch (SQLException e) {
            plugin.getLogger().log(Level.WARNING, "查询称号帧失败 titleId=" + titleId, e);
        }
        return out;
    }

    // ==================== 持有 ====================

    public boolean grantOwnership(UUID uuid, int titleId, String source) {
        OwnershipInfo existing = getOwnershipInfo(uuid, titleId);
        if (existing.permanent()) return false;
        long now = System.currentTimeMillis();
        String sql = existing.exists()
            ? "UPDATE ks_title_ownership SET acquired_at=?, source=?, expires_at=0 WHERE player_uuid=? AND title_id=?"
            : "INSERT INTO ks_title_ownership (acquired_at, source, expires_at, player_uuid, title_id) VALUES (?,?,0,?,?)";
        try (PreparedStatement ps = conn().prepareStatement(sql)) {
            ps.setLong(1, now);
            ps.setString(2, source);
            ps.setString(3, uuid.toString());
            ps.setInt(4, titleId);
            return ps.executeUpdate() > 0;
        } catch (SQLException e) {
            plugin.getLogger().log(Level.WARNING, "发放称号失败 uuid=" + uuid + " titleId=" + titleId, e);
            return false;
        }
    }

    public GrantTempResult grantTemporaryOwnership(UUID uuid, int titleId, long durationMillis, String source) {
        if (durationMillis <= 0L) return new GrantTempResult(false, "duration must be positive", 0L);
        TitleDef def = getDef(titleId);
        if (def == null || !def.enabled()) return new GrantTempResult(false, "title not found or disabled", 0L);

        OwnershipInfo existing = getOwnershipInfo(uuid, titleId);
        if (existing.permanent()) return new GrantTempResult(false, "already owns this title permanently", 0L);

        long now = System.currentTimeMillis();
        long base = existing.temporary() ? existing.expiresAt() : now;
        long expiresAt;
        try {
            expiresAt = Math.addExact(base, durationMillis);
        } catch (ArithmeticException overflow) {
            expiresAt = Long.MAX_VALUE;
        }

        String sql = existing.exists()
            ? "UPDATE ks_title_ownership SET acquired_at=?, source=?, expires_at=? WHERE player_uuid=? AND title_id=?"
            : "INSERT INTO ks_title_ownership (acquired_at, source, expires_at, player_uuid, title_id) VALUES (?,?,?,?,?)";
        try (PreparedStatement ps = conn().prepareStatement(sql)) {
            ps.setLong(1, now);
            ps.setString(2, source);
            ps.setLong(3, expiresAt);
            ps.setString(4, uuid.toString());
            ps.setInt(5, titleId);
            return ps.executeUpdate() > 0
                ? new GrantTempResult(true, "temporary title granted", expiresAt)
                : new GrantTempResult(false, "grant failed", 0L);
        } catch (SQLException e) {
            plugin.getLogger().log(Level.WARNING, "临时发放称号失败 uuid=" + uuid + " titleId=" + titleId, e);
            return new GrantTempResult(false, "database error: " + e.getMessage(), 0L);
        }
    }

    public boolean revokeOwnership(UUID uuid, int titleId) {
        try (PreparedStatement ps = conn().prepareStatement(
                "DELETE FROM ks_title_ownership WHERE player_uuid=? AND title_id=?")) {
            ps.setString(1, uuid.toString());
            ps.setInt(2, titleId);
            int n = ps.executeUpdate();
            Integer equipped = getEquipped(uuid);
            if (equipped != null && equipped == titleId) unequip(uuid);
            return n > 0;
        } catch (SQLException e) {
            plugin.getLogger().log(Level.WARNING, "收回称号失败 uuid=" + uuid + " titleId=" + titleId, e);
            return false;
        }
    }

    public boolean hasOwnership(UUID uuid, int titleId) {
        try (PreparedStatement ps = conn().prepareStatement(
                "SELECT 1 FROM ks_title_ownership WHERE player_uuid=? AND title_id=? AND (expires_at=0 OR expires_at>?)")) {
            ps.setString(1, uuid.toString());
            ps.setInt(2, titleId);
            ps.setLong(3, System.currentTimeMillis());
            try (ResultSet rs = ps.executeQuery()) { return rs.next(); }
        } catch (SQLException e) { return false; }
    }

    public List<Integer> listOwned(UUID uuid) {
        List<Integer> out = new ArrayList<>();
        try (PreparedStatement ps = conn().prepareStatement(
                "SELECT title_id FROM ks_title_ownership WHERE player_uuid=? AND (expires_at=0 OR expires_at>?)")) {
            ps.setString(1, uuid.toString());
            ps.setLong(2, System.currentTimeMillis());
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) out.add(rs.getInt(1));
            }
        } catch (SQLException e) {
            plugin.getLogger().log(Level.WARNING, "查询持有称号失败 uuid=" + uuid, e);
        }
        return out;
    }

    public long getOwnershipExpiresAt(UUID uuid, int titleId) {
        OwnershipInfo info = getOwnershipInfo(uuid, titleId);
        return info.active() ? info.expiresAt() : -1L;
    }

    private OwnershipInfo getOwnershipInfo(UUID uuid, int titleId) {
        try (PreparedStatement ps = conn().prepareStatement(
                "SELECT expires_at FROM ks_title_ownership WHERE player_uuid=? AND title_id=?")) {
            ps.setString(1, uuid.toString());
            ps.setInt(2, titleId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) return new OwnershipInfo(false, false, 0L);
                long expiresAt = rs.getLong(1);
                boolean active = expiresAt <= 0L || expiresAt > System.currentTimeMillis();
                return new OwnershipInfo(true, active, expiresAt);
            }
        } catch (SQLException e) {
            return new OwnershipInfo(false, false, 0L);
        }
    }

    public int expireOwnerships() {
        long now = System.currentTimeMillis();
        int expired = 0;
        try (PreparedStatement ps = conn().prepareStatement(
                "SELECT player_uuid, title_id FROM ks_title_ownership WHERE expires_at>0 AND expires_at<=?")) {
            ps.setLong(1, now);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    UUID uuid = UUID.fromString(rs.getString("player_uuid"));
                    int titleId = rs.getInt("title_id");
                    Integer equipped = getEquipped(uuid);
                    if (equipped != null && equipped == titleId) {
                        Player online = Bukkit.getPlayer(uuid);
                        if (online != null) plugin.attributeApplier().remove(online, titleId);
                        unequip(uuid);
                    }
                    expired++;
                }
            }
        } catch (SQLException | IllegalArgumentException e) {
            plugin.getLogger().log(Level.WARNING, "清理过期称号失败", e);
        }

        try (PreparedStatement ps = conn().prepareStatement(
                "DELETE FROM ks_title_ownership WHERE expires_at>0 AND expires_at<=?")) {
            ps.setLong(1, now);
            ps.executeUpdate();
        } catch (SQLException e) {
            plugin.getLogger().log(Level.WARNING, "删除过期称号失败", e);
        }
        return expired;
    }

    // ==================== 佩戴 ====================

    public Integer getEquipped(UUID uuid) {
        try (PreparedStatement ps = conn().prepareStatement(
                "SELECT title_id FROM ks_title_equipped WHERE player_uuid=?")) {
            ps.setString(1, uuid.toString());
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    int titleId = rs.getInt(1);
                    if (!hasOwnership(uuid, titleId)) {
                        Player online = Bukkit.getPlayer(uuid);
                        if (online != null) plugin.attributeApplier().remove(online, titleId);
                        unequip(uuid);
                        return null;
                    }
                    return titleId;
                }
            }
        } catch (SQLException e) {
            plugin.getLogger().log(Level.WARNING, "查询佩戴称号失败 uuid=" + uuid, e);
        }
        return null;
    }

    /** 迁移工具专用：直接写入佩戴状态（跳过持有校验）。 */
    public void setEquippedRaw(UUID uuid, int titleId, long equippedAt) {
        String sql = "INSERT OR REPLACE INTO ks_title_equipped (player_uuid, title_id, equipped_at) VALUES (?,?,?)";
        try (PreparedStatement ps = conn().prepareStatement(sql)) {
            ps.setString(1, uuid.toString());
            ps.setInt(2, titleId);
            ps.setLong(3, equippedAt);
            ps.executeUpdate();
        } catch (SQLException e) {
            plugin.getLogger().log(Level.WARNING, "迁移写入佩戴状态失败 uuid=" + uuid, e);
        }
    }

    /** 佩戴称号（需已持有且启用），若玩家在线立即应用属性加成。 */
    public boolean equip(Player player, int titleId) {
        UUID uuid = player.getUniqueId();
        if (!hasOwnership(uuid, titleId)) return false;
        TitleDef def = getDef(titleId);
        if (def == null || !def.enabled()) return false;
        Integer prev = getEquipped(uuid);
        if (prev != null) plugin.attributeApplier().remove(player, prev);
        setEquippedRaw(uuid, titleId, System.currentTimeMillis());
        plugin.attributeApplier().apply(player, titleId);
        return true;
    }

    public boolean unequip(Player player) {
        Integer prev = getEquipped(player.getUniqueId());
        boolean removed = unequip(player.getUniqueId());
        if (removed && prev != null) plugin.attributeApplier().remove(player, prev);
        return removed;
    }

    private boolean unequip(UUID uuid) {
        try (PreparedStatement ps = conn().prepareStatement("DELETE FROM ks_title_equipped WHERE player_uuid=?")) {
            ps.setString(1, uuid.toString());
            return ps.executeUpdate() > 0;
        } catch (SQLException e) {
            plugin.getLogger().log(Level.WARNING, "摘下称号失败 uuid=" + uuid, e);
            return false;
        }
    }

    // ==================== 购买 ====================

    public record BuyResult(boolean success, String message) {}

    public BuyResult buy(Player player, int titleId) {
        TitleDef def = getDef(titleId);
        if (def == null || !def.enabled()) return new BuyResult(false, "称号不存在或已禁用");
        if (!def.isPurchase()) return new BuyResult(false, "该称号不可购买");
        if (hasOwnership(player.getUniqueId(), titleId)) return new BuyResult(false, "已持有该称号");
        VaultHook vault = plugin.vaultHook();
        if (!vault.isAvailable()) return new BuyResult(false, "经济系统未就绪 (Vault)");
        if (!vault.has(player, def.price())) return new BuyResult(false, "余额不足，需要 " + vault.format(def.price()));
        if (!vault.withdraw(player, def.price())) return new BuyResult(false, "扣款失败");
        if (!grantOwnership(player.getUniqueId(), titleId, "PURCHASE")) {
            vault.deposit(player, def.price());
            return new BuyResult(false, "发放失败，已退款");
        }
        return new BuyResult(true, "购买成功: " + def.displayName());
    }

    // ==================== IA 图片动画绑定 ====================

    public boolean setIaBinding(int titleId, String imagePrefix, int frameCount, int intervalMs, boolean chatStatic) {
        if (titleId <= 0 || imagePrefix == null || !imagePrefix.matches("[a-z0-9_-]{1,64}")
                || frameCount < 1 || frameCount > MAX_IA_FRAME_COUNT
                || intervalMs < MIN_FRAME_INTERVAL_MS || intervalMs > MAX_FRAME_INTERVAL_MS) return false;
        String sql = "INSERT OR REPLACE INTO ks_title_ia_bindings (title_id, image_prefix, frame_count, interval_ms, chat_static, created_at) VALUES (?,?,?,?,?,?)";
        try (PreparedStatement ps = conn().prepareStatement(sql)) {
            ps.setInt(1, titleId);
            ps.setString(2, imagePrefix);
            ps.setInt(3, frameCount);
            ps.setInt(4, intervalMs);
            ps.setInt(5, chatStatic ? 1 : 0);
            ps.setLong(6, System.currentTimeMillis());
            ps.executeUpdate();
            return true;
        } catch (SQLException e) {
            plugin.getLogger().log(Level.WARNING, "写入IA绑定失败 titleId=" + titleId, e);
            return false;
        }
    }

    public boolean removeIaBinding(int titleId) {
        try (PreparedStatement ps = conn().prepareStatement("DELETE FROM ks_title_ia_bindings WHERE title_id=?")) {
            ps.setInt(1, titleId);
            return ps.executeUpdate() > 0;
        } catch (SQLException e) {
            return false;
        }
    }

    public IaBinding getIaBinding(int titleId) {
        try (PreparedStatement ps = conn().prepareStatement("SELECT * FROM ks_title_ia_bindings WHERE title_id=?")) {
            ps.setInt(1, titleId);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) return mapIaBinding(rs);
            }
        } catch (SQLException e) {
            plugin.getLogger().log(Level.WARNING, "查询IA绑定失败 titleId=" + titleId, e);
        }
        return null;
    }

    public List<IaBinding> listIaBindings() {
        List<IaBinding> out = new ArrayList<>();
        try (Statement stmt = conn().createStatement();
             ResultSet rs = stmt.executeQuery("SELECT * FROM ks_title_ia_bindings ORDER BY title_id")) {
            while (rs.next()) out.add(mapIaBinding(rs));
        } catch (SQLException e) {
            plugin.getLogger().log(Level.WARNING, "列出IA绑定失败", e);
        }
        return out;
    }

    private IaBinding mapIaBinding(ResultSet rs) throws SQLException {
        return new IaBinding(rs.getInt("title_id"), rs.getString("image_prefix"), rs.getInt("frame_count"),
            rs.getInt("interval_ms"), rs.getInt("chat_static") != 0, rs.getLong("created_at"));
    }

    private static boolean validPrice(double price) {
        return Double.isFinite(price) && price >= 0.0;
    }

    private static boolean validAttributeAmount(double amount) {
        return Double.isFinite(amount) && Math.abs(amount) <= MAX_ABS_ATTRIBUTE_AMOUNT;
    }

    // ==================== 当前展示文本（供 PAPI 调用） ====================

    /**
     * 佩戴称号当前展示文本；无佩戴返回空字符串。动画称号按墙钟计算当前帧。
     *
     * <p>返回前就用 {@link LegacyText#colorize} 转换好颜色码——本方法是 PAPI
     * {@code %kstitle_use%} 的数据来源，消费方(如 ntdRpChat)是把返回值动态塞进最终文本，
     * 不会再对这段动态内容做 &amp;→§ 转换，之前导致聊天栏原样露出 "&amp;r" 字面文本。</p>
     */
    public String currentDisplay(OfflinePlayer player) {
        Integer titleId = getEquipped(player.getUniqueId());
        if (titleId == null) return "";
        List<TitleFrame> frames = listFrames(titleId);
        if (frames.isEmpty()) {
            TitleDef def = getDef(titleId);
            return def == null ? "" : LegacyText.colorize(TitleCommand.normalizeCommandBracketWrapper(def.displayName()));
        }
        int interval = Math.max(50, frames.get(0).intervalMs());
        int idx = (int) ((System.currentTimeMillis() / interval) % frames.size());
        return LegacyText.colorize(frames.get(idx).frameText());
    }
}
