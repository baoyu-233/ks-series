package org.ksinherit;

import com.google.gson.Gson;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.sql.*;
import java.util.*;
import java.util.logging.Level;

/**
 * ks-Inherit — 跨版本物品继承插件。
 *
 * <p>1.20.6 模式（无 ks-core）：
 *   /inherit open — GUI 箱子存物品，保留全部 NBT
 *   /inherit slots <player> <N> — 管理员限制玩家槽位数
 *
 * <p>1.21.11 模式（接入 ks-core）：
 *   注册 Web 路由 /ks-Inherit，提供物品审阅/批准/发放 API，
 *   集成 OpenInv 将物品发放到玩家背包。
 */
public final class KsInherit extends JavaPlugin {

    private static final String DB_PATH = "plugins/ks-Inherit/items.db";
    private static final int GUI_ROWS = 6;           // GUI 总行数（54 格）

    private final Gson gson = new Gson();
    private Connection conn;
    private final Map<UUID, Integer> slotOverrides = new HashMap<>();
    private int defaultMaxSlots = 9; // 从 config.yml 加载，默认 9

    // ==================== 生命周期 ====================

    @Override
    public void onEnable() {
        saveDefaultConfig();
        defaultMaxSlots = Math.max(1, Math.min(54, getConfig().getInt("default-slots", 9)));
        getLogger().info("默认槽位数: " + defaultMaxSlots);
        initDatabase();
        loadSlotOverrides();

        // 注册命令
        var cmd = Objects.requireNonNull(getCommand("inherit"));
        cmd.setExecutor(new InheritCommand(this));
        cmd.setTabCompleter(new InheritTabCompleter());

        // 注册事件
        getServer().getPluginManager().registerEvents(new InheritListener(this), this);

        // 1.21.11: 尝试接入 ks-core Web 路由
        tryRegisterWebHandler();

        getLogger().info("ks-Inherit v" + getDescription().getVersion() + " 已启用");
    }

    @Override
    public void onDisable() {
        closeConnection();
        getLogger().info("ks-Inherit 已禁用");
    }

    private void closeConnection() {
        if (conn != null) {
            try { conn.close(); } catch (SQLException ignored) {}
            conn = null;
        }
    }

    /**
     * 热重载：自动检测 plugins/ks-Inherit/items_new.db。
     * 如果存在 → 导入其全部物品到当前 items.db → 删除 items_new.db（释放文件锁）。
     * 如果不存在 → 仅关闭重连数据库连接。
     *
     * <p>工作流：从 1.20.6 复制 db 时改名为 items_new.db 放到插件目录，
     * 执行 /inherit reload 即可，无需停止服务器。
     */
    public String reloadDatabase() {
        File newDbFile = new File(getDataFolder(), "items_new.db");
        if (newDbFile.exists()) {
            int count = importFromExternalDb(newDbFile.getAbsolutePath());
            if (count >= 0) {
                newDbFile.delete();
                loadSlotOverrides();
                String msg = "已从 items_new.db 导入 " + count + " 条物品，外部文件已删除";
                getLogger().info(msg);
                return msg;
            } else {
                String msg = "导入失败（数据库可能被锁定），请重试 /inherit reload";
                getLogger().warning(msg);
                return msg;
            }
        } else {
            closeConnection();
            initDatabase();
            loadSlotOverrides();
            getLogger().info("数据库已热重载 (slot overrides: " + slotOverrides.size() + ")");
            return "OK (connection reloaded, no items_new.db found)";
        }
    }

    /**
     * 从外部 SQLite db 逐行导入全部物品到当前数据库。
     * 使用独立 JDBC 连接读取外部 db，避免 ATTACH 导致的文件锁问题。
     */
    private int importFromExternalDb(String externalPath) {
        Connection extConn = null;
        try {
            extConn = DriverManager.getConnection("jdbc:sqlite:" + externalPath);
            if (conn == null || conn.isClosed()) {
                initDatabase();
            }
            int count = 0;
            try (Statement readStmt = extConn.createStatement();
                 var rs = readStmt.executeQuery("SELECT player_uuid, player_name, slot, item_json, item_type, item_name, item_lore, enchantments, status, submitted_at FROM ks_inherit_items")) {

                conn.setAutoCommit(false);
                try (PreparedStatement ps = conn.prepareStatement(
                        "INSERT OR REPLACE INTO ks_inherit_items (player_uuid, player_name, slot, item_json, item_type, item_name, item_lore, enchantments, status, submitted_at) " +
                        "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)")) {
                    while (rs.next()) {
                        ps.setString(1, rs.getString("player_uuid"));
                        ps.setString(2, rs.getString("player_name"));
                        ps.setInt(3, rs.getInt("slot"));
                        ps.setString(4, rs.getString("item_json"));
                        ps.setString(5, rs.getString("item_type"));
                        ps.setString(6, rs.getString("item_name"));
                        ps.setString(7, rs.getString("item_lore"));
                        ps.setString(8, rs.getString("enchantments"));
                        ps.setString(9, rs.getString("status"));
                        ps.setLong(10, rs.getLong("submitted_at"));
                        ps.addBatch();
                        count++;
                        if (count % 100 == 0) ps.executeBatch();
                    }
                    ps.executeBatch();
                }
                conn.commit();
                conn.setAutoCommit(true);
            }
            getLogger().info("导入完成: " + count + " 条记录从 " + externalPath);
            return count;
        } catch (SQLException e) {
            getLogger().warning("外部数据库导入失败: " + e.getMessage());
            try { if (conn != null) conn.rollback(); } catch (SQLException ignored) {}
            return -1;
        } finally {
            if (extConn != null) {
                try { extConn.close(); } catch (SQLException ignored) {}
            }
        }
    }

    // ==================== 数据库 ====================

    private void initDatabase() {
        try {
            File dbFile = new File(DB_PATH);
            dbFile.getParentFile().mkdirs();
            conn = DriverManager.getConnection("jdbc:sqlite:" + dbFile.getAbsolutePath());
            try (Statement stmt = conn.createStatement()) {
                stmt.execute("""
                    CREATE TABLE IF NOT EXISTS ks_inherit_items (
                        id INTEGER PRIMARY KEY AUTOINCREMENT,
                        player_uuid TEXT NOT NULL,
                        player_name TEXT NOT NULL,
                        slot INTEGER NOT NULL,
                        item_json TEXT NOT NULL,
                        item_type TEXT NOT NULL,
                        item_name TEXT DEFAULT '',
                        item_lore TEXT DEFAULT '',
                        enchantments TEXT DEFAULT '',
                        status TEXT DEFAULT 'PENDING',
                        submitted_at INTEGER NOT NULL,
                        reviewed_by TEXT DEFAULT '',
                        reviewed_at INTEGER DEFAULT 0,
                        delivered_at INTEGER DEFAULT 0,
                        UNIQUE(player_uuid, slot)
                    )
                """);
                stmt.execute("""
                    CREATE TABLE IF NOT EXISTS ks_inherit_config (
                        key TEXT PRIMARY KEY,
                        value TEXT NOT NULL
                    )
                """);
                // 兼容旧表加列
                try { stmt.execute("ALTER TABLE ks_inherit_items ADD COLUMN item_lore TEXT DEFAULT ''"); } catch (SQLException ignored) {}
                try { stmt.execute("ALTER TABLE ks_inherit_items ADD COLUMN enchantments TEXT DEFAULT ''"); } catch (SQLException ignored) {}
            }
        } catch (SQLException e) {
            getLogger().log(Level.SEVERE, "数据库初始化失败", e);
        }
    }

    /** 获取 ks-Inherit 专用数据库连接 */
    public Connection getConnection() {
        return conn;
    }

    private void loadSlotOverrides() {
        try {
            var c = getConnection();
            if (c == null) return;
            var rs = c.createStatement().executeQuery(
                "SELECT key, value FROM ks_inherit_config WHERE key LIKE 'slot_%'");
            while (rs.next()) {
                String uuid = rs.getString("key").substring(5);
                int slots = Integer.parseInt(rs.getString("value"));
                slotOverrides.put(UUID.fromString(uuid), slots);
            }
        } catch (SQLException ignored) {}
    }

    /** 获取玩家可用槽位数 */
    public int getMaxSlots(Player player) {
        if (player.hasPermission("ksinherit.admin")) return GUI_ROWS * 9; // 管理员全部槽位
        return slotOverrides.getOrDefault(player.getUniqueId(), defaultMaxSlots);
    }

    /** 设置玩家可用槽位数 */
    public void setMaxSlots(UUID uuid, int slots) {
        slotOverrides.put(uuid, slots);
        try {
            var c = getConnection();
            if (c == null) return;
            c.createStatement().executeUpdate(
                "INSERT OR REPLACE INTO ks_inherit_config (key, value) VALUES ('slot_" +
                uuid.toString() + "', '" + slots + "')");
        } catch (SQLException ignored) {}
    }

    // ==================== 物品存取 ====================

    /**
     * 保存玩家槽位物品（使用 Paper 原生 NBT 字节序列化，兼容 1.20.6+）。
     *
     * <p>已批准 (APPROVED) 或已发放 (DELIVERED) 的记录绝不允许被玩家的 GUI 保存覆盖，
     * 否则会用 INSERT OR REPLACE 抹掉审批历史并把状态打回 PENDING，
     * 造成同一件物品被管理员重复批准/发放（刷物品漏洞）。
     *
     * @return true 表示保存成功；false 表示该槽位已被锁定（已审批/已发放），未作任何修改
     */
    public boolean saveItem(Player player, int slot, ItemStack item) {
        String uuid = player.getUniqueId().toString();
        String name = player.getName();
        long now = System.currentTimeMillis() / 1000;

        // 序列化物品为 Base64（Paper serializeAsBytes 保留全部 NBT，不触发 Gson Optional 问题）
        String base64 = Base64.getEncoder().encodeToString(item.serializeAsBytes());

        // 提取元信息（仅用于 Web 预览，不依赖 Gson 反射）
        String typeName = item.getType().name();
        String displayName = "";
        String lore = "";
        String enchants = "";
        var meta = item.getItemMeta();
        if (meta != null) {
            displayName = meta.hasDisplayName() ? meta.getDisplayName() : "";
            if (meta.hasLore()) {
                var lores = meta.getLore();
                lore = lores != null && !lores.isEmpty() ? gson.toJson(lores) : "";
            }
            if (meta.hasEnchants()) {
                enchants = gson.toJson(toEnchantMap(meta.getEnchants()));
            }
        }

        try {
            var c = getConnection();
            if (c == null) return false;

            if (isSlotLocked(c, uuid, slot)) return false;

            c.createStatement().executeUpdate(
                "INSERT OR REPLACE INTO ks_inherit_items (player_uuid, player_name, slot, item_json, item_type, item_name, item_lore, enchantments, status, submitted_at) VALUES ('" +
                uuid + "', '" + name.replace("'", "''") + "', " + slot + ", '" +
                base64.replace("'", "''") + "', '" + typeName + "', '" +
                displayName.replace("'", "''") + "', '" + lore.replace("'", "''") + "', '" +
                enchants.replace("'", "''") + "', 'PENDING', " + now + ")");
            return true;
        } catch (SQLException e) {
            getLogger().warning("保存物品失败: " + e.getMessage());
            return false;
        }
    }

    /** 删除玩家槽位物品（已审批/已发放的记录被锁定，不会被删除） */
    public void deleteItem(Player player, int slot) {
        try {
            var c = getConnection();
            if (c == null) return;
            c.createStatement().executeUpdate(
                "DELETE FROM ks_inherit_items WHERE player_uuid='" +
                player.getUniqueId().toString() + "' AND slot=" + slot +
                " AND status NOT IN ('APPROVED','DELIVERED')");
        } catch (SQLException ignored) {}
    }

    /** 槽位是否已被锁定（已审批待发放 / 已发放，禁止玩家在 GUI 中修改或清空） */
    private boolean isSlotLocked(Connection c, String uuid, int slot) throws SQLException {
        var rs = c.createStatement().executeQuery(
            "SELECT status FROM ks_inherit_items WHERE player_uuid='" + uuid + "' AND slot=" + slot);
        if (rs.next()) {
            String status = rs.getString("status");
            return "APPROVED".equals(status) || "DELIVERED".equals(status);
        }
        return false;
    }

    /**
     * 加载玩家可在 GUI 中编辑的物品。
     *
     * <p>故意排除 APPROVED / DELIVERED 状态：这些记录已进入审批/发放流程，
     * 不应再出现在玩家可编辑的存储槽位中，否则玩家关闭 GUI 时会被
     * {@link #saveItem} 重新写入并打回 PENDING，导致重复审批/发放。
     */
    public Map<Integer, ItemStack> loadItems(Player player) {
        Map<Integer, ItemStack> items = new LinkedHashMap<>();
        try {
            var c = getConnection();
            if (c == null) return items;
            var rs = c.createStatement().executeQuery(
                "SELECT slot, item_json, status FROM ks_inherit_items WHERE player_uuid='" +
                player.getUniqueId().toString() + "' AND status NOT IN ('APPROVED','DELIVERED') ORDER BY slot");
            while (rs.next()) {
                int slot = rs.getInt("slot");
                String base64 = rs.getString("item_json");
                try {
                    byte[] data = Base64.getDecoder().decode(base64);
                    ItemStack item = ItemStack.deserializeBytes(data);
                    items.put(slot, item);
                } catch (Exception e) {
                    getLogger().warning("反序列化物品失败 slot=" + slot + ": " + e.getMessage());
                }
            }
        } catch (SQLException e) {
            getLogger().warning("加载物品失败: " + e.getMessage());
        }
        return items;
    }

    /** 清空玩家所有物品（已审批/已发放的记录被锁定，不会被清空） */
    public void clearItems(UUID uuid) {
        try {
            var c = getConnection();
            if (c == null) return;
            c.createStatement().executeUpdate(
                "DELETE FROM ks_inherit_items WHERE player_uuid='" + uuid.toString() +
                "' AND status NOT IN ('APPROVED','DELIVERED')");
        } catch (SQLException ignored) {}
    }

    // ==================== 审阅 / 批准 / 发放 ====================

    /** 获取所有待审物品（管理员用） */
    public List<Map<String, Object>> getAllItems(String statusFilter) {
        List<Map<String, Object>> list = new ArrayList<>();
        try {
            var c = getConnection();
            if (c == null) return list;
            String sql = "SELECT * FROM ks_inherit_items";
            if (statusFilter != null && !statusFilter.isEmpty()) {
                sql += " WHERE status='" + statusFilter.replace("'", "''") + "'";
            }
            sql += " ORDER BY submitted_at DESC LIMIT 500";
            var rs = c.createStatement().executeQuery(sql);
            var meta = rs.getMetaData();
            int cols = meta.getColumnCount();
            while (rs.next()) {
                Map<String, Object> row = new LinkedHashMap<>();
                for (int i = 1; i <= cols; i++) {
                    row.put(meta.getColumnName(i), rs.getObject(i));
                }
                list.add(row);
            }
        } catch (SQLException e) {
            getLogger().warning("查询物品列表失败: " + e.getMessage());
        }
        return list;
    }

    /** 获取指定玩家的物品 */
    public List<Map<String, Object>> getPlayerItems(String playerUuid) {
        List<Map<String, Object>> list = new ArrayList<>();
        try {
            var c = getConnection();
            if (c == null) return list;
            var rs = c.createStatement().executeQuery(
                "SELECT * FROM ks_inherit_items WHERE player_uuid='" +
                playerUuid.replace("'", "''") + "' ORDER BY slot");
            var meta = rs.getMetaData();
            int cols = meta.getColumnCount();
            while (rs.next()) {
                Map<String, Object> row = new LinkedHashMap<>();
                for (int i = 1; i <= cols; i++) {
                    row.put(meta.getColumnName(i), rs.getObject(i));
                }
                list.add(row);
            }
        } catch (SQLException e) {
            getLogger().warning("查询玩家物品失败: " + e.getMessage());
        }
        return list;
    }

    /** 批准物品 */
    public boolean approveItem(int itemId, String reviewerUuid, String reviewerName) {
        try {
            var c = getConnection();
            if (c == null) return false;
            long now = System.currentTimeMillis() / 1000;
            int updated = c.createStatement().executeUpdate(
                "UPDATE ks_inherit_items SET status='APPROVED', reviewed_by='" +
                reviewerUuid + "', reviewed_at=" + now +
                " WHERE id=" + itemId + " AND status='PENDING'");
            return updated > 0;
        } catch (SQLException e) { return false; }
    }

    /** 拒绝物品 */
    public boolean rejectItem(int itemId, String reviewerUuid, String reviewerName) {
        try {
            var c = getConnection();
            if (c == null) return false;
            long now = System.currentTimeMillis() / 1000;
            int updated = c.createStatement().executeUpdate(
                "UPDATE ks_inherit_items SET status='REJECTED', reviewed_by='" +
                reviewerUuid + "', reviewed_at=" + now +
                " WHERE id=" + itemId);
            return updated > 0;
        } catch (SQLException e) { return false; }
    }

    /** 发放物品到玩家背包（通过 OpenInv 或在线发放） */
    public String deliverItem(int itemId) {
        if (Bukkit.isPrimaryThread()) return deliverItemSync(itemId);
        try {
            return Bukkit.getScheduler().callSyncMethod(this, () -> deliverItemSync(itemId))
                    .get(10, java.util.concurrent.TimeUnit.SECONDS);
        } catch (Exception e) {
            return "发放失败: 无法切换到服务器主线程";
        }
    }

    private synchronized String deliverItemSync(int itemId) {
        try {
            var c = getConnection();
            if (c == null) return "数据库未连接";

            var rs = c.createStatement().executeQuery(
                "SELECT * FROM ks_inherit_items WHERE id=" + itemId);
            if (!rs.next()) return "物品不存在";

            String status = rs.getString("status");
            if (!"APPROVED".equals(status)) return "物品未批准（当前状态: " + status + "）";

            String playerUuid = rs.getString("player_uuid");
            String base64 = rs.getString("item_json");
            ItemStack item = ItemStack.deserializeBytes(Base64.getDecoder().decode(base64));

            UUID uuid = UUID.fromString(playerUuid);
            Player player = Bukkit.getPlayer(uuid);

            // 尝试 OpenInv（离线玩家背包操作）
            if (player == null || !player.isOnline()) {
                boolean deliveredViaOpenInv = tryOpenInvDelivery(uuid, item);
                if (!deliveredViaOpenInv) {
                    return "玩家不在线且 OpenInv 不可用，请玩家上线后重试";
                }
            } else {
                // 在线：直接放入背包
                var leftover = player.getInventory().addItem(item);
                if (!leftover.isEmpty()) {
                    // 背包满 → 尝试丢在地上
                    for (ItemStack is : leftover.values()) {
                        player.getWorld().dropItemNaturally(player.getLocation(), is);
                    }
                }
            }

            // 标记已发放
            long now = System.currentTimeMillis() / 1000;
            c.createStatement().executeUpdate(
                "UPDATE ks_inherit_items SET status='DELIVERED', delivered_at=" + now +
                " WHERE id=" + itemId);

            return "OK";
        } catch (Exception e) {
            return "发放失败: " + e.getMessage();
        }
    }

    /** Deliver item to offline player via OpenInv 5.x API (reflection). */
    private boolean tryOpenInvDelivery(UUID uuid, ItemStack item) {
        try {
            var openInvPlugin = Bukkit.getPluginManager().getPlugin("OpenInv");
            if (openInvPlugin == null) return false;

            // Step 1: getPlayerLoader()
            var getLoader = openInvPlugin.getClass().getMethod("getPlayerLoader");
            Object playerLoader = getLoader.invoke(openInvPlugin);

            // Step 2: playerLoader.load(OfflinePlayer) → Player
            var loadMethod = playerLoader.getClass().getMethod("load", OfflinePlayer.class);
            OfflinePlayer offPlayer = Bukkit.getOfflinePlayer(uuid);
            Player loaded = (Player) loadMethod.invoke(playerLoader, offPlayer);

            if (loaded != null) {
                // Step 3: add items to loaded player's inventory
                var leftover = loaded.getInventory().addItem(item);
                if (!leftover.isEmpty()) {
                    getLogger().warning("Player " + uuid + " inventory full, could not place all items");
                }

                // Step 4: save via profile store (OpenInv 5.x)
                var getStore = playerLoader.getClass().getMethod("getProfileStore");
                Object profileStore = getStore.invoke(playerLoader);
                if (profileStore != null) {
                    var saveMethod = profileStore.getClass().getMethod("save", UUID.class);
                    saveMethod.invoke(profileStore, uuid);
                } else {
                    loaded.saveData(); // fallback
                }
                return true;
            }
        } catch (Exception e) {
            getLogger().warning("OpenInv delivery failed: " + e.getClass().getSimpleName() + ": " + e.getMessage());
        }
        return false;
    }

    // ==================== Web 路由（仅 1.21.11 + ks-core） ====================

    private void tryRegisterWebHandler() {
        try {
            // Step 1: find ks-core
            var corePlugin = Bukkit.getPluginManager().getPlugin("ks-core");
            if (corePlugin == null) {
                getLogger().info("未检测到 ks-core，跳过 Web 路由注册（1.20.6 模式）");
                return;
            }
            getLogger().info("Step 1: ks-core found, class=" + corePlugin.getClass().getName());
            // Step 2: call core.bridge()
            var bridgeMethod = corePlugin.getClass().getMethod("bridge");
            Object bridge = bridgeMethod.invoke(corePlugin);
            getLogger().info("Step 2: bridge obtained, class=" + bridge.getClass().getName());
            // Step 3: call bridge.registerRoute("ks-Inherit", "/ks-Inherit", handler)
            var regMethod = bridge.getClass().getMethod("registerRoute",
                String.class, String.class, Class.forName("com.sun.net.httpserver.HttpHandler"));
            regMethod.invoke(bridge, "ks-Inherit", "/ks-Inherit", new InheritWebHandler(this));
            getLogger().info("Step 3: 已注册 Web 路由 /ks-Inherit（1.21.11 模式）");
        } catch (Exception e) {
            getLogger().warning("Web 路由注册失败 (step): " + e.getClass().getSimpleName() + ": " + e.getMessage());
        }
    }

    // ==================== 工具 ====================

    /** 检查是否为潜影盒 */
    public static boolean isShulkerBox(Material type) {
        String name = type.name();
        return name.contains("SHULKER_BOX") || name.equals("SHULKER_BOX");
    }

    /** 将 Enchantment→level Map 转为 namespace:key→level Map */
    private static Map<String, Integer> toEnchantMap(Map<org.bukkit.enchantments.Enchantment, Integer> enchants) {
        Map<String, Integer> result = new LinkedHashMap<>();
        for (var entry : enchants.entrySet()) {
            result.put(entry.getKey().getKey().getKey(), entry.getValue());
        }
        return result;
    }

    public Gson gson() { return gson; }
}
