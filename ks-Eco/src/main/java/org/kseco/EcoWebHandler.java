package org.kseco;

import com.google.gson.Gson;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.kseco.database.PortableSqlMutation;
import org.kseco.crossserver.assets.AssetSource;
import org.kseco.crossserver.assets.FederatedAssetService;
import org.kseco.crossserver.assets.FederatedAssetSettings;
import org.kseco.crossserver.assets.FederatedCapability;
import org.kseco.crossserver.assets.FederatedSnapshot;
import org.kseco.crossserver.assets.FederatedSnapshotCodec;
import org.kseco.extra.BankAccessProvider;
import org.kscore.KsAuthManager;
import org.kscore.KsPluginBridge;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.sql.SQLException;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * ks-Eco Web 管理面板处理器。
 * 通过 ks-core 注册到 /ks-Eco 路由。
 *
 * 主要用于：
 * - 管理员查看市场统计数据
 * - 批量闲置物品强制干预/限价
 * - 交易历史查询
 * - 市场健康度监控
 */
public final class EcoWebHandler implements HttpHandler {

    private static final Set<String> POLITIC_GOVERNED_POST_PATHS = Set.of(
            "/api/admin/force-price", "/api/admin/simulate-trade",
            "/api/admin/price-volatility/settings", "/api/admin/price-volatility/bias",
            "/api/admin/set-balance", "/api/admin/economic-settings",
            "/api/admin/bank/guidance/config", "/api/bank/cb/set-rates",
            "/api/bank/cb/set-rate-range", "/api/bank/cb/inject",
            "/api/admin/bank/policy-events", "/api/admin/bank/operating-status",
            "/api/tax/rates/set", "/api/tax/bracket/upsert", "/api/tax/bracket/delete",
            "/api/prices/official/save", "/api/admin/realestate/zone",
            "/api/admin/realestate/zone/price", "/api/admin/realestate/zone/status",
            "/api/admin/realestate/zone/type", "/api/admin/realestate/zone/max-plots",
            "/api/admin/realestate/zone/dungeon-link", "/api/admin/realestate/zone/delete",
            "/api/admin/land-perks/set", "/api/admin/realestate/plot/perk");
    private static final Map<String, String> FEATURE_MODULES = Map.of(
            "bank", "ks-eco-bank",
            "enterprise", "ks-eco-enterprise",
            "ent_blindbox", "ks-eco-enterprise",
            "invites", "ks-eco-enterprise",
            "realestate", "ks-eco-realestate",
            "dungeon", "ks-eco-realestatedungeon",
            "politic", "ks-eco-politic");

    private final KsEco plugin;
    private final Gson gson = new Gson();
    private final EnterprisePermissionService enterprisePermissions = new EnterprisePermissionService();
    private volatile String adminSpaHtml;
    private volatile String playerSpaHtml;

    private volatile boolean tablesEnsured = false;
    private final AtomicBoolean playerRankingRefreshRunning = new AtomicBoolean(false);
    private volatile List<Map<String, Object>> playerRankingSnapshot = List.of();
    private volatile long playerRankingSnapshotAt = 0;

    public EcoWebHandler(KsEco plugin) {
        this.plugin = plugin;
    }

    /** Bukkit and Vault providers are only accessed from the server thread. */
    private <T> T callOnServerThread(Callable<T> action) throws IOException {
        try {
            return plugin.scheduler().callGlobal(action, Duration.ofSeconds(5));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Server-thread operation interrupted", e);
        } catch (TimeoutException e) {
            throw new IOException("Server-thread operation timed out", e);
        } catch (ExecutionException e) {
            throw new IOException("Server-thread operation failed", e);
        } catch (Exception e) {
            throw new IOException("Server-thread operation failed", e);
        }
    }

    private boolean withdrawWallet(UUID playerUuid, double amount) throws IOException {
        return callOnPlayerThread(playerUuid,
                () -> plugin.vaultHook().withdraw(Bukkit.getOfflinePlayer(playerUuid), amount));
    }

    private boolean depositWallet(UUID playerUuid, double amount) throws IOException {
        return callOnPlayerThread(playerUuid,
                () -> plugin.vaultHook().deposit(Bukkit.getOfflinePlayer(playerUuid), amount));
    }

    private boolean hasWalletBalance(UUID playerUuid, double amount) throws IOException {
        return callOnPlayerThread(playerUuid,
                () -> plugin.vaultHook().has(Bukkit.getOfflinePlayer(playerUuid), amount));
    }

    private double walletBalance(UUID playerUuid) throws IOException {
        return callOnPlayerThread(playerUuid,
                () -> plugin.vaultHook().getBalance(Bukkit.getOfflinePlayer(playerUuid)));
    }

    private <T> T callOnPlayerThread(UUID playerUuid, Callable<T> action) throws IOException {
        try {
            Player online = Bukkit.getPlayer(playerUuid);
            return online != null && online.isOnline()
                    ? plugin.scheduler().callEntity(online, action, Duration.ofSeconds(5))
                    : plugin.scheduler().callGlobal(action, Duration.ofSeconds(5));
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            throw new IOException("Player-thread operation interrupted", failure);
        } catch (ExecutionException | TimeoutException failure) {
            throw new IOException("Player-thread operation failed", failure);
        }
    }

    private void refreshPlayerRankingSnapshotIfStale() {
        if (plugin.foliaRuntime()) {
            // Iterating every online/offline account cannot be assigned to one
            // entity owner. Keep this derived leaderboard closed on Folia until
            // it is sourced entirely from an async JDBC snapshot.
            playerRankingSnapshot = List.of();
            playerRankingSnapshotAt = System.currentTimeMillis();
            playerRankingRefreshRunning.set(false);
            return;
        }
        long now = System.currentTimeMillis();
        if (now - playerRankingSnapshotAt < TimeUnit.MINUTES.toMillis(1)
                || !playerRankingRefreshRunning.compareAndSet(false, true)) return;
        plugin.scheduler().runGlobal(() -> {
            List<org.bukkit.OfflinePlayer> players = Arrays.asList(Bukkit.getOfflinePlayers());
            List<Map<String, Object>> entries = new ArrayList<>();
            final org.kseco.scheduler.EcoScheduler.TaskHandle[] handle = new org.kseco.scheduler.EcoScheduler.TaskHandle[1];
            Runnable refresh = new Runnable() {
                private int cursor;

                @Override
                public void run() {
                    try {
                        int end = Math.min(cursor + 24, players.size());
                        while (cursor < end) {
                            org.bukkit.OfflinePlayer player = players.get(cursor++);
                            if (EconomyStatsFilter.shouldExcludePlayer(plugin, player)) continue;
                            double balance = plugin.vaultHook().getBalance(player);
                            if (balance <= 0) continue;
                            Map<String, Object> entry = new LinkedHashMap<>();
                            entry.put("uuid", player.getUniqueId().toString());
                            entry.put("name", player.getName() == null ? "?" : player.getName());
                            entry.put("balance", balance);
                            entry.put("online", player.isOnline());
                            entries.add(entry);
                        }
                        if (cursor < players.size()) return;
                        entries.sort((a, b) -> Double.compare((Double) b.get("balance"), (Double) a.get("balance")));
                        playerRankingSnapshot = List.copyOf(entries.subList(0, Math.min(50, entries.size())));
                        playerRankingSnapshotAt = System.currentTimeMillis();
                        playerRankingRefreshRunning.set(false);
                        handle[0].cancel();
                    } catch (RuntimeException e) {
                        playerRankingRefreshRunning.set(false);
                        plugin.getLogger().warning("Player ranking snapshot failed: " + e.getMessage());
                        handle[0].cancel();
                    }
                }
            };
            handle[0] = plugin.scheduler().runGlobalTimer(refresh, 1L, 1L);
        });
    }

    /** Marks the derived ranking snapshot stale after a local or remote wallet mutation. */
    public void invalidatePlayerRankingSnapshot() {
        playerRankingSnapshotAt = 0L;
    }

    private void createPendingEnterpriseCreation(HttpExchange exchange, KsAuthManager.Session session, String name,
                                                 String type, List<UUID> owners, double capital, String region) throws IOException {
        String pendingId = "PC-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase(Locale.ROOT);
        long now = System.currentTimeMillis() / 1000;
        long expiresAt = now + 24 * 60 * 60;
        try (var conn = plugin.ksCore().dataStore().getConnection()) {
            if (conn == null) { KsPluginBridge.sendJson(exchange, 500, "{\"error\":\"数据库未连接\"}"); return; }
            conn.setAutoCommit(false);
            try (var creation = conn.prepareStatement(
                    "INSERT INTO ks_ent_pending_creations (id,creator_uuid,name,type,owner_uuids,registered_capital,region,status,created_at,expires_at,kind) VALUES (?,?,?,?,?,?,?,'PENDING',?,?,'ENTERPRISE')");
                 var confirmations = conn.prepareStatement(
                    "INSERT INTO ks_ent_pending_creation_confirmations (pending_id,player_uuid,status,responded_at) VALUES (?,?,?,?)")) {
                creation.setString(1, pendingId); creation.setString(2, session.playerUuid.toString());
                creation.setString(3, name); creation.setString(4, type);
                creation.setString(5, String.join(",", owners.stream().map(UUID::toString).toList()));
                creation.setDouble(6, capital); creation.setString(7, region); creation.setLong(8, now); creation.setLong(9, expiresAt);
                creation.executeUpdate();
                for (UUID owner : owners) {
                    confirmations.setString(1, pendingId); confirmations.setString(2, owner.toString());
                    boolean creator = owner.equals(session.playerUuid);
                    confirmations.setString(3, creator ? "APPROVED" : "PENDING");
                    confirmations.setLong(4, creator ? now : 0);
                    confirmations.addBatch();
                }
                confirmations.executeBatch();
                conn.commit();
            } catch (Exception e) {
                conn.rollback();
                KsPluginBridge.sendJson(exchange, 500, gson.toJson(Map.of("error", "创建合资确认失败"))); return;
            } finally {
                try { conn.setAutoCommit(true); } catch (java.sql.SQLException ignored) {}
            }
        } catch (java.sql.SQLException e) {
            KsPluginBridge.sendJson(exchange, 500, gson.toJson(Map.of("error", "创建合资确认失败"))); return;
        }
        auditLog("ENTERPRISE_CREATION_PENDING", session.playerUuid.toString(), session.playerName, "enterprise_creation", pendingId,
                "企业=" + name + " 所有者=" + owners.size() + " 注册资本=" + capital);
        KsPluginBridge.sendJson(exchange, 202, gson.toJson(Map.of("pendingId", pendingId, "status", "PENDING", "expiresAt", expiresAt,
                "message", "合资创建已发起，等待其余所有者确认。")));
    }

    private void createPendingBankCreation(HttpExchange exchange, KsAuthManager.Session session, String name,
                                           String type, List<UUID> owners, double capital) throws IOException {
        String pendingId = "BC-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase(Locale.ROOT);
        long now = System.currentTimeMillis() / 1000;
        long expiresAt = now + 24 * 60 * 60;
        try (var conn = plugin.ksCore().dataStore().getConnection()) {
            if (conn == null) { KsPluginBridge.sendJson(exchange, 500, "{\"error\":\"Database unavailable\"}"); return; }
            conn.setAutoCommit(false);
            try (var creation = conn.prepareStatement(
                    "INSERT INTO ks_ent_pending_creations (id,creator_uuid,name,type,owner_uuids,registered_capital,region,status,created_at,expires_at,kind) VALUES (?,?,?,?,?,?,?,'PENDING',?,?,'BANK')");
                 var confirmations = conn.prepareStatement(
                    "INSERT INTO ks_ent_pending_creation_confirmations (pending_id,player_uuid,status,responded_at) VALUES (?,?,?,?)")) {
                creation.setString(1, pendingId); creation.setString(2, session.playerUuid.toString());
                creation.setString(3, name); creation.setString(4, type);
                creation.setString(5, String.join(",", owners.stream().map(UUID::toString).toList()));
                creation.setDouble(6, capital); creation.setString(7, ""); creation.setLong(8, now); creation.setLong(9, expiresAt);
                creation.executeUpdate();
                for (UUID owner : owners) {
                    confirmations.setString(1, pendingId); confirmations.setString(2, owner.toString());
                    boolean creator = owner.equals(session.playerUuid);
                    confirmations.setString(3, creator ? "APPROVED" : "PENDING");
                    confirmations.setLong(4, creator ? now : 0);
                    confirmations.addBatch();
                }
                confirmations.executeBatch();
                conn.commit();
            } catch (Exception e) {
                conn.rollback();
                KsPluginBridge.sendJson(exchange, 500, "{\"error\":\"Failed to create bank confirmation\"}"); return;
            } finally {
                try { conn.setAutoCommit(true); } catch (java.sql.SQLException ignored) {}
            }
        } catch (java.sql.SQLException e) {
            KsPluginBridge.sendJson(exchange, 500, "{\"error\":\"Failed to create bank confirmation\"}"); return;
        }
        auditLog("BANK_CREATION_PENDING", session.playerUuid.toString(), session.playerName, "bank_creation", pendingId,
                "bank=" + name + " owners=" + owners.size() + " capital=" + capital);
        KsPluginBridge.sendJson(exchange, 202, gson.toJson(Map.of("pendingId", pendingId, "status", "PENDING", "expiresAt", expiresAt,
                "kind", "BANK", "message", "Bank joint-venture confirmation has been created.")));
    }

    @SuppressWarnings("unchecked")
    private void handlePendingEnterpriseCreationRespond(HttpExchange exchange) throws IOException {
        KsAuthManager.Session session = requireAuth(exchange);
        if (session == null) return;
        Map<String, Object> req = gson.fromJson(KsPluginBridge.readBody(exchange), Map.class);
        String pendingId = req == null ? null : String.valueOf(req.get("pendingId"));
        String action = req == null ? "" : String.valueOf(req.get("action")).toUpperCase(Locale.ROOT);
        if (pendingId == null || pendingId.isBlank() || !(action.equals("ACCEPT") || action.equals("DECLINE"))) {
            KsPluginBridge.sendJson(exchange, 400, "{\"error\":\"缺少 pendingId 或 action 无效\"}"); return;
        }
        boolean finalize = false;
        long now = System.currentTimeMillis() / 1000;
        try (var conn = plugin.ksCore().dataStore().getConnection()) {
            if (conn == null) { KsPluginBridge.sendJson(exchange, 500, "{\"error\":\"数据库未连接\"}"); return; }
            conn.setAutoCommit(false);
            try {
                try (var expire = conn.prepareStatement("UPDATE ks_ent_pending_creations SET status='EXPIRED' WHERE status='PENDING' AND expires_at<?")) {
                    expire.setLong(1, now); expire.executeUpdate();
                }
                try (var check = conn.prepareStatement(
                        "SELECT 1 FROM ks_ent_pending_creation_confirmations c JOIN ks_ent_pending_creations p ON p.id=c.pending_id WHERE c.pending_id=? AND c.player_uuid=? AND c.status='PENDING' AND p.status='PENDING'")) {
                    check.setString(1, pendingId); check.setString(2, session.playerUuid.toString());
                    if (!check.executeQuery().next()) throw new IllegalArgumentException("确认请求不存在、已处理或已过期");
                }
                try (var update = conn.prepareStatement("UPDATE ks_ent_pending_creation_confirmations SET status=?, responded_at=? WHERE pending_id=? AND player_uuid=?")) {
                    update.setString(1, action.equals("ACCEPT") ? "APPROVED" : "DECLINED"); update.setLong(2, now);
                    update.setString(3, pendingId); update.setString(4, session.playerUuid.toString()); update.executeUpdate();
                }
                if (action.equals("DECLINE")) {
                    try (var cancel = conn.prepareStatement("UPDATE ks_ent_pending_creations SET status='CANCELLED' WHERE id=? AND status='PENDING'")) {
                        cancel.setString(1, pendingId); cancel.executeUpdate();
                    }
                } else {
                    try (var pending = conn.prepareStatement("SELECT COUNT(*) FROM ks_ent_pending_creation_confirmations WHERE pending_id=? AND status='PENDING'")) {
                        pending.setString(1, pendingId);
                        var pendingRows = pending.executeQuery();
                        if (pendingRows.next() && pendingRows.getInt(1) == 0) {
                            try (var begin = conn.prepareStatement("UPDATE ks_ent_pending_creations SET status='FINALIZING' WHERE id=? AND status='PENDING'")) {
                                begin.setString(1, pendingId); finalize = begin.executeUpdate() == 1;
                            }
                        }
                    }
                }
                conn.commit();
            } catch (IllegalArgumentException e) {
                conn.rollback();
                KsPluginBridge.sendJson(exchange, 400, gson.toJson(Map.of("error", e.getMessage()))); return;
            } catch (Exception e) {
                conn.rollback();
                KsPluginBridge.sendJson(exchange, 500, gson.toJson(Map.of("error", "处理确认失败"))); return;
            } finally {
                try { conn.setAutoCommit(true); } catch (java.sql.SQLException ignored) {}
            }
        } catch (java.sql.SQLException e) {
            KsPluginBridge.sendJson(exchange, 500, gson.toJson(Map.of("error", "处理确认失败"))); return;
        }
        if (!finalize) {
            KsPluginBridge.sendJson(exchange, 200, gson.toJson(Map.of("message", action.equals("ACCEPT") ? "已确认，等待其他所有者。" : "已拒绝，合资创建已取消。")));
            return;
        }
        try {
            Map<String, Object> result = plugin.scheduler().callGlobal(
                    () -> finalizePendingEnterpriseCreation(pendingId), Duration.ofSeconds(10));
            KsPluginBridge.sendJson(exchange, Boolean.TRUE.equals(result.get("success")) ? 200 : 400, gson.toJson(result));
        } catch (Exception e) {
            KsPluginBridge.sendJson(exchange, 500, gson.toJson(Map.of("error", "合资最终成立失败")));
        }
    }

    private Map<String, Object> finalizePendingEnterpriseCreation(String pendingId) {
        List<UUID> owners;
        String name, type, region, kind;
        double capital;
        try (var conn = plugin.ksCore().dataStore().getConnection();
             var ps = conn == null ? null : conn.prepareStatement("SELECT * FROM ks_ent_pending_creations WHERE id=? AND status='FINALIZING'")) {
            if (ps == null) return Map.of("success", false, "error", "数据库未连接");
            ps.setString(1, pendingId); var rs = ps.executeQuery();
            if (!rs.next()) return Map.of("success", false, "error", "待成立企业状态已变化");
            owners = parseOwnerList(rs.getString("owner_uuids")); name = rs.getString("name"); type = rs.getString("type");
            region = rs.getString("region"); capital = rs.getDouble("registered_capital"); kind = rs.getString("kind");
        } catch (Exception e) { return Map.of("success", false, "error", "读取待成立企业失败"); }
        if (owners.size() < 2 || capital <= 0) return Map.of("success", false, "error", "待成立企业数据无效");
        if ("BANK".equalsIgnoreCase(kind) && capital < getBankSetting("bank_min_capital", 50000)) {
            markPendingCreationCancelled(pendingId);
            return Map.of("success", false, "error", "Bank capital is below the current minimum.");
        }
        double perOwner = capital / owners.size();
        List<org.bukkit.OfflinePlayer> charged = new ArrayList<>();
        for (UUID owner : owners) {
            var player = Bukkit.getOfflinePlayer(owner);
            if (!plugin.vaultHook().has(player, perOwner)) {
                markPendingCreationCancelled(pendingId);
                return Map.of("success", false, "error", "有所有者余额不足，合资创建已取消");
            }
            if (!plugin.vaultHook().withdraw(player, perOwner)) {
                for (var refund : charged) plugin.vaultHook().deposit(refund, perOwner);
                markPendingCreationCancelled(pendingId);
                return Map.of("success", false, "error", "扣除注册资本失败，合资创建已取消");
            }
            charged.add(player);
        }
        String createdId = UUID.randomUUID().toString().substring(0, 8);
        String enterpriseId = createdId;
        long now = System.currentTimeMillis() / 1000;
        try (var conn = plugin.ksCore().dataStore().getConnection()) {
            if (conn == null) throw new java.sql.SQLException("no connection");
            conn.setAutoCommit(false);
            try {
                if ("BANK".equalsIgnoreCase(kind)) {
                    try (var insert = conn.prepareStatement("INSERT INTO ks_bank_banks (id,name,type,owner_uuids,total_assets,created_at) VALUES (?,?,?,?,?,?)")) {
                        insert.setString(1, createdId); insert.setString(2, name); insert.setString(3, type);
                        insert.setString(4, String.join(",", owners.stream().map(UUID::toString).toList()));
                        insert.setDouble(5, capital); insert.setLong(6, now); insert.executeUpdate();
                    }
                    ensureBankOwnerMembers(conn, createdId);
                } else {
                    try (var insert = conn.prepareStatement("INSERT INTO ks_ent_enterprises (id,name,type,owner_uuids,registered_capital,current_assets,region,created_at) VALUES (?,?,?,?,?,?,?,?)")) {
                        insert.setString(1, createdId); insert.setString(2, name); insert.setString(3, type);
                        insert.setString(4, String.join(",", owners.stream().map(UUID::toString).toList())); insert.setDouble(5, capital);
                        insert.setDouble(6, capital); insert.setString(7, region); insert.setLong(8, now); insert.executeUpdate();
                    }
                    ensureEnterpriseOwnerMembers(conn, createdId, owners, now);
                    if (!ensureCorporateAccount(conn, createdId, CORP_BANK_ID, capital, now)) {
                        throw new java.sql.SQLException("Corporate account already exists for a new enterprise");
                    }
                    conn.createStatement().executeUpdate("UPDATE ks_bank_banks SET total_assets=total_assets+" + capital + " WHERE id='" + CORP_BANK_ID + "'");
                }
                try (var done = conn.prepareStatement("UPDATE ks_ent_pending_creations SET status='FINALIZED', finalized_enterprise_id=? WHERE id=? AND status='FINALIZING'")) {
                    done.setString(1, createdId); done.setString(2, pendingId);
                    if (done.executeUpdate() != 1) throw new java.sql.SQLException("pending status changed");
                }
                conn.commit();
            } catch (Exception e) { conn.rollback(); throw e; }
            finally { try { conn.setAutoCommit(true); } catch (java.sql.SQLException ignored) {} }
        } catch (Exception e) {
            for (var refund : charged) plugin.vaultHook().deposit(refund, perOwner);
            markPendingCreationCancelled(pendingId);
            return Map.of("success", false, "error", "写入企业失败，注册资本已退还");
        }
        auditLog("ENTERPRISE_REGISTER", "SYSTEM", "SYSTEM", "enterprise", enterpriseId, "合资确认完成: " + pendingId);
        return Map.of("success", true, "id", enterpriseId, "message", "合资企业已正式成立");
    }

    private void markPendingCreationCancelled(String pendingId) {
        try (var conn = plugin.ksCore().dataStore().getConnection();
             var ps = conn == null ? null : conn.prepareStatement("UPDATE ks_ent_pending_creations SET status='CANCELLED' WHERE id=? AND status='FINALIZING'")) {
            if (ps != null) { ps.setString(1, pendingId); ps.executeUpdate(); }
        } catch (Exception ignored) {}
    }

    /** 确保所有业务表存在（在首次 API 调用时执行，避免 extra 模块未初始化导致表缺失） */
    private void ensureAllTables() {
        if (tablesEnsured) return;
        synchronized (this) {
            if (tablesEnsured) return;
            try (var conn = plugin.ksCore().dataStore().getConnection()) {
                if (conn == null) return;
                EcoWebBusinessSchema.initialize(conn);
                var stmt = conn.createStatement();
                // 自动创建官方企业商业银行（官方创立的商业银行，所有企业公户托管于此）
                long now = System.currentTimeMillis() / 1000;
                var rs = stmt.executeQuery("SELECT COUNT(*) FROM ks_bank_banks WHERE id='CORP-BANK'");
                if (rs.next() && rs.getInt(1) == 0) {
                    stmt.executeUpdate("INSERT INTO ks_bank_banks (id, name, type, owner_uuids, total_assets, created_at) VALUES ('CORP-BANK', '企业商业银行', 'COMMERCIAL', 'SYSTEM', 1000000000, " + now + ")");
                    plugin.getLogger().info("[ks-Eco] 自动创建企业商业银行 (CORP-BANK)");
                } else {
                    // 修复旧数据：将类型从 CENTRAL 改为 COMMERCIAL
                    stmt.executeUpdate("UPDATE ks_bank_banks SET type='COMMERCIAL' WHERE id='CORP-BANK' AND type='CENTRAL'");
                }
                // 确保银行成员表有 SYSTEM（官方）作为企业商业银行的所有者
                long initializedAt = System.currentTimeMillis() / 1000;
                PortableSqlMutation.insertIfAbsent(conn,
                        "SELECT 1 FROM ks_eco_settings WHERE config_key=?",
                        ps -> ps.setString(1, "enterprise_min_capital"),
                        "INSERT INTO ks_eco_settings (config_key,config_value,updated_at) VALUES (?,?,?)",
                        ps -> { ps.setString(1, "enterprise_min_capital"); ps.setString(2, "50000"); ps.setLong(3, initializedAt); });
                upsertBankMember(conn, CORP_BANK_ID, "SYSTEM", "官方", "OWNER", initializedAt, false);
                tablesEnsured = true;
                plugin.getLogger().info("[ks-Eco] 所有业务表已就绪");
            } catch (java.sql.SQLException e) {
                plugin.getLogger().warning("[ks-Eco] 初始化业务表失败: " + e.getMessage());
            }
        }
    }

    @Override
    public void handle(HttpExchange exchange) throws IOException {
        try {
            ensureAllTables(); // 确保所有表在首次 API 调用时创建
            String method = exchange.getRequestMethod();
            String path = exchange.getRequestURI().getPath();
            String query = exchange.getRequestURI().getRawQuery();

            // The bundled UI is same-origin. Do not expose authenticated APIs to arbitrary origins.
            exchange.getResponseHeaders().set("X-Content-Type-Options", "nosniff");
            exchange.getResponseHeaders().set("Referrer-Policy", "no-referrer");
            exchange.getResponseHeaders().set("Cache-Control", "no-store, private");

            if ("OPTIONS".equalsIgnoreCase(method)) {
                exchange.sendResponseHeaders(204, -1);
                return;
            }

            // 路由分发（路径相对于 /ks-Eco）
            String subPath = path;
            if (subPath.startsWith("/ks-Eco")) {
                subPath = subPath.substring(7); // 去掉 "/ks-Eco"
            }
            if (subPath.isEmpty() || subPath.equals("/")) subPath = "/";

            if (subPath.startsWith("/assets/")) {
                serveWebAsset(exchange, subPath);
                return;
            }

            // 玩家功能门控：被关闭的功能，非管理员一律拒绝访问对应 API
            String gateKey = resolveFeatureGateKey(subPath);
            if (gateKey != null && !plugin.featureGate().isOpen(gateKey)) {
                String gateToken = getTokenFromExchange(exchange);
                KsAuthManager.Session gateSession = gateToken != null ? plugin.bridge().validateToken(gateToken) : null;
                if (gateSession == null || !gateSession.isAdmin) {
                    KsPluginBridge.sendJson(exchange, 403, "{\"error\":\"该功能暂未开放\"}");
                    return;
                }
            }
            // The web panel is invitation-only: every API route needs a session.
            // Individual handlers additionally enforce admin and organisation scopes.
            if (subPath.startsWith("/api/") && !subPath.equals("/api/test-token")) {
                KsAuthManager.Session apiSession = requireAuth(exchange);
                if (apiSession == null) return;
            }
            if ("POST".equalsIgnoreCase(method) && POLITIC_GOVERNED_POST_PATHS.contains(subPath)) {
                String politicError = checkPoliticGate();
                if (politicError != null) {
                    KsPluginBridge.sendJson(exchange, 403, gson.toJson(Map.of("error", politicError)));
                    return;
                }
            }

            if (subPath.equals("/") || subPath.equals("/admin")) {
                serveAdminPage(exchange);
            } else if (subPath.equals("/player") || subPath.equals("/player/")) {
                servePlayerPage(exchange);
            } else if (subPath.equals("/api/market/stats")) {
                handleMarketStats(exchange);
            } else if (subPath.equals("/api/macro-data")) {
                handleMacroData(exchange);
            } else if (subPath.equals("/api/listings")) {
                handleListings(exchange, query);
            } else if (subPath.equals("/api/admin/force-price") && "POST".equalsIgnoreCase(method)) {
                handleForcePrice(exchange);
            } else if (subPath.equals("/api/admin/prices")) {
                handleAdminPrices(exchange, query);
            } else if (subPath.equals("/api/admin/simulate-trade") && "POST".equalsIgnoreCase(method)) {
                handleSimulateTrade(exchange);
            } else if (subPath.equals("/api/admin/refresh-prices") && "POST".equalsIgnoreCase(method)) {
                handleRefreshPrices(exchange);
            } else if (subPath.equals("/api/admin/idle-items")) {
                handleIdleItems(exchange, query);
            } else if (subPath.equals("/api/admin/settlements/review") && "GET".equalsIgnoreCase(method)) {
                handleSettlementReviewList(exchange);
            } else if (subPath.equals("/api/admin/settlements/resolve") && "POST".equalsIgnoreCase(method)) {
                handleSettlementReviewResolve(exchange);
            } else if (subPath.equals("/api/players/search")) {
                handlePlayerSearch(exchange, query);
            } else if (subPath.equals("/api/test-token") && "POST".equalsIgnoreCase(method)) {
                handleTestToken(exchange);
            } else if (subPath.equals("/api/login")) {
                handleLogin(exchange, query);
            } else if (subPath.equals("/api/eco/bootstrap")) {
                handleEcoBootstrap(exchange);
            } else if (subPath.equals("/api/eco/public-info")) {
                handlePublicInfo(exchange);
            } else if (subPath.equals("/api/eco/trade-history")) {
                handleTradeHistory(exchange, query);
            } else if (subPath.equals("/api/mo/list")) {
                handleMoList(exchange, false);
            } else if (subPath.equals("/api/admin/mo/list")) {
                handleMoList(exchange, true);
            } else if (subPath.equals("/api/admin/mo/save") && "POST".equalsIgnoreCase(method)) {
                handleMoSave(exchange);
            } else if (subPath.equals("/api/admin/mo/status") && "POST".equalsIgnoreCase(method)) {
                handleMoStatus(exchange);
            } else if (subPath.equals("/api/admin/eco/features")) {
                handleAdminFeaturesGet(exchange);
            } else if (subPath.equals("/api/admin/eco/features/set") && "POST".equalsIgnoreCase(method)) {
                handleAdminFeaturesSet(exchange);
            } else if (subPath.equals("/api/admin/transport/config")) {
                handleAdminTransportConfig(exchange);
            } else if (subPath.equals("/api/admin/transfer/config")) {
                handleAdminTransferConfig(exchange);
            } else if (subPath.equals("/api/admin/listings")) {
                handleAdminListings(exchange);
            } else if (subPath.equals("/api/admin/listings/force-cancel") && "POST".equalsIgnoreCase(method)) {
                handleAdminForceCancel(exchange);
            } else if (subPath.equals("/api/admin/listings/force-destroy") && "POST".equalsIgnoreCase(method)) {
                handleAdminForceDestroy(exchange);
            } else if (subPath.equals("/api/admin/bans")) {
                handleAdminListBans(exchange);
            } else if (subPath.equals("/api/admin/bans/add") && "POST".equalsIgnoreCase(method)) {
                handleAdminAddBan(exchange);
            } else if (subPath.equals("/api/admin/bans/remove") && "POST".equalsIgnoreCase(method)) {
                handleAdminRemoveBan(exchange);
            } else if (subPath.equals("/api/player/bans")) {
                handlePlayerBans(exchange);
            } else if (subPath.equals("/api/admin/price-volatility")) {
                handleAdminPriceVolatility(exchange);
            } else if (subPath.equals("/api/admin/price-volatility/settings") && "POST".equalsIgnoreCase(method)) {
                handleAdminPriceVolatilitySettings(exchange);
            } else if (subPath.equals("/api/admin/price-volatility/bias") && "POST".equalsIgnoreCase(method)) {
                handleAdminPriceVolatilityBias(exchange);
            } else if (subPath.equals("/bank") || subPath.equals("/bank/")) {
                serveBankPage(exchange);
            } else if (subPath.equals("/enterprise") || subPath.equals("/enterprise/")) {
                serveEnterprisePage(exchange);
            } else if (subPath.equals("/tax") || subPath.equals("/tax/")) {
                serveTaxPage(exchange);
            } else if (subPath.equals("/api/bank/stats")) {
                handleBankStats(exchange);
            } else if (subPath.equals("/api/enterprise/stats")) {
                handleEnterpriseStats(exchange);
            } else if (subPath.equals("/api/tax/stats")) {
                handleTaxStats(exchange);
            // ===== Player-scoped APIs =====
            } else if (subPath.equals("/api/my-banks")) {
                handleMyBanks(exchange);
            } else if (subPath.equals("/api/my-loans")) {
                handleMyLoans(exchange);
            } else if (subPath.equals("/api/my-enterprises")) {
                handleMyEnterprises(exchange);
            } else if (subPath.equals("/api/my-tax-records")) {
                handleMyTaxRecords(exchange);
            } else if (subPath.equals("/api/bank/deposit") && "POST".equalsIgnoreCase(method)) {
                handleBankDeposit(exchange);
            } else if (subPath.equals("/api/bank/withdraw") && "POST".equalsIgnoreCase(method)) {
                handleBankWithdraw(exchange);
            // =====
            } else if (subPath.equals("/api/extra-modules")) {
                handleExtraModulesList(exchange);
            } else if (subPath.equals("/api/federated/snapshot-sources") && "GET".equalsIgnoreCase(method)) {
                handleFederatedSnapshotSources(exchange, query);
            } else if (subPath.equals("/api/federated/snapshot") && "GET".equalsIgnoreCase(method)) {
                handleFederatedSnapshotRead(exchange, query);
            } else if (subPath.equals("/api/federated/assets") && "GET".equalsIgnoreCase(method)) {
                handleFederatedAssets(exchange, query, false);
            } else if (subPath.equals("/api/federated/assets/aggregate") && "GET".equalsIgnoreCase(method)) {
                handleFederatedAssets(exchange, query, true);
            } else if (subPath.equals("/api/admin/federated-assets/settings")) {
                handleFederatedSettings(exchange);
            // ===== Real Estate APIs =====
            } else if (subPath.equals("/api/realestate/zones")) {
                handleReZonesList(exchange, query);
            } else if (subPath.equals("/api/admin/realestate/zone") && "POST".equalsIgnoreCase(method)) {
                handleReZoneCreate(exchange);
            } else if (subPath.equals("/api/admin/realestate/house") && "POST".equalsIgnoreCase(method)) {
                handleReHouseRegister(exchange);
            } else if (subPath.equals("/api/admin/realestate/zone/price") && "POST".equalsIgnoreCase(method)) {
                handleReZoneSetPrice(exchange);
            } else if (subPath.equals("/api/admin/realestate/zone/status") && "POST".equalsIgnoreCase(method)) {
                handleReZoneSetStatus(exchange);
            } else if (subPath.equals("/api/admin/realestate/zone/type") && "POST".equalsIgnoreCase(method)) {
                handleReZoneSetType(exchange);
            } else if (subPath.equals("/api/admin/realestate/zone/max-plots") && "POST".equalsIgnoreCase(method)) {
                handleReZoneSetMaxPlots(exchange);
            } else if (subPath.equals("/api/admin/realestate/zone/dungeon-link") && "POST".equalsIgnoreCase(method)) {
                handleReZoneSetDungeonLink(exchange);
            } else if (subPath.equals("/api/admin/realestate/zone/delete") && "POST".equalsIgnoreCase(method)) {
                handleReZoneDelete(exchange);
            } else if (subPath.equals("/api/realestate/plots")) {
                handleRePlotsList(exchange, query);
            } else if (subPath.equals("/api/realestate/plot/purchase") && "POST".equalsIgnoreCase(method)) {
                handleRePlotPurchase(exchange);
            } else if (subPath.equals("/api/realestate/my-plots")) {
                handleReMyPlots(exchange);
            } else if (subPath.equals("/api/realestate/houses-for-sale")) {
                handleReHousesForSale(exchange);
            } else if (subPath.equals("/api/realestate/city/manifest")) {
                handleReCityManifest(exchange, query);
            } else if (subPath.equals("/api/realestate/house/voxels")) {
                handleReHouseVoxels(exchange, query);
            } else if (subPath.equals("/api/realestate/region/voxels")) {
                handleReRegionVoxels(exchange, query);
            // ===== 副本内房产（原 ks-Eco-RealEstateDungeon 迁入，现归房地产模块）=====
            } else if (subPath.equals("/api/realestate/instance/plot/purchase") && "POST".equalsIgnoreCase(method)) {
                handleReInstancePlotPurchase(exchange);
            } else if (subPath.equals("/api/realestate/instance/plot/develop") && "POST".equalsIgnoreCase(method)) {
                handleReInstancePlotDevelop(exchange);
            } else if (subPath.equals("/api/realestate/my-instance-plots")) {
                handleReMyInstancePlots(exchange, query);
            // ===== 地块福利（工业/农业用地额外福利，买地不强制） =====
            } else if (subPath.equals("/api/admin/land-perks")) {
                handleAdminLandPerks(exchange);
            } else if (subPath.equals("/api/admin/land-perks/set") && "POST".equalsIgnoreCase(method)) {
                handleAdminLandPerksSet(exchange);
            } else if (subPath.equals("/api/admin/realestate/plot/perk") && "POST".equalsIgnoreCase(method)) {
                handleRePlotPerkSet(exchange);
            } else if (subPath.equals("/api/realestate/my-land-perks")) {
                handleMyLandPerks(exchange);
            // ===== Wealth Rankings =====
            } else if (subPath.equals("/api/admin/set-balance") && "POST".equalsIgnoreCase(method)) {
                handleAdminSetBalance(exchange);
            } else if (subPath.equals("/api/rankings/players")) {
                handlePlayerRankings(exchange);
            } else if (subPath.equals("/api/rankings/enterprises")) {
                handleEnterpriseRankings(exchange);
            } else if (subPath.equals("/api/rankings/banks")) {
                handleBankRankings(exchange);
            } else if (subPath.equals("/api/admin/economic-settings")) {
                if ("POST".equalsIgnoreCase(method)) handleEconomicSettingsSet(exchange); else handleEconomicSettingsGet(exchange);
            } else if (subPath.equals("/api/admin/enterprise/edit") && "POST".equalsIgnoreCase(method)) {
                handleEnterpriseAdminEdit(exchange);
            // ===== Bank CRUD APIs =====
            } else if (subPath.equals("/api/bank/list")) {
                handleBankList(exchange);
            } else if (subPath.equals("/api/bank/gameplay/dashboard")) {
                handleBankGameplayDashboard(exchange);
            } else if (subPath.equals("/api/bank/deposit-products")) {
                handleBankDepositProducts(exchange, query);
            } else if (subPath.equals("/api/bank/term/open") && "POST".equalsIgnoreCase(method)) {
                handleBankTermOpen(exchange);
            } else if (subPath.equals("/api/bank/term/redeem") && "POST".equalsIgnoreCase(method)) {
                handleBankTermRedeem(exchange);
            } else if (subPath.equals("/api/bank/operations")) {
                handleBankOperations(exchange, query);
            } else if (subPath.equals("/api/bank/dividends")) {
                handleBankDividends(exchange, query);
            } else if (subPath.equals("/api/bank/dividend/declare") && "POST".equalsIgnoreCase(method)) {
                handleBankDividendDeclare(exchange);
            } else if (subPath.equals("/api/bank/loan/products")) {
                handleBankLoanProducts(exchange);
            } else if (subPath.equals("/api/bank/loan/collateral")) {
                handleBankLoanCollateral(exchange, query);
            } else if (subPath.equals("/api/bank/loan/quote") && "POST".equalsIgnoreCase(method)) {
                handleBankLoanQuote(exchange);
            } else if (subPath.equals("/api/bank/loan/apply-quoted") && "POST".equalsIgnoreCase(method)) {
                handleBankLoanApplyQuoted(exchange);
            } else if (subPath.equals("/api/bank/loan/restructure") && "POST".equalsIgnoreCase(method)) {
                handleBankLoanRestructureRequest(exchange);
            } else if (subPath.equals("/api/bank/loan/restructure/requests")) {
                handleBankLoanRestructureRequests(exchange, query);
            } else if (subPath.equals("/api/bank/loan/restructure/decide") && "POST".equalsIgnoreCase(method)) {
                handleBankLoanRestructureDecide(exchange);
            } else if (subPath.equals("/api/bank/policy-events")) {
                handleBankPolicyEvents(exchange);
            } else if (subPath.equals("/api/admin/bank/policy-events") && "POST".equalsIgnoreCase(method)) {
                handleBankPolicyEventCreate(exchange);
            } else if (subPath.equals("/api/admin/bank/operating-status") && "POST".equalsIgnoreCase(method)) {
                handleBankOperatingStatusSet(exchange);
            } else if (subPath.equals("/api/bank/equity/portfolio")) {
                handleBankEquityPortfolio(exchange);
            } else if (subPath.equals("/api/bank/equity/cap-table")) {
                handleBankEquityCapTable(exchange, query);
            } else if (subPath.equals("/api/bank/equity/offerings")) {
                if ("POST".equalsIgnoreCase(method)) handleBankEquityOfferingCreate(exchange);
                else handleBankEquityOfferings(exchange, query);
            } else if (subPath.equals("/api/bank/equity/accept") && "POST".equalsIgnoreCase(method)) {
                handleBankEquityOfferingAccept(exchange);
            } else if (subPath.equals("/api/bank/equity/cancel") && "POST".equalsIgnoreCase(method)) {
                handleBankEquityOfferingCancel(exchange);
            } else if (subPath.equals("/api/admin/bank/resolution/fund")) {
                handleBankResolutionFund(exchange);
            } else if (subPath.equals("/api/admin/bank/resolution/cases")) {
                handleBankResolutionCases(exchange);
            } else if (subPath.equals("/api/admin/bank/resolution/fund/top-up") && "POST".equalsIgnoreCase(method)) {
                handleBankResolutionFundTopUp(exchange);
            } else if (subPath.equals("/api/admin/bank/resolution/preview") && "POST".equalsIgnoreCase(method)) {
                handleBankResolutionPreview(exchange);
            } else if (subPath.equals("/api/admin/bank/resolution/execute") && "POST".equalsIgnoreCase(method)) {
                handleBankResolutionExecute(exchange);
            } else if (subPath.equals("/api/admin/bank/loan-payout/review")) {
                handleBankLoanPayoutReviews(exchange);
            } else if (subPath.equals("/api/admin/bank/loan-payout/resolve") && "POST".equalsIgnoreCase(method)) {
                handleBankLoanPayoutReviewResolve(exchange);
            } else if (subPath.equals("/api/bank/cb/rates")) {
                handleCbRatesGet(exchange);
            } else if (subPath.equals("/api/bank/guidance")) {
                handleGuidanceStatus(exchange);
            } else if (subPath.equals("/api/bank/guidance/claim") && "POST".equalsIgnoreCase(method)) {
                handleGuidanceClaim(exchange);
            } else if (subPath.equals("/api/admin/bank/guidance/config")) {
                if ("POST".equalsIgnoreCase(method)) handleGuidanceConfigSet(exchange); else handleGuidanceConfigGet(exchange);
            } else if (subPath.equals("/api/bank/cb/set-rates") && "POST".equalsIgnoreCase(method)) {
                handleCbRatesSet(exchange);
            } else if (subPath.equals("/api/bank/cb/inject") && "POST".equalsIgnoreCase(method)) {
                handleCbInject(exchange);
            } else if (subPath.equals("/api/bank/loans")) {
                handleLoanList(exchange, query);
            } else if (subPath.equals("/api/bank/cb/set-rate-range") && "POST".equalsIgnoreCase(method)) {
                handleCbSetRateRange(exchange);
            } else if (subPath.equals("/api/bank/cb/loans")) {
                handleCbLoansList(exchange, query);
            } else if (subPath.equals("/api/bank/cb/loan/repay") && "POST".equalsIgnoreCase(method)) {
                handleCbLoanRepay(exchange);
            } else if (subPath.equals("/api/bank/create") && "POST".equalsIgnoreCase(method)) {
                handleBankCreate(exchange);
            } else if (subPath.equals("/api/bank/creation/pending/respond") && "POST".equalsIgnoreCase(method)) {
                handlePendingEnterpriseCreationRespond(exchange);
            } else if (subPath.equals("/api/bank/loan/issue") && "POST".equalsIgnoreCase(method)) {
                handleLoanIssue(exchange);
            } else if (subPath.equals("/api/bank/loan/repay") && "POST".equalsIgnoreCase(method)) {
                handleLoanRepay(exchange);
            } else if (subPath.equals("/api/bank/loan/apply") && "POST".equalsIgnoreCase(method)) {
                handleLoanApply(exchange);
            } else if (subPath.equals("/api/bank/loan/my-requests")) {
                handleMyLoanRequests(exchange);
            } else if (subPath.equals("/api/bank/loan/cancel") && "POST".equalsIgnoreCase(method)) {
                handleMyLoanRequestCancel(exchange);
            } else if (subPath.equals("/api/bank/loan/requests")) {
                handleLoanRequestsList(exchange, query);
            } else if (subPath.equals("/api/bank/loan/approve") && "POST".equalsIgnoreCase(method)) {
                handleLoanRequestDecide(exchange, true);
            } else if (subPath.equals("/api/bank/loan/reject") && "POST".equalsIgnoreCase(method)) {
                handleLoanRequestDecide(exchange, false);
            } else if (subPath.equals("/api/enterprise/finance/request") && "POST".equalsIgnoreCase(method)) {
                handleEnterpriseFinanceRequest(exchange);
            } else if (subPath.equals("/api/enterprise/finance/loans")) {
                handleEnterpriseFinanceLoans(exchange, query);
            } else if (subPath.equals("/api/enterprise/finance/inventory")) {
                handleEnterpriseFinanceInventory(exchange, query);
            } else if (subPath.equals("/api/enterprise/salary/pay") && "POST".equalsIgnoreCase(method)) {
                handleEnterpriseSalaryPay(exchange);
            } else if (subPath.equals("/api/enterprise/finance/repay") && "POST".equalsIgnoreCase(method)) {
                handleEnterpriseFinanceRepay(exchange);
            } else if (subPath.equals("/api/enterprise/corporate-bank") && "POST".equalsIgnoreCase(method)) {
                handleCorporateBankSelect(exchange);
            } else if (subPath.equals("/api/bank/enterprise-loan/requests")) {
                handleEnterpriseFinanceRequests(exchange, query);
            } else if (subPath.equals("/api/bank/enterprise-loan/decide") && "POST".equalsIgnoreCase(method)) {
                handleEnterpriseFinanceDecision(exchange);
            } else if (subPath.equals("/api/bank/collateral-auctions")) {
                handleCollateralAuctions(exchange);
            } else if (subPath.equals("/api/bank/collateral-auctions/bid") && "POST".equalsIgnoreCase(method)) {
                handleCollateralAuctionBid(exchange);
            } else if (subPath.equals("/api/admin/enterprise/finance/inventory") && "POST".equalsIgnoreCase(method)) {
                handleEnterpriseInventoryRegister(exchange);
            // ===== Blind Box APIs =====
            } else if (subPath.equals("/api/blindbox/pools")) {
                handleBbPoolsList(exchange);
            } else if (subPath.equals("/api/blindbox/loot")) {
                handleBbLootList(exchange, query);
            } else if (subPath.equals("/api/blindbox/pull") && "POST".equalsIgnoreCase(method)) {
                handleBbPull(exchange);
            } else if (subPath.equals("/api/blindbox/pity")) {
                handleBbPity(exchange);
            } else if (subPath.equals("/api/blindbox/my-pulls")) {
                handleBbMyPulls(exchange);
            } else if (subPath.equals("/api/admin/blindbox/pool") && "POST".equalsIgnoreCase(method)) {
                handleAdminBbPool(exchange);
            } else if (subPath.equals("/api/admin/blindbox/pool/delete") && "POST".equalsIgnoreCase(method)) {
                handleAdminBbPoolDelete(exchange);
            } else if (subPath.equals("/api/admin/blindbox/loot") && "POST".equalsIgnoreCase(method)) {
                handleAdminBbLootAdd(exchange);
            } else if (subPath.equals("/api/admin/blindbox/loot/delete") && "POST".equalsIgnoreCase(method)) {
                handleAdminBbLootDelete(exchange);
            // ===== Enterprise BlindBox APIs =====
            } else if (subPath.equals("/api/enterprise/blindbox/pools")) {
                handleEntBbPoolsList(exchange, query);
            } else if (subPath.equals("/api/enterprise/blindbox/pull") && "POST".equalsIgnoreCase(method)) {
                handleEntBbPull(exchange);
            } else if (subPath.equals("/api/blindbox/pull-ten") && "POST".equalsIgnoreCase(method)) {
                handleBbPullTen(exchange);
            // ===== Enterprise CRUD APIs =====
            } else if (subPath.equals("/api/enterprise/list")) {
                handleEnterpriseList(exchange);
            } else if (subPath.equals("/api/enterprise/get")) {
                handleEnterpriseGet(exchange, query);
            } else if (subPath.equals("/api/enterprise/register") && "POST".equalsIgnoreCase(method)) {
                handleEnterpriseRegister(exchange);
            } else if (subPath.equals("/api/enterprise/creation/pending/respond") && "POST".equalsIgnoreCase(method)) {
                handlePendingEnterpriseCreationRespond(exchange);
            } else if (subPath.equals("/api/enterprise/dissolve") && "POST".equalsIgnoreCase(method)) {
                handleEnterpriseDissolve(exchange);
            } else if (subPath.equals("/api/enterprise/capital/inject") && "POST".equalsIgnoreCase(method)) {
                handleEnterpriseCapitalInject(exchange);
            } else if (subPath.equals("/api/enterprise/dividend/rate") && "POST".equalsIgnoreCase(method)) {
                handleEnterpriseDividendRate(exchange);
            } else if (subPath.equals("/api/enterprise/dividend/shares") && "POST".equalsIgnoreCase(method)) {
                handleEnterpriseDividendShares(exchange);
            } else if (subPath.equals("/api/enterprise/dividend/distribute") && "POST".equalsIgnoreCase(method)) {
                handleEnterpriseDividendDistribute(exchange);
            } else if (subPath.equals("/api/enterprise/dividend/custom") && "POST".equalsIgnoreCase(method)) {
                handleEnterpriseCustomDividend(exchange);
            } else if (subPath.equals("/api/enterprise/dividend/preview") && "POST".equalsIgnoreCase(method)) {
                handleEnterpriseDividendPreview(exchange);
            } else if (subPath.equals("/api/enterprise/dividend/payouts")) {
                handleEnterpriseDividendPayouts(exchange, query);
            } else if (subPath.equals("/api/enterprise/projects")) {
                handleProjectList(exchange);
            } else if (subPath.equals("/api/enterprise/project/publish") && "POST".equalsIgnoreCase(method)) {
                handleProjectPublish(exchange);
            } else if (subPath.equals("/api/enterprise/bid/submit") && "POST".equalsIgnoreCase(method)) {
                handleBidSubmit(exchange);
            } else if (subPath.equals("/api/enterprise/project/award") && "POST".equalsIgnoreCase(method)) {
                handleProjectAward(exchange);
            } else if (subPath.equals("/api/enterprise/bid/deposit/pay") && "POST".equalsIgnoreCase(method)) {
                handleBidDepositPay(exchange);
            } else if (subPath.equals("/api/enterprise/project/bids")) {
                handleProjectBids(exchange, query);
            } else if (subPath.equals("/api/enterprise/my-bids")) {
                handleMyBids(exchange);
            // ===== Dividend APIs =====
            } else if (subPath.equals("/api/enterprise/dividend/declare") && "POST".equalsIgnoreCase(method)) {
                handleDividendDeclare(exchange);
            } else if (subPath.equals("/api/enterprise/dividends")) {
                handleDividendList(exchange, query);
            // ===== Corporate Account APIs =====
            } else if (subPath.equals("/api/enterprise/corporate/balance")) {
                handleCorporateBalance(exchange, query);
            } else if (subPath.equals("/api/enterprise/corporate/transfer") && "POST".equalsIgnoreCase(method)) {
                handleCorporateTransfer(exchange);
            // ===== Procurement APIs =====
            } else if (subPath.equals("/api/enterprise/procurement/publish") && "POST".equalsIgnoreCase(method)) {
                handleProcurementPublish(exchange);
            } else if (subPath.equals("/api/enterprise/procurements")) {
                handleProcurementList(exchange);
            } else if (subPath.equals("/api/enterprise/procurement/bid") && "POST".equalsIgnoreCase(method)) {
                handleProcurementBid(exchange);
            } else if (subPath.equals("/api/enterprise/procurement/award") && "POST".equalsIgnoreCase(method)) {
                handleProcurementAward(exchange);
            } else if (subPath.equals("/api/enterprise/procurement/bids")) {
                handleProcurementBids(exchange, query);
            // ===== Joint Venture Invites =====
            } else if (subPath.equals("/api/enterprise/invite/send") && "POST".equalsIgnoreCase(method)) {
                handleInviteSend(exchange);
            } else if (subPath.equals("/api/enterprise/invites")) {
                handleInviteList(exchange, query);
            } else if (subPath.equals("/api/player/invites")) {
                handlePlayerInvites(exchange);
            } else if (subPath.equals("/api/player/invites/respond") && "POST".equalsIgnoreCase(method)) {
                handlePlayerInviteRespond(exchange);
            } else if (subPath.startsWith("/api/bank/access/")) {
                var provider = plugin.bankAccessProvider();
                if (provider == null) {
                    KsPluginBridge.sendJson(exchange, 503, "{\"error\":\"银行扩展未加载\"}");
                } else if (!provider.handleAccessRequest(exchange, subPath, query)) {
                    KsPluginBridge.sendJson(exchange, 404, "{\"error\":\"未知银行权限接口\"}");
                }
            } else if (subPath.startsWith("/api/enterprise/access/")) {
                var provider = plugin.enterpriseAccessProvider();
                if (provider == null) {
                    KsPluginBridge.sendJson(exchange, 503, "{\"error\":\"企业扩展未加载\"}");
                } else if (!provider.handleAccessRequest(exchange, subPath, query)) {
                    KsPluginBridge.sendJson(exchange, 404, "{\"error\":\"未知企业权限接口\"}");
                }
            // ===== Enterprise Permissions =====
            } else if (subPath.equals("/api/enterprise/permissions")) {
                handleEnterprisePermissions(exchange, query);
            } else if (subPath.equals("/api/enterprise/permissions/set") && "POST".equalsIgnoreCase(method)) {
                handleEnterprisePermissionSet(exchange);
            // ===== Tax CRUD APIs =====
            } else if (subPath.equals("/api/tax/rates")) {
                handleTaxRatesGet(exchange);
            } else if (subPath.equals("/api/tax/rates/set") && "POST".equalsIgnoreCase(method)) {
                handleTaxRatesSet(exchange);
            } else if (subPath.equals("/api/tax/records")) {
                handleTaxRecords(exchange, query);
            } else if (subPath.equals("/api/tax/penalties")) {
                handlePenaltyList(exchange, query);
            } else if (subPath.equals("/api/tax/brackets")) {
                handleTaxBracketsList(exchange, query);
            } else if (subPath.equals("/api/tax/bracket/upsert") && "POST".equalsIgnoreCase(method)) {
                handleTaxBracketUpsert(exchange);
            } else if (subPath.equals("/api/tax/bracket/delete") && "POST".equalsIgnoreCase(method)) {
                handleTaxBracketDelete(exchange);
            } else if (subPath.equals("/api/tax/bracket/calc") && "POST".equalsIgnoreCase(method)) {
                handleTaxBracketCalc(exchange);
            } else if (subPath.equals("/api/tax/penalty/issue") && "POST".equalsIgnoreCase(method)) {
                handlePenaltyIssue(exchange);
            // ===== Price Configurator =====
            } else if (subPath.equals("/prices") || subPath.equals("/prices/")) {
                servePriceConfigPage(exchange);
            } else if (subPath.equals("/api/prices/items")) {
                handleItemList(exchange);
            } else if (subPath.equals("/api/prices/official")) {
                handleOfficialPricesGet(exchange);
            } else if (subPath.equals("/api/prices/official/save") && "POST".equalsIgnoreCase(method)) {
                handleOfficialPriceSave(exchange);
            // ===== Bank Rate Adjustment (private banks) =====
            } else if (subPath.equals("/api/bank/rates/set") && "POST".equalsIgnoreCase(method)) {
                handleBankRateSet(exchange);
            } else if (subPath.equals("/api/bank/rates/get")) {
                handleBankRateGet(exchange, query);
            // ===== Bank Permissions =====
            } else if (subPath.equals("/api/bank/permissions")) {
                handleBankPermissions(exchange, query);
            } else if (subPath.equals("/api/bank/permissions/set") && "POST".equalsIgnoreCase(method)) {
                handleBankPermissionSet(exchange);
            // ===== Bank/Enterprise Members =====
            } else if (subPath.equals("/api/bank/members")) {
                handleBankMembers(exchange, query);
            } else if (subPath.equals("/api/bank/members/add") && "POST".equalsIgnoreCase(method)) {
                handleBankMemberAdd(exchange);
            } else if (subPath.equals("/api/bank/members/remove") && "POST".equalsIgnoreCase(method)) {
                handleBankMemberRemove(exchange);
            } else if (subPath.equals("/api/enterprise/members")) {
                handleEnterpriseMembers(exchange, query);
            } else if (subPath.equals("/api/enterprise/members/add") && "POST".equalsIgnoreCase(method)) {
                handleEnterpriseMemberAdd(exchange);
            } else if (subPath.equals("/api/enterprise/members/remove") && "POST".equalsIgnoreCase(method)) {
                handleEnterpriseMemberRemove(exchange);
            } else if (subPath.equals("/api/enterprise/members/role") && "POST".equalsIgnoreCase(method)) {
                handleEnterpriseMemberRoleSet(exchange);
            } else if (subPath.equals("/api/enterprise/role-permissions")) {
                handleEnterpriseRolePermissions(exchange, query);
            } else if (subPath.equals("/api/enterprise/role-permissions/set") && "POST".equalsIgnoreCase(method)) {
                handleEnterpriseRolePermissionsSet(exchange);
            } else if (subPath.equals("/api/enterprise/audit")) {
                handleEnterpriseAudit(exchange, query);
            // ===== Player Search (UUID → Name) =====
            } else if (subPath.equals("/api/players/search")) {
                handlePlayerSearch(exchange, query);
            // ===== Audit Log =====
            } else if (subPath.equals("/api/audit/log")) {
                handleAuditLog(exchange, query);
            } else {
                KsPluginBridge.sendJson(exchange, 404, "{\"error\":\"未找到\"}");
            }
        } catch (Exception e) {
            plugin.getLogger().warning("Web API 异常: " + e.getMessage());
            try {
                KsPluginBridge.sendJson(exchange, 500, "{\"error\":\"服务器内部错误\"}");
            } catch (IOException ignored) {}
        }
    }

    // ==================== Auth 辅助 ====================

    /** 从请求中提取认证 Token（页面引导允许 query，API 必须使用 Authorization header）。 */
    private String getTokenFromExchange(HttpExchange exchange) {
        // API credentials must be carried in the Authorization header. This keeps
        // tokens out of logs, copied URLs, browser history and third-party requests.
        String auth = exchange.getRequestHeaders().getFirst("Authorization");
        if (auth != null && auth.startsWith("Bearer ") && auth.length() > 7) {
            return auth.substring(7);
        }

        // A token in the initial player/admin page URL is retained only for the
        // page bootstrap redirect flow; API calls are deliberately header-only.
        String path = exchange.getRequestURI().getPath();
        if (path.startsWith("/ks-Eco/api/")) return null;
        String query = exchange.getRequestURI().getRawQuery();
        if (query == null) return null;
        String token = KsPluginBridge.parseQuery(query).get("token");
        return token == null || token.isBlank() ? null : token;
    }
    /**
     * 玩家功能门控：根据请求路径前缀解析出对应的 FeatureGateManager key。
     * 返回 null 表示该路径不受门控限制（市场/暂存箱/纳税记录/登录等始终可访问）。
     * 注意顺序：更具体的子前缀（bidding / ent_blindbox）必须排在通用的 /api/enterprise/ 之前。
     */
    private String resolveFeatureGateKey(String subPath) {
        if (subPath.startsWith("/api/enterprise/bid")
                || subPath.startsWith("/api/enterprise/project")
                || subPath.startsWith("/api/enterprise/procurement")) {
            return "bidding";
        }
        if (subPath.startsWith("/api/enterprise/creation/pending")) return "invites";
        if (subPath.startsWith("/api/enterprise/blindbox")) return "ent_blindbox";
        if (subPath.startsWith("/api/enterprise/") || subPath.equals("/api/my-enterprises")
                || subPath.equals("/api/rankings/enterprises")) return "enterprise";
        if (subPath.startsWith("/api/bank/") || subPath.equals("/api/my-banks")
                || subPath.equals("/api/my-loans") || subPath.equals("/api/rankings/banks")) return "bank";
        if (subPath.startsWith("/api/realestate/")) return "realestate";
        if (subPath.startsWith("/api/blindbox/")) return "blindbox";
        if (subPath.startsWith("/api/player/invites")) return "invites";
        return null;
    }

    /**
     * 政治门控：如果 ks-eco-politic 模块已加载且 legislative_mode=true，
     * 则阻止 admin 直接修改经济参数，要求走元老院提案流程。
     *
     * @return 错误消息（应返回 403）或 null（放行）
     */
    private String checkPoliticGate() {
        return plugin.politicGovernanceError();
    }

    /** 验证 Admin Token，失败时发送错误响应并返回 null */
    private KsAuthManager.Session requireAdminAuth(HttpExchange exchange) throws IOException {
        String token = getTokenFromExchange(exchange);
        if (token == null) {
            KsPluginBridge.sendJson(exchange, 401, "{\"error\":\"缺少认证 token（请通过 /kseco web 获取链接）\"}");
            return null;
        }
        KsAuthManager.Session session = plugin.bridge().validateToken(token);
        if (session == null) {
            KsPluginBridge.sendJson(exchange, 401, "{\"error\":\"Token 无效或已过期\"}");
            return null;
        }
        if (!session.isAdmin) {
            KsPluginBridge.sendJson(exchange, 403, "{\"error\":\"需要管理员权限\"}");
            return null;
        }
        plugin.bridge().touchToken(token);
        return session;
    }

    /** 验证任意有效 Token（不要求管理员），失败时发送错误响应并返回 null */
    private KsAuthManager.Session requireAuth(HttpExchange exchange) throws IOException {
        String token = getTokenFromExchange(exchange);
        if (token == null) {
            KsPluginBridge.sendJson(exchange, 401, "{\"error\":\"缺少认证 token\"}");
            return null;
        }
        KsAuthManager.Session session = plugin.bridge().validateToken(token);
        if (session == null) {
            KsPluginBridge.sendJson(exchange, 401, "{\"error\":\"Token 无效或已过期\"}");
            return null;
        }
        plugin.bridge().touchToken(token);
        return session;
    }

    // ---- 管理/玩家页面 ----

    /** 根据 token 决定服务哪个页面 */
    private void serveAdminPage(HttpExchange exchange) throws IOException {
        String token = getTokenFromExchange(exchange);
        if (token != null) {
            KsAuthManager.Session session = plugin.bridge().validateToken(token);
            if (session != null) {
                plugin.bridge().touchToken(token);
                if (session.isAdmin) {
                    KsPluginBridge.sendHtml(exchange, 200, buildAdminSPA());
                    return;
                } else {
                    KsPluginBridge.sendHtml(exchange, 200, buildPlayerSPA());
                    return;
                }
            }
        }
        // 无有效 token：显示登录引导页
        KsPluginBridge.sendHtml(exchange, 200, buildLandingPage());
    }

    /** 玩家面板页面 */
    private void servePlayerPage(HttpExchange exchange) throws IOException {
        KsAuthManager.Session session = requireAuth(exchange);
        if (session == null) return;
        KsPluginBridge.sendHtml(exchange, 200, buildPlayerSPA());
    }

    /** 登录引导页 — 提示用户使用 /kseco 或 /kseco-admin 命令获取链接 */
    private String buildLandingPage() {
        return """
        <!DOCTYPE html><html lang="zh-CN"><head><meta charset="UTF-8">
        <title>ks-Eco 经济系统</title>
        <style>
        *{box-sizing:border-box;margin:0;padding:0;}
        body{font-family:'Segoe UI',sans-serif;background:#1a1a2e;color:#e0e0e0;display:flex;align-items:center;justify-content:center;min-height:100vh;}
        .landing{text-align:center;max-width:480px;padding:40px;}
        .landing h1{color:#00d4ff;font-size:28px;margin-bottom:16px;}
        .landing .logo{font-size:64px;margin-bottom:16px;}
        .landing p{color:#888;margin:8px 0;line-height:1.6;}
        .landing .cmd{background:#16213e;border:1px solid #2a2a4a;border-radius:8px;padding:16px;margin:16px 0;text-align:left;}
        .landing .cmd code{color:#00d4ff;font-size:15px;display:block;margin:6px 0;}
        .landing .cmd span{color:#888;font-size:11px;}
        </style></head><body>
        <div class="landing">
        <div class="logo">🏦</div>
        <h1>ks-Eco 经济系统</h1>
        <p>请在游戏中执行以下命令获取专属链接：</p>
        <div class="cmd">
        <code>/kseco web</code><span>— 玩家面板（查看我的银行、企业、税收）</span>
        </div>
        <div class="cmd">
        <code>/kseco-admin web</code><span>— 管理员面板（需要 kseco.admin 权限）</span>
        </div>
        <p style="font-size:12px;margin-top:24px;color:#555;">ks-Eco v1.2.0 · 链接有效期 10 分钟</p>
        </div></body></html>
        """;
    }

    /** 管理员 SPA — 侧边栏 + 多 Tab 仪表盘 */
    private String buildAdminSPA() {
        String cached = adminSpaHtml;
        if (cached != null) return cached;
        synchronized (this) {
            if (adminSpaHtml == null) {
                String loaded = loadBundledSpa("/web/admin.html");
                adminSpaHtml = loaded != null ? loaded : buildInlineAdminHtml();
            }
            return adminSpaHtml;
        }
    }

    /** 玩家 SPA — 个人数据面板 */
    private String buildPlayerSPA() {
        String cached = playerSpaHtml;
        if (cached != null) return cached;
        synchronized (this) {
            if (playerSpaHtml == null) {
                String loaded = loadBundledSpa("/web/player.html");
                playerSpaHtml = loaded != null ? loaded : buildInlinePlayerHtml();
            }
            return playerSpaHtml;
        }
    }

    private String loadBundledSpa(String resourcePath) {
        try (InputStream in = plugin.getClass().getResourceAsStream(resourcePath)) {
            return in == null ? null : new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException ignored) {
            return null;
        }
    }

    private void serveWebAsset(HttpExchange exchange, String subPath) throws IOException {
        if (!subPath.matches("/assets/(admin|player)(-[a-z0-9-]+)?\\.(css|js)")) {
            KsPluginBridge.sendJson(exchange, 404, "{\"error\":\"未找到\"}");
            return;
        }
        String resourcePath = "/web" + subPath;
        String contentType = subPath.endsWith(".css")
                ? "text/css; charset=utf-8" : "text/javascript; charset=utf-8";
        try (InputStream in = plugin.getClass().getResourceAsStream(resourcePath)) {
            if (in == null) {
                KsPluginBridge.sendJson(exchange, 404, "{\"error\":\"资源不存在\"}");
                return;
            }
            byte[] bytes = in.readAllBytes();
            exchange.getResponseHeaders().set("Content-Type", contentType);
            exchange.getResponseHeaders().set("Cache-Control", "public, max-age=300");
            exchange.sendResponseHeaders(200, bytes.length);
            try (OutputStream output = exchange.getResponseBody()) {
                output.write(bytes);
            }
        }
    }
    /** 内联回退 — 管理员简化版 */
    private String buildInlineAdminHtml() {
        return """
        <!DOCTYPE html><html lang="zh-CN"><head><meta charset="UTF-8">
        <title>ks-Eco 管理面板</title>
        <style>
        body{font-family:'Segoe UI',sans-serif;max-width:1000px;margin:20px auto;padding:0 20px;background:#1a1a2e;color:#e0e0e0;}
        h1{color:#00d4ff;border-bottom:2px solid #00d4ff33;padding-bottom:10px;}
        .card{background:#16213e;border-radius:8px;padding:16px;margin:12px 0;border-left:4px solid #00d4ff;}
        table{width:100%;border-collapse:collapse;margin:10px 0;font-size:13px;}
        th,td{padding:8px 12px;text-align:left;border-bottom:1px solid #2a2a4a;}
        th{color:#00d4ff;}
        .btn{background:#00d4ff33;color:#00d4ff;border:1px solid #00d4ff;padding:6px 16px;border-radius:4px;cursor:pointer;margin:4px;}
        .btn:hover{background:#00d4ff66;}
        input{background:#1a1a2e;border:1px solid #2a2a4a;color:#e0e0e0;padding:6px 10px;border-radius:4px;}
        </style></head><body>
        <h1>ks-Eco 经济管理面板</h1>
        <p style="color:#ff9800;">⚠ 资源文件未加载，使用简化版。请重新构建插件。</p>
        <div class="card"><h3>市场概览</h3><p>活跃挂单: <span id="activeListings">—</span></p><p>暂存箱: <span id="storedItems">—</span></p></div>
        <script>
        var TOKEN=(new URL(location)).searchParams.get('token')||'';
        fetch('/ks-Eco/api/market/stats').then(r=>r.json()).then(d=>{
        document.getElementById('activeListings').textContent=d.activeListings;
        document.getElementById('storedItems').textContent=d.storedItems;});
        </script></body></html>
        """;
    }

    /** 内联回退 — 玩家简化版 */
    private String buildInlinePlayerHtml() {
        return """
        <!DOCTYPE html><html lang="zh-CN"><head><meta charset="UTF-8">
        <title>ks-Eco 玩家面板</title>
        <style>
        body{font-family:'Segoe UI',sans-serif;max-width:800px;margin:20px auto;padding:0 20px;background:#1a1a2e;color:#e0e0e0;}
        h1{color:#00d4ff;border-bottom:2px solid #00d4ff33;padding-bottom:10px;}
        .card{background:#16213e;border-radius:8px;padding:16px;margin:12px 0;border-left:4px solid #00d4ff;}
        </style></head><body>
        <h1>ks-Eco 玩家面板</h1><p style="color:#ff9800;">⚠ 资源文件未加载，使用简化版。请重新构建插件。</p>
        </body></html>
        """;
    }

    private void handleFederatedSnapshotSources(HttpExchange exchange, String query) throws IOException {
        KsAuthManager.Session session = requireAuth(exchange);
        if (session == null) return;
        Map<String, String> params = KsPluginBridge.parseQuery(query);
        try {
            FederatedSnapshot.Kind kind = federatedKind(params.get("kind"));
            if (kind == FederatedSnapshot.Kind.ASSET && !session.isAdmin) {
                KsPluginBridge.sendJson(exchange, 403, "{\"error\":\"资产明细快照需要管理员权限\"}");
                return;
            }
            boolean includeStale = booleanQuery(params, "includeStale", false);
            boolean includeOffline = booleanQuery(params, "includeOffline", false);
            var heads = awaitFederated(plugin.listFederatedSnapshotHeads(kind, System.currentTimeMillis(),
                    includeStale, includeOffline));
            KsPluginBridge.sendJson(exchange, 200, gson.toJson(Map.of("kind", kind.id(), "sources", heads)));
        } catch (IllegalArgumentException failure) {
            KsPluginBridge.sendJson(exchange, 400, gson.toJson(Map.of("error", failure.getMessage())));
        } catch (Exception failure) {
            KsPluginBridge.sendJson(exchange, 503, gson.toJson(Map.of("error", "跨服快照暂不可用")));
        }
    }

    private void handleFederatedSnapshotRead(HttpExchange exchange, String query) throws IOException {
        KsAuthManager.Session session = requireAuth(exchange);
        if (session == null) return;
        Map<String, String> params = KsPluginBridge.parseQuery(query);
        try {
            FederatedSnapshot.Kind kind = federatedKind(params.get("kind"));
            if (kind == FederatedSnapshot.Kind.ASSET && !session.isAdmin) {
                KsPluginBridge.sendJson(exchange, 403, "{\"error\":\"资产明细快照需要管理员权限\"}");
                return;
            }
            AssetSource source = new AssetSource(requiredQuery(params, "server"), requiredQuery(params, "world"),
                    requiredQuery(params, "dimension"));
            boolean includeStale = booleanQuery(params, "includeStale", false);
            boolean includeOffline = booleanQuery(params, "includeOffline", false);
            var result = awaitFederated(plugin.readFederatedSnapshot(kind, source, System.currentTimeMillis(),
                    includeStale, includeOffline));
            if (result.isEmpty()) {
                KsPluginBridge.sendJson(exchange, 404, "{\"error\":\"快照不存在、已过期或被策略拒绝\"}");
                return;
            }
            FederatedSnapshot.ReadResult read = result.get();
            byte[] payload = FederatedSnapshotCodec.decode(read.bundle(),
                    plugin.federatedAssetSettings().maxSnapshotBytes());
            Map<String, Object> response = new LinkedHashMap<>();
            response.put("metadata", read.bundle().metadata());
            response.put("stale", read.stale());
            response.put("offline", read.offline());
            response.put("nodeLastSeenAt", read.nodeLastSeenAt());
            response.put("assets", read.bundle().assets());
            if (read.bundle().metadata().mediaType().contains("json")) {
                response.put("payload", gson.fromJson(new String(payload, StandardCharsets.UTF_8), Object.class));
                response.put("payloadEncoding", "json");
            } else {
                response.put("payload", Base64.getEncoder().encodeToString(payload));
                response.put("payloadEncoding", "base64");
            }
            KsPluginBridge.sendJson(exchange, 200, gson.toJson(response));
        } catch (IllegalArgumentException failure) {
            KsPluginBridge.sendJson(exchange, 400, gson.toJson(Map.of("error", failure.getMessage())));
        } catch (Exception failure) {
            KsPluginBridge.sendJson(exchange, 503, gson.toJson(Map.of("error", "跨服快照暂不可用")));
        }
    }

    private void handleFederatedAssets(HttpExchange exchange, String query, boolean aggregate) throws IOException {
        if (aggregate ? requireAuth(exchange) == null : requireAdminAuth(exchange) == null) return;
        Map<String, String> params = KsPluginBridge.parseQuery(query);
        try {
            FederatedAssetService.Query request = new FederatedAssetService.Query(
                    FederatedCapability.ASSET_AGGREGATE,
                    params.get("server"), params.get("world"), params.get("dimension"),
                    params.get("assetType"), params.get("owner"),
                    booleanQuery(params, "includeStale", false), booleanQuery(params, "includeOffline", false));
            Object result = aggregate
                    ? awaitFederated(plugin.aggregateFederatedAssets(request, System.currentTimeMillis()))
                    : awaitFederated(plugin.queryFederatedAssets(request, System.currentTimeMillis()));
            KsPluginBridge.sendJson(exchange, 200, gson.toJson(Map.of(aggregate ? "aggregate" : "assets", result)));
        } catch (IllegalArgumentException failure) {
            KsPluginBridge.sendJson(exchange, 400, gson.toJson(Map.of("error", failure.getMessage())));
        } catch (Exception failure) {
            KsPluginBridge.sendJson(exchange, 503, gson.toJson(Map.of("error", "跨服资产查询暂不可用")));
        }
    }

    @SuppressWarnings("unchecked")
    private void handleFederatedSettings(HttpExchange exchange) throws IOException {
        if (requireAdminAuth(exchange) == null) return;
        if ("GET".equalsIgnoreCase(exchange.getRequestMethod())) {
            KsPluginBridge.sendJson(exchange, 200, gson.toJson(Map.of(
                    "status", plugin.federatedAssetStatus(),
                    "settings", plugin.federatedAssetConfiguration())));
            return;
        }
        if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
            KsPluginBridge.sendJson(exchange, 405, "{\"error\":\"Method not allowed\"}");
            return;
        }
        try {
            Map<String, Object> candidate = gson.fromJson(KsPluginBridge.readBody(exchange), Map.class);
            if (candidate == null) throw new IllegalArgumentException("请求体必须是完整 federated-assets 对象");
            FederatedAssetSettings.fromMap(candidate);
            long generation = callOnServerThread(() -> plugin.applyFederatedAssetSettings(candidate, true));
            auditLog("FEDERATED_ASSET_POLICY_UPDATE", "ADMIN", "ADMIN", "federated-assets", "policy",
                    "generation=" + generation);
            KsPluginBridge.sendJson(exchange, 200, gson.toJson(Map.of("success", true,
                    "generation", generation, "status", plugin.federatedAssetStatus())));
        } catch (IllegalArgumentException failure) {
            KsPluginBridge.sendJson(exchange, 400, gson.toJson(Map.of("error", failure.getMessage())));
        } catch (IOException failure) {
            KsPluginBridge.sendJson(exchange, 409, gson.toJson(Map.of("error", "策略未应用；请检查共享库和重启边界")));
        }
    }

    private static FederatedSnapshot.Kind federatedKind(String raw) {
        if (raw == null || raw.isBlank()) throw new IllegalArgumentException("kind 必须是 MAP、PROPERTY 或 ASSET");
        try {
            return FederatedSnapshot.Kind.valueOf(raw.strip().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException failure) {
            throw new IllegalArgumentException("kind 必须是 MAP、PROPERTY 或 ASSET");
        }
    }

    private static String requiredQuery(Map<String, String> params, String key) {
        String value = params.get(key);
        if (value == null || value.isBlank()) throw new IllegalArgumentException("缺少参数: " + key);
        return value;
    }

    private static boolean booleanQuery(Map<String, String> params, String key, boolean fallback) {
        String value = params.get(key);
        if (value == null) return fallback;
        if ("true".equalsIgnoreCase(value)) return true;
        if ("false".equalsIgnoreCase(value)) return false;
        throw new IllegalArgumentException(key + " 必须是 true 或 false");
    }

    private static <T> T awaitFederated(CompletableFuture<T> future) throws Exception {
        try {
            return future.get(10, TimeUnit.SECONDS);
        } catch (TimeoutException failure) {
            future.cancel(true);
            throw failure;
        }
    }

    private void handleMarketStats(HttpExchange exchange) throws IOException {
        Map<String, Object> stats = new LinkedHashMap<>();
        stats.put("activeListings", plugin.listingManager().activeListingCount());
        stats.put("storedItems", plugin.storageManager().totalStoredItems());
        stats.put("officialWarehouseItems", plugin.officialWarehouseManager().loadPage(0, 1).total());
        stats.put("vaultAvailable", plugin.vaultHook().isAvailable());
        stats.put("officialBuyEnabled", plugin.ecoConfig().isOfficialBuyEnabled());

        // 官方收购物品价格表（官方只收购，不直售 — 直售已由盲盒系统取代）
        List<Map<String, Object>> prices = new ArrayList<>();
        for (var item : plugin.ecoConfig().getDefaultBuyItems()) {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("material", item.material());
            entry.put("buyPrice", plugin.priceEngine().getOfficialBuyPrice(item.material()));
            entry.put("marketAvg", plugin.priceEngine().getMarketAveragePrice(item.material()));
            prices.add(entry);
        }
        stats.put("prices", prices);

        KsPluginBridge.sendJson(exchange, 200, gson.toJson(stats));
    }

    /** 宏观数据 API — 返回 M0/M1/M2 时间序列 + 汇总统计 */
    private void handleMacroData(HttpExchange exchange) throws IOException {
        Map<String, Object> data = new LinkedHashMap<>();
        // M0/M1/M2 历史快照（最近 30 条）
        List<Double> m0History = new ArrayList<>();
        List<Double> m1History = new ArrayList<>();
        List<Double> m2History = new ArrayList<>();
        List<String> labels = new ArrayList<>();
        try (var conn = plugin.ksCore().dataStore().getConnection()) {
            if (conn != null) {
                var rs = conn.createStatement().executeQuery(
                    "SELECT m0, m1, m2, snapshot_at FROM ks_bank_money_supply ORDER BY snapshot_at DESC LIMIT 30");
                while (rs.next()) {
                    m0History.add(0, rs.getDouble("m0"));
                    m1History.add(0, rs.getDouble("m1"));
                    m2History.add(0, rs.getDouble("m2"));
                    java.text.SimpleDateFormat sdf = new java.text.SimpleDateFormat("MM-dd HH:mm");
                    labels.add(0, sdf.format(new java.util.Date(rs.getLong("snapshot_at") * 1000)));
                }
            }
        } catch (java.sql.SQLException ignored) {}
        data.put("labels", labels);
        data.put("m0", m0History);
        data.put("m1", m1History);
        data.put("m2", m2History);
        // 最新值
        if (!m0History.isEmpty()) {
            data.put("latestM0", m0History.get(m0History.size() - 1));
            data.put("latestM1", m1History.get(m1History.size() - 1));
            data.put("latestM2", m2History.get(m2History.size() - 1));
        } else {
            data.put("latestM0", 0); data.put("latestM1", 0); data.put("latestM2", 0);
        }
        // 汇总统计
        queryInt(data, "SELECT COUNT(*) FROM ks_bank_banks WHERE status='ACTIVE'", "bankCount");
        queryDouble(data, "SELECT COALESCE(SUM(total_assets),0) FROM ks_bank_banks WHERE status='ACTIVE'", "totalAssets");
        queryDouble(data, "SELECT COALESCE(SUM(remaining),0) FROM ks_bank_loans WHERE status='ACTIVE'", "totalLoans");
        queryInt(data, "SELECT COUNT(*) FROM ks_ent_enterprises WHERE status='ACTIVE'", "enterpriseCount");
        queryDouble(data, "SELECT COALESCE(SUM(tax_amount),0) FROM ks_tax_records", "totalTaxCollected");
        // 当前央行利率
        try (var conn = plugin.ksCore().dataStore().getConnection()) {
            if (conn != null) {
                var rs = conn.createStatement().executeQuery(
                    "SELECT base_rate, reserve_requirement FROM ks_bank_cb_rates ORDER BY set_at DESC LIMIT 1");
                if (rs.next()) {
                    data.put("baseRate", rs.getDouble("base_rate"));
                    data.put("reserveRequirement", rs.getDouble("reserve_requirement"));
                }
            }
        } catch (java.sql.SQLException ignored) {}
        KsPluginBridge.sendJson(exchange, 200, gson.toJson(data));
    }

    private void handleListings(HttpExchange exchange, String query) throws IOException {
        Map<String, String> params = KsPluginBridge.parseQuery(query);
        String type = params.getOrDefault("type", "SELL");
        var listings = plugin.listingManager().getActiveListings(type);

        List<Map<String, Object>> list = new ArrayList<>();
        for (var l : listings) {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("id", l.id());
            entry.put("sellerName", l.sellerName());
            entry.put("material", l.itemMaterial());
            entry.put("quantity", l.quantity());
            entry.put("unitPrice", l.unitPrice());
            entry.put("totalPrice", l.totalPrice());
            entry.put("type", l.listingType());
            list.add(entry);
        }

        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("listings", list);
        resp.put("count", list.size());
        KsPluginBridge.sendJson(exchange, 200, gson.toJson(resp));
    }

    private void handleForcePrice(HttpExchange exchange) throws IOException {
        KsAuthManager.Session session = requireAdminAuth(exchange);
        if (session == null) return;
        String body = KsPluginBridge.readBody(exchange);

        @SuppressWarnings("unchecked")
        Map<String, Object> req = gson.fromJson(body, Map.class);
        if (req == null) {
            KsPluginBridge.sendJson(exchange, 400, "{\"error\":\"请求体格式错误\"}");
            return;
        }

        String material = (String) req.get("material");
        Object priceObj = req.get("price");
        if (material == null || priceObj == null) {
            KsPluginBridge.sendJson(exchange, 400, "{\"error\":\"缺少 material 或 price\"}");
            return;
        }

        double price = priceObj instanceof Number n ? n.doubleValue() : Double.parseDouble(priceObj.toString());
        plugin.priceEngine().forcePrice(material.toUpperCase(), price);
        KsPluginBridge.sendJson(exchange, 200, "{\"message\":\"已更新 " + material + " 价格为 " + price + "\"}");
    }

    /** 查询当前内存中的动态价格（用于测试验证）。 */
    private void handleAdminPrices(HttpExchange exchange, String query) throws IOException {
        KsAuthManager.Session session = requireAdminAuth(exchange);
        if (session == null) return;
        Map<String, String> params = KsPluginBridge.parseQuery(query);
        String mat = params.get("material");
        if (mat != null) {
            var pp = plugin.priceEngine().getAllPrices().get(mat.toUpperCase());
            if (pp == null) { KsPluginBridge.sendJson(exchange, 404, "{\"error\":\"物品未定价\"}"); return; }
            Map<String,Object> r = new LinkedHashMap<>();
            r.put("material",mat); r.put("basePrice",pp.basePrice);
            r.put("buyPrice",pp.buyPrice); r.put("marketAvg",pp.marketAvg);
            KsPluginBridge.sendJson(exchange, 200, gson.toJson(r));
        } else {
            Map<String,Object> all = new LinkedHashMap<>();
            for (var e : plugin.priceEngine().getAllPrices().entrySet()) {
                var pp = e.getValue();
                Map<String,Object> r = new LinkedHashMap<>();
                r.put("base",pp.basePrice); r.put("buy",pp.buyPrice); r.put("avg",pp.marketAvg);
                all.put(e.getKey(), r);
            }
            KsPluginBridge.sendJson(exchange, 200, gson.toJson(Map.of("prices",all,"count",all.size())));
        }
    }

    /** 模拟交易 — 用于测试市场波动（需管理员 token） */
    @SuppressWarnings("unchecked")
    private void handleSimulateTrade(HttpExchange exchange) throws IOException {
        KsAuthManager.Session session = requireAdminAuth(exchange);
        if (session == null) return;
        Map<String, Object> req = gson.fromJson(KsPluginBridge.readBody(exchange), Map.class);
        if (req == null) { KsPluginBridge.sendJson(exchange, 400, "{\"error\":\"无效请求\"}"); return; }
        String material = (String) req.get("material");
        int quantity = (int) toDouble(req.get("quantity"), 1);
        double price = toDouble(req.get("price"), -1);
        if (material == null) { KsPluginBridge.sendJson(exchange, 400, "{\"error\":\"缺少 material\"}"); return; }
        // 未指定价格时用当前官方收购价
        if (price < 0) price = plugin.priceEngine().getOfficialBuyPrice(material);
        String type = (String) req.getOrDefault("type", "PLAYER_TRADE");
        double resultPrice = plugin.priceEngine().recordAdminTrade(material, quantity, price, type,
                session.playerUuid.toString(), "CONSOLE");
        boolean testMode = plugin.priceEngine().isTestModeEnabled();
        KsPluginBridge.sendJson(exchange, 200, gson.toJson(Map.of(
                "message", "模拟交易完成: " + material + " ×" + quantity + " @" + price + " (" + type + ")" +
                        (testMode ? "（测试模式-仅预览）" : ""),
                "resultPrice", resultPrice,
                "testMode", testMode)));
    }

    /** 手动触发价格刷新（需管理员 token） */
    private void handleRefreshPrices(HttpExchange exchange) throws IOException {
        KsAuthManager.Session session = requireAdminAuth(exchange);
        if (session == null) return;
        plugin.refreshPricesCoordinated();
        KsPluginBridge.sendJson(exchange, 200, "{\"message\":\"价格已刷新\"}");
    }

    private void handleIdleItems(HttpExchange exchange, String query) throws IOException {
        KsAuthManager.Session session = requireAdminAuth(exchange);
        if (session == null) return;

        // Return only aggregate storage status to the administrator.
        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("totalItems", plugin.storageManager().totalStoredItems());
        resp.put("message", "管理员可在此页面批量管理闲置物品");
        KsPluginBridge.sendJson(exchange, 200, gson.toJson(resp));
    }

    /** 创建测试管理员 Token（仅限本地访问，用于自动化测试）。 */
    private void handleTestToken(HttpExchange exchange) throws IOException {
        if (!plugin.ksCore().getConfig().getBoolean("web-gateway.allow-test-token", false)) {
            KsPluginBridge.sendJson(exchange, 404, "{\"error\":\"test token endpoint is disabled\"}");
            return;
        }
        // 安全检查：仅允许 localhost 创建测试 token
        var remote = exchange.getRemoteAddress().getAddress();
        if (!remote.isLoopbackAddress()) {
            KsPluginBridge.sendJson(exchange, 403, "{\"error\":\"仅限本地访问\"}");
            return;
        }
        var session = plugin.ksCore().authManager().create(
                UUID.fromString("00000000-0000-0000-0000-000000000001"), "TEST_ADMIN", true);
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("token", session.token);
        response.put("isAdmin", true);
        response.put("playerName", "TEST_ADMIN");
        response.put("hint", "使用方式: Authorization: Bearer <token>");
        KsPluginBridge.sendJson(exchange, 200, gson.toJson(response));
    }

    private void handleLogin(HttpExchange exchange, String query) throws IOException {
        String callerToken = getTokenFromExchange(exchange);
        KsAuthManager.Session caller = plugin.bridge().validateToken(callerToken);
        if (caller == null) {
            KsPluginBridge.sendJson(exchange, 401, "{\"error\":\"登录令牌已失效，请从游戏内重新打开网页\"}");
            return;
        }
        Map<String, String> params = KsPluginBridge.parseQuery(query);
        String playerName = params.get("player");
        if (playerName == null) {
            KsPluginBridge.sendJson(exchange, 400, "{\"error\":\"缺少 player 参数\"}");
            return;
        }

        Player player = Bukkit.getPlayer(playerName);
        if (player == null) {
            KsPluginBridge.sendJson(exchange, 404, "{\"error\":\"玩家不在线\"}");
            return;
        }

        if (!caller.isAdmin && !caller.playerUuid.equals(player.getUniqueId())) {
            KsPluginBridge.sendJson(exchange, 403, "{\"error\":\"只能续发自己的登录令牌\"}");
            return;
        }

        boolean isAdmin = caller.isAdmin && player.hasPermission("kseco.admin");
        String token = plugin.bridge().createToken(player, isAdmin);

        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("token", token);
        resp.put("isAdmin", isAdmin);
        resp.put("playerName", player.getName());
        KsPluginBridge.sendJson(exchange, 200, gson.toJson(resp));
    }

    // ================================================================
    // Extra 模块 — 反射工具（extra 模块在独立 JAR 中，需反射调用）
    // ================================================================

    /** 获取 extra 模块实例 */
    private Object getExtraModule(String id) {
        var loader = plugin.extraModuleLoader();
        return loader == null ? null : loader.getModule(id);
    }

    /** 检查 extra 模块是否加载 */
    private boolean hasExtra(String id) {
        return getExtraModule(id) != null;
    }

    /** 反射调用 extra 模块的管理器方法，返回结果或 null。错误信息存入 lastReflectError */
    private String lastReflectError = null;
    private Object callExtraManager(String extraId, String getter, String method, Class<?>[] argTypes, Object... args) {
        lastReflectError = null;
        try {
            Object extra = getExtraModule(extraId);
            if (extra == null) { lastReflectError = "模块未加载: " + extraId; return null; }
            var getterMethod = extra.getClass().getMethod(getter);
            Object manager = getterMethod.invoke(extra);
            if (manager == null) { lastReflectError = getter + " 返回 null"; return null; }
            var methodObj = manager.getClass().getMethod(method, argTypes);
            Object result = methodObj.invoke(manager, args);
            if (result == null) {
                lastReflectError = method + " 返回 null（可能: DB未连接/数据冲突）";
            }
            return result;
        } catch (NoSuchMethodException e) {
            lastReflectError = "方法未找到: " + extraId + "." + getter + "()→" + method + " - " + e.getMessage();
            plugin.getLogger().warning("反射调用: " + lastReflectError);
            return null;
        } catch (Exception e) {
            lastReflectError = e.getClass().getSimpleName() + ": " + e.getMessage();
            plugin.getLogger().warning("反射调用 [" + extraId + ":" + getter + ":" + method + "] 失败: " + lastReflectError);
            return null;
        }
    }

    // ================================================================
    // Extra 模块页面
    // ================================================================

    private void serveBankPage(HttpExchange exchange) throws IOException {
        String token = getTokenFromExchange(exchange);
        String q = token != null ? "?token=" + token : "";
        KsPluginBridge.sendHtml(exchange, 302, "<html><head><meta http-equiv=\"refresh\" content=\"0;url=/ks-Eco/admin" + q + "#section=bank\"/></head><body>跳转中...</body></html>");
    }
    private void serveEnterprisePage(HttpExchange exchange) throws IOException {
        String token = getTokenFromExchange(exchange);
        String q = token != null ? "?token=" + token : "";
        KsPluginBridge.sendHtml(exchange, 302, "<html><head><meta http-equiv=\"refresh\" content=\"0;url=/ks-Eco/admin" + q + "#section=enterprise\"/></head><body>跳转中...</body></html>");
    }
    private void serveTaxPage(HttpExchange exchange) throws IOException {
        String token = getTokenFromExchange(exchange);
        String q = token != null ? "?token=" + token : "";
        KsPluginBridge.sendHtml(exchange, 302, "<html><head><meta http-equiv=\"refresh\" content=\"0;url=/ks-Eco/admin" + q + "#section=tax\"/></head><body>跳转中...</body></html>");
    }
    private void handleExtraModulesList(HttpExchange exchange) throws IOException {
        var loader = plugin.extraModuleLoader();
        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("modules", loader == null ? Set.of() : loader.getLoadedModuleIds());
        resp.put("count", loader == null ? 0 : loader.loadedModuleCount());
        KsPluginBridge.sendJson(exchange, 200, gson.toJson(resp));
    }

    // ================================================================
    // Player-scoped APIs — 玩家个人数据
    // ================================================================

    /** 我的银行 — 玩家是所有者或成员的银行 */
    /**
     * 玩家面板启动引导：返回玩家身份 + 当前对该玩家开放的功能列表。
     * 不依赖任何具体功能模块（不受 resolveFeatureGateKey 门控），player.html 应在最先调用，
     * 避免 myUuid 之类全局信息因为某个功能被关闭而连带拿不到。
     */
    private void handleEcoBootstrap(HttpExchange exchange) throws IOException {
        KsAuthManager.Session session = requireAuth(exchange);
        if (session == null) return;
        List<String> enabled = new ArrayList<>();
        for (var entry : plugin.featureGate().snapshot().entrySet()) {
            String requiredModule = FEATURE_MODULES.get(entry.getKey());
            boolean moduleAvailable = requiredModule == null || hasExtra(requiredModule);
            if (moduleAvailable && (session.isAdmin || entry.getValue())) enabled.add(entry.getKey());
        }
        KsPluginBridge.sendJson(exchange, 200, gson.toJson(Map.of(
                "myUuid", session.playerUuid.toString(),
                "myName", session.playerName == null ? "" : session.playerName,
                "isAdmin", session.isAdmin,
                "enabledFeatures", enabled
        )));
    }

    /**
     * 公开信息接口（无需鉴权）：官方收购价格 + 各税率，供玩家端/管理端展示。
     */
    private void handlePublicInfo(HttpExchange exchange) throws IOException {
        // 官方收购价格列表
        List<Map<String, Object>> prices = new ArrayList<>();
        for (var item : plugin.ecoConfig().getDefaultBuyItems()) {
            Map<String, Object> e = new LinkedHashMap<>();
            e.put("material", item.material());
            e.put("chineseName", MaterialNames.get(item.material()));
            e.put("basePrice", item.basePrice());
            e.put("buyPrice", plugin.priceEngine().getOfficialBuyPrice(item.material()));
            e.put("marketAvg", plugin.priceEngine().getMarketAveragePrice(item.material()));
            e.put("trend", plugin.priceEngine().getTrend(item.material()));
            prices.add(e);
        }

        // 税率 — 先从 tax extra module 取完整列表，再补全缺失分类
        String[] standardCategories = {
            "MARKET_TRADE", "PROPERTY_TRADE", "OFFICIAL_TRADE",
            "ENTERPRISE_SMALL", "ENTERPRISE_MEDIUM", "ENTERPRISE_LARGE",
            "DIVIDEND_TAX", "BANK_INTEREST", "PLAYER_TRANSFER", "TAX_PENALTY"
        };
        List<Map<String, Object>> taxRates = new ArrayList<>();

        // 尝试从 TaxRateManager 取完整映射（含行业差异）
        @SuppressWarnings("unchecked")
        Map<String, Double> allRates = (Map<String, Double>) callExtraManager(
                "ks-eco-tax", "taxRateManager", "getAllRates", new Class<?>[]{});
        for (String cat : standardCategories) {
            Map<String, Object> t = new LinkedHashMap<>();
            t.put("category", cat);
            t.put("chineseName", MaterialNames.getTaxCategoryName(cat));
            double rate = (allRates != null && allRates.containsKey(cat))
                    ? allRates.get(cat)
                    : plugin.getCategoryTaxRate(cat);
            t.put("rate", rate);
            t.put("ratePercent", String.format("%.2f%%", rate * 100));
            taxRates.add(t);
        }

        // 行业综合税率矩阵（企业税 + 分红税，按行业分列）
        String[] industries = {"INDUSTRY", "AGRICULTURE", "REAL_ESTATE", "OTHER"};
        String[] industryNames = {"工业", "农业", "房地产", "其他"};
        String[] entTaxCats = {"ENTERPRISE_TAX", "DIVIDEND_TAX", "MARKET_TRADE"};
        List<Map<String, Object>> industryMatrix = new ArrayList<>();
        for (int i = 0; i < industries.length; i++) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("industry", industries[i]);
            row.put("industryName", industryNames[i]);
            for (String cat : entTaxCats) {
                String key = industries[i] + ":" + cat;
                double rate;
                if (allRates != null && allRates.containsKey(key)) {
                    rate = allRates.get(key);
                } else {
                    rate = plugin.getCategoryTaxRate(cat);
                }
                row.put(cat, rate);
                row.put(cat + "Percent", String.format("%.2f%%", rate * 100));
            }
            industryMatrix.add(row);
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("prices", prices);
        result.put("taxRates", taxRates);
        result.put("industryMatrix", industryMatrix);
        KsPluginBridge.sendJson(exchange, 200, gson.toJson(result));
    }

    /** 单材料成交历史（按日聚合，只读；排除测试模式流水） */
    private void handleTradeHistory(HttpExchange exchange, String query) throws IOException {
        KsAuthManager.Session session = requireAuth(exchange);
        if (session == null) return;
        Map<String, String> params = KsPluginBridge.parseQuery(query);
        String material = params.get("material");
        if (material == null || material.isBlank()) {
            KsPluginBridge.sendJson(exchange, 400, "{\"error\":\"缺少 material\"}"); return;
        }
        int days = 30;
        try { days = Integer.parseInt(params.getOrDefault("days", "30")); } catch (NumberFormatException ignored) {}
        days = Math.max(1, Math.min(days, 90));
        long since = System.currentTimeMillis() / 1000 - (long) days * 86400;
        List<Map<String, Object>> points = new ArrayList<>();
        try (var conn = plugin.ksCore().dataStore().getConnection()) {
            if (conn == null) { KsPluginBridge.sendJson(exchange, 500, "{\"error\":\"数据库未连接\"}"); return; }
            try (var ps = conn.prepareStatement(
                    "SELECT timestamp/86400 AS day, AVG(unit_price) AS avg_price, SUM(quantity) AS volume, COUNT(*) AS trades" +
                    " FROM ks_eco_trades WHERE item_material=? AND timestamp>=? AND (is_test IS NULL OR is_test=0)" +
                    " GROUP BY day ORDER BY day ASC")) {
                ps.setString(1, material.toUpperCase(Locale.ROOT));
                ps.setLong(2, since);
                var rs = ps.executeQuery();
                while (rs.next()) {
                    Map<String, Object> p = new LinkedHashMap<>();
                    p.put("day", rs.getLong("day") * 86400);
                    p.put("avgPrice", Math.round(rs.getDouble("avg_price") * 100.0) / 100.0);
                    p.put("volume", rs.getLong("volume"));
                    p.put("trades", rs.getLong("trades"));
                    points.add(p);
                }
            }
        } catch (java.sql.SQLException e) {
            KsPluginBridge.sendJson(exchange, 500, "{\"error\":\"" + e.getMessage() + "\"}"); return;
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("material", material.toUpperCase(Locale.ROOT));
        result.put("chineseName", MaterialNames.get(material.toUpperCase(Locale.ROOT)));
        result.put("days", days);
        result.put("points", points);
        KsPluginBridge.sendJson(exchange, 200, gson.toJson(result));
    }

    /** 管理员查看所有活跃挂单 */
    private void handleAdminListings(HttpExchange exchange) throws IOException {
        KsAuthManager.Session session = requireAdminAuth(exchange);
        if (session == null) return;
        List<ListingManager.Listing> listings = plugin.listingManager().getAllActiveListings();
        List<Map<String, Object>> data = new ArrayList<>();
        for (var l : listings) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", l.id());
            m.put("sellerName", l.sellerName());
            m.put("sellerUuid", l.sellerUuid().toString());
            m.put("itemMaterial", l.itemMaterial());
            m.put("chineseName", MaterialNames.get(l.itemMaterial()));
            m.put("quantity", l.quantity());
            m.put("unitPrice", l.unitPrice());
            m.put("totalPrice", l.totalPrice());
            m.put("listingType", l.listingType());
            m.put("listingMode", l.listingMode());
            m.put("listingAssetType", l.listingAssetType());
            m.put("assetRef", l.assetRef());
            m.put("createdAt", l.createdAt());
            data.add(m);
        }
        KsPluginBridge.sendJson(exchange, 200, gson.toJson(Map.of("listings", data, "count", data.size())));
    }

    /** 管理员强制撤销任意挂单，物品退回原卖家暂存箱 */
    @SuppressWarnings("unchecked")
    private void handleAdminForceCancel(HttpExchange exchange) throws IOException {
        KsAuthManager.Session session = requireAdminAuth(exchange);
        if (session == null) return;
        Map<String, Object> req = gson.fromJson(KsPluginBridge.readBody(exchange), Map.class);
        if (req == null) { KsPluginBridge.sendJson(exchange, 400, "{\"error\":\"无效请求\"}"); return; }
        String listingId = (String) req.get("listingId");
        if (listingId == null || listingId.isEmpty()) {
            KsPluginBridge.sendJson(exchange, 400, "{\"error\":\"缺少 listingId\"}"); return;
        }
        boolean ok = plugin.listingManager().cancelListingAdmin(listingId);
        if (ok) {
            plugin.getLogger().info("[管理员] " + session.playerName + " 强制撤销挂单 " + listingId);
            KsPluginBridge.sendJson(exchange, 200, "{\"message\":\"挂单已撤销，物品已退回卖家暂存箱\"}");
        } else {
            KsPluginBridge.sendJson(exchange, 404, "{\"error\":\"挂单不存在或已失效\"}");
        }
    }

    /** 管理员强制销毁挂单（物品不退回，直接标记取消） */
    @SuppressWarnings("unchecked")
    private void handleAdminForceDestroy(HttpExchange exchange) throws IOException {
        KsAuthManager.Session session = requireAdminAuth(exchange);
        if (session == null) return;
        Map<String, Object> req = gson.fromJson(KsPluginBridge.readBody(exchange), Map.class);
        if (req == null) { KsPluginBridge.sendJson(exchange, 400, "{\"error\":\"无效请求\"}"); return; }
        String listingId = (String) req.get("listingId");
        if (listingId == null || listingId.isEmpty()) {
            KsPluginBridge.sendJson(exchange, 400, "{\"error\":\"缺少 listingId\"}"); return;
        }
        boolean ok = plugin.listingManager().destroyListingAdmin(listingId);
        if (ok) {
            plugin.getLogger().info("[管理员] " + session.playerName + " 强制销毁挂单 " + listingId);
            KsPluginBridge.sendJson(exchange, 200, "{\"message\":\"挂单已销毁（物品未退回）\"}");
        } else {
            KsPluginBridge.sendJson(exchange, 404, "{\"error\":\"挂单不存在或已失效\"}");
        }
    }

    /** 管理员列出所有禁令 */
    private void handleAdminListBans(HttpExchange exchange) throws IOException {
        KsAuthManager.Session session = requireAdminAuth(exchange);
        if (session == null) return;
        var bans = plugin.banManager().listBans();
        KsPluginBridge.sendJson(exchange, 200, gson.toJson(Map.of("bans", bans, "count", bans.size())));
    }

    /** 管理员添加禁令 */
    @SuppressWarnings("unchecked")
    private void handleAdminAddBan(HttpExchange exchange) throws IOException {
        KsAuthManager.Session session = requireAdminAuth(exchange);
        if (session == null) return;
        Map<String, Object> req = gson.fromJson(KsPluginBridge.readBody(exchange), Map.class);
        if (req == null) { KsPluginBridge.sendJson(exchange, 400, "{\"error\":\"无效请求\"}"); return; }
        String playerUuidStr = (String) req.get("playerUuid");
        String playerName = (String) req.get("playerName");
        String banType = (String) req.get("banType");
        String reason = req.getOrDefault("reason", "").toString();
        long durationHours = req.get("durationHours") instanceof Number n ? n.longValue() : 0L;
        if (playerUuidStr == null || banType == null) {
            KsPluginBridge.sendJson(exchange, 400, "{\"error\":\"缺少 playerUuid 或 banType\"}"); return;
        }
        if (!banType.equals(BanManager.BAN_LISTING) && !banType.equals(BanManager.BAN_SELL_TO_OFFICIAL)
                && !banType.equals(BanManager.BAN_ALL_MARKET)) {
            KsPluginBridge.sendJson(exchange, 400, "{\"error\":\"无效 banType\"}"); return;
        }
        java.util.UUID playerUuid;
        try { playerUuid = resolvePlayerReference(playerUuidStr); }
        catch (IllegalArgumentException e) { KsPluginBridge.sendJson(exchange, 400, "{\"error\":\"无效 UUID\"}"); return; }
        if (playerName == null || playerName.isEmpty()) playerName = resolvePlayerName(playerUuid);
        String banId = plugin.banManager().addBan(playerUuid, playerName, banType, reason, durationHours, session.playerName);
        if (banId != null) {
            KsPluginBridge.sendJson(exchange, 200, gson.toJson(Map.of("message", "禁令已添加", "banId", banId)));
        } else {
            KsPluginBridge.sendJson(exchange, 500, "{\"error\":\"添加禁令失败\"}");
        }
    }

    /** 管理员移除禁令 */
    @SuppressWarnings("unchecked")
    private void handleAdminRemoveBan(HttpExchange exchange) throws IOException {
        KsAuthManager.Session session = requireAdminAuth(exchange);
        if (session == null) return;
        Map<String, Object> req = gson.fromJson(KsPluginBridge.readBody(exchange), Map.class);
        if (req == null) { KsPluginBridge.sendJson(exchange, 400, "{\"error\":\"无效请求\"}"); return; }
        String banId = (String) req.get("banId");
        if (banId == null || banId.isEmpty()) { KsPluginBridge.sendJson(exchange, 400, "{\"error\":\"缺少 banId\"}"); return; }
        boolean ok = plugin.banManager().removeBan(banId);
        if (ok) {
            plugin.getLogger().info("[管理员] " + session.playerName + " 解除禁令 " + banId);
            KsPluginBridge.sendJson(exchange, 200, "{\"message\":\"禁令已解除\"}");
        } else {
            KsPluginBridge.sendJson(exchange, 404, "{\"error\":\"禁令不存在\"}");
        }
    }

    /** 玩家查看自己的禁令 */
    private void handlePlayerBans(HttpExchange exchange) throws IOException {
        KsAuthManager.Session session = requireAuth(exchange);
        if (session == null) return;
        var bans = plugin.banManager().listPlayerBans(session.playerUuid);
        KsPluginBridge.sendJson(exchange, 200, gson.toJson(Map.of("bans", bans, "count", bans.size())));
    }

    /** 管理员查看市场波动全局设置 + 每个物品的漂移/导向/供需压力/趋势状态 */
    private void handleAdminPriceVolatility(HttpExchange exchange) throws IOException {
        KsAuthManager.Session session = requireAdminAuth(exchange);
        if (session == null) return;
        var snapshot = plugin.priceEngine().getVolatilitySnapshot();
        KsPluginBridge.sendJson(exchange, 200, gson.toJson(snapshot));
    }

    /** 管理员设置全局波动开关 + 波动率上限 + 刷新间隔（热重载）+ 测试模式 */
    @SuppressWarnings("unchecked")
    private void handleAdminPriceVolatilitySettings(HttpExchange exchange) throws IOException {
        KsAuthManager.Session session = requireAdminAuth(exchange);
        if (session == null) return;
        Map<String, Object> req = gson.fromJson(KsPluginBridge.readBody(exchange), Map.class);
        if (req == null) { KsPluginBridge.sendJson(exchange, 400, "{\"error\":\"无效请求\"}"); return; }
        boolean enabled = req.get("enabled") instanceof Boolean b ? b : true;
        double maxFluctuation = req.get("maxFluctuation") instanceof Number n ? n.doubleValue() : 0.3;
        plugin.priceEngine().setGlobalVolatility(enabled, maxFluctuation);

        if (req.get("refreshMinutes") instanceof Number rm) {
            int minutes = rm.intValue();
            plugin.priceEngine().setPriceRefreshMinutes(minutes);
            plugin.restartPriceRefreshTask();
        }
        if (req.get("testMode") instanceof Boolean tm) {
            plugin.priceEngine().setTestModeEnabled(tm);
        }

        plugin.getLogger().info("[管理员] " + session.playerName + " 设置市场波动: enabled=" + enabled +
                " maxFluctuation=" + maxFluctuation + " refreshMinutes=" + plugin.priceEngine().getPriceRefreshMinutes() +
                " testMode=" + plugin.priceEngine().isTestModeEnabled());
        KsPluginBridge.sendJson(exchange, 200, "{\"message\":\"市场波动设置已更新\"}");
    }

    /** 管理员对某物品设置/清除"大手导向"目标值 */
    @SuppressWarnings("unchecked")
    private void handleAdminPriceVolatilityBias(HttpExchange exchange) throws IOException {
        KsAuthManager.Session session = requireAdminAuth(exchange);
        if (session == null) return;
        Map<String, Object> req = gson.fromJson(KsPluginBridge.readBody(exchange), Map.class);
        if (req == null) { KsPluginBridge.sendJson(exchange, 400, "{\"error\":\"无效请求\"}"); return; }
        String material = (String) req.get("material");
        if (material == null || material.isEmpty()) { KsPluginBridge.sendJson(exchange, 400, "{\"error\":\"缺少 material\"}"); return; }
        double bias = req.get("trendBias") instanceof Number n ? n.doubleValue() : 0.0;
        plugin.priceEngine().setTrendBias(material, bias);
        plugin.getLogger().info("[管理员] " + session.playerName + " 设置 " + material + " 导向值: " + bias);
        KsPluginBridge.sendJson(exchange, 200, "{\"message\":\"导向已更新\"}");
    }

    /** 管理员查看全部功能开放状态 */
    private void handleAdminFeaturesGet(HttpExchange exchange) throws IOException {
        KsAuthManager.Session session = requireAdminAuth(exchange);
        if (session == null) return;
        KsPluginBridge.sendJson(exchange, 200, gson.toJson(Map.of("features", plugin.featureGate().snapshot())));
    }

    /** 管理员切换单个功能开放状态，立即生效（写回 config.yml） */
    private void handleAdminFeaturesSet(HttpExchange exchange) throws IOException {
        KsAuthManager.Session session = requireAdminAuth(exchange);
        if (session == null) return;
        @SuppressWarnings("unchecked")
        Map<String, Object> req = gson.fromJson(KsPluginBridge.readBody(exchange), Map.class);
        if (req == null) { KsPluginBridge.sendJson(exchange, 400, "{\"error\":\"无效请求\"}"); return; }
        String key = (String) req.get("key");
        Object valueObj = req.get("value");
        if (key == null || !FeatureGateManager.FEATURE_KEYS.contains(key) || !(valueObj instanceof Boolean)) {
            KsPluginBridge.sendJson(exchange, 400, "{\"error\":\"参数错误\"}");
            return;
        }
        plugin.featureGate().setOpen(key, (Boolean) valueObj);
        KsPluginBridge.sendJson(exchange, 200, gson.toJson(Map.of("features", plugin.featureGate().snapshot())));
    }

    /** Read or update delivery pricing. Configuration access is marshalled to the server thread. */
    @SuppressWarnings("unchecked")
    private void handleAdminTransportConfig(HttpExchange exchange) throws IOException {
        KsAuthManager.Session session = requireAdminAuth(exchange);
        if (session == null) return;
        boolean update = "POST".equalsIgnoreCase(exchange.getRequestMethod());
        if (!update && !"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
            KsPluginBridge.sendJson(exchange, 405, "{\"error\":\"Method not allowed\"}");
            return;
        }

        Map<String, Object> request = update
                ? gson.fromJson(KsPluginBridge.readBody(exchange), Map.class) : null;
        if (update && request == null) {
            KsPluginBridge.sendJson(exchange, 400, "{\"error\":\"无效请求\"}");
            return;
        }
        try {
            Map<String, Object> config = plugin.scheduler().callGlobal(() -> {
                if (update) {
                    Boolean enabled = request.get("enabled") instanceof Boolean value ? value : null;
                    double freeDistance = nonNegative(request, "freeDistance");
                    double baseFee = nonNegative(request, "baseFee");
                    double perBlockFee = nonNegative(request, "perBlockFee");
                    double crossWorldSurcharge = nonNegative(request, "crossWorldSurcharge");
                    double minimumFee = nonNegative(request, "minimumFee");
                    double maximumFee = nonNegative(request, "maximumFee");
                    if (enabled == null) throw new IllegalArgumentException("enabled 必须为布尔值");
                    if (maximumFee > 0 && maximumFee < minimumFee) {
                        throw new IllegalArgumentException("最高费用不能小于最低费用");
                    }
                    plugin.getConfig().set("transport.enabled", enabled);
                    plugin.getConfig().set("transport.free-distance", freeDistance);
                    plugin.getConfig().set("transport.base-fee", baseFee);
                    plugin.getConfig().set("transport.per-block-fee", perBlockFee);
                    plugin.getConfig().set("transport.cross-world-surcharge", crossWorldSurcharge);
                    plugin.getConfig().set("transport.minimum-fee", minimumFee);
                    plugin.getConfig().set("transport.maximum-fee", maximumFee);
                    plugin.saveConfig();
                }
                Map<String, Object> values = new LinkedHashMap<>();
                values.put("enabled", plugin.getConfig().getBoolean("transport.enabled", true));
                values.put("freeDistance", plugin.getConfig().getDouble("transport.free-distance", 8.0));
                values.put("baseFee", plugin.getConfig().getDouble("transport.base-fee", 10.0));
                values.put("perBlockFee", plugin.getConfig().getDouble("transport.per-block-fee", 0.05));
                values.put("crossWorldSurcharge", plugin.getConfig().getDouble("transport.cross-world-surcharge", 500.0));
                values.put("minimumFee", plugin.getConfig().getDouble("transport.minimum-fee", 1.0));
                values.put("maximumFee", plugin.getConfig().getDouble("transport.maximum-fee", 0.0));
                return values;
            }, Duration.ofSeconds(5));
            config.put("message", update ? "物流配置已保存" : "ok");
            KsPluginBridge.sendJson(exchange, 200, gson.toJson(config));
        } catch (Exception e) {
            Throwable cause = e.getCause() != null ? e.getCause() : e;
            String message = cause instanceof IllegalArgumentException ? cause.getMessage() : "物流配置更新失败";
            KsPluginBridge.sendJson(exchange, 400, gson.toJson(Map.of("error", message)));
        }
    }

    /** Read or update the tax-free portion of each player transfer. */
    @SuppressWarnings("unchecked")
    private void handleAdminTransferConfig(HttpExchange exchange) throws IOException {
        KsAuthManager.Session session = requireAdminAuth(exchange);
        if (session == null) return;
        boolean update = "POST".equalsIgnoreCase(exchange.getRequestMethod());
        if (!update && !"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
            KsPluginBridge.sendJson(exchange, 405, "{\"error\":\"Method not allowed\"}");
            return;
        }
        if (update) {
            String gate = checkPoliticGate();
            if (gate != null) {
                KsPluginBridge.sendJson(exchange, 403, gson.toJson(Map.of("error", gate)));
                return;
            }
        }

        Map<String, Object> request = update
                ? gson.fromJson(KsPluginBridge.readBody(exchange), Map.class) : null;
        if (update && request == null) {
            KsPluginBridge.sendJson(exchange, 400, "{\"error\":\"无效请求\"}");
            return;
        }
        try {
            Map<String, Object> config = plugin.scheduler().callGlobal(() -> {
                if (update) {
                    double taxFreeAmount = nonNegative(request, "taxFreeAmount");
                    plugin.getConfig().set("transfer.tax-free-amount", taxFreeAmount);
                    plugin.saveConfig();
                }
                Map<String, Object> values = new LinkedHashMap<>();
                values.put("taxFreeAmount", plugin.getConfig().getDouble("transfer.tax-free-amount", 1000.0));
                values.put("taxRate", plugin.getCategoryTaxRate("PLAYER_TRANSFER",
                        plugin.getConfig().getDouble("transfer.tax-rate-fallback", 0.01)));
                return values;
            }, Duration.ofSeconds(5));
            config.put("message", update ? "转账免税额已保存" : "ok");
            KsPluginBridge.sendJson(exchange, 200, gson.toJson(config));
        } catch (Exception e) {
            Throwable cause = e.getCause() != null ? e.getCause() : e;
            String message = cause instanceof IllegalArgumentException ? cause.getMessage() : "转账税务配置更新失败";
            KsPluginBridge.sendJson(exchange, 400, gson.toJson(Map.of("error", message)));
        }
    }

    private static double nonNegative(Map<String, Object> request, String key) {
        Object value = request.get(key);
        if (!(value instanceof Number number) || !Double.isFinite(number.doubleValue()) || number.doubleValue() < 0) {
            throw new IllegalArgumentException(key + " 必须为非负有限数字");
        }
        return number.doubleValue();
    }

    private void handleMyBanks(HttpExchange exchange) throws IOException {
        KsAuthManager.Session session = requireAuth(exchange);
        if (session == null) return;
        List<Map<String, Object>> banks = new ArrayList<>();
        queryRows(banks, "SELECT * FROM ks_bank_banks WHERE owner_uuids LIKE '%" +
            session.playerUuid.toString() + "%' OR id IN (SELECT bank_id FROM ks_bank_members WHERE player_uuid='" +
            session.playerUuid.toString() + "') ORDER BY created_at DESC", null);
        // 附加每个银行的账户余额
        for (var b : banks) {
            String bankId = (String) b.get("id");
            try (var conn = plugin.ksCore().dataStore().getConnection()) {
                if (conn != null) {
                    String accId = bankId + ":" + session.playerUuid; // 账户ID已统一为完整UUID（防前8位碰撞）
                    var rs = conn.createStatement().executeQuery(
                        "SELECT balance FROM ks_bank_accounts WHERE id='" + accId.replace("'", "''") + "'");
                    b.put("myBalance", rs.next() ? rs.getDouble("balance") : 0);
                }
            } catch (java.sql.SQLException ignored) {}
        }
        KsPluginBridge.sendJson(exchange, 200, gson.toJson(Map.of("banks", banks, "count", banks.size())));
    }

    /** 我的贷款 */
    private void handleMyLoans(HttpExchange exchange) throws IOException {
        KsAuthManager.Session session = requireAuth(exchange);
        if (session == null) return;
        List<Map<String, Object>> loans = new ArrayList<>();
        queryRows(loans, "SELECT * FROM ks_bank_loans WHERE borrower_uuid='" +
            session.playerUuid.toString() + "' ORDER BY issued_at DESC LIMIT 50", null);
        KsPluginBridge.sendJson(exchange, 200, gson.toJson(Map.of("loans", loans, "count", loans.size())));
    }

    /** 我的企业 */
    private void handleMyEnterprises(HttpExchange exchange) throws IOException {
        KsAuthManager.Session session = requireAuth(exchange);
        if (session == null) return;
        List<Map<String, Object>> ents = new ArrayList<>();
        queryRows(ents, "SELECT e.*, COALESCE(ca.balance,0) AS corporate_balance FROM ks_ent_enterprises e LEFT JOIN ks_ent_corporate_accounts ca ON ca.enterprise_id=e.id WHERE e.owner_uuids LIKE '%" +
            session.playerUuid.toString() + "%' OR e.id IN (SELECT enterprise_id FROM ks_ent_members WHERE player_uuid='" +
            session.playerUuid.toString() + "') ORDER BY e.created_at DESC LIMIT 50", null);
        KsPluginBridge.sendJson(exchange, 200, gson.toJson(Map.of("enterprises", ents, "count", ents.size())));
    }

    /** 我的税收记录 */
    private void handleMyTaxRecords(HttpExchange exchange) throws IOException {
        KsAuthManager.Session session = requireAuth(exchange);
        if (session == null) return;
        List<Map<String, Object>> records = new ArrayList<>();
        queryRows(records, "SELECT * FROM ks_tax_records WHERE payer_uuid='" +
            session.playerUuid.toString() + "' ORDER BY collected_at DESC LIMIT 50", null);
        KsPluginBridge.sendJson(exchange, 200, gson.toJson(Map.of("records", records, "count", records.size())));
    }

    /** 玩家存款 */
    @SuppressWarnings("unchecked")
    private void handleBankDeposit(HttpExchange exchange) throws IOException {
        KsAuthManager.Session session = requireAuth(exchange);
        if (session == null) return;
        Map<String, Object> req = gson.fromJson(KsPluginBridge.readBody(exchange), Map.class);
        if (req == null) { KsPluginBridge.sendJson(exchange, 400, "{\"error\":\"无效请求\"}"); return; }
        String bankId = (String) req.get("bankId");
        double amount = toDouble(req.get("amount"), 0);
        if (bankId == null || amount <= 0) {
            KsPluginBridge.sendJson(exchange, 400, "{\"error\":\"缺少 bankId 或金额无效\"}"); return;
        }
        Object result = callExtraManager("ks-eco-bank", "bankManager", "deposit",
                new Class[]{String.class, UUID.class, double.class}, bankId, session.playerUuid, amount);
        if (result != null && (boolean) result) {
            KsPluginBridge.sendJson(exchange, 200, "{\"message\":\"已存入 " + String.format("%.2f", amount) + "\"}");
        } else {
            KsPluginBridge.sendJson(exchange, 500, "{\"error\":\"存款失败（余额不足或银行不存在）\"}");
        }
    }

    /** 玩家取款 */
    @SuppressWarnings("unchecked")
    private void handleBankWithdraw(HttpExchange exchange) throws IOException {
        KsAuthManager.Session session = requireAuth(exchange);
        if (session == null) return;
        Map<String, Object> req = gson.fromJson(KsPluginBridge.readBody(exchange), Map.class);
        if (req == null) { KsPluginBridge.sendJson(exchange, 400, "{\"error\":\"无效请求\"}"); return; }
        String bankId = (String) req.get("bankId");
        double amount = toDouble(req.get("amount"), 0);
        if (bankId == null || amount <= 0) {
            KsPluginBridge.sendJson(exchange, 400, "{\"error\":\"缺少 bankId 或金额无效\"}"); return;
        }
        Object result = callExtraManager("ks-eco-bank", "bankManager", "withdraw",
                new Class[]{String.class, UUID.class, double.class}, bankId, session.playerUuid, amount);
        if (result != null && (boolean) result) {
            KsPluginBridge.sendJson(exchange, 200, "{\"message\":\"已取出 " + String.format("%.2f", amount) + "\"}");
        } else {
            KsPluginBridge.sendJson(exchange, 500, "{\"error\":\"取款失败（余额不足）\"}");
        }
    }

    // ================================================================
    // Bank APIs — 银行系统
    // ================================================================

    private void handleBankStats(HttpExchange exchange) throws IOException {
        Map<String, Object> s = new LinkedHashMap<>();
        s.put("moduleLoaded", hasExtra("ks-eco-bank"));
        queryInt(s, "SELECT COUNT(*) FROM ks_bank_banks", "bankCount");
        queryInt(s, "SELECT COUNT(*) FROM ks_bank_accounts", "accountCount");
        queryDouble(s, "SELECT COALESCE(SUM(total_assets),0) FROM ks_bank_banks WHERE status='ACTIVE'", "totalAssets");
        queryDouble(s, "SELECT COALESCE(SUM(remaining),0) FROM ks_bank_loans WHERE status='ACTIVE'", "totalLoans");
        try (var conn = plugin.ksCore().dataStore().getConnection()) {
            if (conn != null) {
                try (var rs = conn.createStatement().executeQuery(
                        "SELECT base_rate, reserve_requirement FROM ks_bank_cb_rates ORDER BY set_at DESC LIMIT 1")) {
                    if (rs.next()) {
                        s.put("baseRate", rs.getDouble("base_rate"));
                        s.put("reserveRequirement", rs.getDouble("reserve_requirement"));
                    }
                }
            }
        } catch (java.sql.SQLException ignored) {}
        KsPluginBridge.sendJson(exchange, 200, gson.toJson(s));
    }

    private void handleBankList(HttpExchange exchange) throws IOException {
        List<Map<String, Object>> list = new ArrayList<>();
        queryRows(list, "SELECT * FROM ks_bank_banks ORDER BY created_at DESC LIMIT 100", null);
        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("banks", list); resp.put("count", list.size());
        KsPluginBridge.sendJson(exchange, 200, gson.toJson(resp));
    }

    private void handleBankGameplayDashboard(HttpExchange exchange) throws IOException {
        KsAuthManager.Session session = requireAuth(exchange);
        if (session == null) return;
        Object result = callExtraManager("ks-eco-bank", "gameplayManager", "dashboard",
                new Class[]{UUID.class}, session.playerUuid);
        sendBankGameplayResult(exchange, result);
    }

    private void handleBankDepositProducts(HttpExchange exchange, String query) throws IOException {
        KsAuthManager.Session session = requireAuth(exchange);
        if (session == null) return;
        String bankId = KsPluginBridge.parseQuery(query).get("bankId");
        if (bankId == null || bankId.isBlank()) {
            KsPluginBridge.sendJson(exchange, 400, gson.toJson(Map.of("error", "请选择银行")));
            return;
        }
        Object result = callExtraManager("ks-eco-bank", "gameplayManager", "depositProducts",
                new Class[]{String.class}, bankId);
        if (result instanceof List<?> products) {
            KsPluginBridge.sendJson(exchange, 200, gson.toJson(Map.of("products", products)));
        } else {
            KsPluginBridge.sendJson(exchange, 503, gson.toJson(Map.of("error", "银行产品服务不可用")));
        }
    }

    private void handleBankTermOpen(HttpExchange exchange) throws IOException {
        KsAuthManager.Session session = requireAuth(exchange);
        if (session == null) return;
        Map<String, Object> request = gson.fromJson(KsPluginBridge.readBody(exchange), Map.class);
        if (request == null) {
            KsPluginBridge.sendJson(exchange, 400, gson.toJson(Map.of("error", "无效请求")));
            return;
        }
        String bankId = String.valueOf(request.getOrDefault("bankId", ""));
        String productCode = String.valueOf(request.getOrDefault("productCode", ""));
        double amount = toDouble(request.get("amount"), 0);
        boolean autoRenew = Boolean.TRUE.equals(request.get("autoRenew"));
        Object result = callExtraManager("ks-eco-bank", "gameplayManager", "openTermDeposit",
                new Class[]{String.class, UUID.class, String.class, double.class, boolean.class},
                bankId, session.playerUuid, productCode, amount, autoRenew);
        if (result instanceof Map<?, ?> values && Boolean.TRUE.equals(values.get("success"))) {
            auditLog("BANK_TERM_OPEN", session.playerUuid.toString(), session.playerName, "bank", bankId,
                    "product=" + productCode + " amount=" + amount + " autoRenew=" + autoRenew);
        }
        sendBankGameplayResult(exchange, result);
    }

    private void handleBankTermRedeem(HttpExchange exchange) throws IOException {
        KsAuthManager.Session session = requireAuth(exchange);
        if (session == null) return;
        Map<String, Object> request = gson.fromJson(KsPluginBridge.readBody(exchange), Map.class);
        String id = request == null ? "" : String.valueOf(request.getOrDefault("depositId", ""));
        Object result = callExtraManager("ks-eco-bank", "gameplayManager", "redeemTermDeposit",
                new Class[]{String.class, UUID.class}, id, session.playerUuid);
        if (result instanceof Map<?, ?> values && Boolean.TRUE.equals(values.get("success"))) {
            auditLog("BANK_TERM_REDEEM", session.playerUuid.toString(), session.playerName,
                    "term_deposit", id, "payout=" + values.get("payout"));
        }
        sendBankGameplayResult(exchange, result);
    }

    private void handleBankOperations(HttpExchange exchange, String query) throws IOException {
        KsAuthManager.Session session = requireAuth(exchange);
        if (session == null) return;
        String bankId = KsPluginBridge.parseQuery(query).get("bankId");
        try {
            if (bankId == null || !hasBankPermission(session, bankId, "VIEW_FINANCE")) {
                KsPluginBridge.sendJson(exchange, 403, gson.toJson(Map.of("error", "无权查看该银行经营数据")));
                return;
            }
        } catch (java.sql.SQLException failure) {
            KsPluginBridge.sendJson(exchange, 500, gson.toJson(Map.of("error", "权限检查失败")));
            return;
        }
        Object result = callExtraManager("ks-eco-bank", "gameplayManager", "bankOperations",
                new Class[]{String.class}, bankId);
        sendBankGameplayResult(exchange, result);
    }

    private void handleBankDividends(HttpExchange exchange, String query) throws IOException {
        KsAuthManager.Session session = requireAuth(exchange);
        if (session == null) return;
        String bankId = KsPluginBridge.parseQuery(query).get("bankId");
        try {
            if (bankId == null || !hasBankPermission(session, bankId, "VIEW_FINANCE")) {
                KsPluginBridge.sendJson(exchange, 403, gson.toJson(Map.of("error", "无权查看该银行分红")));
                return;
            }
        } catch (java.sql.SQLException failure) {
            KsPluginBridge.sendJson(exchange, 500, gson.toJson(Map.of("error", "权限检查失败")));
            return;
        }
        Object result = callExtraManager("ks-eco-bank", "gameplayManager", "dividends",
                new Class[]{String.class}, bankId);
        if (result instanceof List<?> batches) {
            KsPluginBridge.sendJson(exchange, 200, gson.toJson(Map.of("dividends", batches)));
        } else {
            KsPluginBridge.sendJson(exchange, 503, gson.toJson(Map.of("error", "分红服务不可用")));
        }
    }

    private void handleBankDividendDeclare(HttpExchange exchange) throws IOException {
        KsAuthManager.Session session = requireAuth(exchange);
        if (session == null) return;
        Map<String, Object> request = gson.fromJson(KsPluginBridge.readBody(exchange), Map.class);
        String bankId = request == null ? "" : String.valueOf(request.getOrDefault("bankId", ""));
        double amount = request == null ? 0 : toDouble(request.get("amount"), 0);
        try {
            if (!hasBankPermission(session, bankId, "DECLARE_DIVIDEND")) {
                KsPluginBridge.sendJson(exchange, 403, gson.toJson(Map.of("error", "无权宣布银行分红")));
                return;
            }
        } catch (java.sql.SQLException failure) {
            KsPluginBridge.sendJson(exchange, 500, gson.toJson(Map.of("error", "权限检查失败")));
            return;
        }
        Object result = callExtraManager("ks-eco-bank", "gameplayManager", "declareDividend",
                new Class[]{String.class, UUID.class, double.class}, bankId, session.playerUuid, amount);
        if (result instanceof Map<?, ?> values && Boolean.TRUE.equals(values.get("success"))) {
            auditLog("BANK_DIVIDEND_DECLARE", session.playerUuid.toString(), session.playerName,
                    "bank", bankId, "batch=" + values.get("batchId") + " amount=" + amount);
        }
        sendBankGameplayResult(exchange, result);
    }

    private void handleBankLoanProducts(HttpExchange exchange) throws IOException {
        KsAuthManager.Session session = requireAuth(exchange);
        if (session == null) return;
        Object result = callExtraManager("ks-eco-bank", "gameplayManager", "loanProducts", new Class[]{});
        if (result instanceof List<?> products) {
            KsPluginBridge.sendJson(exchange, 200, gson.toJson(Map.of("products", products)));
        } else {
            KsPluginBridge.sendJson(exchange, 503, gson.toJson(Map.of("error", "贷款产品服务不可用")));
        }
    }

    private void handleBankLoanCollateral(HttpExchange exchange, String query) throws IOException {
        KsAuthManager.Session session = requireAuth(exchange);
        if (session == null) return;
        Map<String, String> params = KsPluginBridge.parseQuery(query);
        String productType = params.getOrDefault("productType", "HOME");
        Object result = callExtraManager("ks-eco-bank", "gameplayManager", "eligibleCollateral",
                new Class[]{UUID.class, String.class}, session.playerUuid, productType);
        if (result instanceof List<?> assets) {
            KsPluginBridge.sendJson(exchange, 200, gson.toJson(Map.of("assets", assets)));
        } else {
            KsPluginBridge.sendJson(exchange, 503, gson.toJson(Map.of("error", "抵押资产服务不可用")));
        }
    }

    private void handleBankLoanQuote(HttpExchange exchange) throws IOException {
        KsAuthManager.Session session = requireAuth(exchange);
        if (session == null) return;
        Map<String, Object> request = gson.fromJson(KsPluginBridge.readBody(exchange), Map.class);
        if (request == null) {
            KsPluginBridge.sendJson(exchange, 400, gson.toJson(Map.of("error", "无效请求")));
            return;
        }
        String bankId = String.valueOf(request.getOrDefault("bankId", ""));
        double principal = toDouble(request.get("principal"), 0);
        int termDays = (int) toDouble(request.get("termDays"), 0);
        String productType = String.valueOf(request.getOrDefault("productType", "CONSUMER"));
        String repaymentType = String.valueOf(request.getOrDefault("repaymentType", "BULLET"));
        String collateralType = String.valueOf(request.getOrDefault("collateralType", ""));
        String collateralRef = String.valueOf(request.getOrDefault("collateralRef", ""));
        Object result = callExtraManager("ks-eco-bank", "gameplayManager", "productLoanQuote",
                new Class[]{String.class, UUID.class, double.class, int.class, String.class, String.class,
                        String.class, String.class},
                bankId, session.playerUuid, principal, termDays, productType, repaymentType,
                collateralType, collateralRef);
        sendBankGameplayResult(exchange, result);
    }

    private void handleBankLoanApplyQuoted(HttpExchange exchange) throws IOException {
        KsAuthManager.Session session = requireAuth(exchange);
        if (session == null) return;
        Map<String, Object> request = gson.fromJson(KsPluginBridge.readBody(exchange), Map.class);
        if (request == null) {
            KsPluginBridge.sendJson(exchange, 400, gson.toJson(Map.of("error", "无效请求")));
            return;
        }
        String bankId = String.valueOf(request.getOrDefault("bankId", ""));
        double principal = toDouble(request.get("principal"), 0);
        int termDays = (int) toDouble(request.get("termDays"), 0);
        double acceptedRate = toDouble(request.get("acceptedRate"), -1);
        long validUntil = (long) toDouble(request.get("validUntil"), 0);
        String productType = String.valueOf(request.getOrDefault("productType", "CONSUMER"));
        String repaymentType = String.valueOf(request.getOrDefault("repaymentType", "BULLET"));
        String purpose = String.valueOf(request.getOrDefault("purpose", ""));
        String collateralType = String.valueOf(request.getOrDefault("collateralType", ""));
        String collateralRef = String.valueOf(request.getOrDefault("collateralRef", ""));
        Object requestId = callExtraManager("ks-eco-bank", "bankManager", "requestProductLoan",
                new Class[]{String.class, UUID.class, String.class, double.class, int.class, double.class, long.class,
                        String.class, String.class, String.class, String.class, String.class},
                bankId, session.playerUuid, session.playerName, principal, termDays, acceptedRate, validUntil,
                productType, repaymentType, purpose, collateralType, collateralRef);
        if (requestId instanceof String id) {
            auditLog("LOAN_APPLY_QUOTED", session.playerUuid.toString(), session.playerName,
                    "bank", bankId, "request=" + id + " principal=" + principal + " term=" + termDays);
            KsPluginBridge.sendJson(exchange, 200,
                    gson.toJson(Map.of("success", true, "id", id, "message", "报价已锁定，贷款申请已提交")));
        } else {
            KsPluginBridge.sendJson(exchange, 400,
                    gson.toJson(Map.of("success", false, "error", "报价已失效或申请条件发生变化，请重新报价")));
        }
    }

    private void handleMyLoanRequestCancel(HttpExchange exchange) throws IOException {
        KsAuthManager.Session session = requireAuth(exchange);
        if (session == null) return;
        Map<String, Object> request = gson.fromJson(KsPluginBridge.readBody(exchange), Map.class);
        String requestId = request == null ? "" : String.valueOf(request.getOrDefault("requestId", ""));
        Object result = callExtraManager("ks-eco-bank", "bankManager", "cancelLoanRequest",
                new Class[]{String.class, UUID.class}, requestId, session.playerUuid);
        boolean success = Boolean.TRUE.equals(result);
        if (success) auditLog("BANK_LOAN_REQUEST_CANCEL", session.playerUuid.toString(), session.playerName,
                "loan_request", requestId, "cancelled by borrower");
        KsPluginBridge.sendJson(exchange, success ? 200 : 400, gson.toJson(success
                ? Map.of("success", true, "message", "贷款申请已撤销，抵押物已释放")
                : Map.of("success", false, "error", "申请不存在、已处理或无法撤销")));
    }

    private void handleBankLoanRestructureRequest(HttpExchange exchange) throws IOException {
        KsAuthManager.Session session = requireAuth(exchange);
        if (session == null) return;
        Map<String, Object> request = gson.fromJson(KsPluginBridge.readBody(exchange), Map.class);
        String loanId = request == null ? "" : String.valueOf(request.getOrDefault("loanId", ""));
        int requestedDays = request == null ? 0 : (int) toDouble(request.get("requestedDays"), 0);
        Object result = callExtraManager("ks-eco-bank", "gameplayManager", "requestRestructure",
                new Class[]{UUID.class, String.class, int.class}, session.playerUuid, loanId, requestedDays);
        if (result instanceof Map<?, ?> values && Boolean.TRUE.equals(values.get("success"))) {
            auditLog("BANK_LOAN_RESTRUCTURE_REQUEST", session.playerUuid.toString(), session.playerName,
                    "loan", loanId, "days=" + requestedDays + " fee=" + values.get("fee"));
        }
        sendBankGameplayResult(exchange, result);
    }

    private void handleBankLoanRestructureRequests(HttpExchange exchange, String query) throws IOException {
        KsAuthManager.Session session = requireAuth(exchange);
        if (session == null) return;
        Map<String, String> values = KsPluginBridge.parseQuery(query);
        String bankId = values.get("bankId");
        try {
            if (bankId == null || !hasBankPermission(session, bankId, "APPROVE_LOAN")) {
                KsPluginBridge.sendJson(exchange, 403, gson.toJson(Map.of("error", "无权查看展期申请")));
                return;
            }
        } catch (java.sql.SQLException failure) {
            KsPluginBridge.sendJson(exchange, 500, gson.toJson(Map.of("error", "权限检查失败")));
            return;
        }
        Object result = callExtraManager("ks-eco-bank", "gameplayManager", "restructureRequests",
                new Class[]{String.class, String.class}, bankId, values.get("status"));
        if (result instanceof List<?> requests) {
            KsPluginBridge.sendJson(exchange, 200, gson.toJson(Map.of("requests", requests)));
        } else {
            KsPluginBridge.sendJson(exchange, 503, gson.toJson(Map.of("error", "展期服务不可用")));
        }
    }

    private void handleBankLoanRestructureDecide(HttpExchange exchange) throws IOException {
        KsAuthManager.Session session = requireAuth(exchange);
        if (session == null) return;
        Map<String, Object> request = gson.fromJson(KsPluginBridge.readBody(exchange), Map.class);
        String requestId = request == null ? "" : String.valueOf(request.getOrDefault("requestId", ""));
        boolean approve = request != null && Boolean.TRUE.equals(request.get("approve"));
        try {
            String bankId = requestBankId("ks_bank_restructure_requests", requestId);
            if (bankId == null || !hasBankPermission(session, bankId, "APPROVE_LOAN")) {
                KsPluginBridge.sendJson(exchange, 403, gson.toJson(Map.of("error", "无权处理该展期申请")));
                return;
            }
        } catch (java.sql.SQLException failure) {
            KsPluginBridge.sendJson(exchange, 500, gson.toJson(Map.of("error", "权限检查失败")));
            return;
        }
        Object result = callExtraManager("ks-eco-bank", "gameplayManager", "decideRestructure",
                new Class[]{String.class, UUID.class, boolean.class}, requestId, session.playerUuid, approve);
        if (result instanceof Map<?, ?> values && Boolean.TRUE.equals(values.get("success"))) {
            auditLog("BANK_LOAN_RESTRUCTURE_DECIDE", session.playerUuid.toString(), session.playerName,
                    "restructure", requestId, "approve=" + approve);
        }
        sendBankGameplayResult(exchange, result);
    }

    private void handleBankPolicyEvents(HttpExchange exchange) throws IOException {
        KsAuthManager.Session session = requireAuth(exchange);
        if (session == null) return;
        Object result = callExtraManager("ks-eco-bank", "gameplayManager", "policyEvents", new Class[]{});
        if (result instanceof List<?> events) {
            KsPluginBridge.sendJson(exchange, 200, gson.toJson(Map.of("events", events)));
        } else {
            KsPluginBridge.sendJson(exchange, 503, gson.toJson(Map.of("error", "政策事件服务不可用")));
        }
    }

    private void handleBankPolicyEventCreate(HttpExchange exchange) throws IOException {
        KsAuthManager.Session session = requireAdminAuth(exchange);
        if (session == null) return;
        Map<String, Object> request = gson.fromJson(KsPluginBridge.readBody(exchange), Map.class);
        if (request == null) {
            KsPluginBridge.sendJson(exchange, 400, gson.toJson(Map.of("error", "无效请求")));
            return;
        }
        long now = System.currentTimeMillis() / 1000;
        String eventType = String.valueOf(request.getOrDefault("eventType", "RATE_CYCLE"));
        String title = String.valueOf(request.getOrDefault("title", ""));
        String description = String.valueOf(request.getOrDefault("description", ""));
        double rateModifier = toDouble(request.get("rateModifier"), 0);
        double riskModifier = toDouble(request.get("riskModifier"), 0);
        long startsAt = (long) toDouble(request.get("startsAt"), now);
        long endsAt = (long) toDouble(request.get("endsAt"), now + 7 * 86400L);
        Object result = callExtraManager("ks-eco-bank", "gameplayManager", "createPolicyEvent",
                new Class[]{String.class, String.class, String.class, double.class, double.class,
                        long.class, long.class, UUID.class}, eventType, title, description,
                rateModifier, riskModifier, startsAt, endsAt, session.playerUuid);
        if (result instanceof Map<?, ?> values && Boolean.TRUE.equals(values.get("success"))) {
            auditLog("BANK_POLICY_EVENT_CREATE", session.playerUuid.toString(), session.playerName,
                    "bank_policy_event", String.valueOf(values.get("id")), "type=" + eventType);
        }
        sendBankGameplayResult(exchange, result);
    }

    private void handleBankOperatingStatusSet(HttpExchange exchange) throws IOException {
        KsAuthManager.Session session = requireAdminAuth(exchange);
        if (session == null) return;
        Map<String, Object> request = gson.fromJson(KsPluginBridge.readBody(exchange), Map.class);
        String bankId = request == null ? "" : String.valueOf(request.getOrDefault("bankId", ""));
        String status = request == null ? "" : String.valueOf(request.getOrDefault("status", ""));
        Object result = callExtraManager("ks-eco-bank", "gameplayManager", "setOperatingStatus",
                new Class[]{String.class, String.class}, bankId, status);
        if (result instanceof Map<?, ?> values && Boolean.TRUE.equals(values.get("success"))) {
            auditLog("BANK_OPERATING_STATUS", session.playerUuid.toString(), session.playerName,
                    "bank", bankId, "status=" + status);
        }
        sendBankGameplayResult(exchange, result);
    }

    private void handleBankEquityPortfolio(HttpExchange exchange) throws IOException {
        KsAuthManager.Session session = requireAuth(exchange);
        if (session == null) return;
        Object result = callExtraManager("ks-eco-bank", "equityManager", "portfolio",
                new Class[]{UUID.class}, session.playerUuid);
        if (result instanceof List<?> portfolio) {
            KsPluginBridge.sendJson(exchange, 200, gson.toJson(Map.of("portfolio", portfolio)));
        } else {
            KsPluginBridge.sendJson(exchange, 503, gson.toJson(Map.of("error", "股权服务不可用")));
        }
    }

    private void handleBankEquityCapTable(HttpExchange exchange, String query) throws IOException {
        KsAuthManager.Session session = requireAuth(exchange);
        if (session == null) return;
        String bankId = KsPluginBridge.parseQuery(query).getOrDefault("bankId", "");
        Object result = callExtraManager("ks-eco-bank", "equityManager", "capTable",
                new Class[]{String.class}, bankId);
        sendBankGameplayResult(exchange, result);
    }

    private void handleBankEquityOfferings(HttpExchange exchange, String query) throws IOException {
        KsAuthManager.Session session = requireAuth(exchange);
        if (session == null) return;
        String bankId = KsPluginBridge.parseQuery(query).getOrDefault("bankId", "");
        Object result = callExtraManager("ks-eco-bank", "equityManager", "offerings",
                new Class[]{String.class}, bankId);
        if (result instanceof List<?> offerings) {
            KsPluginBridge.sendJson(exchange, 200, gson.toJson(Map.of("offerings", offerings)));
        } else {
            KsPluginBridge.sendJson(exchange, 503, gson.toJson(Map.of("error", "股份市场不可用")));
        }
    }

    private void handleBankEquityOfferingCreate(HttpExchange exchange) throws IOException {
        KsAuthManager.Session session = requireAuth(exchange);
        if (session == null) return;
        Map<String, Object> request = gson.fromJson(KsPluginBridge.readBody(exchange), Map.class);
        String bankId = request == null ? "" : String.valueOf(request.getOrDefault("bankId", ""));
        String offerType = request == null ? "" : String.valueOf(request.getOrDefault("offerType", "SECONDARY"));
        long shares = request == null ? 0 : (long) toDouble(request.get("shares"), 0);
        double price = request == null ? 0 : toDouble(request.get("pricePerShare"), 0);
        Object result = callExtraManager("ks-eco-bank", "equityManager", "createOffering",
                new Class[]{String.class, UUID.class, String.class, long.class, double.class},
                bankId, session.playerUuid, offerType, shares, price);
        if (result instanceof Map<?, ?> values && Boolean.TRUE.equals(values.get("success"))) {
            auditLog("BANK_SHARE_OFFER_CREATE", session.playerUuid.toString(), session.playerName,
                    "bank", bankId, "type=" + offerType + ",shares=" + shares);
        }
        sendBankGameplayResult(exchange, result);
    }

    private void handleBankEquityOfferingAccept(HttpExchange exchange) throws IOException {
        KsAuthManager.Session session = requireAuth(exchange);
        if (session == null) return;
        Map<String, Object> request = gson.fromJson(KsPluginBridge.readBody(exchange), Map.class);
        String offeringId = request == null ? "" : String.valueOf(request.getOrDefault("offeringId", ""));
        Object result = callExtraManager("ks-eco-bank", "equityManager", "acceptOffering",
                new Class[]{String.class, UUID.class}, offeringId, session.playerUuid);
        if (result instanceof Map<?, ?> values && Boolean.TRUE.equals(values.get("success"))) {
            auditLog("BANK_SHARE_OFFER_ACCEPT", session.playerUuid.toString(), session.playerName,
                    "share_offering", offeringId, "accepted");
        }
        sendBankGameplayResult(exchange, result);
    }

    private void handleBankEquityOfferingCancel(HttpExchange exchange) throws IOException {
        KsAuthManager.Session session = requireAuth(exchange);
        if (session == null) return;
        Map<String, Object> request = gson.fromJson(KsPluginBridge.readBody(exchange), Map.class);
        String offeringId = request == null ? "" : String.valueOf(request.getOrDefault("offeringId", ""));
        Object result = callExtraManager("ks-eco-bank", "equityManager", "cancelOffering",
                new Class[]{String.class, UUID.class}, offeringId, session.playerUuid);
        if (result instanceof Map<?, ?> values && Boolean.TRUE.equals(values.get("success"))) {
            auditLog("BANK_SHARE_OFFER_CANCEL", session.playerUuid.toString(), session.playerName,
                    "share_offering", offeringId, "cancelled");
        }
        sendBankGameplayResult(exchange, result);
    }

    private void handleBankResolutionFund(HttpExchange exchange) throws IOException {
        KsAuthManager.Session session = requireAdminAuth(exchange);
        if (session == null) return;
        Object result = callExtraManager("ks-eco-bank", "resolutionManager", "fundStatus", new Class[]{});
        sendBankGameplayResult(exchange, result);
    }

    private void handleBankResolutionCases(HttpExchange exchange) throws IOException {
        KsAuthManager.Session session = requireAdminAuth(exchange);
        if (session == null) return;
        Object result = callExtraManager("ks-eco-bank", "resolutionManager", "cases", new Class[]{});
        if (result instanceof List<?> cases) {
            KsPluginBridge.sendJson(exchange, 200, gson.toJson(Map.of("cases", cases)));
        } else {
            KsPluginBridge.sendJson(exchange, 503, gson.toJson(Map.of("error", "处置历史不可用")));
        }
    }

    private void handleBankResolutionFundTopUp(HttpExchange exchange) throws IOException {
        KsAuthManager.Session session = requireAdminAuth(exchange);
        if (session == null) return;
        Map<String, Object> request = gson.fromJson(KsPluginBridge.readBody(exchange), Map.class);
        double amount = request == null ? 0 : toDouble(request.get("amount"), 0);
        Object result = callExtraManager("ks-eco-bank", "resolutionManager", "recapitalizeFund",
                new Class[]{double.class}, amount);
        if (result instanceof Map<?, ?> values && Boolean.TRUE.equals(values.get("success"))) {
            auditLog("BANK_INSURANCE_FUND_TOP_UP", session.playerUuid.toString(), session.playerName,
                    "insurance_fund", "DEFAULT", "amount=" + amount);
        }
        sendBankGameplayResult(exchange, result);
    }

    private void handleBankResolutionPreview(HttpExchange exchange) throws IOException {
        KsAuthManager.Session session = requireAdminAuth(exchange);
        if (session == null) return;
        Map<String, Object> request = gson.fromJson(KsPluginBridge.readBody(exchange), Map.class);
        String bankId = request == null ? "" : String.valueOf(request.getOrDefault("bankId", ""));
        String bridgeBankId = request == null ? "" : String.valueOf(request.getOrDefault("bridgeBankId", ""));
        Object result = callExtraManager("ks-eco-bank", "resolutionManager", "preview",
                new Class[]{String.class, String.class}, bankId, bridgeBankId);
        sendBankGameplayResult(exchange, result);
    }

    private void handleBankResolutionExecute(HttpExchange exchange) throws IOException {
        KsAuthManager.Session session = requireAdminAuth(exchange);
        if (session == null) return;
        Map<String, Object> request = gson.fromJson(KsPluginBridge.readBody(exchange), Map.class);
        String bankId = request == null ? "" : String.valueOf(request.getOrDefault("bankId", ""));
        String bridgeBankId = request == null ? "" : String.valueOf(request.getOrDefault("bridgeBankId", ""));
        Object result = callExtraManager("ks-eco-bank", "resolutionManager", "resolve",
                new Class[]{String.class, String.class, UUID.class}, bankId, bridgeBankId, session.playerUuid);
        if (result instanceof Map<?, ?> values && Boolean.TRUE.equals(values.get("success"))) {
            auditLog("BANK_RESOLUTION_EXECUTE", session.playerUuid.toString(), session.playerName,
                    "bank", bankId, "bridge=" + bridgeBankId + ",case=" + values.get("caseId"));
        }
        sendBankGameplayResult(exchange, result);
    }

    private void handleBankLoanPayoutReviews(HttpExchange exchange) throws IOException {
        KsAuthManager.Session session = requireAdminAuth(exchange);
        if (session == null) return;
        Object result = callExtraManager("ks-eco-bank", "bankManager", "listLoanPayoutReviews",
                new Class[]{});
        if (result instanceof List<?> reviews) {
            KsPluginBridge.sendJson(exchange, 200, gson.toJson(Map.of("reviews", reviews)));
            return;
        }
        sendBankGameplayResult(exchange, result);
    }

    private void handleBankLoanPayoutReviewResolve(HttpExchange exchange) throws IOException {
        KsAuthManager.Session session = requireAdminAuth(exchange);
        if (session == null) return;
        Map<String, Object> request = gson.fromJson(KsPluginBridge.readBody(exchange), Map.class);
        String loanId = request == null ? "" : String.valueOf(request.getOrDefault("loanId", ""));
        String action = request == null ? "" : String.valueOf(request.getOrDefault("action", ""));
        Object result = callExtraManager("ks-eco-bank", "bankManager", "resolveLoanPayoutReview",
                new Class[]{String.class, String.class}, loanId, action);
        if (result instanceof Map<?, ?> values && Boolean.TRUE.equals(values.get("success"))) {
            auditLog("BANK_LOAN_PAYOUT_REVIEW_RESOLVED", session.playerUuid.toString(), session.playerName,
                    "bank_loan", loanId, "action=" + action);
        }
        sendBankGameplayResult(exchange, result);
    }

    private void sendBankGameplayResult(HttpExchange exchange, Object result) throws IOException {
        if (result instanceof Map<?, ?> values) {
            boolean success = !Boolean.FALSE.equals(values.get("success"))
                    && !Boolean.FALSE.equals(values.get("available"));
            KsPluginBridge.sendJson(exchange, success ? 200 : 400, gson.toJson(values));
        } else {
            KsPluginBridge.sendJson(exchange, 503, gson.toJson(Map.of("error", "银行玩法模块不可用")));
        }
    }

    @SuppressWarnings("unchecked")
    private void handleGuidanceStatus(HttpExchange exchange) throws IOException {
        KsAuthManager.Session session = requireAuth(exchange);
        if (session == null) return;
        Object result = callExtraManager("ks-eco-bank", "bankManager", "guidanceStatus",
                new Class[]{UUID.class}, session.playerUuid);
        if (result instanceof Map<?, ?> status) {
            KsPluginBridge.sendJson(exchange, 200, gson.toJson(status));
        } else {
            KsPluginBridge.sendJson(exchange, 503, gson.toJson(Map.of("error", "引导银行模块不可用")));
        }
    }

    @SuppressWarnings("unchecked")
    private void handleGuidanceClaim(HttpExchange exchange) throws IOException {
        KsAuthManager.Session session = requireAuth(exchange);
        if (session == null) return;
        try {
            Object result = plugin.scheduler().callGlobal(() -> callExtraManager(
                    "ks-eco-bank", "bankManager", "claimStarterLoan", new Class[]{UUID.class}, session.playerUuid),
                    Duration.ofSeconds(10));
            if (result instanceof Map<?, ?> raw) {
                Map<String, Object> response = new LinkedHashMap<>();
                for (Map.Entry<?, ?> entry : raw.entrySet()) response.put(String.valueOf(entry.getKey()), entry.getValue());
                boolean success = Boolean.TRUE.equals(response.get("success"));
                if (success) {
                    auditLog("GUIDANCE_LOAN_CLAIM", session.playerUuid.toString(), session.playerName, "bank", "GUIDE-BANK",
                            "starter loan=" + response.get("loanId") + " amount=" + response.get("amount"));
                }
                KsPluginBridge.sendJson(exchange, success ? 200 : 400, gson.toJson(response));
            } else {
                KsPluginBridge.sendJson(exchange, 503, gson.toJson(Map.of("error", "引导银行模块不可用")));
            }
        } catch (Exception e) {
            plugin.getLogger().warning("[ks-Eco] 引导贷款主线程调用失败: " + e.getMessage());
            KsPluginBridge.sendJson(exchange, 503, gson.toJson(Map.of("error", "服务器繁忙，请稍后重试")));
        }
    }

    @SuppressWarnings("unchecked")
    private void handleGuidanceConfigGet(HttpExchange exchange) throws IOException {
        KsAuthManager.Session session = requireAdminAuth(exchange);
        if (session == null) return;
        Object result = callExtraManager("ks-eco-bank", "bankManager", "guidanceConfig", new Class[]{}, (Object[]) new Object[]{});
        if (result instanceof Map<?, ?> config) {
            Map<String, Object> response = new LinkedHashMap<>();
            for (Map.Entry<?, ?> entry : config.entrySet()) response.put(String.valueOf(entry.getKey()), entry.getValue());
            try (var conn = plugin.ksCore().dataStore().getConnection();
                 var ps = conn == null ? null : conn.prepareStatement("SELECT total_assets,status FROM ks_bank_banks WHERE id='GUIDE-BANK'")) {
                if (ps != null) try (var rs = ps.executeQuery()) {
                    if (rs.next()) {
                        response.put("assets", rs.getDouble("total_assets"));
                        response.put("bankStatus", rs.getString("status"));
                    }
                }
            } catch (java.sql.SQLException ignored) {}
            KsPluginBridge.sendJson(exchange, 200, gson.toJson(response));
        } else {
            KsPluginBridge.sendJson(exchange, 503, gson.toJson(Map.of("error", "引导银行模块不可用")));
        }
    }

    @SuppressWarnings("unchecked")
    private void handleGuidanceConfigSet(HttpExchange exchange) throws IOException {
        KsAuthManager.Session session = requireAdminAuth(exchange);
        if (session == null) return;
        Map<String, Object> request = gson.fromJson(KsPluginBridge.readBody(exchange), Map.class);
        if (request == null || request.isEmpty()) {
            KsPluginBridge.sendJson(exchange, 400, gson.toJson(Map.of("error", "缺少政策参数")));
            return;
        }
        Object result = callExtraManager("ks-eco-bank", "bankManager", "updateGuidanceConfig",
                new Class[]{Map.class}, request);
        if (Boolean.TRUE.equals(result)) {
            auditLog("GUIDANCE_BANK_POLICY", session.playerUuid.toString(), session.playerName, "bank", "GUIDE-BANK",
                    "updated keys=" + String.join(",", request.keySet()));
            KsPluginBridge.sendJson(exchange, 200, gson.toJson(Map.of("message", "引导银行政策已保存")));
        } else {
            KsPluginBridge.sendJson(exchange, 400, gson.toJson(Map.of("error", "政策参数无效或引导银行模块不可用")));
        }
    }

    @SuppressWarnings("unchecked")
    private void handleBankCreate(HttpExchange exchange) throws IOException {
        KsAuthManager.Session session = requireAuth(exchange);
        if (session == null) return;
        Map<String, Object> req = gson.fromJson(KsPluginBridge.readBody(exchange), Map.class);
        if (req == null) { KsPluginBridge.sendJson(exchange, 400, "{\"error\":\"无效请求\"}"); return; }
        String name = req.get("name") == null ? "" : req.get("name").toString().trim();
        // 只有管理员可以创建中央银行；普通玩家只能创建商业银行
        String type = req.getOrDefault("type", "COMMERCIAL").toString();
        if ("CENTRAL".equalsIgnoreCase(type) && !session.isAdmin) {
            KsPluginBridge.sendJson(exchange, 403, "{\"error\":\"仅管理员可创建中央银行\"}");
            return;
        }
        List<String> ownerStrs = (List<String>) req.get("ownerUuids");
        double capital = toDouble(req.get("initialCapital"), 10000);
        if (name.length() < 2 || name.length() > 32 || ownerStrs == null || ownerStrs.isEmpty()) {
            KsPluginBridge.sendJson(exchange, 400, "{\"error\":\"缺少 name/ownerUuids\"}"); return;
        }
        // Replace __self__ placeholder with session UUID
        LinkedHashSet<UUID> ownerSet = new LinkedHashSet<>();
        ownerSet.add(session.playerUuid);
        for (String s : ownerStrs) {
            if ("__self__".equals(s.trim())) {
                ownerSet.add(session.playerUuid);
            } else {
                try {
                    ownerSet.add(resolvePlayerReference(s));
                } catch (IllegalArgumentException e) {
                    KsPluginBridge.sendJson(exchange, 400, gson.toJson(Map.of("error", e.getMessage())));
                    return;
                }
            }
        }
        List<UUID> owners = new ArrayList<>(ownerSet);
        int maxOwners = (int) getEconomicSetting("enterprise_max_owners", 4);
        if (owners.size() > maxOwners) {
            KsPluginBridge.sendJson(exchange, 400, gson.toJson(Map.of("error", "银行所有者人数不能超过 " + maxOwners)));
            return;
        }
        // 反射调用 BankManager.createBank：普通玩家走 5 参重载（真扣初始资本 + 资质门槛），
        // 管理员走 4 参重载（不扣款，用于官方/系统银行）
        if (!Double.isFinite(capital) || capital < getBankSetting("bank_min_capital", 50000)) {
            KsPluginBridge.sendJson(exchange, 400, gson.toJson(Map.of("error", "Initial capital is below the bank minimum.")));
            return;
        }
        if (!session.isAdmin && owners.size() > 1) {
            createPendingBankCreation(exchange, session, name, type, owners, capital);
            return;
        }
        Object bank = session.isAdmin
                ? callExtraManager("ks-eco-bank", "bankManager", "createBank",
                        new Class[]{String.class, String.class, List.class, double.class},
                        name, type, owners, capital)
                : callExtraManager("ks-eco-bank", "bankManager", "createBank",
                        new Class[]{String.class, String.class, List.class, double.class, UUID.class},
                        name, type, owners, capital, session.playerUuid);
        if (bank == null) {
            String err = lastReflectError != null ? lastReflectError
                    : "创建失败：余额不足 / 初始资本低于门槛（默认 5 万）/ 模块未加载";
            KsPluginBridge.sendJson(exchange, 500, "{\"error\":\"创建失败: " + err.replace("\"", "'") + "\"}");
        } else {
            String bid = gson.fromJson(gson.toJson(bank), Map.class).get("id").toString();
            auditLog("BANK_CREATE", session.playerUuid.toString(), session.playerName, "bank", bid,
                "银行名称: " + name + " | 类型: " + type + " | 初始资本: " + String.format("%.2f", capital));
            KsPluginBridge.sendJson(exchange, 200, gson.toJson(bank));
        }
    }

    private void handleCbRatesGet(HttpExchange exchange) throws IOException {
        Map<String, Object> s = new LinkedHashMap<>();
        try (var conn = plugin.ksCore().dataStore().getConnection()) {
            if (conn != null) {
                try (var rs = conn.createStatement().executeQuery(
                        "SELECT base_rate, reserve_requirement FROM ks_bank_cb_rates ORDER BY set_at DESC LIMIT 1")) {
                    if (rs.next()) {
                        s.put("baseRate", rs.getDouble("base_rate"));
                        s.put("reserveRequirement", rs.getDouble("reserve_requirement"));
                    }
                }
            }
        } catch (java.sql.SQLException ignored) {}
        // 利率区间（来自 CentralBankManager）
        Object rateMin = callExtraManager("ks-eco-bank", "centralBankManager", "getRateMin", new Class[]{}, (Object[]) new Object[]{});
        Object rateMax = callExtraManager("ks-eco-bank", "centralBankManager", "getRateMax", new Class[]{}, (Object[]) new Object[]{});
        if (rateMin instanceof Number) s.put("rateMin", ((Number) rateMin).doubleValue());
        if (rateMax instanceof Number) s.put("rateMax", ((Number) rateMax).doubleValue());
        // 央行配置键：利率浮动限制 / 利息结算周期天数 / 开行最低资本
        try (var conn = plugin.ksCore().dataStore().getConnection()) {
            if (conn != null) {
                try (var rs = conn.createStatement().executeQuery(
                        "SELECT config_key, config_value FROM ks_bank_cb_config WHERE config_key IN ('rate_adjust_limit','interest_period_days','bank_min_capital')")) {
                    while (rs.next()) {
                        switch (rs.getString("config_key")) {
                            case "rate_adjust_limit" -> s.put("rateAdjustLimit", rs.getDouble("config_value"));
                            case "interest_period_days" -> s.put("interestPeriodDays", rs.getDouble("config_value"));
                            case "bank_min_capital" -> s.put("bankMinCapital", rs.getDouble("config_value"));
                        }
                    }
                }
            }
        } catch (java.sql.SQLException ignored) {}
        s.putIfAbsent("rateAdjustLimit", 0.02);
        s.putIfAbsent("interestPeriodDays", 7);
        s.putIfAbsent("bankMinCapital", 50000);
        KsPluginBridge.sendJson(exchange, 200, gson.toJson(s));
    }

    @SuppressWarnings("unchecked")
    private void handleCbRatesSet(HttpExchange exchange) throws IOException {
        KsAuthManager.Session session = requireAdminAuth(exchange);
        if (session == null) return;
        String gate = checkPoliticGate();
        if (gate != null) { KsPluginBridge.sendJson(exchange, 403, gson.toJson(Map.of("error", gate))); return; }
        Map<String, Object> req = gson.fromJson(KsPluginBridge.readBody(exchange), Map.class);
        if (req == null) { KsPluginBridge.sendJson(exchange, 400, "{\"error\":\"无效请求\"}"); return; }
        if (req.containsKey("baseRate")) {
            double rate = toDouble(req.get("baseRate"), -1);
            if (rate < -1.0 || rate > 1.0) {
                KsPluginBridge.sendJson(exchange, 400, "{\"error\":\"基准利率必须在 -1.0 到 1.0 范围内（-100% 到 +100%）\"}");
                return;
            }
            callExtraManager("ks-eco-bank", "centralBankManager", "setBaseRate",
                    new Class[]{double.class}, rate);
        }
        if (req.containsKey("reserveRequirement")) {
            double rr = toDouble(req.get("reserveRequirement"), -1);
            if (rr < 0 || rr > 1) {
                KsPluginBridge.sendJson(exchange, 400, "{\"error\":\"准备金率必须在 0-1 范围内\"}");
                return;
            }
            callExtraManager("ks-eco-bank", "centralBankManager", "setReserveRequirement",
                    new Class[]{double.class}, rr);
        }
        if (req.containsKey("rateAdjustLimit")) {
            double limit = toDouble(req.get("rateAdjustLimit"), 0.02);
            if (limit < 0 || limit > 1) {
                KsPluginBridge.sendJson(exchange, 400, "{\"error\":\"利率浮动限制必须在 0-1 范围内\"}");
                return;
            }
            // 保存浮动限制到数据库
            try (var conn = plugin.ksCore().dataStore().getConnection()) {
                if (conn != null) {
                    upsertBankConfig(conn, "rate_adjust_limit", Double.toString(limit));
                }
            } catch (java.sql.SQLException e) {
                KsPluginBridge.sendJson(exchange, 500, "{\"error\":\"保存利率浮动限制失败\"}");
                return;
            }
        }
        if (req.containsKey("interestPeriodDays")) {
            double days = toDouble(req.get("interestPeriodDays"), 7);
            if (days <= 0 || days > 365) {
                KsPluginBridge.sendJson(exchange, 400, "{\"error\":\"利息结算周期必须在 0-365 天之间（支持小数，0.01≈15分钟用于测试）\"}");
                return;
            }
            try (var conn = plugin.ksCore().dataStore().getConnection()) {
                if (conn != null) {
                    upsertBankConfig(conn, "interest_period_days", Double.toString(days));
                }
            } catch (java.sql.SQLException e) {
                KsPluginBridge.sendJson(exchange, 500, "{\"error\":\"保存利息结算周期失败\"}");
                return;
            }
        }
        if (req.containsKey("bankMinCapital")) {
            double minCap = toDouble(req.get("bankMinCapital"), 50000);
            if (minCap < 0) {
                KsPluginBridge.sendJson(exchange, 400, "{\"error\":\"开行最低资本不能为负\"}");
                return;
            }
            try (var conn = plugin.ksCore().dataStore().getConnection()) {
                if (conn != null) {
                    upsertBankConfig(conn, "bank_min_capital", Double.toString(minCap));
                }
            } catch (java.sql.SQLException e) {
                KsPluginBridge.sendJson(exchange, 500, "{\"error\":\"保存开行最低资本失败\"}");
                return;
            }
        }
        KsPluginBridge.sendJson(exchange, 200, "{\"message\":\"央行利率已更新\"}");
        auditLog("CB_RATES_SET", session.playerUuid.toString(), session.playerName, "central_bank", "CB",
            "baseRate=" + (req.containsKey("baseRate") ? req.get("baseRate").toString() : "—") +
            " reserveReq=" + (req.containsKey("reserveRequirement") ? req.get("reserveRequirement").toString() : "—"));
    }

    @SuppressWarnings("unchecked")
    private void handleCbSetRateRange(HttpExchange exchange) throws IOException {
        KsAuthManager.Session s = requireAdminAuth(exchange);
        if (s == null) return;
        Map<String, Object> req = gson.fromJson(KsPluginBridge.readBody(exchange), Map.class);
        if (req == null) { KsPluginBridge.sendJson(exchange, 400, "{\"error\":\"无效请求\"}"); return; }
        double min = toDouble(req.get("rateMin"), -1);
        double max = toDouble(req.get("rateMax"), -1);
        if (min < 0 || min > 1 || max < 0 || max > 1) {
            KsPluginBridge.sendJson(exchange, 400, "{\"error\":\"利率区间必须在 0-1\"}");
            return;
        }
        if (max < min) {
            KsPluginBridge.sendJson(exchange, 400, "{\"error\":\"上限不能小于下限\"}");
            return;
        }
        callExtraManager("ks-eco-bank", "centralBankManager", "setRateRange",
                new Class[]{double.class, double.class}, min, max);
        auditLog("CB_RATE_RANGE", s.playerUuid.toString(), s.playerName, "central_bank", "CB",
                "rateMin=" + min + ",rateMax=" + max);
        KsPluginBridge.sendJson(exchange, 200, "{\"message\":\"利率区间已更新\"}");
    }

    private void handleCbLoansList(HttpExchange exchange, String query) throws IOException {
        KsAuthManager.Session session = requireAdminAuth(exchange);
        if (session == null) return;
        Map<String, String> q = KsPluginBridge.parseQuery(query);
        String bankId = q.get("bankId");
        Object res = callExtraManager("ks-eco-bank", "cbLoanManager", "listLoans",
                new Class[]{String.class, boolean.class},
                bankId, "1".equals(q.get("includeRepaid"))); // 旧写法多了个 ! 把语义反了
        List<Map<String, Object>> out = new ArrayList<>();
        if (res instanceof List<?> lst) {
            for (Object o : lst) {
                if (o instanceof Map<?,?> m) out.add(toStrMap(m));
            }
        }
        KsPluginBridge.sendJson(exchange, 200, gson.toJson(Map.of("loans", out)));
    }

    @SuppressWarnings("unchecked")
    private void handleCbLoanRepay(HttpExchange exchange) throws IOException {
        KsAuthManager.Session s = requireAdminAuth(exchange);
        if (s == null) return;
        Map<String, Object> req = gson.fromJson(KsPluginBridge.readBody(exchange), Map.class);
        if (req == null) { KsPluginBridge.sendJson(exchange, 400, "{\"error\":\"无效请求\"}"); return; }
        String id = (String) req.get("id");
        if (id == null) { KsPluginBridge.sendJson(exchange, 400, "{\"error\":\"缺少 id\"}"); return; }
        Object res = callExtraManager("ks-eco-bank", "cbLoanManager", "repay",
                new Class[]{String.class}, id);
        if (res instanceof Boolean b && b) {
            auditLog("CB_LOAN_REPAY", s.playerUuid.toString(), s.playerName, "cb_loan", id, "");
            KsPluginBridge.sendJson(exchange, 200, "{\"message\":\"已还款\"}");
        } else {
            KsPluginBridge.sendJson(exchange, 500, "{\"error\":\"还款失败（银行余额不足或贷款已还）\"}");
        }
    }

    /** 央行向商业银行注入流动性（仅限央行→商业银行，不可注入普通玩家） */
    @SuppressWarnings("unchecked")
    private void handleCbInject(HttpExchange exchange) throws IOException {
        KsAuthManager.Session session = requireAdminAuth(exchange);
        if (session == null) return;
        String gate = checkPoliticGate();
        if (gate != null) { KsPluginBridge.sendJson(exchange, 403, gson.toJson(Map.of("error", gate))); return; }
        Map<String, Object> req = gson.fromJson(KsPluginBridge.readBody(exchange), Map.class);
        if (req == null) { KsPluginBridge.sendJson(exchange, 400, "{\"error\":\"无效请求\"}"); return; }
        String bankId = (String) req.get("bankId");
        double amount = toDouble(req.get("amount"), 0);
        if (bankId == null || amount <= 0) {
            KsPluginBridge.sendJson(exchange, 400, "{\"error\":\"缺少 bankId 或金额无效\"}");
            return;
        }
        // 验证目标银行为商业银行
        try (var conn = plugin.ksCore().dataStore().getConnection()) {
            if (conn == null) { KsPluginBridge.sendJson(exchange, 500, "{\"error\":\"数据库未连接\"}"); return; }
            var rs = conn.createStatement().executeQuery(
                "SELECT type FROM ks_bank_banks WHERE id='" + bankId.replace("'", "''") + "'");
            if (!rs.next()) {
                KsPluginBridge.sendJson(exchange, 404, "{\"error\":\"银行不存在\"}");
                return;
            }
            String bankType = rs.getString("type");
            if ("CENTRAL".equalsIgnoreCase(bankType)) {
                KsPluginBridge.sendJson(exchange, 400, "{\"error\":\"不能向央行自身注入流动性\"}");
                return;
            }
        } catch (java.sql.SQLException e) {
            KsPluginBridge.sendJson(exchange, 500, "{\"error\":\"查询银行失败: " + e.getMessage() + "\"}");
            return;
        }
        Object result = callExtraManager("ks-eco-bank", "centralBankManager", "injectLiquidity",
                new Class[]{String.class, double.class, String.class}, bankId, amount,
                (String) req.getOrDefault("mode", "GRANT"));
        if (result == null) {
            KsPluginBridge.sendJson(exchange, 200, "{\"message\":\"已向银行 " + bankId + " 注入流动性 " + String.format("%.2f", amount) + "\"}");
            auditLog("CB_INJECT", session.playerUuid.toString(), session.playerName, "bank", bankId,
                "注入流动性 " + String.format("%.2f", amount) + " mode=" + req.getOrDefault("mode", "GRANT"));
        } else {
            KsPluginBridge.sendJson(exchange, 500, "{\"error\":\"注资失败（目标银行不存在或为央行自身）\"}");
        }
    }

    @SuppressWarnings("unchecked")
    private void handleLoanApply(HttpExchange exchange) throws IOException {
        KsAuthManager.Session session = requireAuth(exchange);
        if (session == null) return;
        Map<String, Object> req = gson.fromJson(KsPluginBridge.readBody(exchange), Map.class);
        if (req == null) { KsPluginBridge.sendJson(exchange, 400, "{\"error\":\"无效请求\"}"); return; }
        String bankId = (String) req.get("bankId");
        double principal = toDouble(req.get("principal"), 1000);
        int termDays = (int) toDouble(req.get("termDays"), 30);
        if (bankId == null || principal <= 0) {
            KsPluginBridge.sendJson(exchange, 400, "{\"error\":\"缺少 bankId 或贷款金额无效\"}"); return;
        }
        Object result = callExtraManager("ks-eco-bank", "bankManager", "requestLoan",
                new Class[]{String.class, UUID.class, String.class, double.class, int.class},
                bankId, session.playerUuid, session.playerName, principal, termDays);
        if (result != null) {
            auditLog("LOAN_APPLY", session.playerUuid.toString(), session.playerName, "bank", bankId,
                "申请贷款 " + String.format("%.2f", principal) + " (" + termDays + "天)");
            KsPluginBridge.sendJson(exchange, 200, "{\"id\":\"" + result + "\",\"message\":\"申请已提交，等待银行审批\"}");
        } else {
            KsPluginBridge.sendJson(exchange, 500, "{\"error\":\"申请失败（银行不存在或模块未加载）\"}");
        }
    }

    private void handleMyLoanRequests(HttpExchange exchange) throws IOException {
        KsAuthManager.Session session = requireAuth(exchange);
        if (session == null) return;
        Object result = callExtraManager("ks-eco-bank", "bankManager", "myLoanRequests",
                new Class[]{UUID.class}, session.playerUuid);
        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("requests", result != null ? result : new ArrayList<>());
        KsPluginBridge.sendJson(exchange, 200, gson.toJson(resp));
    }

    private boolean canApproveBankLoan(KsAuthManager.Session session, String bankId) throws java.sql.SQLException {
        return hasBankPermission(session, bankId, "APPROVE_LOAN");
    }

    private boolean canIssueBankLoan(KsAuthManager.Session session, String bankId) throws java.sql.SQLException {
        return hasBankPermission(session, bankId, "ISSUE_LOAN");
    }

    private String requestBankId(String table, String requestId) throws java.sql.SQLException {
        if (requestId == null || requestId.isBlank()) return null;
        if (!Set.of("ks_bank_loan_requests", "ks_bank_enterprise_loan_requests",
                "ks_bank_restructure_requests").contains(table)) {
            throw new java.sql.SQLException("Unsupported loan request table");
        }
        try (var conn = plugin.ksCore().dataStore().getConnection()) {
            if (conn == null) return null;
            try (var ps = conn.prepareStatement("SELECT bank_id FROM " + table + " WHERE id=? AND status='PENDING'")) {
                ps.setString(1, requestId);
                try (var rs = ps.executeQuery()) {
                    return rs.next() ? rs.getString(1) : null;
                }
            }
        }
    }

    private boolean hasBankPermission(KsAuthManager.Session session, String bankId, String permission) throws java.sql.SQLException {
        if (session.isAdmin) return true;
        var provider = plugin.bankAccessProvider();
        if (provider != null) return provider.hasPermission(bankId, session.playerUuid, permission);
        try (var conn = plugin.ksCore().dataStore().getConnection()) {
            if (conn == null) return false;
            var rs = conn.createStatement().executeQuery(
                "SELECT owner_uuids FROM ks_bank_banks WHERE id='" + bankId.replace("'", "''") + "'");
            if (!rs.next()) return false;
            if (ownerListContains(rs.getString("owner_uuids"), session.playerUuid)) return true;
            var rs2 = conn.createStatement().executeQuery(
                "SELECT COUNT(*) FROM ks_bank_permissions WHERE bank_id='" + bankId.replace("'", "''") +
                "' AND player_uuid='" + session.playerUuid.toString() + "' AND permission='" + permission.replace("'", "''") + "'");
            return rs2.next() && rs2.getInt(1) > 0;
        }
    }

    private void handleLoanRequestsList(HttpExchange exchange, String query) throws IOException {
        KsAuthManager.Session session = requireAuth(exchange);
        if (session == null) return;
        Map<String, String> params = KsPluginBridge.parseQuery(query);
        String bankId = params.get("bankId");
        if (bankId == null) { KsPluginBridge.sendJson(exchange, 400, "{\"error\":\"缺少 bankId\"}"); return; }
        try {
            if (!canApproveBankLoan(session, bankId)) {
                KsPluginBridge.sendJson(exchange, 403, "{\"error\":\"需要银行所有者或 APPROVE_LOAN 权限\"}"); return;
            }
        } catch (java.sql.SQLException e) {
            KsPluginBridge.sendJson(exchange, 500, "{\"error\":\"权限检查失败: " + e.getMessage() + "\"}"); return;
        }
        String status = params.getOrDefault("status", "PENDING");
        Object result = callExtraManager("ks-eco-bank", "bankManager", "listLoanRequests",
                new Class[]{String.class, String.class}, bankId, status.isEmpty() ? null : status);
        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("requests", result != null ? result : new ArrayList<>());
        KsPluginBridge.sendJson(exchange, 200, gson.toJson(resp));
    }

    @SuppressWarnings("unchecked")
    private void handleLoanRequestDecide(HttpExchange exchange, boolean approve) throws IOException {
        KsAuthManager.Session session = requireAuth(exchange);
        if (session == null) return;
        Map<String, Object> req = gson.fromJson(KsPluginBridge.readBody(exchange), Map.class);
        if (req == null) { KsPluginBridge.sendJson(exchange, 400, "{\"error\":\"无效请求\"}"); return; }
        String requestId = (String) req.get("requestId");
        String bankId = (String) req.get("bankId");
        if (requestId == null || bankId == null) {
            KsPluginBridge.sendJson(exchange, 400, "{\"error\":\"缺少 requestId/bankId\"}"); return;
        }
        try {
            String actualBankId = requestBankId("ks_bank_loan_requests", requestId);
            if (actualBankId == null) {
                KsPluginBridge.sendJson(exchange, 404, "{\"error\":\"贷款申请不存在或已处理\"}"); return;
            }
            if (!actualBankId.equals(bankId)) {
                KsPluginBridge.sendJson(exchange, 400, "{\"error\":\"申请不属于所选银行\"}"); return;
            }
            bankId = actualBankId;
            if (!canApproveBankLoan(session, bankId)) {
                KsPluginBridge.sendJson(exchange, 403, "{\"error\":\"需要银行所有者或 APPROVE_LOAN 权限\"}"); return;
            }
        } catch (java.sql.SQLException e) {
            KsPluginBridge.sendJson(exchange, 500, "{\"error\":\"权限检查失败: " + e.getMessage() + "\"}"); return;
        }
        Object result = callExtraManager("ks-eco-bank", "bankManager",
                approve ? "approveLoanRequest" : "rejectLoanRequest",
                new Class[]{String.class}, requestId);
        if (result != null && (boolean) result) {
            auditLog(approve ? "LOAN_APPROVE" : "LOAN_REJECT", session.playerUuid.toString(), session.playerName,
                "bank", bankId, "贷款申请 " + requestId);
            KsPluginBridge.sendJson(exchange, 200, "{\"message\":\"" + (approve ? "已批准并放款" : "已拒绝") + "\"}");
        } else {
            KsPluginBridge.sendJson(exchange, 500, "{\"error\":\"操作失败（申请不存在/已处理/银行资产不足）\"}");
        }
    }

    private void handleLoanList(HttpExchange exchange, String query) throws IOException {
        KsAuthManager.Session session = requireAdminAuth(exchange);
        if (session == null) return;
        List<Map<String, Object>> list = new ArrayList<>();
        Map<String, String> params = KsPluginBridge.parseQuery(query);
        String status = params.getOrDefault("status", "");
        String sql = "SELECT * FROM ks_bank_loans";
        if (!status.isEmpty()) sql += " WHERE status='" + status.replace("'", "''") + "'";
        sql += " ORDER BY issued_at DESC LIMIT 100";
        queryRows(list, sql, null);
        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("loans", list); resp.put("count", list.size());
        KsPluginBridge.sendJson(exchange, 200, gson.toJson(resp));
    }

    @SuppressWarnings("unchecked")
    private void handleLoanIssue(HttpExchange exchange) throws IOException {
        KsAuthManager.Session session = requireAuth(exchange);
        if (session == null) return;
        Map<String, Object> req = gson.fromJson(KsPluginBridge.readBody(exchange), Map.class);
        if (req == null) { KsPluginBridge.sendJson(exchange, 400, "{\"error\":\"无效请求\"}"); return; }
        String bankId = (String) req.get("bankId");
        String borrowerUuid = (String) req.get("borrowerUuid");
        double principal = toDouble(req.get("principal"), 1000);
        int termDays = (int) toDouble(req.get("termDays"), 30);
        if (bankId == null || borrowerUuid == null) {
            KsPluginBridge.sendJson(exchange, 400, "{\"error\":\"缺少 bankId/borrowerUuid\"}"); return;
        }
        // 贷款审批权限检查：必须是银行所有者 或 拥有 APPROVE_LOAN 权限
        try (var conn = plugin.ksCore().dataStore().getConnection()) {
            if (conn == null) { KsPluginBridge.sendJson(exchange, 500, "{\"error\":\"数据库未连接\"}"); return; }
            var rs = conn.createStatement().executeQuery(
                "SELECT owner_uuids FROM ks_bank_banks WHERE id='" + bankId.replace("'", "''") + "'");
            if (!rs.next()) { KsPluginBridge.sendJson(exchange, 404, "{\"error\":\"银行不存在\"}"); return; }
            var bankAccess = plugin.bankAccessProvider();
            boolean isOwner = bankAccess != null
                    ? bankAccess.hasPermission(bankId, session.playerUuid, "ISSUE_LOAN")
                    : ownerListContains(rs.getString("owner_uuids"), session.playerUuid);
            if (bankAccess != null && !isOwner && !session.isAdmin) {
                KsPluginBridge.sendJson(exchange, 403, gson.toJson(Map.of("error", "需要 ISSUE_LOAN 权限才能直接放贷")));
                return;
            }
            if (!isOwner && !session.isAdmin && bankAccess == null) {
                // 检查是否有 APPROVE_LOAN 权限
                var rs2 = conn.createStatement().executeQuery(
                    "SELECT COUNT(*) FROM ks_bank_permissions WHERE bank_id='" + bankId.replace("'", "''") +
                    "' AND player_uuid='" + session.playerUuid.toString() + "' AND permission='APPROVE_LOAN'");
                if (!rs2.next() || rs2.getInt(1) == 0) {
                    KsPluginBridge.sendJson(exchange, 403, "{\"error\":\"需要 APPROVE_LOAN 权限或银行所有者身份才能发放贷款\"}");
                    return;
                }
            }
        } catch (java.sql.SQLException e) {
            KsPluginBridge.sendJson(exchange, 500, "{\"error\":\"权限检查失败: " + e.getMessage() + "\"}"); return;
        }
        UUID borrower;
        try { borrower = resolvePlayerReference(borrowerUuid); }
        catch (IllegalArgumentException e) { KsPluginBridge.sendJson(exchange, 400, gson.toJson(Map.of("error", e.getMessage()))); return; }
        Object result = callExtraManager("ks-eco-bank", "bankManager", "issueLoan",
                new Class[]{String.class, UUID.class, double.class, int.class},
                bankId, borrower, principal, termDays);
        if (result != null && (boolean) result) {
            auditLog("LOAN_ISSUE", session.playerUuid.toString(), session.playerName, "bank", bankId,
                "贷款 " + String.format("%.2f", principal) + " → " + borrowerUuid + " (" + termDays + "天)");
            KsPluginBridge.sendJson(exchange, 200, "{\"message\":\"贷款已发放\"}");
        } else {
            KsPluginBridge.sendJson(exchange, 500, "{\"error\":\"放贷失败（余额不足或模块未加载）\"}");
        }
    }

    @SuppressWarnings("unchecked")
    private void handleLoanRepay(HttpExchange exchange) throws IOException {
        KsAuthManager.Session session = requireAuth(exchange);
        if (session == null) return;
        Map<String, Object> req = gson.fromJson(KsPluginBridge.readBody(exchange), Map.class);
        if (req == null) { KsPluginBridge.sendJson(exchange, 400, "{\"error\":\"无效请求\"}"); return; }
        String loanId = (String) req.get("loanId");
        String borrowerUuid = (String) req.get("borrowerUuid");
        double amount = toDouble(req.get("amount"), 0);
        // Never trust a client-provided borrower UUID here: repayment must debit the logged-in borrower.
        borrowerUuid = session.playerUuid.toString();
        UUID borrower = session.playerUuid;
        if (loanId == null) {
            KsPluginBridge.sendJson(exchange, 400, "{\"error\":\"缺少 loanId\"}"); return;
        }
        Object result;
        try {
            // Vault providers are Bukkit APIs and must be invoked from the server thread.
            result = plugin.scheduler().callGlobal(() -> callExtraManager(
                    "ks-eco-bank", "bankManager", "repayLoan",
                    new Class[]{String.class, UUID.class, double.class},
                    loanId, borrower, amount), Duration.ofSeconds(10));
        } catch (Exception e) {
            plugin.getLogger().warning("[ks-Eco] Bank repayment sync call failed: " + e.getMessage());
            KsPluginBridge.sendJson(exchange, 503, gson.toJson(Map.of("error", "Repayment service unavailable")));
            return;
        }
        if (result != null && (boolean) result) {
            KsPluginBridge.sendJson(exchange, 200, "{\"message\":\"还款成功\"}");
        } else {
            KsPluginBridge.sendJson(exchange, 500, "{\"error\":\"还款失败\"}");
        }
    }

    @SuppressWarnings("unchecked")
    private void handleEnterpriseFinanceRequest(HttpExchange exchange) throws IOException {
        KsAuthManager.Session session = requireAuth(exchange);
        if (session == null) return;
        Map<String, Object> request = gson.fromJson(KsPluginBridge.readBody(exchange), Map.class);
        if (request == null) { KsPluginBridge.sendJson(exchange, 400, gson.toJson(Map.of("error", "无效请求"))); return; }
        String bankId = String.valueOf(request.getOrDefault("bankId", ""));
        String enterpriseId = String.valueOf(request.getOrDefault("enterpriseId", ""));
        String purpose = String.valueOf(request.getOrDefault("purpose", ""));
        String collateralType = String.valueOf(request.getOrDefault("collateralType", ""));
        String collateralRef = String.valueOf(request.getOrDefault("collateralRef", ""));
        double principal = toDouble(request.get("principal"), 0);
        int termDays = (int) toDouble(request.get("termDays"), 0);
        double loanToValue = toDouble(request.get("loanToValue"), 0);
        Object result = callExtraManager("ks-eco-bank", "enterpriseFinanceManager", "requestLoan",
                new Class[]{String.class, String.class, UUID.class, double.class, int.class, String.class, String.class, String.class, double.class},
                bankId, enterpriseId, session.playerUuid, principal, termDays, purpose, collateralType, collateralRef, loanToValue);
        if (result instanceof Map<?, ?> response) {
            Map<String, Object> body = stringKeyedMap(response);
            if (Boolean.TRUE.equals(body.get("success"))) {
                auditLog("ENTERPRISE_LOAN_REQUEST", session.playerUuid.toString(), session.playerName, "enterprise", enterpriseId,
                        "bank=" + bankId + " amount=" + principal + " purpose=" + purpose);
                KsPluginBridge.sendJson(exchange, 200, gson.toJson(body));
            } else KsPluginBridge.sendJson(exchange, 400, gson.toJson(body));
        } else KsPluginBridge.sendJson(exchange, 503, gson.toJson(Map.of("error", "企业融资模块不可用")));
    }

    private void handleEnterpriseFinanceLoans(HttpExchange exchange, String query) throws IOException {
        KsAuthManager.Session session = requireAuth(exchange);
        if (session == null) return;
        String enterpriseId = KsPluginBridge.parseQuery(query).get("enterpriseId");
        try (var conn = plugin.ksCore().dataStore().getConnection()) {
            if (enterpriseId == null || conn == null
                    || !hasEnterprisePermission(conn, enterpriseId, session, EnterprisePermissionService.VIEW_FINANCE)) {
                KsPluginBridge.sendJson(exchange, 403, gson.toJson(Map.of("error", "无权查看该企业融资信息"))); return;
            }
        } catch (java.sql.SQLException e) {
            KsPluginBridge.sendJson(exchange, 500, gson.toJson(Map.of("error", "权限检查失败"))); return;
        }
        Object result = callExtraManager("ks-eco-bank", "enterpriseFinanceManager", "listEnterpriseLoans",
                new Class[]{String.class}, enterpriseId);
        KsPluginBridge.sendJson(exchange, 200, gson.toJson(Map.of("loans", result == null ? List.of() : result)));
    }

    private void handleEnterpriseFinanceInventory(HttpExchange exchange, String query) throws IOException {
        KsAuthManager.Session session = requireAuth(exchange);
        if (session == null) return;
        String enterpriseId = KsPluginBridge.parseQuery(query).get("enterpriseId");
        try (var conn = plugin.ksCore().dataStore().getConnection()) {
            if (enterpriseId == null || conn == null
                    || !hasEnterprisePermission(conn, enterpriseId, session, EnterprisePermissionService.VIEW_FINANCE)) {
                KsPluginBridge.sendJson(exchange, 403, gson.toJson(Map.of("error", "无权查看该企业库存"))); return;
            }
        } catch (java.sql.SQLException e) {
            KsPluginBridge.sendJson(exchange, 500, gson.toJson(Map.of("error", "权限检查失败"))); return;
        }
        Object result = callExtraManager("ks-eco-bank", "enterpriseFinanceManager", "listInventoryLots",
                new Class[]{String.class}, enterpriseId);
        KsPluginBridge.sendJson(exchange, 200, gson.toJson(Map.of("lots", result == null ? List.of() : result)));
    }

    @SuppressWarnings("unchecked")
    private void handleEnterpriseSalaryPay(HttpExchange exchange) throws IOException {
        KsAuthManager.Session session = requireAuth(exchange);
        if (session == null) return;
        Map<String, Object> request = gson.fromJson(KsPluginBridge.readBody(exchange), Map.class);
        if (request == null) { KsPluginBridge.sendJson(exchange, 400, gson.toJson(Map.of("error", "无效请求"))); return; }
        String enterpriseId = String.valueOf(request.getOrDefault("enterpriseId", "")).trim();
        String employeeRef = String.valueOf(request.getOrDefault("employee", "")).trim();
        if (enterpriseId.isEmpty() || employeeRef.isEmpty()) {
            KsPluginBridge.sendJson(exchange, 400, gson.toJson(Map.of("error", "缺少企业或员工"))); return;
        }
        try {
            UUID employee = resolvePlayerReference(employeeRef);
            Object result = callExtraManager("ks-eco-enterprise", "enterpriseManager", "paySalary",
                    new Class[]{String.class, UUID.class, UUID.class}, enterpriseId, session.playerUuid, employee);
            if (result instanceof Map<?, ?> response) {
                Map<String, Object> body = stringKeyedMap(response);
                if (Boolean.TRUE.equals(body.get("success"))) {
                    auditLog("ENTERPRISE_SALARY_PAY", session.playerUuid.toString(), session.playerName, "enterprise", enterpriseId,
                            "employee=" + employee);
                    KsPluginBridge.sendJson(exchange, 200, gson.toJson(body));
                } else KsPluginBridge.sendJson(exchange, 400, gson.toJson(body));
            } else KsPluginBridge.sendJson(exchange, 503, gson.toJson(Map.of("error", "企业模块不可用")));
        } catch (IllegalArgumentException e) {
            KsPluginBridge.sendJson(exchange, 400, gson.toJson(Map.of("error", e.getMessage())));
        }
    }

    @SuppressWarnings("unchecked")
    private void handleEnterpriseInventoryRegister(HttpExchange exchange) throws IOException {
        KsAuthManager.Session session = requireAdminAuth(exchange);
        if (session == null) return;
        Map<String, Object> request = gson.fromJson(KsPluginBridge.readBody(exchange), Map.class);
        if (request == null) { KsPluginBridge.sendJson(exchange, 400, gson.toJson(Map.of("error", "无效请求"))); return; }
        String enterpriseId = String.valueOf(request.getOrDefault("enterpriseId", ""));
        String description = String.valueOf(request.getOrDefault("description", ""));
        int quantity = (int) toDouble(request.get("quantity"), 0);
        double value = toDouble(request.get("appraisedValue"), 0);
        Object result = callExtraManager("ks-eco-bank", "enterpriseFinanceManager", "registerInventoryLot",
                new Class[]{String.class, String.class, int.class, double.class}, enterpriseId, description, quantity, value);
        if (result instanceof String id) {
            auditLog("ENTERPRISE_INVENTORY_APPRAISE", session.playerUuid.toString(), session.playerName, "enterprise", enterpriseId,
                    "lot=" + id + " value=" + value + " quantity=" + quantity);
            KsPluginBridge.sendJson(exchange, 200, gson.toJson(Map.of("id", id, "message", "企业库存批次已入库")));
        } else KsPluginBridge.sendJson(exchange, 400, gson.toJson(Map.of("error", "库存批次入库失败")));
    }

    @SuppressWarnings("unchecked")
    private void handleEnterpriseFinanceRepay(HttpExchange exchange) throws IOException {
        KsAuthManager.Session session = requireAuth(exchange);
        if (session == null) return;
        Map<String, Object> request = gson.fromJson(KsPluginBridge.readBody(exchange), Map.class);
        if (request == null) { KsPluginBridge.sendJson(exchange, 400, gson.toJson(Map.of("error", "无效请求"))); return; }
        String enterpriseId = String.valueOf(request.getOrDefault("enterpriseId", ""));
        String loanId = String.valueOf(request.getOrDefault("loanId", ""));
        double amount = toDouble(request.get("amount"), 0);
        Object result = callExtraManager("ks-eco-bank", "enterpriseFinanceManager", "repay",
                new Class[]{String.class, String.class, UUID.class, double.class}, loanId, enterpriseId, session.playerUuid, amount);
        if (result instanceof Map<?, ?> response) {
            Map<String, Object> body = stringKeyedMap(response);
            if (Boolean.TRUE.equals(body.get("success"))) {
                auditLog("ENTERPRISE_LOAN_REPAY", session.playerUuid.toString(), session.playerName, "enterprise", enterpriseId,
                        "loan=" + loanId + " amount=" + amount);
                KsPluginBridge.sendJson(exchange, 200, gson.toJson(body));
            } else KsPluginBridge.sendJson(exchange, 400, gson.toJson(body));
        } else KsPluginBridge.sendJson(exchange, 503, gson.toJson(Map.of("error", "企业融资模块不可用")));
    }

    @SuppressWarnings("unchecked")
    private void handleCorporateBankSelect(HttpExchange exchange) throws IOException {
        KsAuthManager.Session session = requireAuth(exchange);
        if (session == null) return;
        Map<String, Object> request = gson.fromJson(KsPluginBridge.readBody(exchange), Map.class);
        if (request == null) { KsPluginBridge.sendJson(exchange, 400, gson.toJson(Map.of("error", "无效请求"))); return; }
        String enterpriseId = String.valueOf(request.getOrDefault("enterpriseId", ""));
        String bankId = String.valueOf(request.getOrDefault("bankId", ""));
        Object result = callExtraManager("ks-eco-bank", "enterpriseFinanceManager", "selectCorporateBank",
                new Class[]{String.class, UUID.class, String.class}, enterpriseId, session.playerUuid, bankId);
        if (Boolean.TRUE.equals(result)) {
            auditLog("ENTERPRISE_BANK_SELECT", session.playerUuid.toString(), session.playerName, "enterprise", enterpriseId, "bank=" + bankId);
            KsPluginBridge.sendJson(exchange, 200, gson.toJson(Map.of("message", "企业开户行已更新")));
        } else KsPluginBridge.sendJson(exchange, 400, gson.toJson(Map.of("error", "切换失败：需要企业所有者身份、目标银行可用，且企业没有未结贷款")));
    }

    private void handleEnterpriseFinanceRequests(HttpExchange exchange, String query) throws IOException {
        KsAuthManager.Session session = requireAuth(exchange);
        if (session == null) return;
        Map<String, String> params = KsPluginBridge.parseQuery(query);
        String bankId = params.get("bankId");
        if (bankId == null) { KsPluginBridge.sendJson(exchange, 400, gson.toJson(Map.of("error", "缺少 bankId"))); return; }
        try {
            if (!canApproveBankLoan(session, bankId)) { KsPluginBridge.sendJson(exchange, 403, gson.toJson(Map.of("error", "需要贷款审批权限"))); return; }
        } catch (java.sql.SQLException e) { KsPluginBridge.sendJson(exchange, 500, gson.toJson(Map.of("error", "权限检查失败"))); return; }
        Object result = callExtraManager("ks-eco-bank", "enterpriseFinanceManager", "listRequests",
                new Class[]{String.class, String.class}, bankId, params.getOrDefault("status", "PENDING"));
        KsPluginBridge.sendJson(exchange, 200, gson.toJson(Map.of("requests", result == null ? List.of() : result)));
    }

    @SuppressWarnings("unchecked")
    private void handleEnterpriseFinanceDecision(HttpExchange exchange) throws IOException {
        KsAuthManager.Session session = requireAuth(exchange);
        if (session == null) return;
        Map<String, Object> request = gson.fromJson(KsPluginBridge.readBody(exchange), Map.class);
        if (request == null) { KsPluginBridge.sendJson(exchange, 400, gson.toJson(Map.of("error", "无效请求"))); return; }
        String bankId = String.valueOf(request.getOrDefault("bankId", ""));
        String requestId = String.valueOf(request.getOrDefault("requestId", ""));
        boolean approve = Boolean.TRUE.equals(request.get("approve"));
        try {
            String actualBankId = requestBankId("ks_bank_enterprise_loan_requests", requestId);
            if (actualBankId == null) {
                KsPluginBridge.sendJson(exchange, 404, gson.toJson(Map.of("error", "企业融资申请不存在或已处理"))); return;
            }
            if (!actualBankId.equals(bankId)) {
                KsPluginBridge.sendJson(exchange, 400, gson.toJson(Map.of("error", "申请不属于所选银行"))); return;
            }
            bankId = actualBankId;
            if (!canApproveBankLoan(session, bankId)) { KsPluginBridge.sendJson(exchange, 403, gson.toJson(Map.of("error", "需要贷款审批权限"))); return; }
        } catch (java.sql.SQLException e) { KsPluginBridge.sendJson(exchange, 500, gson.toJson(Map.of("error", "权限检查失败"))); return; }
        Object result = callExtraManager("ks-eco-bank", "enterpriseFinanceManager", "decideRequest",
                new Class[]{String.class, boolean.class}, requestId, approve);
        if (Boolean.TRUE.equals(result)) {
            auditLog(approve ? "ENTERPRISE_LOAN_APPROVE" : "ENTERPRISE_LOAN_REJECT", session.playerUuid.toString(), session.playerName,
                    "bank", bankId, "request=" + requestId);
            KsPluginBridge.sendJson(exchange, 200, gson.toJson(Map.of("message", approve ? "企业融资已批准并存入企业公户" : "企业融资申请已拒绝")));
        } else KsPluginBridge.sendJson(exchange, 400, gson.toJson(Map.of("error", "审批失败：抵押物、企业开户行或银行准备金可能已变化")));
    }

    private void handleCollateralAuctions(HttpExchange exchange) throws IOException {
        Object result = callExtraManager("ks-eco-bank", "enterpriseFinanceManager", "listAuctions", new Class[]{}, (Object[]) new Object[]{});
        KsPluginBridge.sendJson(exchange, 200, gson.toJson(Map.of("auctions", result == null ? List.of() : result)));
    }

    @SuppressWarnings("unchecked")
    private void handleCollateralAuctionBid(HttpExchange exchange) throws IOException {
        KsAuthManager.Session session = requireAuth(exchange);
        if (session == null) return;
        Map<String, Object> request = gson.fromJson(KsPluginBridge.readBody(exchange), Map.class);
        if (request == null) { KsPluginBridge.sendJson(exchange, 400, gson.toJson(Map.of("error", "无效请求"))); return; }
        String auctionId = String.valueOf(request.getOrDefault("auctionId", ""));
        double amount = toDouble(request.get("amount"), 0);
        try {
            Object result = plugin.scheduler().callGlobal(() -> callExtraManager(
                    "ks-eco-bank", "enterpriseFinanceManager", "placeAuctionBid", new Class[]{String.class, UUID.class, double.class},
                    auctionId, session.playerUuid, amount), Duration.ofSeconds(10));
            if (result instanceof Map<?, ?> response) {
                Map<String, Object> body = stringKeyedMap(response);
                if (Boolean.TRUE.equals(body.get("success"))) {
                    auditLog("COLLATERAL_AUCTION_BID", session.playerUuid.toString(), session.playerName, "auction", auctionId, "amount=" + amount);
                    KsPluginBridge.sendJson(exchange, 200, gson.toJson(body));
                } else KsPluginBridge.sendJson(exchange, 400, gson.toJson(body));
            } else KsPluginBridge.sendJson(exchange, 503, gson.toJson(Map.of("error", "拍卖模块不可用")));
        } catch (Exception e) {
            KsPluginBridge.sendJson(exchange, 503, gson.toJson(Map.of("error", "服务器繁忙，请稍后重试")));
        }
    }

    private static Map<String, Object> stringKeyedMap(Map<?, ?> source) {
        Map<String, Object> result = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : source.entrySet()) result.put(String.valueOf(entry.getKey()), entry.getValue());
        return result;
    }

    // ================================================================
    // Enterprise APIs — 企业系统
    // ================================================================

    private void handleEnterpriseStats(HttpExchange exchange) throws IOException {
        Map<String, Object> s = new LinkedHashMap<>();
        s.put("moduleLoaded", hasExtra("ks-eco-enterprise"));
        queryInt(s, "SELECT COUNT(*) FROM ks_ent_enterprises", "enterpriseCount");
        queryInt(s, "SELECT COUNT(*) FROM ks_ent_enterprises WHERE status='ACTIVE'", "activeCount");
        queryDouble(s, "SELECT COALESCE(SUM(registered_capital),0) FROM ks_ent_enterprises", "totalCapital");
        queryInt(s, "SELECT COALESCE(SUM(employee_count),0) FROM ks_ent_enterprises", "totalEmployees");
        queryInt(s, "SELECT COUNT(*) FROM ks_ent_projects WHERE status='OPEN'", "openProjects");
        KsPluginBridge.sendJson(exchange, 200, gson.toJson(s));
    }

    private void handleEnterpriseList(HttpExchange exchange) throws IOException {
        KsAuthManager.Session session = requireAuth(exchange);
        if (session == null) return;
        List<Map<String, Object>> list = new ArrayList<>();
        queryRows(list, "SELECT id,name,COALESCE(description,'') AS description,type,owner_uuids,registered_capital,current_assets," +
                "employee_count,level,region,industry,dividend_rate,status,created_at FROM ks_ent_enterprises ORDER BY created_at DESC LIMIT 100", null);
        try (var conn = plugin.ksCore().dataStore().getConnection()) {
            if (conn != null) {
                for (Map<String, Object> enterprise : list) {
                    String enterpriseId = String.valueOf(enterprise.get("id"));
                    if (session.isAdmin || hasEnterprisePermission(conn, enterpriseId, session, EnterprisePermissionService.VIEW_FINANCE)) {
                        enterprise.put("corporate_balance", getCorporateBalance(conn, enterpriseId));
                    }
                }
            }
        } catch (java.sql.SQLException e) {
            plugin.getLogger().warning("Enterprise finance visibility check failed: " + e.getMessage());
        }
        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("enterprises", list); resp.put("count", list.size());
        resp.put("maxEnterpriseLevel", plugin.enterpriseLevelManager().getMaxLevel());
        KsPluginBridge.sendJson(exchange, 200, gson.toJson(resp));
    }

    private void handleEnterpriseGet(HttpExchange exchange, String query) throws IOException {
        Map<String, String> params = KsPluginBridge.parseQuery(query);
        String id = params.get("id");
        if (id == null) { KsPluginBridge.sendJson(exchange, 400, "{\"error\":\"缺少 id\"}"); return; }
        // 反射调用 EnterpriseManager.getEnterprise
        Object ent = callExtraManager("ks-eco-enterprise", "enterpriseManager", "getEnterprise",
                new Class[]{String.class}, id);
        if (ent == null) {
            KsPluginBridge.sendJson(exchange, 404, "{\"error\":\"企业未找到\"}");
        } else {
            KsPluginBridge.sendJson(exchange, 200, gson.toJson(ent));
        }
    }

    @SuppressWarnings("unchecked")
    private void handleEnterpriseRegister(HttpExchange exchange) throws IOException {
        KsAuthManager.Session session = requireAuth(exchange);
        if (session == null) return;
        Map<String, Object> req = gson.fromJson(KsPluginBridge.readBody(exchange), Map.class);
        if (req == null) { KsPluginBridge.sendJson(exchange, 400, "{\"error\":\"无效请求\"}"); return; }
        String name = req.get("name") == null ? "" : req.get("name").toString().trim();
        // 国有企业仅管理员可注册
        String type = req.getOrDefault("type", "PRIVATE").toString();
        if ("STATE_OWNED".equalsIgnoreCase(type) && !session.isAdmin) {
            KsPluginBridge.sendJson(exchange, 403, "{\"error\":\"仅管理员可注册国有企业\"}");
            return;
        }
        List<String> ownerStrs = (List<String>) req.get("ownerUuids");
        double capital = toDouble(req.get("registeredCapital"), 50000);
        String region = (String) req.getOrDefault("region", "");
        if (name.length() < 2 || name.length() > 32 || ownerStrs == null || ownerStrs.isEmpty()) {
            KsPluginBridge.sendJson(exchange, 400, "{\"error\":\"缺少 name/ownerUuids\"}"); return;
        }
        // Replace __self__ placeholder with session UUID
        LinkedHashSet<UUID> ownerSet = new LinkedHashSet<>();
        ownerSet.add(session.playerUuid);
        for (String s : ownerStrs) {
            if ("__self__".equals(s.trim())) {
                ownerSet.add(session.playerUuid);
            } else {
                try {
                    ownerSet.add(resolvePlayerReference(s));
                } catch (IllegalArgumentException e) {
                    KsPluginBridge.sendJson(exchange, 400, gson.toJson(Map.of("error", e.getMessage())));
                    return;
                }
            }
        }
        List<UUID> owners = new ArrayList<>(ownerSet);
        if (!"STATE_OWNED".equalsIgnoreCase(type) && capital < getEconomicSetting("enterprise_min_capital", 50000)) {
            KsPluginBridge.sendJson(exchange, 400, "{\"error\":\"注册资本低于企业成立门槛\"}"); return;
        }
        if (!"STATE_OWNED".equalsIgnoreCase(type) && owners.size() > 1) {
            createPendingEnterpriseCreation(exchange, session, name, type, owners, capital, region);
            return;
        }
        // 私有企业：先检查余额，等 DB 成功后扣款
        boolean deductFromOwners = !"STATE_OWNED".equalsIgnoreCase(type);
        if (deductFromOwners) {
            double perOwner = capital / owners.size();
            for (UUID ownerUuid : owners) {
                double balance = walletBalance(ownerUuid);
                if (balance < perOwner) {
                    KsPluginBridge.sendJson(exchange, 400, "{\"error\":\"玩家 " + ownerUuid + " 余额不足（需要 " + String.format("%.2f", perOwner) + "，当前余额 " + String.format("%.2f", balance) + "）\"}");
                    return;
                }
            }
        }
        // 直接写入数据库（绕过 extra module 的 classloader 问题）
        String entId = UUID.randomUUID().toString().substring(0, 8);
        long now = System.currentTimeMillis() / 1000;
        try (var conn = plugin.ksCore().dataStore().getConnection()) {
            if (conn == null) {
                KsPluginBridge.sendJson(exchange, 500, "{\"error\":\"数据库未连接\"}");
                return;
            }
            try (var ps = conn.prepareStatement(
                    "INSERT INTO ks_ent_enterprises (id, name, type, owner_uuids, registered_capital, current_assets, region, created_at) VALUES (?,?,?,?,?,?,?,?)")) {
                ps.setString(1, entId);
                ps.setString(2, name);
                ps.setString(3, type);
                ps.setString(4, String.join(",", owners.stream().map(UUID::toString).toList()));
                ps.setDouble(5, capital);
                ps.setDouble(6, capital);
                ps.setString(7, region);
                ps.setLong(8, now);
                ps.executeUpdate();
            }
            ensureEnterpriseOwnerMembers(conn, entId, owners, now);
            Map<String, Object> ent = new LinkedHashMap<>();
            ent.put("id", entId); ent.put("name", name); ent.put("type", type);
            ent.put("registered_capital", capital); ent.put("current_assets", capital);
            ent.put("region", region); ent.put("created_at", now);
            // 自动创建企业公户并注入注册资本（使用当前连接）
            try {
                if (!ensureCorporateAccount(conn, entId, CORP_BANK_ID, capital, now)) {
                    throw new java.sql.SQLException("Corporate account already exists for a new enterprise");
                }
                try (var bank = conn.prepareStatement(
                        "UPDATE ks_bank_banks SET total_assets=total_assets+? WHERE id=?")) {
                    bank.setDouble(1, capital); bank.setString(2, CORP_BANK_ID);
                    if (bank.executeUpdate() != 1) throw new java.sql.SQLException("Corporate bank is missing");
                }
            } catch (java.sql.SQLException e) {
                removeFailedEnterpriseRegistration(entId);
                KsPluginBridge.sendJson(exchange, 500, "{\"error\":\"企业公户初始化失败\"}");
                return;
            }
            auditLog("ENTERPRISE_REGISTER", session.playerUuid.toString(), session.playerName, "enterprise", entId,
                "企业名称: " + name + " | 类型: " + type + " | 注册资本: " + String.format("%.2f", capital) + " | 区域: " + region);
            // DB write precedes Vault because this legacy endpoint is handled by the HTTP worker.
            // A failed charge must remove the just-created capital and refund every successful debit.
            if (deductFromOwners) {
                double perOwner = capital / owners.size();
                List<UUID> chargedOwners = new ArrayList<>();
                for (UUID ownerUuid : owners) {
                    if (!withdrawWallet(ownerUuid, perOwner)) {
                        for (UUID chargedOwner : chargedOwners) depositWallet(chargedOwner, perOwner);
                        removeFailedEnterpriseRegistration(entId);
                        KsPluginBridge.sendJson(exchange, 409, "{\"error\":\"注册资本扣除失败，企业创建已回滚\"}");
                        return;
                    }
                    chargedOwners.add(ownerUuid);
                }
            }
            KsPluginBridge.sendJson(exchange, 200, gson.toJson(ent));
        } catch (java.sql.SQLException e) {
            KsPluginBridge.sendJson(exchange, 500, "{\"error\":\"注册失败: " + e.getMessage() + "\"}");
        }
    }

    private void removeFailedEnterpriseRegistration(String enterpriseId) {
        try (var conn = plugin.ksCore().dataStore().getConnection()) {
            if (conn == null) return;
            conn.setAutoCommit(false);
            try {
                String bankId = null;
                double corporateBalance = 0.0;
                try (var ps = conn.prepareStatement("SELECT bank_id, balance FROM ks_ent_corporate_accounts WHERE enterprise_id=?")) {
                    ps.setString(1, enterpriseId);
                    try (var rs = ps.executeQuery()) {
                        if (rs.next()) {
                            bankId = rs.getString(1);
                            corporateBalance = rs.getDouble(2);
                        }
                    }
                }
                try (var ps = conn.prepareStatement("DELETE FROM ks_ent_corporate_accounts WHERE enterprise_id=?")) {
                    ps.setString(1, enterpriseId);
                    ps.executeUpdate();
                }
                try (var ps = conn.prepareStatement("DELETE FROM ks_ent_members WHERE enterprise_id=?")) {
                    ps.setString(1, enterpriseId);
                    ps.executeUpdate();
                }
                try (var ps = conn.prepareStatement("DELETE FROM ks_ent_enterprises WHERE id=?")) {
                    ps.setString(1, enterpriseId);
                    ps.executeUpdate();
                }
                if (bankId != null && corporateBalance > 0) {
                    try (var ps = conn.prepareStatement("UPDATE ks_bank_banks SET total_assets=MAX(0,total_assets-?) WHERE id=?")) {
                        ps.setDouble(1, corporateBalance);
                        ps.setString(2, bankId);
                        ps.executeUpdate();
                    }
                }
                conn.commit();
            } catch (java.sql.SQLException e) {
                conn.rollback();
                throw e;
            } finally {
                try { conn.setAutoCommit(true); } catch (java.sql.SQLException ignored) {}
            }
        } catch (java.sql.SQLException e) {
            plugin.getLogger().severe("Failed to roll back unpaid enterprise " + enterpriseId + ": " + e.getMessage());
        }
    }

    @SuppressWarnings("unchecked")
    private void handleEnterpriseDissolve(HttpExchange exchange) throws IOException {
        KsAuthManager.Session session = requireAuth(exchange);
        if (session == null) return;
        Map<String, Object> req = gson.fromJson(KsPluginBridge.readBody(exchange), Map.class);
        if (req == null) { KsPluginBridge.sendJson(exchange, 400, "{\"error\":\"无效请求\"}"); return; }
        String enterpriseId = (String) req.get("enterpriseId");
        String requesterUuid = (String) req.get("requesterUuid");
        if (enterpriseId == null) {
            KsPluginBridge.sendJson(exchange, 400, "{\"error\":\"缺少 enterpriseId\"}"); return;
        }
        // 防伪造：非管理员只能以自己的身份注销（旧逻辑直接信任请求体里的 requesterUuid，
        // 任何登录玩家填个所有者 UUID 就能注销任意企业并触发资产派发）
        if (requesterUuid == null || !session.isAdmin) {
            requesterUuid = session.playerUuid.toString();
        }
        Object result = callExtraManager("ks-eco-enterprise", "enterpriseManager", "dissolve",
                new Class[]{String.class, UUID.class}, enterpriseId, resolvePlayerReference(requesterUuid));
        boolean ok = result != null && (boolean) result;
        KsPluginBridge.sendJson(exchange, ok ? 200 : 500,
                ok ? "{\"message\":\"企业已注销\"}" : "{\"error\":\"注销失败\"}");
    }

    @SuppressWarnings("unchecked")
    private void handleEnterpriseCapitalInject(HttpExchange exchange) throws IOException {
        KsAuthManager.Session session = requireAuth(exchange);
        if (session == null) return;
        Map<String, Object> req = gson.fromJson(KsPluginBridge.readBody(exchange), Map.class);
        String enterpriseId = req == null ? null : (String) req.get("enterpriseId");
        double amount = req == null ? 0 : toDouble(req.get("amount"), 0);
        if (enterpriseId == null || amount <= 0 || !Double.isFinite(amount)) {
            KsPluginBridge.sendJson(exchange, 400, "{\"error\":\"缺少 enterpriseId 或金额无效\"}"); return;
        }
        Object raw = callExtraManager("ks-eco-enterprise", "enterpriseManager", "injectCapital",
                new Class[]{String.class, UUID.class, double.class}, enterpriseId, session.playerUuid, amount);
        if (!(raw instanceof Map<?, ?> map)) {
            KsPluginBridge.sendJson(exchange, 503, "{\"error\":\"企业扩展未加载\"}"); return;
        }
        Map<String, Object> result = new LinkedHashMap<>((Map<String, Object>) map);
        boolean success = Boolean.TRUE.equals(result.get("success"));
        if (success) auditLog("ENTERPRISE_CAPITAL_INJECT", session.playerUuid.toString(), session.playerName, "enterprise", enterpriseId,
                "追加注资 " + String.format(java.util.Locale.ROOT, "%.2f", amount));
        KsPluginBridge.sendJson(exchange, success ? 200 : 400, gson.toJson(result));
    }

    @SuppressWarnings("unchecked")
    private void handleEnterpriseDividendRate(HttpExchange exchange) throws IOException {
        KsAuthManager.Session session = requireAuth(exchange);
        if (session == null) return;
        Map<String, Object> req = gson.fromJson(KsPluginBridge.readBody(exchange), Map.class);
        String enterpriseId = req == null ? null : (String) req.get("enterpriseId");
        double rate = req == null ? Double.NaN : toDouble(req.get("rate"), Double.NaN);
        if (enterpriseId == null || !Double.isFinite(rate)) {
            KsPluginBridge.sendJson(exchange, 400, "{\"error\":\"缺少 enterpriseId 或分红比例无效\"}"); return;
        }
        Object raw = callExtraManager("ks-eco-enterprise", "enterpriseManager", "setDividendRate",
                new Class[]{String.class, UUID.class, double.class}, enterpriseId, session.playerUuid, rate);
        if (!(raw instanceof Map<?, ?> map)) {
            KsPluginBridge.sendJson(exchange, 503, "{\"error\":\"企业扩展未加载\"}"); return;
        }
        Map<String, Object> result = new LinkedHashMap<>((Map<String, Object>) map);
        boolean success = Boolean.TRUE.equals(result.get("success"));
        if (success) auditLog("ENTERPRISE_DIVIDEND_RATE", session.playerUuid.toString(), session.playerName, "enterprise", enterpriseId,
                "分红比例 " + String.format(java.util.Locale.ROOT, "%.2f%%", rate));
        KsPluginBridge.sendJson(exchange, success ? 200 : 400, gson.toJson(result));
    }

    @SuppressWarnings("unchecked")
    private void handleEnterpriseDividendShares(HttpExchange exchange) throws IOException {
        KsAuthManager.Session session = requireAuth(exchange);
        if (session == null) return;
        Map<String, Object> req = gson.fromJson(KsPluginBridge.readBody(exchange), Map.class);
        String enterpriseId = req == null ? null : (String) req.get("enterpriseId");
        Object requestedShares = req == null ? null : req.get("shares");
        if (enterpriseId == null || !(requestedShares instanceof Map<?, ?> shares)) {
            KsPluginBridge.sendJson(exchange, 400, "{\"error\":\"缺少 enterpriseId 或所有者分红比例\"}"); return;
        }
        Map<String, Object> shareMap = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : shares.entrySet()) shareMap.put(String.valueOf(entry.getKey()), entry.getValue());
        Object raw = callExtraManager("ks-eco-enterprise", "enterpriseManager", "setDividendShares",
                new Class[]{String.class, UUID.class, Map.class}, enterpriseId, session.playerUuid, shareMap);
        if (!(raw instanceof Map<?, ?> map)) {
            KsPluginBridge.sendJson(exchange, 503, "{\"error\":\"企业扩展未加载\"}"); return;
        }
        Map<String, Object> result = new LinkedHashMap<>((Map<String, Object>) map);
        boolean success = Boolean.TRUE.equals(result.get("success"));
        if (success) auditLog("ENTERPRISE_DIVIDEND_SHARES", session.playerUuid.toString(), session.playerName, "enterprise", enterpriseId,
                "更新所有者分红占比");
        KsPluginBridge.sendJson(exchange, success ? 200 : 400, gson.toJson(result));
    }

    @SuppressWarnings("unchecked")
    private void handleEnterpriseDividendDistribute(HttpExchange exchange) throws IOException {
        KsAuthManager.Session session = requireAuth(exchange);
        if (session == null) return;
        Map<String, Object> req = gson.fromJson(KsPluginBridge.readBody(exchange), Map.class);
        String enterpriseId = req == null ? null : (String) req.get("enterpriseId");
        if (enterpriseId == null) {
            KsPluginBridge.sendJson(exchange, 400, "{\"error\":\"缺少 enterpriseId\"}"); return;
        }
        Object raw = callExtraManager("ks-eco-enterprise", "enterpriseManager", "distributeDividend",
                new Class[]{String.class, UUID.class}, enterpriseId, session.playerUuid);
        if (!(raw instanceof Map<?, ?> map)) {
            KsPluginBridge.sendJson(exchange, 503, "{\"error\":\"企业扩展未加载\"}"); return;
        }
        Map<String, Object> result = new LinkedHashMap<>((Map<String, Object>) map);
        boolean success = Boolean.TRUE.equals(result.get("success"));
        if (success) auditLog("ENTERPRISE_DIVIDEND_DISTRIBUTE", session.playerUuid.toString(), session.playerName, "enterprise", enterpriseId,
                "按预设比例发放分红");
        KsPluginBridge.sendJson(exchange, success ? 200 : 400, gson.toJson(result));
    }

    @SuppressWarnings("unchecked")
    private void handleEnterpriseCustomDividend(HttpExchange exchange) throws IOException {
        KsAuthManager.Session session = requireAuth(exchange);
        if (session == null) return;
        Map<String, Object> req = gson.fromJson(KsPluginBridge.readBody(exchange), Map.class);
        String enterpriseId = req == null ? null : (String) req.get("enterpriseId");
        double amount = req == null ? 0 : toDouble(req.get("amount"), 0);
        Object requestedShares = req == null ? null : req.get("shares");
        if (enterpriseId == null || amount <= 0 || !Double.isFinite(amount) || !(requestedShares instanceof Map<?, ?> shares)) {
            KsPluginBridge.sendJson(exchange, 400, "{\"error\":\"分红总额或成员比例无效\"}"); return;
        }
        Map<String, Object> normalizedShares = new LinkedHashMap<>();
        try {
            for (Map.Entry<?, ?> entry : shares.entrySet()) {
                normalizedShares.put(resolvePlayerReference(String.valueOf(entry.getKey())).toString(), entry.getValue());
            }
        } catch (IllegalArgumentException e) {
            KsPluginBridge.sendJson(exchange, 400, gson.toJson(Map.of("error", e.getMessage()))); return;
        }
        Object raw = callExtraManager("ks-eco-enterprise", "enterpriseManager", "distributeCustomDividend",
                new Class[]{String.class, UUID.class, double.class, Map.class}, enterpriseId, session.playerUuid, amount, normalizedShares);
        if (!(raw instanceof Map<?, ?> map)) {
            KsPluginBridge.sendJson(exchange, 503, "{\"error\":\"企业扩展未加载\"}"); return;
        }
        Map<String, Object> result = new LinkedHashMap<>((Map<String, Object>) map);
        boolean success = Boolean.TRUE.equals(result.get("success"));
        if (success) auditLog("ENTERPRISE_CUSTOM_DIVIDEND", session.playerUuid.toString(), session.playerName, "enterprise", enterpriseId,
                "精确分红总额 " + String.format(Locale.ROOT, "%.2f", amount));
        KsPluginBridge.sendJson(exchange, success ? 200 : 400, gson.toJson(result));
    }

    @SuppressWarnings("unchecked")
    private void handleEnterpriseDividendPreview(HttpExchange exchange) throws IOException {
        KsAuthManager.Session session = requireAuth(exchange);
        if (session == null) return;
        Map<String, Object> req = gson.fromJson(KsPluginBridge.readBody(exchange), Map.class);
        String enterpriseId = req == null ? null : (String) req.get("enterpriseId");
        double amount = req == null ? 0 : toDouble(req.get("amount"), 0);
        Object rawShares = req == null ? null : req.get("shares");
        if (enterpriseId == null || amount <= 0 || !Double.isFinite(amount) || !(rawShares instanceof Map<?, ?> input)) {
            KsPluginBridge.sendJson(exchange, 400, "{\"error\":\"分红总额或成员比例无效\"}"); return;
        }
        Map<UUID, Double> shares = new LinkedHashMap<>();
        try {
            for (Map.Entry<?, ?> entry : input.entrySet()) {
                shares.put(resolvePlayerReference(String.valueOf(entry.getKey())), toDouble(entry.getValue(), Double.NaN));
            }
        } catch (IllegalArgumentException e) {
            KsPluginBridge.sendJson(exchange, 400, gson.toJson(Map.of("error", e.getMessage()))); return;
        }
        double total = shares.values().stream().mapToDouble(Double::doubleValue).sum();
        if (shares.isEmpty() || shares.values().stream().anyMatch(v -> !Double.isFinite(v) || v < 0) || Math.abs(total - 100.0) > 0.0001) {
            KsPluginBridge.sendJson(exchange, 400, "{\"error\":\"成员比例必须合计 100%\"}"); return;
        }
        try (var conn = plugin.ksCore().dataStore().getConnection()) {
            if (conn == null) { KsPluginBridge.sendJson(exchange, 500, "{\"error\":\"数据库未连接\"}"); return; }
            var ent = conn.createStatement().executeQuery("SELECT owner_uuids, COALESCE((SELECT balance FROM ks_ent_corporate_accounts WHERE enterprise_id='" + enterpriseId.replace("'", "''") + "'),0) AS balance FROM ks_ent_enterprises WHERE id='" + enterpriseId.replace("'", "''") + "' AND status='ACTIVE'");
            if (!ent.next() || !ownerListContains(ent.getString("owner_uuids"), session.playerUuid)) {
                KsPluginBridge.sendJson(exchange, 403, "{\"error\":\"只有经营中企业的所有者可预览分红\"}"); return;
            }
            if (amount > ent.getDouble("balance")) { KsPluginBridge.sendJson(exchange, 400, "{\"error\":\"企业公户余额不足\"}"); return; }
            Set<UUID> eligible = new LinkedHashSet<>(parseOwnerList(ent.getString("owner_uuids")));
            var members = conn.createStatement().executeQuery("SELECT player_uuid FROM ks_ent_members WHERE enterprise_id='" + enterpriseId.replace("'", "''") + "'");
            while (members.next()) { try { eligible.add(UUID.fromString(members.getString(1))); } catch (IllegalArgumentException ignored) {} }
            if (!eligible.containsAll(shares.keySet())) { KsPluginBridge.sendJson(exchange, 400, "{\"error\":\"分红成员必须是企业所有者或成员\"}"); return; }
            double taxRate = plugin.getCategoryTaxRate("DIVIDEND_TAX", 0.10);
            List<Map<String, Object>> payouts = new ArrayList<>(); double remainingNet = amount * (1 - taxRate);
            List<Map.Entry<UUID, Double>> entries = new ArrayList<>(shares.entrySet());
            for (int i = 0; i < entries.size(); i++) {
                var entry = entries.get(i); double gross = i == entries.size() - 1 ? amount - payouts.stream().mapToDouble(p -> (double) p.get("gross")).sum() : amount * entry.getValue() / 100.0;
                double net = i == entries.size() - 1 ? remainingNet : amount * (1 - taxRate) * entry.getValue() / 100.0; remainingNet -= net;
                payouts.add(Map.of("playerUuid", entry.getKey().toString(), "sharePercent", entry.getValue(), "gross", gross, "tax", gross - net, "net", net));
            }
            KsPluginBridge.sendJson(exchange, 200, gson.toJson(Map.of("amount", amount, "taxRate", taxRate, "tax", amount * taxRate, "net", amount * (1 - taxRate), "payouts", payouts)));
        } catch (java.sql.SQLException e) { KsPluginBridge.sendJson(exchange, 500, "{\"error\":\"分红预览失败\"}"); }
    }

    private void handleEnterpriseDividendPayouts(HttpExchange exchange, String query) throws IOException {
        KsAuthManager.Session session = requireAuth(exchange);
        if (session == null) return;
        String enterpriseId = KsPluginBridge.parseQuery(query).get("enterpriseId");
        List<Map<String, Object>> payouts = new ArrayList<>();
        try (var conn = plugin.ksCore().dataStore().getConnection()) {
            if (conn == null) { KsPluginBridge.sendJson(exchange, 500, "{\"error\":\"Database unavailable\"}"); return; }
            boolean viewEnterprise = enterpriseId != null && !enterpriseId.isBlank();
            if (viewEnterprise && !hasEnterprisePermission(conn, enterpriseId, session, EnterprisePermissionService.VIEW_FINANCE)) {
                KsPluginBridge.sendJson(exchange, 403, "{\"error\":\"Only enterprise owners can view all payout records.\"}"); return;
            }
            String sql = "SELECT p.*, e.name AS enterprise_name FROM ks_ent_dividend_payouts p "
                    + "LEFT JOIN ks_ent_enterprises e ON e.id=p.enterprise_id "
                    + (viewEnterprise ? "WHERE p.enterprise_id=? " : "WHERE p.recipient_uuid=? ")
                    + "ORDER BY p.paid_at DESC LIMIT 100";
            try (var ps = conn.prepareStatement(sql)) {
                ps.setString(1, viewEnterprise ? enterpriseId : session.playerUuid.toString());
                var rs = ps.executeQuery();
                var meta = rs.getMetaData();
                while (rs.next()) {
                    Map<String, Object> row = new LinkedHashMap<>();
                    for (int i = 1; i <= meta.getColumnCount(); i++) row.put(meta.getColumnLabel(i), rs.getObject(i));
                    payouts.add(row);
                }
            }
        } catch (java.sql.SQLException e) {
            KsPluginBridge.sendJson(exchange, 500, "{\"error\":\"Failed to read dividend payouts.\"}"); return;
        }
        KsPluginBridge.sendJson(exchange, 200, gson.toJson(Map.of("payouts", payouts, "count", payouts.size())));
    }

    private void handleProjectList(HttpExchange exchange) throws IOException {
        try (var conn = plugin.ksCore().dataStore().getConnection()) {
            if (conn != null) expireOverdueDeposits(conn);
        } catch (java.sql.SQLException ignored) {}
        List<Map<String, Object>> list = new ArrayList<>();
        queryRows(list, "SELECT * FROM ks_ent_projects ORDER BY created_at DESC LIMIT 100", null);
        // 每个项目附加投标数
        for (var p : list) {
            String pid = (String) p.get("id");
            queryInt(p, "SELECT COUNT(*) FROM ks_ent_bids WHERE project_id='" + pid.replace("'", "''") + "'", "bidCount");
        }
        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("projects", list); resp.put("count", list.size());
        KsPluginBridge.sendJson(exchange, 200, gson.toJson(resp));
    }

    private void handleProjectBids(HttpExchange exchange, String query) throws IOException {
        KsAuthManager.Session session = requireAuth(exchange);
        if (session == null) return;
        Map<String, String> params = KsPluginBridge.parseQuery(query);
        String projectId = params.get("projectId");
        if (projectId == null) { KsPluginBridge.sendJson(exchange, 400, "{\"error\":\"缺少 projectId\"}"); return; }
        List<Map<String, Object>> list = new ArrayList<>();
        queryRows(list, "SELECT * FROM ks_ent_bids WHERE project_id='" + projectId.replace("'", "''") +
            "' ORDER BY bid_amount ASC", null);
        try (var conn = plugin.ksCore().dataStore().getConnection()) {
            if (conn == null) { KsPluginBridge.sendJson(exchange, 500, "{\"error\":\"数据库未连接\"}"); return; }
            expireOverdueDeposits(conn);
            boolean viewAll = session.isAdmin;
            try (var ps = conn.prepareStatement("SELECT publisher_type,publisher_uuid FROM ks_ent_projects WHERE id=?")) {
                ps.setString(1, projectId);
                try (var rs = ps.executeQuery()) {
                    if (!rs.next()) { KsPluginBridge.sendJson(exchange, 404, "{\"error\":\"项目不存在\"}"); return; }
                    String publisherType = rs.getString("publisher_type");
                    String publisherRef = rs.getString("publisher_uuid");
                    if ("ENTERPRISE".equalsIgnoreCase(publisherType)) {
                        viewAll = viewAll || hasEnterprisePermission(conn, publisherRef, session,
                                EnterprisePermissionService.MANAGE_BIDDING);
                    } else {
                        viewAll = viewAll || session.playerUuid.toString().equalsIgnoreCase(publisherRef);
                    }
                }
            }
            if (!viewAll) {
                for (var it = list.iterator(); it.hasNext();) {
                    Map<String, Object> bid = it.next();
                    boolean ownBid = session.playerUuid.toString().equalsIgnoreCase(String.valueOf(bid.get("bidder_uuid")));
                    String enterpriseId = String.valueOf(bid.getOrDefault("enterprise_id", ""));
                    boolean ownEnterpriseBid = !enterpriseId.isBlank() && !"null".equalsIgnoreCase(enterpriseId)
                            && hasEnterprisePermission(conn, enterpriseId, session, EnterprisePermissionService.MANAGE_BIDDING);
                    if (!ownBid && !ownEnterpriseBid) it.remove();
                }
            }
        } catch (java.sql.SQLException e) {
            KsPluginBridge.sendJson(exchange, 500, "{\"error\":\"投标权限校验失败\"}"); return;
        }
        attachBidderNames(list);
        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("bids", list); resp.put("count", list.size());
        KsPluginBridge.sendJson(exchange, 200, gson.toJson(resp));
    }

    private void handleProcurementBids(HttpExchange exchange, String query) throws IOException {
        KsAuthManager.Session session = requireAuth(exchange);
        if (session == null) return;
        Map<String, String> params = KsPluginBridge.parseQuery(query);
        String procurementId = params.get("procurementId");
        if (procurementId == null) { KsPluginBridge.sendJson(exchange, 400, "{\"error\":\"缺少 procurementId\"}"); return; }
        List<Map<String, Object>> list = new ArrayList<>();
        queryRows(list, "SELECT * FROM ks_ent_procurement_bids WHERE procurement_id='" + procurementId.replace("'", "''") +
            "' ORDER BY total_price ASC", null);
        try (var conn = plugin.ksCore().dataStore().getConnection()) {
            if (conn == null) { KsPluginBridge.sendJson(exchange, 500, "{\"error\":\"数据库未连接\"}"); return; }
            boolean viewAll = session.isAdmin;
            try (var ps = conn.prepareStatement("SELECT enterprise_id FROM ks_ent_procurements WHERE id=?")) {
                ps.setString(1, procurementId);
                try (var rs = ps.executeQuery()) {
                    if (!rs.next()) { KsPluginBridge.sendJson(exchange, 404, "{\"error\":\"采购不存在\"}"); return; }
                    viewAll = viewAll || hasEnterprisePermission(conn, rs.getString("enterprise_id"), session,
                            EnterprisePermissionService.MANAGE_BIDDING);
                }
            }
            if (!viewAll) {
                for (var it = list.iterator(); it.hasNext();) {
                    Map<String, Object> bid = it.next();
                    boolean ownBid = session.playerUuid.toString().equalsIgnoreCase(String.valueOf(bid.get("bidder_uuid")));
                    String enterpriseId = String.valueOf(bid.getOrDefault("enterprise_id", ""));
                    boolean ownEnterpriseBid = !enterpriseId.isBlank() && !"null".equalsIgnoreCase(enterpriseId)
                            && hasEnterprisePermission(conn, enterpriseId, session, EnterprisePermissionService.MANAGE_BIDDING);
                    if (!ownBid && !ownEnterpriseBid) it.remove();
                }
            }
        } catch (java.sql.SQLException e) {
            KsPluginBridge.sendJson(exchange, 500, "{\"error\":\"投标权限校验失败\"}"); return;
        }
        attachBidderNames(list);
        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("bids", list); resp.put("count", list.size());
        KsPluginBridge.sendJson(exchange, 200, gson.toJson(resp));
    }

    private void handleMyBids(HttpExchange exchange) throws IOException {
        KsAuthManager.Session session = requireAuth(exchange);
        if (session == null) return;
        String uuid = session.playerUuid.toString();
        try (var conn = plugin.ksCore().dataStore().getConnection()) {
            if (conn != null) expireOverdueDeposits(conn);
        } catch (java.sql.SQLException ignored) {}
        List<Map<String, Object>> projectBids = new ArrayList<>();
        queryRows(projectBids, "SELECT b.*, p.title AS project_title, p.status AS project_status FROM ks_ent_bids b " +
            "LEFT JOIN ks_ent_projects p ON p.id = b.project_id WHERE b.bidder_uuid='" + uuid.replace("'", "''") +
            "' ORDER BY b.submitted_at DESC LIMIT 100", null);
        attachBidderNames(projectBids);
        List<Map<String, Object>> procBids = new ArrayList<>();
        queryRows(procBids, "SELECT b.*, p.title AS procurement_title, p.status AS procurement_status FROM ks_ent_procurement_bids b " +
            "LEFT JOIN ks_ent_procurements p ON p.id = b.procurement_id WHERE b.bidder_uuid='" + uuid.replace("'", "''") +
            "' ORDER BY b.submitted_at DESC LIMIT 100", null);
        attachBidderNames(procBids);
        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("projectBids", projectBids);
        resp.put("procurementBids", procBids);
        KsPluginBridge.sendJson(exchange, 200, gson.toJson(resp));
    }

    @SuppressWarnings("unchecked")
    private void handleProjectPublish(HttpExchange exchange) throws IOException {
        KsAuthManager.Session session = requireAuth(exchange);
        if (session == null) return;
        Map<String, Object> req = gson.fromJson(KsPluginBridge.readBody(exchange), Map.class);
        if (req == null) { KsPluginBridge.sendJson(exchange, 400, "{\"error\":\"无效请求\"}"); return; }
        String title = (String) req.get("title");
        String publisherUuid = (String) req.get("publisherRef");
        if (publisherUuid == null || publisherUuid.isBlank()) publisherUuid = (String) req.get("publisherUuid");
        String publisherType = req.getOrDefault("publisherType", "OFFICIAL").toString().toUpperCase();
        double budget = toDouble(req.get("budget"), 10000);
        double prepay = toDouble(req.get("prepaymentRatio"), 0.3);
        double penalty = toDouble(req.get("penaltyRatio"), 0.1);
        double depositRatio = toDouble(req.get("depositRatio"), 0);
        int depositDeadlineHours = (int) toDouble(req.get("depositDeadlineHours"), 24);
        long deadline = (long) toDouble(req.get("deadline"), 0);
        String location = (String) req.getOrDefault("location", "");
        boolean subcontract = (boolean) req.getOrDefault("allowSubcontract", true);
        boolean consortium = (boolean) req.getOrDefault("allowConsortium", true);
        if (title == null || title.isBlank() || title.length() > 128 || publisherUuid == null || publisherUuid.isBlank()) {
            KsPluginBridge.sendJson(exchange, 400, "{\"error\":\"缺少 title/publisherUuid\"}"); return;
        }
        if (!Set.of("OFFICIAL", "STATE_OWNED", "ENTERPRISE", "PLAYER").contains(publisherType)
                || !Double.isFinite(budget) || budget <= 0
                || !Double.isFinite(prepay) || prepay < 0 || prepay > 1
                || !Double.isFinite(penalty) || penalty < 0 || penalty > 1
                || !Double.isFinite(depositRatio) || depositRatio < 0 || depositRatio > 1
                || depositDeadlineHours < 1 || depositDeadlineHours > 24 * 30
                || deadline <= System.currentTimeMillis() / 1000) {
            KsPluginBridge.sendJson(exchange, 400, "{\"error\":\"项目资金参数无效\"}"); return;
        }
        if ("PLAYER".equals(publisherType)) {
            KsPluginBridge.sendJson(exchange, 400, "{\"error\":\"个人项目暂不支持预付款托管，请使用企业项目\"}"); return;
        }
        if (("OFFICIAL".equals(publisherType) || "STATE_OWNED".equals(publisherType)) && prepay > 0) {
            KsPluginBridge.sendJson(exchange, 400, "{\"error\":\"官方项目尚未配置财政托管账户，不能创建带预付款的项目\"}"); return;
        }
        // 权限校验：官方/国企招标仅管理员可发布；企业招标须为该企业所有者；个人招标须为本人
        try (var conn = plugin.ksCore().dataStore().getConnection()) {
            if (conn == null) { KsPluginBridge.sendJson(exchange, 500, "{\"error\":\"数据库未连接\"}"); return; }
            if ("OFFICIAL".equals(publisherType) || "STATE_OWNED".equals(publisherType)) {
                if (!session.isAdmin) {
                    KsPluginBridge.sendJson(exchange, 403, "{\"error\":\"仅管理员可发布官方/国企招标\"}"); return;
                }
            } else if ("ENTERPRISE".equals(publisherType)) {
                if (!hasEnterprisePermission(conn, publisherUuid, session, EnterprisePermissionService.MANAGE_BIDDING)) {
                    KsPluginBridge.sendJson(exchange, 403, "{\"error\":\"只有该企业所有者才能以企业名义发布招标\"}"); return;
                }
            } else {
                if (!session.isAdmin && !publisherUuid.equals(session.playerUuid.toString())) {
                    KsPluginBridge.sendJson(exchange, 403, "{\"error\":\"只能以本人身份发布招标\"}"); return;
                }
            }
            double escrowAmount = budget * prepay;
            conn.setAutoCommit(false);
            // 直接写入数据库（publisher_uuid 为 TEXT，企业ID/玩家UUID均可存储，避免反射模块的 UUID 强转崩溃）
            String id = UUID.randomUUID().toString().substring(0, 8);
            long now = System.currentTimeMillis() / 1000;
            try (var ps = conn.prepareStatement(
                    "INSERT INTO ks_ent_projects (id, title, publisher_uuid, publisher_type, budget, prepayment_ratio, penalty_ratio, deposit_ratio, deposit_deadline_hours, deadline, location, allow_subcontract, allow_consortium, status, created_at) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?, 'OPEN', ?)")) {
                ps.setString(1, id);
                ps.setString(2, title);
                ps.setString(3, publisherUuid);
                ps.setString(4, publisherType);
                ps.setDouble(5, budget);
                ps.setDouble(6, prepay);
                ps.setDouble(7, penalty);
                ps.setDouble(8, depositRatio);
                ps.setInt(9, depositDeadlineHours);
                ps.setLong(10, deadline);
                ps.setString(11, location);
                ps.setInt(12, subcontract ? 1 : 0);
                ps.setInt(13, consortium ? 1 : 0);
                ps.setLong(14, now);
                ps.executeUpdate();
            }
            try (var ps = conn.prepareStatement(
                    "INSERT INTO ks_ent_project_escrow (project_id,publisher_type,publisher_ref,remaining,created_at) VALUES (?,?,?,?,?)")) {
                ps.setString(1, id);
                ps.setString(2, publisherType);
                ps.setString(3, publisherUuid);
                ps.setDouble(4, escrowAmount);
                ps.setLong(5, now);
                ps.executeUpdate();
            }
            if ("ENTERPRISE".equals(publisherType) && !reserveCorporateProjectEscrow(conn, publisherUuid, escrowAmount)) {
                conn.rollback();
                KsPluginBridge.sendJson(exchange, 400, "{\"error\":\"企业公户余额不足，无法托管项目预付款\"}"); return;
            }
            conn.commit();
            auditLog("PROJECT_PUBLISH", session.playerUuid.toString(), session.playerName, "project", id,
                "标题: " + title + " | 发布方: " + publisherType + "(" + publisherUuid + ") | 预算: " + String.format("%.2f", budget));
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("id", id); result.put("title", title); result.put("publisherUuid", publisherUuid);
            result.put("publisherType", publisherType); result.put("budget", budget); result.put("status", "OPEN");
            KsPluginBridge.sendJson(exchange, 200, gson.toJson(result));
        } catch (java.sql.SQLException e) {
            KsPluginBridge.sendJson(exchange, 500, "{\"error\":\"发布失败: " + e.getMessage() + "\"}");
        }
    }

    @SuppressWarnings("unchecked")
    private void handleBidSubmit(HttpExchange exchange) throws IOException {
        KsAuthManager.Session session = requireAuth(exchange);
        if (session == null) return;
        Map<String, Object> req = gson.fromJson(KsPluginBridge.readBody(exchange), Map.class);
        if (req == null) { KsPluginBridge.sendJson(exchange, 400, "{\"error\":\"无效请求\"}"); return; }
        String projectId = (String) req.get("projectId");
        String enterpriseId = (String) req.get("enterpriseId");
        String bidderUuid = (String) req.get("bidderUuid");
        String bidderType = req.getOrDefault("bidderType", enterpriseId != null ? "ENTERPRISE" : "PLAYER").toString().toUpperCase(Locale.ROOT);
        double bidAmount = toDouble(req.get("bidAmount"), 0);
        boolean isConsortium = (boolean) req.getOrDefault("isConsortium", false);
        List<String> members = (List<String>) req.getOrDefault("consortiumMembers", List.of());
        if (projectId == null || (enterpriseId == null && bidderUuid == null)) {
            KsPluginBridge.sendJson(exchange, 400, "{\"error\":\"缺少 projectId 以及 enterpriseId 或 bidderUuid\"}"); return;
        }
        if (!Set.of("PLAYER", "ENTERPRISE").contains(bidderType)) {
            KsPluginBridge.sendJson(exchange, 400, "{\"error\":\"无效的投标方类型\"}"); return;
        }
        if (!Double.isFinite(bidAmount) || bidAmount <= 0) {
            KsPluginBridge.sendJson(exchange, 400, "{\"error\":\"投标金额必须大于 0\"}"); return;
        }
        if ("PLAYER".equals(bidderType)) {
            if (enterpriseId != null && !enterpriseId.isBlank()) {
                KsPluginBridge.sendJson(exchange, 400, "{\"error\":\"个人投标不能指定企业\"}"); return;
            }
            if (bidderUuid != null && !bidderUuid.equals(session.playerUuid.toString())) {
                KsPluginBridge.sendJson(exchange, 403, "{\"error\":\"不能代替其他玩家投标\"}"); return;
            }
            bidderUuid = session.playerUuid.toString();
        } else {
            if (enterpriseId == null || enterpriseId.isBlank()) {
                KsPluginBridge.sendJson(exchange, 400, "{\"error\":\"企业投标必须指定企业\"}"); return;
            }
            bidderUuid = session.playerUuid.toString();
        }
        // 直接写入 DB（绕过 extra module classloader）
        String bidId = UUID.randomUUID().toString().substring(0, 8);
        long now = System.currentTimeMillis() / 1000;
        try (var conn = plugin.ksCore().dataStore().getConnection()) {
            if (conn == null) { KsPluginBridge.sendJson(exchange, 500, "{\"error\":\"数据库未连接\"}"); return; }
            double projectBudget;
            try (var project = conn.prepareStatement(
                    "SELECT status,budget,deadline FROM ks_ent_projects WHERE id=?")) {
                project.setString(1, projectId);
                try (var rows = project.executeQuery()) {
                    if (!rows.next()) { KsPluginBridge.sendJson(exchange, 404, "{\"error\":\"项目不存在\"}"); return; }
                    if (!"OPEN".equals(rows.getString("status"))
                            || !ProjectBiddingDeadlineStore.acceptsBid(rows.getLong("deadline"), now)) {
                        KsPluginBridge.sendJson(exchange, 409, "{\"error\":\"项目已截止或不再接受投标\"}"); return;
                    }
                    projectBudget = rows.getDouble("budget");
                }
            }
            // 如果是企业投标，做资质检查
            if ("ENTERPRISE".equals(bidderType) && enterpriseId != null) {
                if (!hasEnterprisePermission(conn, enterpriseId, session, EnterprisePermissionService.MANAGE_BIDDING)) {
                    KsPluginBridge.sendJson(exchange, 403, "{\"error\":\"没有该企业的投标权限\"}"); return;
                }
                var rs2 = conn.createStatement().executeQuery(
                    "SELECT registered_capital FROM ks_ent_enterprises WHERE id='" + enterpriseId.replace("'", "''") + "' AND status='ACTIVE'");
                if (!rs2.next()) { KsPluginBridge.sendJson(exchange, 404, "{\"error\":\"企业不存在或已注销\"}"); return; }
                double regCap = rs2.getDouble("registered_capital");
                if (regCap < projectBudget * 0.75) {
                    KsPluginBridge.sendJson(exchange, 400, "{\"error\":\"企业资质不足（注册资本需 ≥ 项目预算的75%）\"}"); return;
                }
            }
            if (ProjectBiddingDeadlineStore.insertBidIfOpen(conn, bidId, projectId,
                    enterpriseId, bidderUuid, bidderType, bidAmount, isConsortium,
                    String.join(",", members), now) != 1) {
                KsPluginBridge.sendJson(exchange, 409,
                        "{\"error\":\"项目状态或截止时间已变更，投标未写入\"}");
                return;
            }
            Map<String, Object> bid = new LinkedHashMap<>();
            bid.put("id", bidId); bid.put("projectId", projectId);
            bid.put("enterpriseId", enterpriseId); bid.put("bidderUuid", bidderUuid);
            bid.put("bidderType", bidderType); bid.put("bidAmount", bidAmount);
            bid.put("status", "PENDING"); bid.put("submittedAt", now);
            auditLog("BID_SUBMIT", session.playerUuid.toString(), session.playerName, "project", projectId,
                "投标方: " + bidderType + " | 金额: " + String.format("%.2f", bidAmount) + " | ID: " + bidId);
            KsPluginBridge.sendJson(exchange, 200, gson.toJson(bid));
        } catch (java.sql.SQLException e) {
            KsPluginBridge.sendJson(exchange, 500, "{\"error\":\"投标失败: " + e.getMessage() + "\"}");
        }
    }

    @SuppressWarnings("unchecked")
    private void handleProjectAward(HttpExchange exchange) throws IOException {
        KsAuthManager.Session session = requireAuth(exchange);
        if (session == null) return;
        Map<String, Object> req = gson.fromJson(KsPluginBridge.readBody(exchange), Map.class);
        if (req == null) { KsPluginBridge.sendJson(exchange, 400, "{\"error\":\"无效请求\"}"); return; }
        String projectId = (String) req.get("projectId");
        String manualBidId = (String) req.get("bidId"); // 手动指定中标方（仅私企可用）
        if (projectId == null) { KsPluginBridge.sendJson(exchange, 400, "{\"error\":\"缺少 projectId\"}"); return; }

        // 评分权重: 价格50% + 资质30% + 时效20%
        final double W_PRICE = 0.50, W_QUAL = 0.30, W_TIME = 0.20;

        try (var conn = plugin.ksCore().dataStore().getConnection()) {
            if (conn == null) { KsPluginBridge.sendJson(exchange, 500, "{\"error\":\"数据库未连接\"}"); return; }
            conn.setAutoCommit(false);
            expireOverdueDeposits(conn);
            // 获取项目信息（用完立即关闭 Statement/ResultSet，避免在同一连接上堆叠未关闭的游标导致 SQLITE_BUSY_SNAPSHOT）
            String publisherType, publisherUuid; double budget, prepayRatio, depositRatio;
            int depositDeadlineHours; long projectDeadline;
            try (var st = conn.createStatement();
                 var rsProj = st.executeQuery("SELECT * FROM ks_ent_projects WHERE id='" + projectId.replace("'", "''") + "'")) {
                if (!rsProj.next()) { KsPluginBridge.sendJson(exchange, 404, "{\"error\":\"项目不存在\"}"); return; }
                publisherType = rsProj.getString("publisher_type");
                publisherUuid = rsProj.getString("publisher_uuid");
                budget = rsProj.getDouble("budget");
                prepayRatio = rsProj.getDouble("prepayment_ratio");
                depositRatio = rsProj.getDouble("deposit_ratio");
                depositDeadlineHours = rsProj.getInt("deposit_deadline_hours");
                projectDeadline = rsProj.getLong("deadline");
                if (!"OPEN".equals(rsProj.getString("status"))) {
                    KsPluginBridge.sendJson(exchange, 400, "{\"error\":\"项目已不是开放状态（可能已评标或处于待缴保证金阶段）\"}"); return;
                }
            }
            if (!ProjectBiddingDeadlineStore.acceptsAward(projectDeadline,
                    System.currentTimeMillis() / 1000)) {
                KsPluginBridge.sendJson(exchange, 409,
                        "{\"error\":\"投标尚未截止，评标必须在截止时间后进行\"}");
                return;
            }
            // 权限校验：官方/国企招标仅管理员可评标；企业招标须为该企业所有者；个人招标须为发布人本人
            if ("OFFICIAL".equalsIgnoreCase(publisherType) || "STATE_OWNED".equalsIgnoreCase(publisherType)) {
                if (!session.isAdmin) {
                    KsPluginBridge.sendJson(exchange, 403, "{\"error\":\"官方/国企招标仅管理员可评标\"}"); return;
                }
            } else if ("ENTERPRISE".equalsIgnoreCase(publisherType)) {
                if (!hasEnterprisePermission(conn, publisherUuid, session, EnterprisePermissionService.MANAGE_BIDDING)) {
                    KsPluginBridge.sendJson(exchange, 403, "{\"error\":\"只有该企业所有者才能评标\"}"); return;
                }
            } else {
                if (!session.isAdmin && !session.playerUuid.toString().equals(publisherUuid)) {
                    KsPluginBridge.sendJson(exchange, 403, "{\"error\":\"只有发布人本人才能评标\"}"); return;
                }
            }
            double maximumPrepayment = budget * prepayRatio;

            // 获取所有待处理投标
            List<Map<String, Object>> candidates = new ArrayList<>();
            try (var st = conn.createStatement();
                 var rsBids = st.executeQuery("SELECT * FROM ks_ent_bids WHERE project_id='" + projectId.replace("'", "''") + "' AND status='PENDING'")) {
                while (rsBids.next()) {
                    Map<String, Object> c = new LinkedHashMap<>();
                    c.put("id", rsBids.getString("id"));
                    c.put("enterprise_id", rsBids.getString("enterprise_id"));
                    c.put("bidder_uuid", rsBids.getString("bidder_uuid"));
                    String bType = rsBids.getString("bidder_type") != null ? rsBids.getString("bidder_type")
                        : (rsBids.getString("enterprise_id") != null ? "ENTERPRISE" : "PLAYER");
                    c.put("bidder_type", bType);
                    c.put("bid_amount", rsBids.getDouble("bid_amount"));
                    c.put("submitted_at", rsBids.getLong("submitted_at"));
                    candidates.add(c);
                }
            }
            if (candidates.isEmpty()) { KsPluginBridge.sendJson(exchange, 500, "{\"error\":\"评标失败（无投标）\"}"); return; }

            // ===== 判断评标模式 =====
            final boolean isManual = (manualBidId != null && !manualBidId.isEmpty());
            final boolean isStrict = "STATE_OWNED".equalsIgnoreCase(publisherType) || "OFFICIAL".equalsIgnoreCase(publisherType);

            if (isManual && isStrict) {
                KsPluginBridge.sendJson(exchange, 400,
                    "{\"error\":\"国有企业和官方项目必须采用综合评分标准评标，不可自主挑选中标方\"}");
                return;
            }

            Map<String, Object> winner = null;
            String awardMode;
            List<Map<String, Object>> scoreDetails = new ArrayList<>();

            if (isManual) {
                // ===== 手动指定中标（仅私企） =====
                for (var c : candidates) {
                    if (manualBidId.equals(c.get("id"))) { winner = c; break; }
                }
                if (winner == null) {
                    KsPluginBridge.sendJson(exchange, 404, "{\"error\":\"指定的投标不存在或已处理\"}"); return;
                }
                awardMode = "自主挑选";

            } else {
                // ===== 综合评分算法（国企/官方强制，私企可选） =====
                double minBid = Double.MAX_VALUE, maxBid = 0;
                long earliestBid = Long.MAX_VALUE, latestBid = 0;
                for (var c : candidates) {
                    double ba = (double) c.get("bid_amount");
                    if (ba < minBid) minBid = ba;
                    if (ba > maxBid) maxBid = ba;
                    long sa = (long) c.get("submitted_at");
                    if (sa < earliestBid) earliestBid = sa;
                    if (sa > latestBid) latestBid = sa;
                }
                double bidRange = maxBid > minBid ? (maxBid - minBid) : 1;
                double timeRange = latestBid > earliestBid ? (latestBid - earliestBid) : 1;
                double bestScore = -1;

                for (var c : candidates) {
                    double bidAmount = (double) c.get("bid_amount");
                    long submittedAt = (long) c.get("submitted_at");
                    String bidderType = (String) c.get("bidder_type");
                    String entId = (String) c.get("enterprise_id");

                    double priceScore = 1.0 - (bidAmount - minBid) / bidRange;
                    if (bidRange == 1) priceScore = 1.0;

                    double qualScore = 0.3;
                    if ("ENTERPRISE".equals(bidderType) && entId != null && !entId.isEmpty()) {
                        try (var st = conn.createStatement();
                             var rsEnt = st.executeQuery("SELECT registered_capital FROM ks_ent_enterprises WHERE id='" + entId.replace("'", "''") + "'")) {
                            if (rsEnt.next()) {
                                qualScore = Math.min(1.0, rsEnt.getDouble("registered_capital") / (budget * 1.5));
                            }
                        } catch (java.sql.SQLException ignored) {}
                    }

                    double timeScore = timeRange == 1 ? 1.0 : 1.0 - (submittedAt - earliestBid) / timeRange;
                    double composite = priceScore * W_PRICE + qualScore * W_QUAL + timeScore * W_TIME;

                    Map<String, Object> detail = new LinkedHashMap<>();
                    detail.put("bidId", c.get("id"));
                    detail.put("bidderType", bidderType);
                    detail.put("bidAmount", bidAmount);
                    detail.put("priceScore", Math.round(priceScore * 1000.0) / 1000.0);
                    detail.put("qualScore", Math.round(qualScore * 1000.0) / 1000.0);
                    detail.put("timeScore", Math.round(timeScore * 1000.0) / 1000.0);
                    detail.put("composite", Math.round(composite * 1000.0) / 1000.0);
                    scoreDetails.add(detail);

                    if (composite > bestScore) { bestScore = composite; winner = c; }
                }
                awardMode = "综合评分";
            }

            // 确定中标方
            String winBidId = (String) winner.get("id");
            String winEntId = (String) winner.get("enterprise_id");
            String winBidderUuid = (String) winner.get("bidder_uuid");
            String winBidderType = (String) winner.get("bidder_type");
            double winBidAmount = (double) winner.get("bid_amount");
            double prepayment = winBidAmount * prepayRatio;
            if (prepayment > maximumPrepayment + 0.000001d) {
                KsPluginBridge.sendJson(exchange, 400, "{\"error\":\"中标金额超过项目托管预算，无法发放预付款\"}"); return;
            }

            long awardNow = System.currentTimeMillis() / 1000;
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("id", winBidId); result.put("project_id", projectId);
            result.put("enterprise_id", winEntId); result.put("bidder_uuid", winBidderUuid);
            result.put("bidder_type", winBidderType); result.put("bid_amount", winBidAmount);
            result.put("award_mode", awardMode);
            if (!isManual) {
                result.put("scoring_method", "综合评分: 价格" + String.format("%.0f", W_PRICE*100) + "% + 资质" + String.format("%.0f", W_QUAL*100) + "% + 时效" + String.format("%.0f", W_TIME*100) + "%");
                result.put("score_details", scoreDetails);
            }

            if ("PLAYER".equalsIgnoreCase(winBidderType) && depositRatio <= 0.0d && prepayment > 0.0d) {
                ProjectWalletSettlementStore.Settlement settlement =
                        ProjectWalletSettlementService.prepareDirectAward(conn, projectId, winBidId,
                                UUID.fromString(winBidderUuid), prepayment, awardNow);
                conn.commit();
                ProjectSettlementResult outcome = processPersonalProjectPrepayment(settlement);
                result.put("settlementId", outcome.settlementId());
                result.put("prepayment", prepayment);
                result.put("status", outcome.success() ? "AWARDED"
                        : outcome.reviewRequired() ? "REVIEW_REQUIRED" : "OPEN");
                result.put("message", outcome.message());
                if (outcome.success()) {
                    auditLog("PROJECT_AWARD", session.playerUuid.toString(), session.playerName,
                            "project", projectId, "个人中标预付款已通过持久结算发放");
                }
                KsPluginBridge.sendJson(exchange, outcome.success() ? 200 : outcome.reviewRequired() ? 202 : 409,
                        gson.toJson(result));
                return;
            }

            if (depositRatio > 0) {
                // ===== 需先缴纳保证金才能确认中标，此时不发放预付款、不淘汰其余投标 =====
                double depositAmount = winBidAmount * depositRatio;
                long depositDeadline = awardNow + depositDeadlineHours * 3600L;
                if (ProjectBiddingDeadlineStore.claimProjectForAward(
                        conn, projectId, "PENDING_DEPOSIT", awardNow) != 1) {
                    throw new java.sql.SQLException("Project award window or status changed");
                }
                try (var bidClaim = conn.prepareStatement(
                        "UPDATE ks_ent_bids SET status='PENDING_DEPOSIT',deposit_amount=?,deposit_deadline=? "
                                + "WHERE id=? AND project_id=? AND status='PENDING'")) {
                    bidClaim.setDouble(1, depositAmount);
                    bidClaim.setLong(2, depositDeadline);
                    bidClaim.setString(3, winBidId);
                    bidClaim.setString(4, projectId);
                    if (bidClaim.executeUpdate() != 1) {
                        throw new java.sql.SQLException("Project bid changed before deposit claim");
                    }
                }
                conn.commit();
                auditLog("PROJECT_AWARD_PENDING_DEPOSIT", session.playerUuid.toString(), session.playerName, "project", projectId,
                    "模式: " + awardMode + " | 拟中标方: " + winBidderType + " | 金额: " + String.format("%.2f", winBidAmount) +
                    " | 待缴保证金: " + String.format("%.2f", depositAmount));
                result.put("status", "PENDING_DEPOSIT");
                result.put("depositAmount", depositAmount);
                result.put("depositDeadline", depositDeadline);
                result.put("message", "评标完成（" + awardMode + "），中标方需在 " + depositDeadlineHours + " 小时内缴纳保证金 " +
                    String.format("%.2f", depositAmount) + " 才能正式中标，否则将自动流标");
                KsPluginBridge.sendJson(exchange, 200, gson.toJson(result));
                return;
            }

            // ===== 无保证金要求：维持原有流程，直接确认中标并发放预付款 =====
            if (conn.createStatement().executeUpdate(
                "UPDATE ks_ent_bids SET status='AWARDED' WHERE id='" + winBidId.replace("'", "''") + "' AND status='PENDING'") != 1) {
                throw new java.sql.SQLException("Project bid was already awarded");
            }
            conn.createStatement().executeUpdate(
                "UPDATE ks_ent_bids SET status='REJECTED' WHERE project_id='" + projectId.replace("'", "''") +
                "' AND status='PENDING' AND id<>'" + winBidId.replace("'", "''") + "'");
            if (ProjectBiddingDeadlineStore.claimProjectForAward(
                    conn, projectId, "AWARDED", awardNow) != 1) {
                throw new java.sql.SQLException("Project was already awarded");
            }

            payProjectPrepayment(conn, winEntId, winBidderUuid, winBidderType, prepayment, projectId, session, awardNow);

            conn.commit();
            auditLog("PROJECT_AWARD", session.playerUuid.toString(), session.playerName, "project", projectId,
                "模式: " + awardMode + " | 中标方: " + winBidderType + " | 金额: " + String.format("%.2f", winBidAmount) +
                (isManual ? "" : " | 综合得分: " + String.format("%.3f", scoreDetails.stream()
                    .filter(s -> winBidId.equals(s.get("bidId"))).findFirst()
                    .map(s -> (double)s.get("composite")).orElse(0.0))));

            result.put("prepayment", prepayment); result.put("status", "AWARDED");
            result.put("message", "评标完成（" + awardMode + "），预付款 " + String.format("%.2f", prepayment) + " 已发放");
            KsPluginBridge.sendJson(exchange, 200, gson.toJson(result));
        } catch (java.sql.SQLException | IOException e) {
            KsPluginBridge.sendJson(exchange, 500, "{\"error\":\"评标失败: " + e.getMessage() + "\"}");
        }
    }

    // 发放项目中标预付款 → 企业进公户，个人进钱包（使用传入连接，避免 SQLITE_BUSY）
    private void payProjectPrepayment(java.sql.Connection conn, String winEntId, String winBidderUuid, String winBidderType,
            double prepayment, String projectId, KsAuthManager.Session session, long now) throws java.sql.SQLException, IOException {
        if (prepayment <= 0) return;
        try (var ps = conn.prepareStatement(
                "UPDATE ks_ent_project_escrow SET remaining=remaining-? WHERE project_id=? AND remaining>=?")) {
            ps.setDouble(1, prepayment);
            ps.setString(2, projectId);
            ps.setDouble(3, prepayment);
            if (ps.executeUpdate() != 1) throw new java.sql.SQLException("Project prepayment is not funded by escrow");
        }
        if ("ENTERPRISE".equals(winBidderType) && winEntId != null && !winEntId.isEmpty()) {
            ensureCorporateAccount(conn, winEntId, CORP_BANK_ID, 0, now);
            int accountUpdated = conn.createStatement().executeUpdate(
                "UPDATE ks_ent_corporate_accounts SET balance = balance + " + prepayment + ", updated_at = " + now +
                " WHERE enterprise_id='" + winEntId.replace("'", "''") + "'");
            int enterpriseUpdated = conn.createStatement().executeUpdate(
                "UPDATE ks_ent_enterprises SET current_assets = current_assets + " + prepayment +
                " WHERE id='" + winEntId.replace("'", "''") + "'");
            int bankUpdated = conn.createStatement().executeUpdate(
                "UPDATE ks_bank_banks SET total_assets = total_assets + " + prepayment +
                " WHERE id=(SELECT bank_id FROM ks_ent_corporate_accounts WHERE enterprise_id='" + winEntId.replace("'", "''") + "')");
            if (accountUpdated != 1 || enterpriseUpdated != 1 || bankUpdated != 1) {
                throw new java.sql.SQLException("Project prepayment accounting rows are incomplete");
            }
        } else if (winBidderUuid != null && !winBidderUuid.isEmpty()) {
            if (!depositWallet(UUID.fromString(winBidderUuid), prepayment)) {
                throw new java.sql.SQLException("Project prepayment wallet payout failed");
            }
        }
    }

    private boolean reserveCorporateProjectEscrow(java.sql.Connection conn, String enterpriseId, double amount) throws java.sql.SQLException {
        if (amount <= 0) return true;
        String bankId = getCorporateBankId(conn, enterpriseId);
        if (bankId == null) return false;
        try (var ps = conn.prepareStatement(
                "UPDATE ks_ent_corporate_accounts SET balance=balance-?, updated_at=? WHERE enterprise_id=? AND balance>=?")) {
            ps.setDouble(1, amount);
            ps.setLong(2, System.currentTimeMillis() / 1000);
            ps.setString(3, enterpriseId);
            ps.setDouble(4, amount);
            if (ps.executeUpdate() != 1) return false;
        }
        try (var ps = conn.prepareStatement(
                "UPDATE ks_ent_enterprises SET current_assets=current_assets-? WHERE id=? AND current_assets>=?")) {
            ps.setDouble(1, amount);
            ps.setString(2, enterpriseId);
            ps.setDouble(3, amount);
            if (ps.executeUpdate() != 1) throw new java.sql.SQLException("Enterprise asset balance is inconsistent");
        }
        try (var ps = conn.prepareStatement(
                "UPDATE ks_bank_banks SET total_assets=total_assets-? WHERE id=? AND total_assets>=?")) {
            ps.setDouble(1, amount);
            ps.setString(2, bankId);
            ps.setDouble(3, amount);
            if (ps.executeUpdate() != 1) throw new java.sql.SQLException("Corporate bank asset balance is inconsistent");
        }
        return true;
    }

    private ProjectSettlementResult processPersonalProjectPrepayment(
            ProjectWalletSettlementStore.Settlement settlement) {
        String expected = ProjectWalletSettlementStore.DEPOSIT_HELD.equals(settlement.status())
                ? ProjectWalletSettlementStore.DEPOSIT_HELD
                : ProjectWalletSettlementStore.PREPAYMENT_READY;
        try {
            boolean claimed = projectTransaction(connection ->
                    ProjectWalletSettlementService.claimPrepayment(connection, settlement.id(), expected, projectNow()));
            if (!claimed) return ProjectSettlementResult.failed(settlement.id(),
                    "项目预付款已被其他请求处理，请刷新状态");
        } catch (SQLException exception) {
            return ProjectSettlementResult.failed(settlement.id(), "无法认领项目预付款: " + exception.getMessage());
        }

        final boolean paid;
        try {
            paid = depositWallet(settlement.playerUuid(), settlement.prepaymentAmount());
        } catch (IOException unknown) {
            markProjectSettlementReview(settlement.id(), ProjectWalletSettlementStore.PREPAYMENT_CLAIMED,
                    "prepayment wallet outcome unknown: " + unknown.getMessage());
            return ProjectSettlementResult.review(settlement.id(), "预付款钱包结果未知，已进入人工复核");
        }

        if (!paid) {
            try {
                if (ProjectWalletSettlementStore.DIRECT_AWARD.equals(settlement.kind())) {
                    projectTransaction(connection -> {
                        ProjectWalletSettlementService.rollbackRejectedExternalCall(connection, settlement,
                                ProjectWalletSettlementStore.PREPAYMENT_CLAIMED,
                                "prepayment payout rejected", projectNow());
                        return null;
                    });
                } else {
                    projectTransaction(connection -> {
                        if (!ProjectWalletSettlementStore.transition(connection, settlement.id(),
                                ProjectWalletSettlementStore.PREPAYMENT_CLAIMED,
                                ProjectWalletSettlementStore.PREPAYMENT_READY,
                                "prepayment payout rejected", projectNow())) {
                            throw new SQLException("prepayment retry state changed concurrently");
                        }
                        return null;
                    });
                }
            } catch (SQLException exception) {
                markProjectSettlementReview(settlement.id(), ProjectWalletSettlementStore.PREPAYMENT_CLAIMED,
                        "payout rejected but rollback failed: " + exception.getMessage());
                return ProjectSettlementResult.review(settlement.id(), "预付款失败后的回滚状态不确定，已进入人工复核");
            }
            return ProjectSettlementResult.failed(settlement.id(),
                    ProjectWalletSettlementStore.DIRECT_AWARD.equals(settlement.kind())
                            ? "预付款入账失败，项目已恢复开放"
                            : "预付款入账失败，保证金仍在托管，可重新尝试发放");
        }

        try {
            projectTransaction(connection -> {
                ProjectWalletSettlementService.finalizePayout(connection, settlement, projectNow());
                return null;
            });
            return ProjectSettlementResult.success(settlement.id(), "个人预付款已发放，项目已正式中标");
        } catch (SQLException exception) {
            markProjectSettlementReview(settlement.id(), ProjectWalletSettlementStore.PREPAYMENT_CLAIMED,
                    "wallet paid but SQL finalization failed: " + exception.getMessage());
            return ProjectSettlementResult.review(settlement.id(), "预付款可能已到账，但项目终态提交失败，已进入人工复核");
        }
    }

    private ProjectSettlementResult processPersonalProjectDeposit(
            ProjectWalletSettlementStore.Settlement settlement) {
        try {
            boolean claimed = projectTransaction(connection ->
                    ProjectWalletSettlementService.claimDepositCharge(connection, settlement.id(), projectNow()));
            if (!claimed) return ProjectSettlementResult.failed(settlement.id(),
                    "保证金已被其他请求处理，请刷新状态");
        } catch (SQLException exception) {
            return ProjectSettlementResult.failed(settlement.id(), "无法认领个人保证金: " + exception.getMessage());
        }

        final boolean charged;
        try {
            charged = withdrawWallet(settlement.playerUuid(), settlement.depositAmount());
        } catch (IOException unknown) {
            markProjectSettlementReview(settlement.id(), ProjectWalletSettlementStore.DEPOSIT_CHARGE_CLAIMED,
                    "deposit wallet outcome unknown: " + unknown.getMessage());
            return ProjectSettlementResult.review(settlement.id(), "保证金扣款结果未知，已进入人工复核");
        }
        if (!charged) {
            try {
                projectTransaction(connection -> {
                    ProjectWalletSettlementService.rollbackRejectedExternalCall(connection, settlement,
                            ProjectWalletSettlementStore.DEPOSIT_CHARGE_CLAIMED,
                            "deposit charge rejected", projectNow());
                    return null;
                });
                return ProjectSettlementResult.failed(settlement.id(), "个人保证金扣款失败，投标仍可重新缴纳");
            } catch (SQLException exception) {
                markProjectSettlementReview(settlement.id(), ProjectWalletSettlementStore.DEPOSIT_CHARGE_CLAIMED,
                        "charge rejected but rollback failed: " + exception.getMessage());
                return ProjectSettlementResult.review(settlement.id(), "保证金失败后的回滚状态不确定，已进入人工复核");
            }
        }

        try {
            projectTransaction(connection -> {
                ProjectWalletSettlementService.recordDepositHeld(connection, settlement, projectNow());
                return null;
            });
        } catch (SQLException exception) {
            markProjectSettlementReview(settlement.id(), ProjectWalletSettlementStore.DEPOSIT_CHARGE_CLAIMED,
                    "wallet charged but deposit hold failed: " + exception.getMessage());
            return ProjectSettlementResult.review(settlement.id(), "保证金已可能扣除，但托管提交失败，已进入人工复核");
        }

        ProjectWalletSettlementStore.Settlement held = new ProjectWalletSettlementStore.Settlement(
                settlement.id(), settlement.kind(), settlement.projectId(), settlement.bidId(),
                settlement.playerUuid(), settlement.depositAmount(), settlement.prepaymentAmount(),
                ProjectWalletSettlementStore.DEPOSIT_HELD, "", "");
        if (held.prepaymentAmount() > 0.0d) return processPersonalProjectPrepayment(held);
        try {
            projectTransaction(connection -> {
                ProjectWalletSettlementService.finalizeDepositWithoutPayout(connection, held, projectNow());
                return null;
            });
            return ProjectSettlementResult.success(settlement.id(), "个人保证金已托管，项目已正式中标");
        } catch (SQLException exception) {
            markProjectSettlementReview(settlement.id(), ProjectWalletSettlementStore.DEPOSIT_HELD,
                    "deposit held but finalization failed: " + exception.getMessage());
            return ProjectSettlementResult.review(settlement.id(), "保证金已托管，但项目终态提交失败，已进入人工复核");
        }
    }

    private void markProjectSettlementReview(String id, String expected, String error) {
        try {
            projectTransaction(connection -> {
                if (!ProjectWalletSettlementStore.transition(connection, id, expected,
                        ProjectWalletSettlementStore.REVIEW_REQUIRED, error, projectNow())) {
                    throw new SQLException("project settlement state changed before review mark");
                }
                return null;
            });
        } catch (SQLException exception) {
            plugin.getLogger().severe("[项目结算] 无法标记人工复核 " + id + ": " + exception.getMessage());
        }
    }

    private <T> T projectTransaction(ProjectSqlTransaction<T> transaction) throws SQLException {
        try (var connection = plugin.ksCore().dataStore().getConnection()) {
            if (connection == null) throw new SQLException("database unavailable");
            connection.setAutoCommit(false);
            try {
                ProjectWalletSettlementStore.initialize(connection);
                T result = transaction.run(connection);
                connection.commit();
                return result;
            } catch (SQLException | RuntimeException failure) {
                try { connection.rollback(); } catch (SQLException rollback) { failure.addSuppressed(rollback); }
                throw failure;
            } finally {
                try { connection.setAutoCommit(true); } catch (SQLException ignored) { }
            }
        }
    }

    private static long projectNow() {
        return System.currentTimeMillis() / 1000;
    }

    void startProjectSettlementRecovery() {
        try {
            plugin.asyncWorkPool().executeDatabase(this::recoverProjectWalletSettlements);
        } catch (java.util.concurrent.RejectedExecutionException rejected) {
            plugin.getLogger().severe("[Project settlement] Startup recovery queue rejected");
        }
    }

    private void recoverProjectWalletSettlements() {
        List<ProjectWalletSettlementStore.Settlement> recoverable;
        int reviews;
        try (var connection = plugin.ksCore().dataStore().getConnection()) {
            if (connection == null) return;
            ProjectWalletSettlementStore.initialize(connection);
            reviews = ProjectWalletSettlementStore.markInterruptedClaimsForReview(connection, projectNow());
            recoverable = ProjectWalletSettlementStore.recoverable(connection);
        } catch (SQLException failure) {
            plugin.getLogger().severe("[Project settlement] Startup scan failed: " + failure.getMessage());
            return;
        }
        if (reviews > 0) {
            plugin.getLogger().severe("[Project settlement] " + reviews
                    + " external wallet outcomes are unknown and require administrator review");
        }
        for (ProjectWalletSettlementStore.Settlement settlement : recoverable) {
            ProjectSettlementResult result = ProjectWalletSettlementStore.DEPOSIT_CHARGE_READY.equals(settlement.status())
                    ? processPersonalProjectDeposit(settlement)
                    : processPersonalProjectPrepayment(settlement);
            if (!result.success()) {
                plugin.getLogger().warning("[Project settlement] Startup recovery did not finish "
                        + settlement.id() + ": " + result.message());
            }
        }
    }

    private void handleSettlementReviewList(HttpExchange exchange) throws IOException {
        if (requireAdminAuth(exchange) == null) return;
        try (var connection = plugin.ksCore().dataStore().getConnection()) {
            if (connection == null) {
                KsPluginBridge.sendJson(exchange, 503, gson.toJson(Map.of("error", "database unavailable")));
                return;
            }
            ProjectWalletSettlementStore.initialize(connection);
            List<Map<String, Object>> rows = new ArrayList<>();
            for (ProjectWalletSettlementStore.Settlement settlement
                    : ProjectWalletSettlementStore.reviewRequired(connection)) {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("type", "PROJECT_WALLET");
                row.put("id", settlement.id());
                row.put("projectId", settlement.projectId());
                row.put("bidId", settlement.bidId());
                row.put("playerUuid", settlement.playerUuid().toString());
                row.put("depositAmount", settlement.depositAmount());
                row.put("prepaymentAmount", settlement.prepaymentAmount());
                row.put("reviewStage", settlement.reviewStage());
                row.put("lastError", settlement.lastError());
                rows.add(row);
            }
            PropertyMarketSettlementStore.initialize(connection);
            for (PropertyMarketSettlementStore.Settlement settlement
                    : PropertyMarketSettlementStore.reviewRequired(connection)) {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("type", "PROPERTY_MARKET");
                row.put("id", settlement.id());
                row.put("listingId", settlement.listingId());
                row.put("houseId", settlement.houseId());
                row.put("buyerUuid", settlement.buyerUuid().toString());
                row.put("sellerUuid", settlement.sellerUuid().toString());
                row.put("saleAmount", settlement.saleAmount());
                row.put("taxAmount", settlement.taxAmount());
                row.put("totalCharge", settlement.totalCharge());
                row.put("reviewStage", settlement.reviewStage());
                row.put("lastError", settlement.lastError());
                rows.add(row);
            }
            MarketPurchaseSettlementStore.initialize(connection);
            for (MarketPurchaseSettlementStore.Settlement settlement
                    : MarketPurchaseSettlementStore.reviewRequired(connection)) {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("type", "MARKET_PURCHASE");
                row.put("id", settlement.id());
                row.put("listingId", settlement.listingId());
                row.put("storageId", settlement.storageId());
                row.put("buyerUuid", settlement.buyerUuid().toString());
                row.put("sellerUuid", settlement.sellerUuid().toString());
                row.put("totalCost", settlement.totalCost());
                row.put("tax", settlement.tax());
                row.put("totalCharge", settlement.totalCharge());
                row.put("reviewStage", settlement.reviewStage());
                row.put("lastError", settlement.lastError());
                rows.add(row);
            }
            BankAccessProvider bankProvider = plugin.bankAccessProvider();
            if (bankProvider != null) {
                for (BankAccessProvider.SettlementReview settlement
                        : bankProvider.listLoanRepaymentReviews()) {
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("type", "BANK_LOAN_REPAYMENT");
                    row.put("id", settlement.id());
                    row.put("loanId", settlement.loanId());
                    row.put("borrowerUuid", settlement.borrowerUuid().toString());
                    row.put("bankId", settlement.bankId());
                    row.put("amount", settlement.amount());
                    row.put("expectedRemaining", settlement.expectedRemaining());
                    row.put("reviewStage", settlement.reviewStage());
                    row.put("lastError", settlement.lastError());
                    rows.add(row);
                }
            }
            KsPluginBridge.sendJson(exchange, 200, gson.toJson(Map.of("settlements", rows)));
        } catch (SQLException failure) {
            KsPluginBridge.sendJson(exchange, 500, gson.toJson(Map.of("error", failure.getMessage())));
        }
    }

    @SuppressWarnings("unchecked")
    private void handleSettlementReviewResolve(HttpExchange exchange) throws IOException {
        KsAuthManager.Session admin = requireAdminAuth(exchange);
        if (admin == null) return;
        Map<String, Object> request = gson.fromJson(KsPluginBridge.readBody(exchange), Map.class);
        String id = request == null ? null : Objects.toString(request.get("id"), null);
        String action = request == null ? null : Objects.toString(request.get("action"), null);
        String type = request == null ? "PROJECT_WALLET"
                : Objects.toString(request.get("type"), "PROJECT_WALLET").trim().toUpperCase(Locale.ROOT);
        if (id == null || id.isBlank() || action == null || action.isBlank()) {
            KsPluginBridge.sendJson(exchange, 400, gson.toJson(Map.of("error", "id and action are required")));
            return;
        }
        try {
            if ("PROPERTY_MARKET".equals(type)) {
                MarketManager.PropertyReviewResolution resolution = plugin.marketManager()
                        .resolvePropertyReview(id, action.trim().toUpperCase(Locale.ROOT));
                auditLog("PROPERTY_SETTLEMENT_REVIEW_RESOLVED", admin.playerUuid.toString(), admin.playerName,
                        "property_settlement", id, "action=" + action + ", status=" + resolution.status());
                KsPluginBridge.sendJson(exchange, 200, gson.toJson(Map.of(
                        "id", id, "type", type, "status", resolution.status(), "action", action)));
                return;
            }
            if ("MARKET_PURCHASE".equals(type)) {
                MarketManager.MarketReviewResolution resolution = plugin.marketManager()
                        .resolveMarketReview(id, action.trim().toUpperCase(Locale.ROOT));
                auditLog("MARKET_SETTLEMENT_REVIEW_RESOLVED", admin.playerUuid.toString(), admin.playerName,
                        "market_settlement", id, "action=" + action + ", status=" + resolution.status());
                KsPluginBridge.sendJson(exchange, 200, gson.toJson(Map.of(
                        "id", id, "type", type, "status", resolution.status(), "action", action)));
                return;
            }
            if ("BANK_LOAN_REPAYMENT".equals(type)) {
                BankAccessProvider bankProvider = plugin.bankAccessProvider();
                if (bankProvider == null) throw new SQLException("bank module is unavailable");
                BankAccessProvider.SettlementResolution resolution = bankProvider
                        .resolveLoanRepaymentReview(id, action.trim().toUpperCase(Locale.ROOT));
                auditLog("BANK_LOAN_REPAYMENT_REVIEW_RESOLVED", admin.playerUuid.toString(), admin.playerName,
                        "bank_loan_repayment", id,
                        "action=" + action + ", status=" + resolution.status());
                KsPluginBridge.sendJson(exchange, 200, gson.toJson(Map.of(
                        "id", id, "type", type, "status", resolution.status(),
                        "reviewStage", resolution.reviewStage(), "action", action,
                        "message", resolution.message())));
                return;
            }
            if (!"PROJECT_WALLET".equals(type)) throw new SQLException("unsupported settlement type");
            ProjectWalletSettlementStore.Settlement settlement = projectTransaction(connection -> {
                ProjectWalletSettlementStore.Settlement current = ProjectWalletSettlementStore.find(connection, id);
                if (current == null || !ProjectWalletSettlementStore.REVIEW_REQUIRED.equals(current.status())) {
                    throw new SQLException("settlement is not awaiting review");
                }
                resolveProjectSettlementReview(connection, current, action.trim().toUpperCase(Locale.ROOT));
                return ProjectWalletSettlementStore.find(connection, id);
            });
            if ("CONFIRM_CHARGE_SUCCEEDED".equalsIgnoreCase(action)
                    && ProjectWalletSettlementStore.DEPOSIT_HELD.equals(settlement.status())
                    && settlement.prepaymentAmount() > 0.0d) {
                processPersonalProjectPrepayment(settlement);
                settlement = projectTransaction(connection -> ProjectWalletSettlementStore.find(connection, id));
            }
            auditLog("PROJECT_SETTLEMENT_REVIEW_RESOLVED", admin.playerUuid.toString(), admin.playerName,
                    "project_settlement", id, "action=" + action + ", status=" + settlement.status());
            KsPluginBridge.sendJson(exchange, 200, gson.toJson(Map.of(
                    "id", id, "status", settlement.status(), "action", action)));
        } catch (SQLException failure) {
            KsPluginBridge.sendJson(exchange, 409, gson.toJson(Map.of("error", failure.getMessage())));
        }
    }

    private void resolveProjectSettlementReview(java.sql.Connection connection,
                                                ProjectWalletSettlementStore.Settlement settlement,
                                                String action) throws SQLException {
        String stage = settlement.reviewStage();
        long now = projectNow();
        switch (action) {
            case "CONFIRM_CHARGE_SUCCEEDED" -> {
                requireReviewStage(stage, ProjectWalletSettlementStore.DEPOSIT_CHARGE_CLAIMED);
                requireReviewResolution(ProjectWalletSettlementStore.resolveReview(connection, settlement.id(), stage,
                        ProjectWalletSettlementStore.DEPOSIT_CHARGE_CLAIMED, "administrator confirmed charge", now));
                ProjectWalletSettlementService.recordDepositHeld(connection, settlement, now);
                if (settlement.prepaymentAmount() <= 0.0d) {
                    ProjectWalletSettlementService.finalizeDepositWithoutPayout(connection, settlement, now);
                }
            }
            case "CONFIRM_CHARGE_FAILED" -> {
                requireReviewStage(stage, ProjectWalletSettlementStore.DEPOSIT_CHARGE_CLAIMED);
                requireReviewResolution(ProjectWalletSettlementStore.resolveReview(connection, settlement.id(), stage,
                        ProjectWalletSettlementStore.DEPOSIT_CHARGE_CLAIMED, "administrator rejected charge", now));
                ProjectWalletSettlementService.rollbackRejectedExternalCall(connection, settlement,
                        ProjectWalletSettlementStore.DEPOSIT_CHARGE_CLAIMED,
                        "administrator confirmed charge failed", now);
            }
            case "CONFIRM_PAYOUT_SUCCEEDED" -> {
                requireReviewStage(stage, ProjectWalletSettlementStore.PREPAYMENT_CLAIMED);
                requireReviewResolution(ProjectWalletSettlementStore.resolveReview(connection, settlement.id(), stage,
                        ProjectWalletSettlementStore.PREPAYMENT_CLAIMED, "administrator confirmed payout", now));
                ProjectWalletSettlementService.finalizePayout(connection, settlement, now);
            }
            case "CONFIRM_PAYOUT_FAILED" -> {
                requireReviewStage(stage, ProjectWalletSettlementStore.PREPAYMENT_CLAIMED);
                if (ProjectWalletSettlementStore.DIRECT_AWARD.equals(settlement.kind())) {
                    requireReviewResolution(ProjectWalletSettlementStore.resolveReview(connection, settlement.id(), stage,
                            ProjectWalletSettlementStore.PREPAYMENT_CLAIMED, "administrator rejected payout", now));
                    ProjectWalletSettlementService.rollbackRejectedExternalCall(connection, settlement,
                            ProjectWalletSettlementStore.PREPAYMENT_CLAIMED,
                            "administrator confirmed payout failed", now);
                } else {
                    requireReviewResolution(ProjectWalletSettlementStore.resolveReview(connection, settlement.id(), stage,
                            ProjectWalletSettlementStore.DEPOSIT_HELD,
                            "administrator confirmed payout failed; ready to retry", now));
                }
            }
            default -> throw new SQLException("unsupported review action");
        }
    }

    private static void requireReviewStage(String actual, String expected) throws SQLException {
        if (!expected.equals(actual)) throw new SQLException("review stage does not allow this action");
    }

    private static void requireReviewResolution(boolean resolved) throws SQLException {
        if (!resolved) throw new SQLException("settlement review changed concurrently");
    }

    @FunctionalInterface
    private interface ProjectSqlTransaction<T> {
        T run(java.sql.Connection connection) throws SQLException;
    }

    private record ProjectSettlementResult(boolean success, boolean reviewRequired,
                                           String settlementId, String message) {
        static ProjectSettlementResult success(String id, String message) {
            return new ProjectSettlementResult(true, false, id, message);
        }

        static ProjectSettlementResult failed(String id, String message) {
            return new ProjectSettlementResult(false, false, id, message);
        }

        static ProjectSettlementResult review(String id, String message) {
            return new ProjectSettlementResult(false, true, id, message);
        }
    }

    @SuppressWarnings("unchecked")
    private void handleBidDepositPay(HttpExchange exchange) throws IOException {
        KsAuthManager.Session session = requireAuth(exchange);
        if (session == null) return;
        Map<String, Object> req = gson.fromJson(KsPluginBridge.readBody(exchange), Map.class);
        if (req == null) { KsPluginBridge.sendJson(exchange, 400, "{\"error\":\"无效请求\"}"); return; }
        String bidId = (String) req.get("bidId");
        if (bidId == null) { KsPluginBridge.sendJson(exchange, 400, "{\"error\":\"缺少 bidId\"}"); return; }
        try (var conn = plugin.ksCore().dataStore().getConnection()) {
            if (conn == null) { KsPluginBridge.sendJson(exchange, 500, "{\"error\":\"数据库未连接\"}"); return; }
            conn.setAutoCommit(false);
            expireOverdueDeposits(conn);
            String projectId, enterpriseId, bidderUuid, bidderType; double bidAmount, depositAmount; long depositDeadline; String status;
            try (var st = conn.createStatement();
                 var rs = st.executeQuery("SELECT * FROM ks_ent_bids WHERE id='" + bidId.replace("'", "''") + "'")) {
                if (!rs.next()) { KsPluginBridge.sendJson(exchange, 404, "{\"error\":\"投标不存在\"}"); return; }
                projectId = rs.getString("project_id");
                enterpriseId = rs.getString("enterprise_id");
                bidderUuid = rs.getString("bidder_uuid");
                bidderType = rs.getString("bidder_type");
                bidAmount = rs.getDouble("bid_amount");
                depositAmount = rs.getDouble("deposit_amount");
                depositDeadline = rs.getLong("deposit_deadline");
                status = rs.getString("status");
            }
            boolean resumePersonalPayout = "PLAYER".equalsIgnoreCase(bidderType)
                    && "PREPAYMENT_SETTLING".equals(status);
            if (!"PENDING_DEPOSIT".equals(status) && !resumePersonalPayout) {
                KsPluginBridge.sendJson(exchange, 400, "{\"error\":\"该投标当前不处于待缴保证金状态（可能已超时流标或已处理）\"}"); return;
            }
            long now = System.currentTimeMillis() / 1000;
            if (!resumePersonalPayout && depositDeadline > 0 && now > depositDeadline) {
                KsPluginBridge.sendJson(exchange, 400, "{\"error\":\"保证金缴纳已超时，该中标资格已失效\"}"); return;
            }
            // 权限校验：企业投标须为企业所有者/管理员，个人投标须为本人
            if ("ENTERPRISE".equals(bidderType) && enterpriseId != null && !enterpriseId.isEmpty()) {
                if (!hasEnterprisePermission(conn, enterpriseId, session, EnterprisePermissionService.MANAGE_BIDDING)) {
                    KsPluginBridge.sendJson(exchange, 403, "{\"error\":\"只有该企业所有者才能缴纳保证金\"}"); return;
                }
            } else if (!session.isAdmin && !session.playerUuid.toString().equals(bidderUuid)) {
                KsPluginBridge.sendJson(exchange, 403, "{\"error\":\"只有中标方本人才能缴纳保证金\"}"); return;
            }
            double prepayRatio;
            try (var ps = conn.prepareStatement(
                    "SELECT prepayment_ratio FROM ks_ent_projects WHERE id=?")) {
                ps.setString(1, projectId);
                try (var rsP = ps.executeQuery()) {
                    if (!rsP.next()) throw new java.sql.SQLException("Project disappeared during deposit settlement");
                    prepayRatio = rsP.getDouble(1);
                }
            }
            double prepayment = bidAmount * prepayRatio;

            if (resumePersonalPayout) {
                ProjectWalletSettlementStore.initialize(conn);
                ProjectWalletSettlementStore.Settlement settlement =
                        ProjectWalletSettlementStore.findOpenByBid(conn, bidId);
                if (settlement == null || (!ProjectWalletSettlementStore.DEPOSIT_HELD.equals(settlement.status())
                        && !ProjectWalletSettlementStore.PREPAYMENT_READY.equals(settlement.status()))) {
                    KsPluginBridge.sendJson(exchange, 409,
                            "{\"error\":\"未找到可重试的个人预付款结算\"}"); return;
                }
                conn.commit();
                ProjectSettlementResult outcome = processPersonalProjectPrepayment(settlement);
                KsPluginBridge.sendJson(exchange, outcome.success() ? 200 : outcome.reviewRequired() ? 202 : 409,
                        gson.toJson(Map.of("id", bidId, "projectId", projectId,
                                "settlementId", outcome.settlementId(),
                                "status", outcome.success() ? "AWARDED"
                                        : outcome.reviewRequired() ? "REVIEW_REQUIRED" : "PREPAYMENT_SETTLING",
                                "message", outcome.message())));
                return;
            }

            if (!"ENTERPRISE".equalsIgnoreCase(bidderType)) {
                ProjectWalletSettlementStore.Settlement settlement =
                        ProjectWalletSettlementService.prepareDepositAward(conn, projectId, bidId,
                                UUID.fromString(bidderUuid), depositAmount, prepayment, now);
                conn.commit();
                ProjectSettlementResult outcome = processPersonalProjectDeposit(settlement);
                KsPluginBridge.sendJson(exchange, outcome.success() ? 200 : outcome.reviewRequired() ? 202 : 409,
                        gson.toJson(Map.of("id", bidId, "projectId", projectId,
                                "settlementId", outcome.settlementId(), "depositAmount", depositAmount,
                                "prepayment", prepayment,
                                "status", outcome.success() ? "AWARDED"
                                        : outcome.reviewRequired() ? "REVIEW_REQUIRED" : "PENDING_DEPOSIT",
                                "message", outcome.message())));
                return;
            }
            // 扣款
            if ("ENTERPRISE".equals(bidderType) && enterpriseId != null && !enterpriseId.isEmpty()) {
                double corpBal = getCorporateBalance(conn, enterpriseId);
                if (corpBal < depositAmount) {
                    KsPluginBridge.sendJson(exchange, 400, "{\"error\":\"企业公户余额不足（余额: " + String.format("%.2f", corpBal) + "，需缴保证金: " + String.format("%.2f", depositAmount) + "）\"}"); return;
                }
                if (!reserveCorporateProjectEscrow(conn, enterpriseId, depositAmount)) {
                    KsPluginBridge.sendJson(exchange, 400, "{\"error\":\"扣款失败\"}"); return;
                }
            }
            // 保证金计入托管台账
            String depId = UUID.randomUUID().toString().substring(0, 8);
            conn.createStatement().executeUpdate(
                "INSERT INTO ks_ent_bid_deposits (id, bid_id, project_id, payer_uuid, payer_enterprise_id, amount, status, paid_at) VALUES ('" +
                depId + "', '" + bidId.replace("'", "''") + "', '" + projectId.replace("'", "''") + "', " +
                (bidderUuid != null ? "'" + bidderUuid.replace("'", "''") + "'" : "NULL") + ", " +
                (enterpriseId != null && !enterpriseId.isEmpty() ? "'" + enterpriseId.replace("'", "''") + "'" : "NULL") + ", " +
                depositAmount + ", 'HELD', " + now + ")");
            // 正式确认中标
            if (conn.createStatement().executeUpdate(
                "UPDATE ks_ent_bids SET status='AWARDED', deposit_paid_at=" + now + " WHERE id='" + bidId.replace("'", "''") + "' AND status='PENDING_DEPOSIT'") != 1) {
                throw new java.sql.SQLException("Bid deposit was already processed");
            }
            conn.createStatement().executeUpdate(
                "UPDATE ks_ent_bids SET status='REJECTED' WHERE project_id='" + projectId.replace("'", "''") +
                "' AND status='PENDING' AND id<>'" + bidId.replace("'", "''") + "'");
            if (conn.createStatement().executeUpdate(
                "UPDATE ks_ent_projects SET status='AWARDED' WHERE id='" + projectId.replace("'", "''") + "' AND status='PENDING_DEPOSIT'") != 1) {
                throw new java.sql.SQLException("Project deposit was already processed");
            }
            // 发放预付款
            payProjectPrepayment(conn, enterpriseId, bidderUuid, bidderType, prepayment, projectId, session, now);
            conn.commit();
            auditLog("PROJECT_DEPOSIT_PAID", session.playerUuid.toString(), session.playerName, "project", projectId,
                "投标: " + bidId + " | 保证金: " + String.format("%.2f", depositAmount) + " | 中标确认，预付款: " + String.format("%.2f", prepayment));
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("id", bidId); result.put("projectId", projectId); result.put("status", "AWARDED");
            result.put("depositAmount", depositAmount); result.put("prepayment", prepayment);
            result.put("message", "保证金已缴纳，中标确认，预付款 " + String.format("%.2f", prepayment) + " 已发放");
            KsPluginBridge.sendJson(exchange, 200, gson.toJson(result));
        } catch (java.sql.SQLException | IOException e) {
            KsPluginBridge.sendJson(exchange, 500, "{\"error\":\"缴纳保证金失败: " + e.getMessage() + "\"}");
        }
    }

    // ================================================================
    // Dividend APIs — 分红系统
    // ================================================================

    @SuppressWarnings("unchecked")
    private void handleDividendDeclare(HttpExchange exchange) throws IOException {
        KsAuthManager.Session session = requireAdminAuth(exchange);
        if (session == null) return;
        Map<String, Object> req = gson.fromJson(KsPluginBridge.readBody(exchange), Map.class);
        if (req == null) { KsPluginBridge.sendJson(exchange, 400, "{\"error\":\"无效请求\"}"); return; }
        String enterpriseId = (String) req.get("enterpriseId");
        double amount = toDouble(req.get("amount"), 0);
        if (enterpriseId == null || amount <= 0) {
            KsPluginBridge.sendJson(exchange, 400, "{\"error\":\"缺少 enterpriseId 或金额无效\"}");
            return;
        }
        // 获取企业信息和所有者
        try (var conn = plugin.ksCore().dataStore().getConnection()) {
            if (conn == null) { KsPluginBridge.sendJson(exchange, 500, "{\"error\":\"数据库未连接\"}"); return; }
            var rs = conn.createStatement().executeQuery(
                "SELECT owner_uuids, current_assets FROM ks_ent_enterprises WHERE id='" + enterpriseId.replace("'", "''") + "' AND status='ACTIVE'");
            if (!rs.next()) { KsPluginBridge.sendJson(exchange, 404, "{\"error\":\"企业不存在或已注销\"}"); return; }
            double currentAssets = rs.getDouble("current_assets");
            String ownerStr = rs.getString("owner_uuids");
            if (amount > currentAssets) {
                KsPluginBridge.sendJson(exchange, 400, "{\"error\":\"分红金额超过企业当前资产 (" + String.format("%.2f", currentAssets) + ")\"}");
                return;
            }
            List<UUID> ownerUuids = new ArrayList<>();
            for (String ownerValue : ownerStr.split(",")) {
                String normalizedOwner = ownerValue.trim();
                if (normalizedOwner.isEmpty()) continue;
                try { ownerUuids.add(UUID.fromString(normalizedOwner)); }
                catch (IllegalArgumentException invalidOwner) {
                    KsPluginBridge.sendJson(exchange, 400, "{\"error\":\"企业所有者数据无效\"}");
                    return;
                }
            }
            if (ownerUuids.isEmpty()) {
                KsPluginBridge.sendJson(exchange, 400, "{\"error\":\"企业没有有效所有者\"}");
                return;
            }
            double taxRate = 0.10; // 默认分红税率 10%
            try {
                var rs2 = conn.createStatement().executeQuery("SELECT rate FROM ks_tax_rates WHERE category='DIVIDEND_TAX'");
                if (rs2.next()) taxRate = rs2.getDouble("rate");
            } catch (java.sql.SQLException ignored) {}
            if (!Double.isFinite(taxRate)) taxRate = 0.10;
            if (taxRate > 1.0 && taxRate <= 100.0) taxRate /= 100.0;
            taxRate = Math.max(0.0, Math.min(1.0, taxRate));
            double taxPaid = amount * taxRate;
            double netAmount = amount - taxPaid;
            double perOwner = netAmount / ownerUuids.size();
            // 从企业公户扣除分红（使用当前连接避免 SQLITE_BUSY）
            long divNow = System.currentTimeMillis() / 1000;
            var rsBal = conn.createStatement().executeQuery(
                "SELECT balance FROM ks_ent_corporate_accounts WHERE enterprise_id='" + enterpriseId.replace("'", "''") + "'");
            double corpBal = rsBal.next() ? rsBal.getDouble("balance") : 0;
            if (corpBal < amount) {
                KsPluginBridge.sendJson(exchange, 400, "{\"error\":\"企业公户余额不足（余额: " + String.format("%.2f", corpBal) + "，分红: " + String.format("%.2f", amount) + "）\"}");
                return;
            }
            conn.createStatement().executeUpdate(
                "UPDATE ks_ent_corporate_accounts SET balance = balance - " + amount + ", updated_at = " + divNow +
                " WHERE enterprise_id='" + enterpriseId.replace("'", "''") + "'");
            conn.createStatement().executeUpdate(
                "UPDATE ks_ent_enterprises SET current_assets = current_assets - " + amount +
                " WHERE id='" + enterpriseId.replace("'", "''") + "'");
            conn.createStatement().executeUpdate(
                "UPDATE ks_bank_banks SET total_assets = total_assets - " + amount +
                " WHERE id=(SELECT bank_id FROM ks_ent_corporate_accounts WHERE enterprise_id='" + enterpriseId.replace("'", "''") + "')");
            // 分配给所有者。HTTP 请求线程不能直接调用 Vault；任一笔失败时撤销已付款项并退回公户。
            List<UUID> paidOwners = new ArrayList<>();
            try {
                for (UUID owner : ownerUuids) {
                    if (!depositWallet(owner, perOwner)) throw new IOException("Dividend wallet payout failed");
                    paidOwners.add(owner);
                }
            } catch (IOException e) {
                for (UUID paidOwner : paidOwners) {
                    try { withdrawWallet(paidOwner, perOwner); } catch (IOException ignored) {}
                }
                depositCorporate(enterpriseId, amount, "分红发放失败退回");
                KsPluginBridge.sendJson(exchange, 500, "{\"error\":\"分红钱包发放失败，款项已退回企业公户\"}");
                return;
            }
            // 记录分红
            String divId = UUID.randomUUID().toString().substring(0, 8);
            long now = System.currentTimeMillis() / 1000;
            conn.createStatement().executeUpdate(
                "INSERT INTO ks_ent_dividends (id, enterprise_id, amount, declared_at, tax_rate, tax_paid, status) VALUES ('" +
                divId + "', '" + enterpriseId.replace("'", "''") + "', " + amount + ", " + now + ", " + taxRate + ", " + taxPaid + ", 'PAID')");
            try (var payout = conn.prepareStatement(
                    "INSERT INTO ks_ent_dividend_payouts (id,dividend_id,enterprise_id,recipient_uuid,share_percent,gross_amount,tax_amount,net_amount,paid_at) VALUES (?,?,?,?,?,?,?,?,?)")) {
                double sharePercent = 100.0 / ownerUuids.size();
                double grossPerOwner = amount / ownerUuids.size();
                double taxPerOwner = taxPaid / ownerUuids.size();
                for (UUID owner : ownerUuids) {
                    payout.setString(1, UUID.randomUUID().toString());
                    payout.setString(2, divId);
                    payout.setString(3, enterpriseId);
                    payout.setString(4, owner.toString());
                    payout.setDouble(5, sharePercent);
                    payout.setDouble(6, grossPerOwner);
                    payout.setDouble(7, taxPerOwner);
                    payout.setDouble(8, perOwner);
                    payout.setLong(9, now);
                    payout.addBatch();
                }
                payout.executeBatch();
            }
            // 记录分红税收
            try (var tax = conn.prepareStatement(
                    "INSERT INTO ks_tax_records (id,payer_uuid,payer_name,category,base_amount,tax_rate,"
                            + "tax_amount,description,collected_at) VALUES (?,?,?,?,?,?,?,?,?)")) {
                tax.setString(1, "TAX-" + UUID.randomUUID());
                tax.setString(2, enterpriseId);
                tax.setString(3, "企业分红");
                tax.setString(4, "DIVIDEND_TAX");
                tax.setDouble(5, amount);
                tax.setDouble(6, taxRate);
                tax.setDouble(7, taxPaid);
                tax.setString(8, "企业 " + enterpriseId + " 分红纳税");
                tax.setLong(9, now);
                tax.executeUpdate();
            } catch (java.sql.SQLException ignored) {}
            Map<String, Object> resp = new LinkedHashMap<>();
            resp.put("message", "分红已发放"); resp.put("dividendId", divId);
            resp.put("amount", amount); resp.put("taxPaid", taxPaid); resp.put("perOwner", perOwner);
            auditLog("DIVIDEND_DECLARE", session.playerUuid.toString(), session.playerName, "enterprise", enterpriseId,
                "分红: " + String.format("%.2f", amount) + " | 税额: " + String.format("%.2f", taxPaid) + " (" + String.format("%.0f", taxRate*100) + "%)");
            KsPluginBridge.sendJson(exchange, 200, gson.toJson(resp));
        } catch (java.sql.SQLException e) {
            KsPluginBridge.sendJson(exchange, 500, "{\"error\":\"分红失败: " + e.getMessage() + "\"}");
        }
    }

    private void handleDividendList(HttpExchange exchange, String query) throws IOException {
        Map<String, String> params = KsPluginBridge.parseQuery(query);
        String enterpriseId = params.get("enterpriseId");
        List<Map<String, Object>> list = new ArrayList<>();
        String sql = "SELECT * FROM ks_ent_dividends";
        if (enterpriseId != null) sql += " WHERE enterprise_id='" + enterpriseId.replace("'", "''") + "'";
        sql += " ORDER BY declared_at DESC LIMIT 50";
        queryRows(list, sql, null);
        KsPluginBridge.sendJson(exchange, 200, gson.toJson(Map.of("dividends", list, "count", list.size())));
    }

    // ================================================================
    // Corporate Account APIs — 企业公户
    // ================================================================

    private void handleCorporateBalance(HttpExchange exchange, String query) throws IOException {
        KsAuthManager.Session session = requireAuth(exchange);
        if (session == null) return;
        Map<String, String> params = KsPluginBridge.parseQuery(query);
        String enterpriseId = params.get("enterpriseId");
        if (enterpriseId == null) { KsPluginBridge.sendJson(exchange, 400, "{\"error\":\"缺少 enterpriseId\"}"); return; }
        try (var conn = plugin.ksCore().dataStore().getConnection()) {
            if (conn == null) { KsPluginBridge.sendJson(exchange, 500, gson.toJson(Map.of("error", "数据库未连接"))); return; }
            if (!hasEnterprisePermission(conn, enterpriseId, session, EnterprisePermissionService.VIEW_FINANCE)) {
                KsPluginBridge.sendJson(exchange, 403, gson.toJson(Map.of("error", "没有企业财务查看权限"))); return;
            }
            String bankId = getCorporateBankId(conn, enterpriseId);
            if (bankId == null) { KsPluginBridge.sendJson(exchange, 404, gson.toJson(Map.of("error", "企业公户不存在"))); return; }
            double bal = getCorporateBalance(conn, enterpriseId);
            String bankName = getBankName(conn, bankId);
            KsPluginBridge.sendJson(exchange, 200, gson.toJson(Map.of("enterpriseId", enterpriseId, "bankId", bankId, "balance", bal,
                    "bankName", bankName == null ? bankId : bankName)));
        } catch (java.sql.SQLException e) {
            KsPluginBridge.sendJson(exchange, 500, gson.toJson(Map.of("error", "读取公户失败")));
        }
    }

    @SuppressWarnings("unchecked")
    private void handleCorporateTransfer(HttpExchange exchange) throws IOException {
        KsAuthManager.Session session = requireAuth(exchange);
        if (session == null) return;
        Map<String, Object> req = gson.fromJson(KsPluginBridge.readBody(exchange), Map.class);
        if (req == null) { KsPluginBridge.sendJson(exchange, 400, "{\"error\":\"无效请求\"}"); return; }
        String enterpriseId = (String) req.get("enterpriseId");
        String direction = req.getOrDefault("direction", "TO_PLAYER").toString(); // TO_PLAYER | TO_CORPORATE
        double amount = toDouble(req.get("amount"), 0);
        if (enterpriseId == null || amount <= 0) {
            KsPluginBridge.sendJson(exchange, 400, "{\"error\":\"缺少 enterpriseId 或金额无效\"}"); return;
        }
        if (!Set.of("TO_PLAYER", "TO_CORPORATE").contains(direction)) {
            KsPluginBridge.sendJson(exchange, 400, "{\"error\":\"无效转账方向\"}"); return;
        }
        // 资金权限由企业角色模板和个人授权统一控制。
        try (var conn = plugin.ksCore().dataStore().getConnection()) {
            if (conn == null) { KsPluginBridge.sendJson(exchange, 500, "{\"error\":\"数据库未连接\"}"); return; }
            try (var exists = conn.prepareStatement("SELECT 1 FROM ks_ent_enterprises WHERE id=?")) {
                exists.setString(1, enterpriseId);
                if (!exists.executeQuery().next()) { KsPluginBridge.sendJson(exchange, 404, "{\"error\":\"企业不存在\"}"); return; }
            }
            if (!hasEnterprisePermission(conn, enterpriseId, session, EnterprisePermissionService.MANAGE_FUNDS)) {
                KsPluginBridge.sendJson(exchange, 403, "{\"error\":\"没有企业资金管理权限\"}"); return;
            }
        } catch (java.sql.SQLException e) {
            KsPluginBridge.sendJson(exchange, 500, "{\"error\":\"" + e.getMessage() + "\"}"); return;
        }
        if ("TO_PLAYER".equals(direction)) {
            // 公户 → 玩家钱包
            if (!withdrawCorporate(enterpriseId, amount, "转账至玩家 " + session.playerUuid)) {
                KsPluginBridge.sendJson(exchange, 400, "{\"error\":\"公户余额不足\"}"); return;
            }
            boolean credited;
            try {
                credited = depositWallet(session.playerUuid, amount);
            } catch (IOException e) {
                credited = false;
            }
            if (!credited) {
                depositCorporate(enterpriseId, amount, "钱包入账失败退回 | 玩家 " + session.playerUuid);
                KsPluginBridge.sendJson(exchange, 500, "{\"error\":\"钱包入账失败，款项已退回企业公户\"}"); return;
            }
            KsPluginBridge.sendJson(exchange, 200, "{\"message\":\"已从公户转账 " + String.format("%.2f", amount) + " 到个人钱包\"}");
        } else {
            // 玩家钱包 → 公户
            if (!hasWalletBalance(session.playerUuid, amount)) {
                KsPluginBridge.sendJson(exchange, 400, "{\"error\":\"个人余额不足\"}"); return;
            }
            if (!withdrawWallet(session.playerUuid, amount)) {
                KsPluginBridge.sendJson(exchange, 400, "{\"error\":\"个人扣款失败\"}"); return;
            }
            if (!depositCorporate(enterpriseId, amount, "玩家 " + session.playerUuid + " 存入")) {
                depositWallet(session.playerUuid, amount);
                KsPluginBridge.sendJson(exchange, 500, "{\"error\":\"企业公户入账失败，款项已退回钱包\"}"); return;
            }
            KsPluginBridge.sendJson(exchange, 200, "{\"message\":\"已从个人钱包转入公户 " + String.format("%.2f", amount) + "\"}");
        }
    }

    // ================================================================
    // Procurement APIs — 企业采购（反向招标）
    // ================================================================

    @SuppressWarnings("unchecked")
    private void handleProcurementPublish(HttpExchange exchange) throws IOException {
        KsAuthManager.Session session = requireAuth(exchange);
        if (session == null) return;
        Map<String, Object> req = gson.fromJson(KsPluginBridge.readBody(exchange), Map.class);
        if (req == null) { KsPluginBridge.sendJson(exchange, 400, "{\"error\":\"无效请求\"}"); return; }
        String enterpriseId = (String) req.get("enterpriseId");
        String title = (String) req.get("title");
        String itemDesc = (String) req.getOrDefault("itemDesc", "");
        int quantity = (int) toDouble(req.get("quantity"), 1);
        double budget = toDouble(req.get("budget"), 0);
        if (enterpriseId == null || title == null || title.isBlank() || title.length() > 128
                || itemDesc.length() > 1024 || quantity <= 0 || quantity > 1_000_000
                || !Double.isFinite(budget) || budget <= 0) {
            KsPluginBridge.sendJson(exchange, 400, "{\"error\":\"缺少 enterpriseId/title/budget\"}"); return;
        }
        // 检查公户余额是否够预算
        double corpBal = getCorporateBalance(enterpriseId);
        if (corpBal < budget) {
            KsPluginBridge.sendJson(exchange, 400, "{\"error\":\"企业公户余额不足（余额: " + String.format("%.2f", corpBal) + ", 预算: " + String.format("%.2f", budget) + "）\"}");
            return;
        }
        String id = UUID.randomUUID().toString().substring(0, 8);
        long now = System.currentTimeMillis() / 1000;
        try (var conn = plugin.ksCore().dataStore().getConnection()) {
            if (conn == null) { KsPluginBridge.sendJson(exchange, 500, "{\"error\":\"数据库未连接\"}"); return; }
            if (!hasEnterprisePermission(conn, enterpriseId, session, EnterprisePermissionService.MANAGE_BIDDING)) {
                KsPluginBridge.sendJson(exchange, 403, "{\"error\":\"只有该企业所有者才能发布采购需求\"}"); return;
            }
            conn.createStatement().executeUpdate(
                "INSERT INTO ks_ent_procurements (id, enterprise_id, title, item_desc, quantity, budget, status, created_at) VALUES ('" +
                id + "', '" + enterpriseId.replace("'", "''") + "', '" + title.replace("'", "''") + "', '" +
                itemDesc.replace("'", "''") + "', " + quantity + ", " + budget + ", 'OPEN', " + now + ")");
        } catch (java.sql.SQLException e) {
            KsPluginBridge.sendJson(exchange, 500, "{\"error\":\"发布采购失败: " + e.getMessage() + "\"}"); return;
        }
        auditLog("PROCUREMENT_PUBLISH", session.playerUuid.toString(), session.playerName, "enterprise", enterpriseId,
            "采购: " + title + " | 预算: " + String.format("%.2f", budget) + " | 数量: " + quantity);
        KsPluginBridge.sendJson(exchange, 200, gson.toJson(Map.of("id", id, "message", "采购已发布")));
    }

    private void handleProcurementList(HttpExchange exchange) throws IOException {
        List<Map<String, Object>> list = new ArrayList<>();
        queryRows(list, "SELECT p.*, COUNT(b.id) AS bid_count FROM ks_ent_procurements p " +
                "LEFT JOIN ks_ent_procurement_bids b ON b.procurement_id=p.id " +
                "GROUP BY p.id ORDER BY p.created_at DESC LIMIT 100", null);
        for (var p : list) {
            p.put("bidCount", p.getOrDefault("bid_count", 0));
            p.remove("bid_count");
        }
        KsPluginBridge.sendJson(exchange, 200, gson.toJson(Map.of("procurements", list, "count", list.size())));
    }

    @SuppressWarnings("unchecked")
    private void handleProcurementBid(HttpExchange exchange) throws IOException {
        KsAuthManager.Session session = requireAuth(exchange);
        if (session == null) return;
        Map<String, Object> req = gson.fromJson(KsPluginBridge.readBody(exchange), Map.class);
        if (req == null) { KsPluginBridge.sendJson(exchange, 400, "{\"error\":\"无效请求\"}"); return; }
        String procurementId = (String) req.get("procurementId");
        String enterpriseId = (String) req.get("enterpriseId");
        String bidderUuid = (String) req.get("bidderUuid");
        String bidderType = req.getOrDefault("bidderType", enterpriseId != null ? "ENTERPRISE" : "PLAYER").toString().toUpperCase(Locale.ROOT);
        double unitPrice = toDouble(req.get("unitPrice"), 0);
        double totalPrice;
        if (procurementId == null || (enterpriseId == null && bidderUuid == null)
                || !Double.isFinite(unitPrice) || unitPrice <= 0 || unitPrice > 1_000_000_000_000d) {
            KsPluginBridge.sendJson(exchange, 400, "{\"error\":\"缺少 procurementId/投标方/单价\"}"); return;
        }
        if (!Set.of("PLAYER", "ENTERPRISE").contains(bidderType)) {
            KsPluginBridge.sendJson(exchange, 400, "{\"error\":\"无效的投标方类型\"}"); return;
        }
        if ("PLAYER".equals(bidderType)) {
            if (enterpriseId != null && !enterpriseId.isBlank()) {
                KsPluginBridge.sendJson(exchange, 400, "{\"error\":\"个人投标不能指定企业\"}"); return;
            }
            if (bidderUuid != null && !bidderUuid.equals(session.playerUuid.toString())) {
                KsPluginBridge.sendJson(exchange, 403, "{\"error\":\"不能代替其他玩家投标\"}"); return;
            }
            bidderUuid = session.playerUuid.toString();
        } else {
            if (enterpriseId == null || enterpriseId.isBlank()) {
                KsPluginBridge.sendJson(exchange, 400, "{\"error\":\"企业投标必须指定企业\"}"); return;
            }
            bidderUuid = session.playerUuid.toString();
        }
        // 总价只由服务端按采购数量计算，不能信任浏览器提交值。
        try (var conn = plugin.ksCore().dataStore().getConnection()) {
            if (conn == null) { KsPluginBridge.sendJson(exchange, 500, "{\"error\":\"数据库未连接\"}"); return; }
            try (var ps = conn.prepareStatement("SELECT status,quantity FROM ks_ent_procurements WHERE id=?")) {
                ps.setString(1, procurementId);
                try (var rs = ps.executeQuery()) {
                    if (!rs.next()) { KsPluginBridge.sendJson(exchange, 404, "{\"error\":\"采购不存在\"}"); return; }
                    if (!"OPEN".equals(rs.getString("status"))) {
                        KsPluginBridge.sendJson(exchange, 400, "{\"error\":\"采购已关闭\"}"); return;
                    }
                    int quantity = rs.getInt("quantity");
                    totalPrice = unitPrice * quantity;
                    if (quantity <= 0 || !Double.isFinite(totalPrice) || totalPrice <= 0
                            || totalPrice > 1_000_000_000_000_000d) {
                        KsPluginBridge.sendJson(exchange, 400, "{\"error\":\"采购总价超出允许范围\"}"); return;
                    }
                }
            }
        } catch (java.sql.SQLException e) {
            KsPluginBridge.sendJson(exchange, 500, "{\"error\":\"" + e.getMessage() + "\"}"); return;
        }
        String bidId = UUID.randomUUID().toString().substring(0, 8);
        long now = System.currentTimeMillis() / 1000;
        try (var conn = plugin.ksCore().dataStore().getConnection()) {
            if (conn == null) { KsPluginBridge.sendJson(exchange, 500, "{\"error\":\"数据库未连接\"}"); return; }
            if ("ENTERPRISE".equals(bidderType)
                    && !hasEnterprisePermission(conn, enterpriseId, session, EnterprisePermissionService.MANAGE_BIDDING)) {
                KsPluginBridge.sendJson(exchange, 403, "{\"error\":\"没有该企业的投标权限\"}"); return;
            }
            try (var insert = conn.prepareStatement(
                    "INSERT INTO ks_ent_procurement_bids (id, procurement_id, bidder_uuid, enterprise_id, bidder_type, unit_price, total_price, status, submitted_at) VALUES (?,?,?,?,?,?,?,?,?)")) {
                insert.setString(1, bidId);
                insert.setString(2, procurementId);
                insert.setString(3, bidderUuid);
                insert.setString(4, enterpriseId == null ? "" : enterpriseId);
                insert.setString(5, bidderType);
                insert.setDouble(6, unitPrice);
                insert.setDouble(7, totalPrice);
                insert.setString(8, "PENDING");
                insert.setLong(9, now);
                insert.executeUpdate();
            }
        } catch (java.sql.SQLException e) {
            KsPluginBridge.sendJson(exchange, 500, "{\"error\":\"投标失败: " + e.getMessage() + "\"}"); return;
        }
        KsPluginBridge.sendJson(exchange, 200, gson.toJson(Map.of("id", bidId, "status", "PENDING", "message", "供应投标已提交")));
    }

    @SuppressWarnings("unchecked")
    private void handleProcurementAward(HttpExchange exchange) throws IOException {
        KsAuthManager.Session session = requireAuth(exchange);
        if (session == null) return;
        Map<String, Object> req = gson.fromJson(KsPluginBridge.readBody(exchange), Map.class);
        if (req == null) { KsPluginBridge.sendJson(exchange, 400, "{\"error\":\"无效请求\"}"); return; }
        String procurementId = (String) req.get("procurementId");
        String manualBidId = (String) req.get("bidId"); // 可选手动指定
        if (procurementId == null) { KsPluginBridge.sendJson(exchange, 400, "{\"error\":\"缺少 procurementId\"}"); return; }
        UUID personalSupplierUuid = null;
        double personalWalletPayout = 0.0;
        boolean personalWalletPaid = false;
        boolean databaseCommitted = false;
        try (var conn = plugin.ksCore().dataStore().getConnection()) {
            if (conn == null) { KsPluginBridge.sendJson(exchange, 500, "{\"error\":\"数据库未连接\"}"); return; }
            conn.setAutoCommit(false);
            // 获取采购信息（每个查询用完立即关闭 Statement/ResultSet，避免在同一连接上堆叠未关闭的游标导致 SQLITE_BUSY_SNAPSHOT）
            String entId, procurementStatus; double budget; int procurementQty;
            try (var st = conn.createStatement();
                 var rsP = st.executeQuery("SELECT * FROM ks_ent_procurements WHERE id='" + procurementId.replace("'", "''") + "'")) {
                if (!rsP.next()) { KsPluginBridge.sendJson(exchange, 404, "{\"error\":\"采购不存在\"}"); return; }
                entId = rsP.getString("enterprise_id");
                budget = rsP.getDouble("budget");
                procurementQty = rsP.getInt("quantity");
                procurementStatus = rsP.getString("status");
            }
            if (!"OPEN".equals(procurementStatus)) { KsPluginBridge.sendJson(exchange, 409, "{\"error\":\"采购已被处理\"}"); return; }
            if (!hasEnterprisePermission(conn, entId, session, EnterprisePermissionService.MANAGE_BIDDING)) {
                KsPluginBridge.sendJson(exchange, 403, "{\"error\":\"只有该企业所有者才能评标\"}"); return;
            }
            // 获取投标列表
            String bidSql = "SELECT * FROM ks_ent_procurement_bids WHERE procurement_id='" + procurementId.replace("'", "''") + "' AND status='PENDING'";
            if (manualBidId != null && !manualBidId.isEmpty()) {
                bidSql += " AND id='" + manualBidId.replace("'", "''") + "'";
            } else {
                // 数量对同一采购的所有投标恒定，按单价排序等价于按总价排序，且不受历史脏数据的 total_price 影响。
                bidSql += " ORDER BY unit_price ASC";
            }
            bidSql += " LIMIT 1";
            String bestBidId, supplierEntId, supplierUuid, supplierType; double totalPrice, bidUnitPrice;
            try (var st = conn.createStatement();
                 var rsB = st.executeQuery(bidSql)) {
                if (!rsB.next()) { KsPluginBridge.sendJson(exchange, 500, "{\"error\":\"无可用投标\"}"); return; }
                bestBidId = rsB.getString("id");
                bidUnitPrice = rsB.getDouble("unit_price");
                supplierEntId = rsB.getString("enterprise_id");
                supplierUuid = rsB.getString("bidder_uuid");
                supplierType = rsB.getString("bidder_type");
            }
            // 定标时按 单价 × 当前采购数量 重新计算总价，防止历史/损坏投标行里被篡改的 total_price。
            totalPrice = bidUnitPrice * procurementQty;
            if (procurementQty <= 0 || !Double.isFinite(totalPrice) || totalPrice <= 0
                    || totalPrice > 1_000_000_000_000_000d) {
                KsPluginBridge.sendJson(exchange, 400, "{\"error\":\"采购总价超出允许范围\"}"); return;
            }
            if (totalPrice > budget) {
                KsPluginBridge.sendJson(exchange, 400, "{\"error\":\"最低报价超出预算（报价: " + String.format("%.2f", totalPrice) + " > 预算: " + String.format("%.2f", budget) + "）\"}");
                return;
            }
            // 从企业公户付款（使用当前连接避免 SQLITE_BUSY）
            long now = System.currentTimeMillis() / 1000;
            double corpBal;
            try (var st = conn.createStatement();
                 var rsBal = st.executeQuery("SELECT balance FROM ks_ent_corporate_accounts WHERE enterprise_id='" + entId.replace("'", "''") + "'")) {
                corpBal = rsBal.next() ? rsBal.getDouble("balance") : 0;
            }
            if (corpBal < totalPrice) {
                KsPluginBridge.sendJson(exchange, 400, "{\"error\":\"企业公户余额不足（余额: " + String.format("%.2f", corpBal) + "）\"}"); return;
            }
            conn.createStatement().executeUpdate(
                "UPDATE ks_ent_corporate_accounts SET balance = balance - " + totalPrice + ", updated_at = " + now +
                " WHERE enterprise_id='" + entId.replace("'", "''") + "'");
            conn.createStatement().executeUpdate(
                "UPDATE ks_ent_enterprises SET current_assets = current_assets - " + totalPrice +
                " WHERE id='" + entId.replace("'", "''") + "'");
            String buyerBankId = getCorporateBankId(conn, entId);
            if (buyerBankId == null) throw new java.sql.SQLException("付款企业未开立公户");
            conn.createStatement().executeUpdate(
                "UPDATE ks_bank_banks SET total_assets = total_assets - " + totalPrice +
                " WHERE id='" + buyerBankId.replace("'", "''") + "'");
            // 付款给供应方
            if ("ENTERPRISE".equals(supplierType) && supplierEntId != null && !supplierEntId.isEmpty()) {
                ensureCorporateAccount(conn, supplierEntId, CORP_BANK_ID, 0, now);
                conn.createStatement().executeUpdate(
                    "UPDATE ks_ent_corporate_accounts SET balance = balance + " + totalPrice + ", updated_at = " + now +
                    " WHERE enterprise_id='" + supplierEntId.replace("'", "''") + "'");
                String supplierBankId = getCorporateBankId(conn, supplierEntId);
                if (supplierBankId == null) throw new java.sql.SQLException("供应企业未开立公户");
                conn.createStatement().executeUpdate(
                    "UPDATE ks_ent_enterprises SET current_assets = current_assets + " + totalPrice +
                    " WHERE id='" + supplierEntId.replace("'", "''") + "'");
                conn.createStatement().executeUpdate(
                    "UPDATE ks_bank_banks SET total_assets = total_assets + " + totalPrice +
                    " WHERE id='" + supplierBankId.replace("'", "''") + "'");
            } else if (supplierUuid != null && !supplierUuid.isEmpty()) {
                try {
                    personalSupplierUuid = UUID.fromString(supplierUuid);
                } catch (IllegalArgumentException invalidUuid) {
                    throw new java.sql.SQLException("供应方玩家标识无效");
                }
                personalWalletPayout = totalPrice;
                if (plugin.builtinEconomy().isRegistered()
                        && !plugin.builtinEconomy().depositInTransaction(
                        conn, personalSupplierUuid, "", personalWalletPayout)) {
                    throw new java.sql.SQLException("供应方内置钱包入账失败");
                }
            } else {
                throw new java.sql.SQLException("投标缺少有效的供应方账户");
            }
            // 更新状态
            conn.createStatement().executeUpdate(
                "UPDATE ks_ent_procurement_bids SET status='AWARDED' WHERE id='" + bestBidId.replace("'", "''") + "'");
            conn.createStatement().executeUpdate(
                "UPDATE ks_ent_procurement_bids SET status='REJECTED' WHERE procurement_id='" + procurementId.replace("'", "''") + "' AND status='PENDING' AND id<>'" + bestBidId.replace("'", "''") + "'");
            if (conn.createStatement().executeUpdate(
                "UPDATE ks_ent_procurements SET status='AWARDED' WHERE id='" + procurementId.replace("'", "''") + "' AND status='OPEN'") != 1) {
                throw new java.sql.SQLException("Procurement was already awarded");
            }
            if (personalSupplierUuid != null && !plugin.builtinEconomy().isRegistered()) {
                if (!depositWallet(personalSupplierUuid, personalWalletPayout)) {
                    throw new java.sql.SQLException("供应方钱包入账失败");
                }
                personalWalletPaid = true;
            }
            conn.commit();
            databaseCommitted = true;
            auditLog("PROCUREMENT_AWARD", session.playerUuid.toString(), session.playerName, "enterprise", entId,
                "采购完成: " + procurementId + " | 付款: " + String.format("%.2f", totalPrice) + " → " + supplierType);
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("procurementId", procurementId); result.put("bidId", bestBidId);
            result.put("supplierType", supplierType); result.put("totalPrice", totalPrice);
            result.put("paymentSource", "企业公户 (" + buyerBankId + ")");
            result.put("message", "采购完成，已从公户付款 " + String.format("%.2f", totalPrice));
            KsPluginBridge.sendJson(exchange, 200, gson.toJson(result));
        } catch (java.sql.SQLException | IOException e) {
            if (personalWalletPaid && !databaseCommitted) {
                try {
                    if (!withdrawWallet(personalSupplierUuid, personalWalletPayout)) {
                        plugin.getLogger().severe("Web 采购定标回滚后无法扣回供应方钱包款项: player="
                                + personalSupplierUuid + " amount=" + personalWalletPayout);
                    }
                } catch (IOException compensationError) {
                    plugin.getLogger().severe("Web 采购定标补偿扣款异常: " + compensationError.getMessage());
                }
            }
            KsPluginBridge.sendJson(exchange, 500, "{\"error\":\"采购定标失败: " + e.getMessage() + "\"}");
        }
    }

    // ================================================================
    // Joint Venture Invites — 合资邀请系统
    // ================================================================

    @SuppressWarnings("unchecked")
    private void handleInviteSend(HttpExchange exchange) throws IOException {
        KsAuthManager.Session session = requireAuth(exchange);
        if (session == null) return;
        Map<String, Object> req = gson.fromJson(KsPluginBridge.readBody(exchange), Map.class);
        if (req == null) { KsPluginBridge.sendJson(exchange, 400, "{\"error\":\"无效请求\"}"); return; }
        String enterpriseId = (String) req.get("enterpriseId");
        String bankId = (String) req.get("bankId");
        String inviteeRef = (String) req.get("inviteeUuid");
        if ((enterpriseId == null && bankId == null) || inviteeRef == null) {
            KsPluginBridge.sendJson(exchange, 400, "{\"error\":\"缺少 enterpriseId/bankId 或 inviteeUuid\"}"); return;
        }
        UUID invitee;
        try {
            invitee = resolvePlayerReference(inviteeRef);
        } catch (IllegalArgumentException e) {
            KsPluginBridge.sendJson(exchange, 400, gson.toJson(Map.of("error", e.getMessage()))); return;
        }
        String inviteeUuid = invitee.toString();
        String targetId = enterpriseId != null ? enterpriseId : bankId;
        String targetType = enterpriseId != null ? "enterprise" : "bank";
        // Admins and organisation members with MANAGE_MEMBERS may invite.
        try (var conn = plugin.ksCore().dataStore().getConnection()) {
            if (conn == null) { KsPluginBridge.sendJson(exchange, 500, "{\"error\":\"数据库未连接\"}"); return; }
            String tableName = enterpriseId != null ? "ks_ent_enterprises" : "ks_bank_banks";
            var rs = conn.createStatement().executeQuery(
                "SELECT owner_uuids FROM " + tableName + " WHERE id='" + targetId.replace("'", "''") + "'");
            if (!rs.next()) { KsPluginBridge.sendJson(exchange, 404, "{\"error\":\"企业/银行不存在\"}"); return; }
            boolean canInvite = enterpriseId != null
                    ? canManageEnterpriseMembers(conn, targetId, session)
                    : canManageBankMembers(conn, targetId, session);
            if (!canInvite) {
                KsPluginBridge.sendJson(exchange, 403, "{\"error\":\"没有成员邀请权限\"}"); return;
            }
            String membersTable = enterpriseId != null ? "ks_ent_members" : "ks_bank_members";
            var existing = conn.createStatement().executeQuery(
                    "SELECT COUNT(*) FROM " + membersTable + " WHERE "
                            + (enterpriseId != null ? "enterprise_id" : "bank_id") + "='" + targetId.replace("'", "''")
                            + "' AND player_uuid='" + inviteeUuid + "'");
            if (existing.next() && existing.getInt(1) > 0) {
                KsPluginBridge.sendJson(exchange, 400, "{\"error\":\"该玩家已经是成员\"}"); return;
            }
            int maxMembers = (int) getEconomicSetting("enterprise_max_members", 50);
            var memberCount = conn.createStatement().executeQuery(
                    "SELECT COUNT(*) FROM " + membersTable + " WHERE "
                            + (enterpriseId != null ? "enterprise_id" : "bank_id") + "='" + targetId.replace("'", "''") + "'");
            var pendingCount = conn.createStatement().executeQuery(
                    "SELECT COUNT(*) FROM ks_ent_invites WHERE " + (enterpriseId != null ? "enterprise_id" : "bank_id")
                            + "='" + targetId.replace("'", "''") + "' AND status='PENDING'");
            int occupied = memberCount.next() ? memberCount.getInt(1) : 0;
            occupied += pendingCount.next() ? pendingCount.getInt(1) : 0;
            if (occupied >= maxMembers) {
                KsPluginBridge.sendJson(exchange, 400, gson.toJson(Map.of("error", "成员名额已满，最大人数为 " + maxMembers))); return;
            }
            // 检查是否已有待处理邀请
            var rs2 = conn.createStatement().executeQuery(
                "SELECT COUNT(*) FROM ks_ent_invites WHERE " + (enterpriseId != null ? "enterprise_id" : "bank_id") +
                "='" + targetId.replace("'", "''") + "' AND invitee_uuid='" + inviteeUuid.replace("'", "''") + "' AND status='PENDING'");
            if (rs2.next() && rs2.getInt(1) > 0) {
                KsPluginBridge.sendJson(exchange, 400, "{\"error\":\"已存在待处理的邀请\"}"); return;
            }
        } catch (java.sql.SQLException e) {
            KsPluginBridge.sendJson(exchange, 500, "{\"error\":\"查询失败: " + e.getMessage() + "\"}"); return;
        }
        // 创建邀请
        String inviteId = UUID.randomUUID().toString().substring(0, 8);
        long now = System.currentTimeMillis() / 1000;
        try (var conn = plugin.ksCore().dataStore().getConnection()) {
            if (conn == null) { KsPluginBridge.sendJson(exchange, 500, "{\"error\":\"数据库未连接\"}"); return; }
            conn.createStatement().executeUpdate(
                "INSERT INTO ks_ent_invites (id, enterprise_id, bank_id, inviter_uuid, invitee_uuid, status, created_at) VALUES ('" +
                inviteId + "', " + (enterpriseId != null ? "'" + enterpriseId.replace("'", "''") + "'" : "NULL") + ", " +
                (bankId != null ? "'" + bankId.replace("'", "''") + "'" : "NULL") + ", '" +
                session.playerUuid.toString() + "', '" + inviteeUuid.replace("'", "''") + "', 'PENDING', " + now + ")");
        } catch (java.sql.SQLException e) {
            KsPluginBridge.sendJson(exchange, 500, "{\"error\":\"创建邀请失败: " + e.getMessage() + "\"}"); return;
        }
        auditLog("INVITE_SEND", session.playerUuid.toString(), session.playerName,
            enterpriseId != null ? "enterprise" : "bank", targetId,
            "邀请 " + inviteeUuid + " 加入");
        KsPluginBridge.sendJson(exchange, 200, gson.toJson(Map.of("message", "邀请已发送", "inviteId", inviteId)));
    }

    private void handleInviteList(HttpExchange exchange, String query) throws IOException {
        KsAuthManager.Session session = requireAuth(exchange);
        if (session == null) return;
        Map<String, String> params = KsPluginBridge.parseQuery(query);
        String enterpriseId = params.get("enterpriseId");
        String bankId = params.get("bankId");
        try (var conn = plugin.ksCore().dataStore().getConnection()) {
            if (conn == null) { KsPluginBridge.sendJson(exchange, 500, "{\"error\":\"数据库未连接\"}"); return; }
            if (enterpriseId != null && !canManageEnterpriseMembers(conn, enterpriseId, session)) {
                KsPluginBridge.sendJson(exchange, 403, "{\"error\":\"没有查看企业邀请的权限\"}"); return;
            }
            if (bankId != null && !canManageBankMembers(conn, bankId, session)) {
                KsPluginBridge.sendJson(exchange, 403, "{\"error\":\"没有查看银行邀请的权限\"}"); return;
            }
            if (enterpriseId == null && bankId == null && !session.isAdmin) {
                KsPluginBridge.sendJson(exchange, 403, "{\"error\":\"必须指定可管理的组织\"}"); return;
            }
        } catch (java.sql.SQLException e) {
            KsPluginBridge.sendJson(exchange, 500, "{\"error\":\"权限校验失败\"}"); return;
        }
        List<Map<String, Object>> list = new ArrayList<>();
        StringBuilder sql = new StringBuilder("SELECT * FROM ks_ent_invites WHERE 1=1");
        if (enterpriseId != null) sql.append(" AND enterprise_id='").append(enterpriseId.replace("'", "''")).append("'");
        if (bankId != null) sql.append(" AND bank_id='").append(bankId.replace("'", "''")).append("'");
        sql.append(" ORDER BY created_at DESC LIMIT 100");
        queryRows(list, sql.toString(), null);
        KsPluginBridge.sendJson(exchange, 200, gson.toJson(Map.of("invites", list, "count", list.size())));
    }

    private void handlePlayerInvites(HttpExchange exchange) throws IOException {
        KsAuthManager.Session session = requireAuth(exchange);
        if (session == null) return;
        List<Map<String, Object>> list = new ArrayList<>();
        queryRows(list, "SELECT * FROM ks_ent_invites WHERE invitee_uuid='" +
            session.playerUuid.toString() + "' AND status='PENDING' ORDER BY created_at DESC LIMIT 50", null);
        List<Map<String, Object>> confirmations = new ArrayList<>();
        long now = System.currentTimeMillis() / 1000;
        queryRows(confirmations, "SELECT p.id,p.name,p.type,p.kind,p.creator_uuid,p.registered_capital,p.created_at,p.expires_at "
                + "FROM ks_ent_pending_creations p JOIN ks_ent_pending_creation_confirmations c ON c.pending_id=p.id "
                + "WHERE c.player_uuid='" + session.playerUuid + "' AND c.status='PENDING' AND p.status='PENDING' AND p.expires_at>" + now
                + " ORDER BY p.created_at DESC LIMIT 50", null);
        KsPluginBridge.sendJson(exchange, 200, gson.toJson(Map.of("invites", list, "creationConfirmations", confirmations,
                "count", list.size() + confirmations.size())));
    }

    @SuppressWarnings("unchecked")
    private void handlePlayerInviteRespond(HttpExchange exchange) throws IOException {
        KsAuthManager.Session session = requireAuth(exchange);
        if (session == null) return;
        Map<String, Object> req = gson.fromJson(KsPluginBridge.readBody(exchange), Map.class);
        if (req == null) { KsPluginBridge.sendJson(exchange, 400, "{\"error\":\"无效请求\"}"); return; }
        String inviteId = (String) req.get("inviteId");
        String action = req.getOrDefault("action", "DECLINE").toString().toUpperCase();
        if (inviteId == null || (!action.equals("ACCEPT") && !action.equals("DECLINE"))) {
            KsPluginBridge.sendJson(exchange, 400, "{\"error\":\"缺少 inviteId 或 action 无效 (ACCEPT/DECLINE)\"}");
            return;
        }
        try (var conn = plugin.ksCore().dataStore().getConnection()) {
            if (conn == null) { KsPluginBridge.sendJson(exchange, 500, "{\"error\":\"数据库未连接\"}"); return; }
            // 验证邀请归属
            var rs = conn.createStatement().executeQuery(
                "SELECT * FROM ks_ent_invites WHERE id='" + inviteId.replace("'", "''") + "' AND invitee_uuid='" +
                session.playerUuid.toString() + "' AND status='PENDING'");
            if (!rs.next()) {
                KsPluginBridge.sendJson(exchange, 404, "{\"error\":\"邀请不存在或已处理\"}"); return;
            }
            String entId = rs.getString("enterprise_id");
            String bankId = rs.getString("bank_id");
            long now = System.currentTimeMillis() / 1000;
            if ("ACCEPT".equals(action)) {
                if (entId != null) {
                    // 加入企业成员
                    try { EcoWebBusinessSchema.ensureEnterpriseMembers(conn);
                    } catch (java.sql.SQLException ignored) {}
                    int maxMembers = (int) getEconomicSetting("enterprise_max_members", 50);
                    var count = conn.createStatement().executeQuery(
                            "SELECT COUNT(*) FROM ks_ent_members WHERE enterprise_id='" + entId.replace("'", "''") + "'");
                    if (count.next() && count.getInt(1) >= maxMembers) {
                        KsPluginBridge.sendJson(exchange, 409, gson.toJson(Map.of("error", "企业成员名额已满"))); return;
                    }
                    upsertEnterpriseMember(conn, entId, session.playerUuid.toString(), session.playerName,
                            "MEMBER", 0, now, true);
                } else if (bankId != null) {
                    try { EcoWebBusinessSchema.ensureBankMembers(conn);
                    } catch (java.sql.SQLException ignored) {}
                    int maxMembers = (int) getEconomicSetting("enterprise_max_members", 50);
                    var count = conn.createStatement().executeQuery(
                            "SELECT COUNT(*) FROM ks_bank_members WHERE bank_id='" + bankId.replace("'", "''") + "'");
                    if (count.next() && count.getInt(1) >= maxMembers) {
                        KsPluginBridge.sendJson(exchange, 409, gson.toJson(Map.of("error", "银行成员名额已满"))); return;
                    }
                    upsertBankMember(conn, bankId, session.playerUuid.toString(), session.playerName,
                            "MEMBER", now, true);
                }
            }
            conn.createStatement().executeUpdate(
                "UPDATE ks_ent_invites SET status='" + action + "', responded_at=" + now +
                " WHERE id='" + inviteId.replace("'", "''") + "'");
            KsPluginBridge.sendJson(exchange, 200, "{\"message\":\"已" + (action.equals("ACCEPT") ? "接受" : "拒绝") + "邀请\"}");
        } catch (java.sql.SQLException e) {
            KsPluginBridge.sendJson(exchange, 500, "{\"error\":\"处理邀请失败: " + e.getMessage() + "\"}");
        }
    }

    // ================================================================
    // Enterprise Permissions — 企业权限管理
    // ================================================================

    private void handleEnterprisePermissions(HttpExchange exchange, String query) throws IOException {
        KsAuthManager.Session session = requireAuth(exchange);
        if (session == null) return;
        Map<String, String> params = KsPluginBridge.parseQuery(query);
        String enterpriseId = params.get("enterpriseId");
        if (enterpriseId == null) { KsPluginBridge.sendJson(exchange, 400, "{\"error\":\"缺少 enterpriseId\"}"); return; }
        List<Map<String, Object>> list = new ArrayList<>();
        try (var conn = plugin.ksCore().dataStore().getConnection()) {
            if (conn == null) { KsPluginBridge.sendJson(exchange, 500, "{\"error\":\"数据库未连接\"}"); return; }
            if (!canManageEnterpriseMembers(conn, enterpriseId, session)) {
                KsPluginBridge.sendJson(exchange, 403, "{\"error\":\"仅企业创始人或经理可查看权限\"}"); return;
            }
            try (var ps = conn.prepareStatement("SELECT * FROM ks_ent_permissions WHERE enterprise_id=? ORDER BY granted_at DESC")) {
                ps.setString(1, enterpriseId);
                try (var rs = ps.executeQuery()) {
                    while (rs.next()) {
                        Map<String, Object> row = new LinkedHashMap<>();
                        row.put("enterprise_id", rs.getString("enterprise_id"));
                        row.put("player_uuid", rs.getString("player_uuid"));
                        row.put("permission", rs.getString("permission"));
                        row.put("granted_by", rs.getString("granted_by"));
                        row.put("granted_at", rs.getLong("granted_at"));
                        list.add(row);
                    }
                }
            }
        } catch (java.sql.SQLException e) {
            KsPluginBridge.sendJson(exchange, 500, gson.toJson(Map.of("error", "权限查询失败: " + e.getMessage()))); return;
        }
        KsPluginBridge.sendJson(exchange, 200, gson.toJson(Map.of("permissions", list, "count", list.size())));
    }

    @SuppressWarnings("unchecked")
    private void handleEnterprisePermissionSet(HttpExchange exchange) throws IOException {
        KsAuthManager.Session session = requireAuth(exchange);
        if (session == null) return;
        Map<String, Object> req = gson.fromJson(KsPluginBridge.readBody(exchange), Map.class);
        if (req == null) { KsPluginBridge.sendJson(exchange, 400, "{\"error\":\"无效请求\"}"); return; }
        String enterpriseId = (String) req.get("enterpriseId");
        String playerUuid = (String) req.get("playerUuid");
        String permission = (String) req.get("permission");
        boolean enabled = !Boolean.FALSE.equals(req.get("enabled"));
        if (enterpriseId == null || playerUuid == null || permission == null) {
            KsPluginBridge.sendJson(exchange, 400, "{\"error\":\"缺少 enterpriseId/playerUuid/permission\"}"); return;
        }
        permission = permission.toUpperCase(Locale.ROOT);
        if (!enterprisePermissions.allowedPermissions().contains(permission)) {
            KsPluginBridge.sendJson(exchange, 400, "{\"error\":\"不支持的企业权限\"}"); return;
        }
        try (var conn = plugin.ksCore().dataStore().getConnection()) {
            if (conn == null) { KsPluginBridge.sendJson(exchange, 500, "{\"error\":\"数据库未连接\"}"); return; }
            if (!session.isAdmin && !enterprisePermissions.hasPermission(conn, enterpriseId, session.playerUuid,
                    EnterprisePermissionService.MANAGE_PERMISSIONS)) {
                KsPluginBridge.sendJson(exchange, 403, "{\"error\":\"需要企业权限管理权限\"}"); return;
            }
            UUID targetUuid = resolvePlayerReference(playerUuid);
            if (enterprisePermissions.roleOf(conn, enterpriseId, targetUuid) == null) {
                KsPluginBridge.sendJson(exchange, 400, "{\"error\":\"目标必须是该企业成员\"}"); return;
            }
            if (!session.isAdmin && !enterprisePermissions.isOwner(conn, enterpriseId, session.playerUuid)
                    && !enterprisePermissions.hasPermission(conn, enterpriseId, session.playerUuid, permission)) {
                KsPluginBridge.sendJson(exchange, 403, "{\"error\":\"不能授予自己不拥有的权限\"}"); return;
            }
            if (enabled) {
                upsertEnterprisePermission(conn, enterpriseId, targetUuid.toString(), permission,
                        session.playerUuid.toString(), System.currentTimeMillis() / 1000);
            } else {
                try (var ps = conn.prepareStatement("DELETE FROM ks_ent_permissions WHERE enterprise_id=? AND player_uuid=? AND permission=?")) {
                    ps.setString(1, enterpriseId); ps.setString(2, targetUuid.toString());
                    ps.setString(3, permission);
                    ps.executeUpdate();
                }
            }
        } catch (java.sql.SQLException e) {
            KsPluginBridge.sendJson(exchange, 500, "{\"error\":\"设置权限失败: " + e.getMessage() + "\"}"); return;
        }
        KsPluginBridge.sendJson(exchange, 200, gson.toJson(Map.of("message", enabled ? "企业权限已授予" : "企业权限已撤销")));
        auditLog("ENTERPRISE_PERMISSION_SET", session.playerUuid.toString(), session.playerName, "enterprise", enterpriseId,
            (enabled ? "授予 " : "撤销 ") + playerUuid + " 权限: " + permission);
    }

    // ================================================================
    // Tax APIs — 税法系统
    // ================================================================

    private void handleTaxStats(HttpExchange exchange) throws IOException {
        Map<String, Object> s = new LinkedHashMap<>();
        s.put("moduleLoaded", hasExtra("ks-eco-tax"));
        queryInt(s, "SELECT COUNT(*) FROM ks_tax_records", "totalTransactions");
        queryDouble(s, "SELECT COALESCE(SUM(tax_amount),0) FROM ks_tax_records", "totalCollected");
        queryInt(s, "SELECT COUNT(*) FROM ks_tax_penalties WHERE paid=0", "unpaidPenalties");
        queryDouble(s, "SELECT COALESCE(SUM(penalty_amount),0) FROM ks_tax_penalties WHERE paid=0", "unpaidAmount");
        KsPluginBridge.sendJson(exchange, 200, gson.toJson(s));
    }

    private void handleTaxRatesGet(HttpExchange exchange) throws IOException {
        Map<String, Object> resp = new LinkedHashMap<>();
        // 默认通用税率
        Map<String, Double> base = new LinkedHashMap<>();
        base.put("MARKET_TRADE", 0.02);
        base.put("OFFICIAL_TRADE", 0.03);
        base.put("ENTERPRISE_TAX", 0.08);
        base.put("BANK_INTEREST", 0.05);
        base.put("PENALTY_TAX", 0.20);
        base.put("DIVIDEND_TAX", 0.10);
        // 反射获取最新值
        Object result = callExtraManager("ks-eco-tax", "taxRateManager", "getAllRates",
                new Class[]{}, (Object[]) new Object[]{});
        if (result instanceof Map<?,?> rates) {
            for (var e : ((Map<?,?>) rates).entrySet()) {
                String k = e.getKey().toString();
                if (!k.contains(":") && e.getValue() instanceof Number n) {
                    base.put(k, n.doubleValue());
                }
            }
        }
        resp.put("base", base);
        // 行业税率
        Object indRes = callExtraManager("ks-eco-tax", "taxRateManager", "getAllIndustryRates",
                new Class[]{}, (Object[]) new Object[]{});
        if (indRes instanceof Map<?,?> ir) {
            Map<String, Object> industryMap = new LinkedHashMap<>();
            for (var e : ((Map<?,?>) ir).entrySet()) {
                if (e.getValue() instanceof Map<?,?> inner) {
                    Map<String, Double> sub = new LinkedHashMap<>();
                    for (var c : ((Map<?,?>) inner).entrySet()) {
                        if (c.getValue() instanceof Number n) {
                            sub.put(c.getKey().toString(), n.doubleValue());
                        }
                    }
                    industryMap.put(e.getKey().toString(), sub);
                }
            }
            resp.put("industry", industryMap);
        }
        // 阶梯
        List<Map<String, Object>> brackets = new ArrayList<>();
        for (String ind : new String[]{"INDUSTRY", "AGRICULTURE", "REAL_ESTATE", "OTHER"}) {
            Object br = callExtraManager("ks-eco-tax", "taxRateManager", "listBrackets",
                    new Class[]{String.class}, ind);
            if (br instanceof List<?> lst) {
                for (Object o : lst) {
                    if (o instanceof Map<?,?> m) brackets.add(toStrMap(m));
                }
            }
        }
        resp.put("brackets", brackets);
        KsPluginBridge.sendJson(exchange, 200, gson.toJson(resp));
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> toStrMap(Map<?,?> m) {
        Map<String, Object> out = new LinkedHashMap<>();
        for (var e : m.entrySet()) out.put(e.getKey().toString(), e.getValue());
        return out;
    }

    @SuppressWarnings("unchecked")
    private void handleTaxRatesSet(HttpExchange exchange) throws IOException {
        KsAuthManager.Session session = requireAdminAuth(exchange);
        if (session == null) return;
        String gate = checkPoliticGate();
        if (gate != null) { KsPluginBridge.sendJson(exchange, 403, gson.toJson(Map.of("error", gate))); return; }
        Map<String, Object> req = gson.fromJson(KsPluginBridge.readBody(exchange), Map.class);
        if (req == null) { KsPluginBridge.sendJson(exchange, 400, "{\"error\":\"无效请求\"}"); return; }
        String category = (String) req.get("category");
        String industry = (String) req.get("industry");
        double rate = toDouble(req.get("rate"), -1);
        if (rate < 0 || rate > 1) {
            KsPluginBridge.sendJson(exchange, 400, "{\"error\":\"税率必须在 0-1 范围内（例如 0.05 = 5%）\"}");
            return;
        }
        if (category == null) { KsPluginBridge.sendJson(exchange, 400, "{\"error\":\"缺少 category\"}"); return; }
        callExtraManager("ks-eco-tax", "taxRateManager", "setRate",
                new Class[]{String.class, double.class, String.class}, category, rate,
                industry != null ? industry : "");
        KsPluginBridge.sendJson(exchange, 200, "{\"message\":\"税率已更新: " + (industry == null || industry.isEmpty() ? "通用/" : industry + "/") + category + " = " + rate + "\"}");
        auditLog("TAX_RATE_SET", session.playerUuid.toString(), session.playerName, "tax", category,
            "税率调整为 " + String.format("%.1f", rate*100) + "%");
    }

    private void handleTaxRecords(HttpExchange exchange, String query) throws IOException {
        KsAuthManager.Session session = requireAuth(exchange);
        if (session == null) return;
        List<Map<String, Object>> list = new ArrayList<>();
        Map<String, String> params = KsPluginBridge.parseQuery(query);
        String payer = params.get("payer");
        if (!session.isAdmin) payer = session.playerUuid.toString();
        String sql = "SELECT * FROM ks_tax_records";
        if (payer != null) sql += " WHERE payer_uuid='" + payer.replace("'", "''") + "'";
        sql += " ORDER BY collected_at DESC LIMIT 100";
        queryRows(list, sql, null);
        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("records", list); resp.put("count", list.size());
        KsPluginBridge.sendJson(exchange, 200, gson.toJson(resp));
    }

    private void handlePenaltyList(HttpExchange exchange, String query) throws IOException {
        KsAuthManager.Session session = requireAuth(exchange);
        if (session == null) return;
        List<Map<String, Object>> list = new ArrayList<>();
        Map<String, String> params = KsPluginBridge.parseQuery(query);
        String target = params.get("target");
        if (!session.isAdmin) target = session.playerUuid.toString();
        String sql = "SELECT * FROM ks_tax_penalties";
        if (target != null) sql += " WHERE target_uuid='" + target.replace("'", "''") + "'";
        sql += " ORDER BY issued_at DESC LIMIT 100";
        queryRows(list, sql, null);
        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("penalties", list); resp.put("count", list.size());
        KsPluginBridge.sendJson(exchange, 200, gson.toJson(resp));
    }

    @SuppressWarnings("unchecked")
    private void handlePenaltyIssue(HttpExchange exchange) throws IOException {
        KsAuthManager.Session session = requireAdminAuth(exchange);
        if (session == null) return;
        Map<String, Object> req = gson.fromJson(KsPluginBridge.readBody(exchange), Map.class);
        if (req == null) { KsPluginBridge.sendJson(exchange, 400, "{\"error\":\"无效请求\"}"); return; }
        String targetUuid = (String) req.get("targetUuid");
        String targetName = (String) req.getOrDefault("targetName", "");
        String penaltyType = (String) req.getOrDefault("penaltyType", "TAX_EVASION");
        double baseAmount = toDouble(req.get("baseAmount"), 0);
        String reason = (String) req.getOrDefault("reason", "");
        if (targetUuid == null) { KsPluginBridge.sendJson(exchange, 400, "{\"error\":\"缺少 targetUuid\"}"); return; }
        try { UUID.fromString(targetUuid); }
        catch (IllegalArgumentException invalid) {
            KsPluginBridge.sendJson(exchange, 400, "{\"error\":\"targetUuid 无效\"}"); return;
        }
        targetName = targetName == null ? "" : targetName.trim();
        penaltyType = penaltyType == null ? "TAX_EVASION" : penaltyType.trim().toUpperCase(Locale.ROOT);
        reason = reason == null ? "" : reason.trim();
        if (!penaltyType.matches("[A-Z0-9_]{1,64}") || targetName.length() > 64 || reason.length() > 512
                || !Double.isFinite(baseAmount) || baseAmount <= 0.0d || baseAmount > 1_000_000_000_000d) {
            KsPluginBridge.sendJson(exchange, 400, "{\"error\":\"罚单参数无效\"}"); return;
        }
        String penId = "PEN-" + UUID.randomUUID();
        long now = System.currentTimeMillis() / 1000;
        double penaltyRate = 0.20;
        try (var conn = plugin.ksCore().dataStore().getConnection()) {
            if (conn == null) { KsPluginBridge.sendJson(exchange, 500, "{\"error\":\"数据库未连接\"}"); return; }
            try (var rate = conn.prepareStatement("SELECT rate FROM ks_tax_rates WHERE category=?")) {
                rate.setString(1, "TAX_PENALTY");
                try (var rows = rate.executeQuery()) {
                    if (rows.next()) penaltyRate = rows.getDouble(1);
                }
            }
            if (!Double.isFinite(penaltyRate) || penaltyRate < 0.0d || penaltyRate > 1.0d
                    || !Double.isFinite(baseAmount * penaltyRate)) {
                KsPluginBridge.sendJson(exchange, 409, "{\"error\":\"罚金税率配置无效\"}"); return;
            }
            try (var insert = conn.prepareStatement(
                    "INSERT INTO ks_tax_penalties (id,target_uuid,target_name,penalty_type,base_amount,"
                            + "penalty_rate,penalty_amount,reason,paid,issued_at) VALUES (?,?,?,?,?,?,?,?,0,?)")) {
                insert.setString(1, penId);
                insert.setString(2, targetUuid);
                insert.setString(3, targetName);
                insert.setString(4, penaltyType);
                insert.setDouble(5, baseAmount);
                insert.setDouble(6, penaltyRate);
                insert.setDouble(7, baseAmount * penaltyRate);
                insert.setString(8, reason);
                insert.setLong(9, now);
                insert.executeUpdate();
            }
            Map<String, Object> resp = new LinkedHashMap<>();
            resp.put("id", penId); resp.put("message", "罚单已发出");
            auditLog("PENALTY_ISSUE", session.playerUuid.toString(), session.playerName, "player", targetUuid,
                "罚单: " + penaltyType + " | 基数: " + String.format("%.2f", baseAmount) + " | 原因: " + reason);
            KsPluginBridge.sendJson(exchange, 200, gson.toJson(resp));
        } catch (java.sql.SQLException e) {
            KsPluginBridge.sendJson(exchange, 500, "{\"error\":\"发出罚单失败: " + e.getMessage() + "\"}");
        }
    }

    // ================================================================
    // BlindBox APIs — 盲盒系统
    // ================================================================

    /** 列出所有卡池（公开 + admin 都可用） */
    private void handleBbPoolsList(HttpExchange exchange) throws IOException {
        KsPluginBridge.sendJson(exchange, 200, gson.toJson(Map.of(
                "pools", plugin.blindBoxManager().listPools(),
                "maxEnterpriseLevel", plugin.enterpriseLevelManager().getMaxLevel()
        )));
    }

    /** 列出某池战利品（公开，不含 item_data 大字段） */
    private void handleBbLootList(HttpExchange exchange, String query) throws IOException {
        Map<String, String> q = KsPluginBridge.parseQuery(query);
        String poolId = q.get("poolId");
        if (poolId == null || poolId.isEmpty()) {
            KsPluginBridge.sendJson(exchange, 400, "{\"error\":\"缺少 poolId\"}"); return;
        }
        CompletableFuture<List<Map<String, Object>>> future = new CompletableFuture<>();
        plugin.blindBoxManager().loadLootViewsAsync(poolId, false, future::complete,
                error -> future.completeExceptionally(new IllegalStateException(error)));
        try {
            List<Map<String, Object>> loot = future.get(30, TimeUnit.SECONDS);
            KsPluginBridge.sendJson(exchange, 200, gson.toJson(Map.of(
                    "poolId", poolId,
                    "loot", loot
            )));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            KsPluginBridge.sendJson(exchange, 503, "{\"error\":\"战利品列表请求被中断\"}");
        } catch (ExecutionException e) {
            KsPluginBridge.sendJson(exchange, 503, "{\"error\":\"数据库队列繁忙，请稍后重试\"}");
        } catch (TimeoutException e) {
            KsPluginBridge.sendJson(exchange, 504, "{\"error\":\"加载战利品列表超时\"}");
        }
    }

    /** 玩家单抽 */
    private void handleBbPull(HttpExchange exchange) throws IOException {
        KsAuthManager.Session session = requireAuth(exchange);
        if (session == null) return;
        Map<String, Object> req = gson.fromJson(KsPluginBridge.readBody(exchange), Map.class);
        if (req == null) { KsPluginBridge.sendJson(exchange, 400, "{\"error\":\"无效请求\"}"); return; }
        String poolId = (String) req.get("poolId");
        if (poolId == null || poolId.isEmpty()) {
            KsPluginBridge.sendJson(exchange, 400, "{\"error\":\"缺少 poolId\"}"); return;
        }
        CompletableFuture<BlindBoxManager.PullResult> future = new CompletableFuture<>();
        plugin.blindBoxManager().pullAsync(session.playerUuid, poolId, future::complete);
        try {
            BlindBoxManager.PullResult result = future.get(30, TimeUnit.SECONDS);
            KsPluginBridge.sendJson(exchange, result.success ? 200 : 400, gson.toJson(result.toMap()));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            KsPluginBridge.sendJson(exchange, 503, "{\"error\":\"盲盒请求被中断\"}");
        } catch (ExecutionException e) {
            KsPluginBridge.sendJson(exchange, 500, "{\"error\":\"盲盒结算失败\"}");
        } catch (TimeoutException e) {
            KsPluginBridge.sendJson(exchange, 504, "{\"error\":\"盲盒结算超时，请稍后查看抽取记录\"}");
        }
    }

    /** 玩家保底进度 */
    private void handleBbPity(HttpExchange exchange) throws IOException {
        KsAuthManager.Session session = requireAuth(exchange);
        if (session == null) return;
        String poolId = KsPluginBridge.parseQuery(exchange.getRequestURI().getRawQuery()).get("poolId");
        if (poolId == null || poolId.isEmpty()) {
            KsPluginBridge.sendJson(exchange, 400, "{\"error\":\"缺少 poolId\"}"); return;
        }
        int count = plugin.blindBoxManager().getPityCount(session.playerUuid, poolId);
        Map<String, Integer> counts = plugin.blindBoxManager().getPityCounts(session.playerUuid, poolId);
        Map<String, Object> pool = plugin.blindBoxManager().getPool(poolId);
        int pityMax = pool != null ? ((Number) pool.get("pityMax")).intValue() : 0;
        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("poolId", poolId);
        resp.put("count", count);
        resp.put("counts", counts);
        resp.put("pityMax", pityMax);
        resp.put("pityRulesText", pool != null ? pool.getOrDefault("pityRulesText", "") : "");
        KsPluginBridge.sendJson(exchange, 200, gson.toJson(resp));
    }

    /** 玩家近 20 条抽取记录 */
    private void handleBbMyPulls(HttpExchange exchange) throws IOException {
        KsAuthManager.Session session = requireAuth(exchange);
        if (session == null) return;
        KsPluginBridge.sendJson(exchange, 200, gson.toJson(Map.of(
                "pulls", plugin.blindBoxManager().recentPulls(session.playerUuid, 20)
        )));
    }

    /** admin: 创建/更新卡池 */
    @SuppressWarnings("unchecked")
    private void handleAdminBbPool(HttpExchange exchange) throws IOException {
        KsAuthManager.Session s = requireAdminAuth(exchange);
        if (s == null) return;
        Map<String, Object> req = gson.fromJson(KsPluginBridge.readBody(exchange), Map.class);
        if (req == null) { KsPluginBridge.sendJson(exchange, 400, "{\"error\":\"无效请求\"}"); return; }
        String id = (String) req.get("id");
        String name = (String) req.get("name");
        String type = (String) req.getOrDefault("poolType", "ITEM");
        double price = toDouble(req.get("price"), 100);
        boolean enabled = Boolean.TRUE.equals(req.get("enabled"));
        int pityMax = (int) toDouble(req.get("pityMax"), 50);
        String desc = (String) req.getOrDefault("description", "");
        String ownerType = (String) req.getOrDefault("ownerType", "PUBLIC");
        String allowedCat = (String) req.getOrDefault("allowedCategories", "");
        String allowedInd = (String) req.getOrDefault("allowedIndustries", "");
        String requiredLandZoneTypes = (String) req.getOrDefault("requiredLandZoneTypes", "");
        String pityRules = (String) req.getOrDefault("pityRules", "");
        int minEnterpriseLevel = (int) toDouble(req.get("minEnterpriseLevel"), 1);
        boolean limitedOnly = Boolean.TRUE.equals(req.get("limitedOnly"));
        if (id == null || id.isBlank() || name == null || name.isBlank()) {
            KsPluginBridge.sendJson(exchange, 400, "{\"error\":\"id 和 name 必填\"}"); return;
        }
        if (minEnterpriseLevel < 1 || minEnterpriseLevel > plugin.enterpriseLevelManager().getMaxLevel()) {
            KsPluginBridge.sendJson(exchange, 400, gson.toJson(Map.of("error", "最低企业等级必须在 1-" + plugin.enterpriseLevelManager().getMaxLevel() + " 之间"))); return;
        }
        if (plugin.blindBoxManager().upsertPool(id, name, type, price, enabled, pityMax, desc,
                ownerType, allowedCat, allowedInd, requiredLandZoneTypes, pityRules, minEnterpriseLevel)
                && plugin.blindBoxManager().setLimitedOnly(id, limitedOnly)) {
            auditLog("BLINDBOX_POOL_UPSERT", s.playerUuid.toString(), s.playerName, "blindbox_pool", id,
                    "name=" + name + ",type=" + type + ",price=" + price + ",ownerType=" + ownerType + ",minEnterpriseLevel=" + minEnterpriseLevel + ",limitedOnly=" + limitedOnly);
            KsPluginBridge.sendJson(exchange, 200, "{\"message\":\"卡池已保存\"}");
        } else {
            KsPluginBridge.sendJson(exchange, 500, "{\"error\":\"保存失败\"}");
        }
    }

    private void handleAdminBbPoolDelete(HttpExchange exchange) throws IOException {
        KsAuthManager.Session s = requireAdminAuth(exchange);
        if (s == null) return;
        Map<String, Object> req = gson.fromJson(KsPluginBridge.readBody(exchange), Map.class);
        if (req == null) { KsPluginBridge.sendJson(exchange, 400, "{\"error\":\"无效请求\"}"); return; }
        String id = (String) req.get("id");
        if (id == null) { KsPluginBridge.sendJson(exchange, 400, "{\"error\":\"缺少 id\"}"); return; }
        if (plugin.blindBoxManager().deletePool(id)) {
            auditLog("BLINDBOX_POOL_DELETE", s.playerUuid.toString(), s.playerName, "blindbox_pool", id, "");
            KsPluginBridge.sendJson(exchange, 200, "{\"message\":\"已删除\"}");
        } else {
            KsPluginBridge.sendJson(exchange, 500, "{\"error\":\"删除失败\"}");
        }
    }

    /** admin: 新增战利品 */
    @SuppressWarnings("unchecked")
    private void handleAdminBbLootAdd(HttpExchange exchange) throws IOException {
        KsAuthManager.Session s = requireAdminAuth(exchange);
        if (s == null) return;
        Map<String, Object> req = gson.fromJson(KsPluginBridge.readBody(exchange), Map.class);
        if (req == null) { KsPluginBridge.sendJson(exchange, 400, "{\"error\":\"无效请求\"}"); return; }
        String poolId = (String) req.get("poolId");
        String material = (String) req.get("itemMaterial");
        String display = (String) req.getOrDefault("displayName", "");
        int weight = (int) toDouble(req.get("weight"), 1);
        String rarity = (String) req.getOrDefault("rarity", "COMMON");
        int qty = (int) toDouble(req.get("quantity"), 1);
        if (poolId == null || material == null) {
            KsPluginBridge.sendJson(exchange, 400, "{\"error\":\"poolId 和 itemMaterial 必填\"}"); return;
        }
        // 校验 material 合法
        try { Material.valueOf(material); }
        catch (IllegalArgumentException e) {
            KsPluginBridge.sendJson(exchange, 400, "{\"error\":\"未知材质: " + material + "\"}"); return;
        }
        String id = plugin.blindBoxManager().addLoot(poolId, material, display, weight, rarity, qty);
        if (id != null) {
            auditLog("BLINDBOX_LOOT_ADD", s.playerUuid.toString(), s.playerName, "blindbox_loot", id,
                    "pool=" + poolId + ",mat=" + material + ",rarity=" + rarity);
            KsPluginBridge.sendJson(exchange, 200, "{\"message\":\"已添加\",\"id\":\"" + id + "\"}");
        } else {
            KsPluginBridge.sendJson(exchange, 500, "{\"error\":\"添加失败\"}");
        }
    }

    private void handleAdminBbLootDelete(HttpExchange exchange) throws IOException {
        KsAuthManager.Session s = requireAdminAuth(exchange);
        if (s == null) return;
        Map<String, Object> req = gson.fromJson(KsPluginBridge.readBody(exchange), Map.class);
        if (req == null) { KsPluginBridge.sendJson(exchange, 400, "{\"error\":\"无效请求\"}"); return; }
        String id = (String) req.get("id");
        if (id == null) { KsPluginBridge.sendJson(exchange, 400, "{\"error\":\"缺少 id\"}"); return; }
        if (plugin.blindBoxManager().deleteLoot(id)) {
            auditLog("BLINDBOX_LOOT_DELETE", s.playerUuid.toString(), s.playerName, "blindbox_loot", id, "");
            KsPluginBridge.sendJson(exchange, 200, "{\"message\":\"已删除\"}");
        } else {
            KsPluginBridge.sendJson(exchange, 500, "{\"error\":\"删除失败\"}");
        }
    }

    // ================================================================
    // Enterprise BlindBox APIs — 企业盲盒
    // ================================================================

    /** 校验玩家是否为企业成员（所有者 OR 雇员） */
    private boolean isEnterpriseMember(String enterpriseId, UUID playerUuid) {
        if (enterpriseId == null || playerUuid == null) return false;
        try (var conn = plugin.ksCore().dataStore().getConnection()) {
            if (conn == null) return false;
            try (var ps = conn.prepareStatement(
                    "SELECT owner_uuids FROM ks_ent_enterprises WHERE id=?")) {
                ps.setString(1, enterpriseId);
                try (var rs = ps.executeQuery()) {
                    if (rs.next()) {
                        String owners = rs.getString(1);
                        if (owners != null) {
                            for (String o : owners.split(",")) {
                                if (o.trim().equals(playerUuid.toString())) return true;
                            }
                        }
                    }
                }
            }
            try (var ps = conn.prepareStatement(
                    "SELECT 1 FROM ks_ent_members WHERE enterprise_id=? AND player_uuid=? LIMIT 1")) {
                ps.setString(1, enterpriseId);
                ps.setString(2, playerUuid.toString());
                try (var rs = ps.executeQuery()) { if (rs.next()) return true; }
            }
        } catch (java.sql.SQLException ignored) {}
        return false;
    }

    /** 列出企业盲盒池（按企业行业过滤） */
    private void handleEntBbPoolsList(HttpExchange exchange, String query) throws IOException {
        KsAuthManager.Session s = requireAuth(exchange);
        if (s == null) return;
        Map<String, String> q = KsPluginBridge.parseQuery(query);
        String entId = q.get("enterpriseId");
        if (entId == null || entId.isEmpty()) {
            KsPluginBridge.sendJson(exchange, 400, "{\"error\":\"缺少 enterpriseId\"}"); return;
        }
        if (!plugin.blindBoxManager().canEnterpriseBlindBoxDraw(entId, s.playerUuid)) {
            KsPluginBridge.sendJson(exchange, 403, "{\"error\":\"没有企业盲盒抽取权限\"}"); return;
        }
        String industry = plugin.blindBoxManager().getIndustry(entId);
        List<Map<String, Object>> all = plugin.blindBoxManager().listPools();
        List<Map<String, Object>> filtered = new ArrayList<>();
        for (var p : all) {
            String ownerType = (String) p.getOrDefault("ownerType", "PUBLIC");
            if (!"ENTERPRISE".equalsIgnoreCase(ownerType) && !"PUBLIC".equalsIgnoreCase(ownerType)) continue;
            String allowedInd = (String) p.getOrDefault("allowedIndustries", "");
            // 空白名单 = 任何企业可抽；非空 = 仅列在名单内
            if (allowedInd != null && !allowedInd.isEmpty()) {
                boolean ok = false;
                for (String ind : allowedInd.split(",")) {
                    if (ind.trim().equalsIgnoreCase(industry)) { ok = true; break; }
                }
                if (!ok) continue;
            }
            filtered.add(p);
        }
        KsPluginBridge.sendJson(exchange, 200, gson.toJson(Map.of(
                "pools", filtered,
                "industry", industry,
                "enterpriseLevel", plugin.enterpriseLevelManager().getLevel(entId),
                "count", filtered.size()
        )));
    }

    /** 企业公户扣款抽盲盒 */
    private void handleEntBbPull(HttpExchange exchange) throws IOException {
        KsAuthManager.Session s = requireAuth(exchange);
        if (s == null) return;
        Map<String, Object> req = gson.fromJson(KsPluginBridge.readBody(exchange), Map.class);
        if (req == null) { KsPluginBridge.sendJson(exchange, 400, "{\"error\":\"无效请求\"}"); return; }
        String entId = (String) req.get("enterpriseId");
        String poolId = (String) req.get("poolId");
        if (entId == null || poolId == null) {
            KsPluginBridge.sendJson(exchange, 400, "{\"error\":\"enterpriseId 和 poolId 必填\"}"); return;
        }
        if (!plugin.blindBoxManager().canEnterpriseBlindBoxDraw(entId, s.playerUuid)) {
            KsPluginBridge.sendJson(exchange, 403, "{\"error\":\"没有企业盲盒抽取权限\"}"); return;
        }
        BlindBoxManager.PullResult r = plugin.blindBoxManager().pullForEnterprise(entId, s.playerUuid, poolId);
        int status = r.success ? 200 : 400;
        Map<String, Object> resp = r.toMap();
        if (r.success) resp.put("corporateBalance", plugin.blindBoxManager().getCorporateBalance(entId));
        KsPluginBridge.sendJson(exchange, status, gson.toJson(resp));
    }

    /** 玩家 10 连抽 */
    private void handleBbPullTen(HttpExchange exchange) throws IOException {
        KsAuthManager.Session s = requireAuth(exchange);
        if (s == null) return;
        Map<String, Object> req = gson.fromJson(KsPluginBridge.readBody(exchange), Map.class);
        if (req == null) { KsPluginBridge.sendJson(exchange, 400, "{\"error\":\"无效请求\"}"); return; }
        String poolId = (String) req.get("poolId");
        if (poolId == null) { KsPluginBridge.sendJson(exchange, 400, "{\"error\":\"缺少 poolId\"}"); return; }
        CompletableFuture<List<BlindBoxManager.PullResult>> future = new CompletableFuture<>();
        plugin.blindBoxManager().pullTenAsync(s.playerUuid, poolId, future::complete);
        List<BlindBoxManager.PullResult> results;
        try {
            results = future.get(30, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            KsPluginBridge.sendJson(exchange, 503, "{\"error\":\"十连抽请求被中断\"}");
            return;
        } catch (ExecutionException e) {
            KsPluginBridge.sendJson(exchange, 500, "{\"error\":\"十连抽结算失败\"}");
            return;
        } catch (TimeoutException e) {
            KsPluginBridge.sendJson(exchange, 504, "{\"error\":\"十连抽结算超时，请稍后查看抽取记录\"}");
            return;
        }
        List<Map<String, Object>> out = new ArrayList<>();
        int rareCount = 0;
        for (var r : results) {
            out.add(r.toMap());
            if (r.success && r.rarity != null && !r.rarity.equals(BlindBoxManager.RARITY_COMMON) && !r.rarity.equals(BlindBoxManager.RARITY_UNCOMMON)) {
                rareCount++;
            }
        }
        KsPluginBridge.sendJson(exchange, 200, gson.toJson(Map.of(
                "results", out,
                "totalPulls", results.size(),
                "rareCount", rareCount,
                "allSuccess", results.stream().allMatch(r -> r.success)
        )));
    }

    // ================================================================
    // Tax Bracket APIs — 阶梯税率
    // ================================================================

    private void handleTaxBracketsList(HttpExchange exchange, String query) throws IOException {
        Map<String, String> q = KsPluginBridge.parseQuery(query);
        String industry = q.getOrDefault("industry", "INDUSTRY");
        Object res = callExtraManager("ks-eco-tax", "taxRateManager", "listBrackets",
                new Class[]{String.class}, industry);
        List<Map<String, Object>> list = new ArrayList<>();
        if (res instanceof List<?> lst) {
            for (Object o : lst) {
                if (o instanceof Map<?,?> m) list.add(toStrMap(m));
            }
        }
        KsPluginBridge.sendJson(exchange, 200, gson.toJson(Map.of("industry", industry, "brackets", list)));
    }

    @SuppressWarnings("unchecked")
    private void handleTaxBracketUpsert(HttpExchange exchange) throws IOException {
        KsAuthManager.Session s = requireAdminAuth(exchange);
        if (s == null) return;
        String gate = checkPoliticGate();
        if (gate != null) { KsPluginBridge.sendJson(exchange, 403, gson.toJson(Map.of("error", gate))); return; }
        Map<String, Object> req = gson.fromJson(KsPluginBridge.readBody(exchange), Map.class);
        if (req == null) { KsPluginBridge.sendJson(exchange, 400, "{\"error\":\"无效请求\"}"); return; }
        String id = (String) req.get("id");
        if (id == null || id.isEmpty()) id = java.util.UUID.randomUUID().toString();
        String industry = (String) req.getOrDefault("industry", "INDUSTRY");
        String scope = (String) req.getOrDefault("scope", "ENTERPRISE_TAX");
        double pmin = toDouble(req.get("profitMin"), 0);
        double pmax = toDouble(req.get("profitMax"), Double.MAX_VALUE);
        double rate = toDouble(req.get("rate"), 0.05);
        if (pmax <= pmin) {
            KsPluginBridge.sendJson(exchange, 400, "{\"error\":\"profitMax 必须大于 profitMin\"}"); return;
        }
        if (rate < 0 || rate > 1) {
            KsPluginBridge.sendJson(exchange, 400, "{\"error\":\"rate 必须在 0-1\"}"); return;
        }
        Object res = callExtraManager("ks-eco-tax", "taxRateManager", "upsertBracket",
                new Class[]{String.class, String.class, String.class, double.class, double.class, double.class},
                id, industry, scope, pmin, pmax, rate);
        if (res instanceof Boolean b && b) {
            auditLog("TAX_BRACKET_UPSERT", s.playerUuid.toString(), s.playerName, "tax_bracket", id,
                    "industry=" + industry + ",scope=" + scope + ",[" + pmin + "," + pmax + "),rate=" + rate);
            KsPluginBridge.sendJson(exchange, 200, "{\"message\":\"阶梯已保存\",\"id\":\"" + id + "\"}");
        } else {
            KsPluginBridge.sendJson(exchange, 500, "{\"error\":\"保存失败\"}");
        }
    }

    private void handleTaxBracketDelete(HttpExchange exchange) throws IOException {
        KsAuthManager.Session s = requireAdminAuth(exchange);
        if (s == null) return;
        String gate = checkPoliticGate();
        if (gate != null) { KsPluginBridge.sendJson(exchange, 403, gson.toJson(Map.of("error", gate))); return; }
        Map<String, Object> req = gson.fromJson(KsPluginBridge.readBody(exchange), Map.class);
        if (req == null) { KsPluginBridge.sendJson(exchange, 400, "{\"error\":\"无效请求\"}"); return; }
        String id = (String) req.get("id");
        if (id == null) { KsPluginBridge.sendJson(exchange, 400, "{\"error\":\"缺少 id\"}"); return; }
        Object res = callExtraManager("ks-eco-tax", "taxRateManager", "deleteBracket",
                new Class[]{String.class}, id);
        if (res instanceof Boolean b && b) {
            auditLog("TAX_BRACKET_DELETE", s.playerUuid.toString(), s.playerName, "tax_bracket", id, "");
            KsPluginBridge.sendJson(exchange, 200, "{\"message\":\"已删除\"}");
        } else {
            KsPluginBridge.sendJson(exchange, 500, "{\"error\":\"删除失败\"}");
        }
    }

    /** 试算：给定行业 + 利润，应用阶梯或回退基础税率 */
    @SuppressWarnings("unchecked")
    private void handleTaxBracketCalc(HttpExchange exchange) throws IOException {
        KsAuthManager.Session s = requireAuth(exchange);
        if (s == null) return;
        Map<String, Object> req = gson.fromJson(KsPluginBridge.readBody(exchange), Map.class);
        if (req == null) { KsPluginBridge.sendJson(exchange, 400, "{\"error\":\"无效请求\"}"); return; }
        String industry = (String) req.getOrDefault("industry", "INDUSTRY");
        String scope = (String) req.getOrDefault("scope", "ENTERPRISE_TAX");
        double profit = toDouble(req.get("profit"), 0);
        if (profit < 0) profit = 0;
        double rate = -1;
        Object br = callExtraManager("ks-eco-tax", "taxRateManager", "getBracketRate",
                new Class[]{String.class, String.class, double.class}, industry, scope, profit);
        if (br instanceof Number n) rate = n.doubleValue();
        boolean fromBracket = rate >= 0;
        if (!fromBracket) {
            // 回退到行业基础税率
            Object rr = callExtraManager("ks-eco-tax", "taxRateManager", "getRate",
                    new Class[]{String.class, String.class}, scope, industry);
            if (rr instanceof Number n2) rate = n2.doubleValue();
            else rate = 0.08;
        }
        KsPluginBridge.sendJson(exchange, 200, gson.toJson(Map.of(
                "industry", industry,
                "scope", scope,
                "profit", profit,
                "rate", rate,
                "tax", profit * rate,
                "fromBracket", fromBracket
        )));
    }

    // ================================================================
    // Real Estate APIs — 房地产（通过 callExtraManager 调 ks-eco-realestate）
    // ================================================================

    private Object callRealEstate(String getter, String method, Class<?>[] argTypes, Object... args) {
        return callExtraManager("ks-eco-realestate", "realEstateManager", method, argTypes, args);
    }

    /** 地块福利（LandPerkManager）：工业/农业用地额外福利，与房地产主 manager 是同模块内的另一个 getter。 */
    private Object callLandPerk(String method, Class<?>[] argTypes, Object... args) {
        return callExtraManager("ks-eco-realestate", "landPerkManager", method, argTypes, args);
    }

    @SuppressWarnings("unchecked")
    private void handleAdminLandPerks(HttpExchange exchange) throws IOException {
        KsAuthManager.Session s = requireAdminAuth(exchange);
        if (s == null) return;
        Object res = callLandPerk("getAllPerkConfig", new Class[]{}, (Object[]) new Object[]{});
        Map<String, Object> config = new LinkedHashMap<>();
        if (res instanceof Map<?, ?> m) for (var e : m.entrySet()) config.put(e.getKey().toString(), e.getValue());
        Map<String, Object> runtime = new LinkedHashMap<>();
        Object rt = callLandPerk("getRuntimeStats", new Class[]{}, (Object[]) new Object[]{});
        if (rt instanceof Map<?, ?> m) for (var e : m.entrySet()) runtime.put(e.getKey().toString(), e.getValue());
        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("config", config);
        resp.put("runtime", runtime);
        resp.put("moduleLoaded", hasExtra("ks-eco-realestate"));
        KsPluginBridge.sendJson(exchange, 200, gson.toJson(resp));
    }

    @SuppressWarnings("unchecked")
    private void handleAdminLandPerksSet(HttpExchange exchange) throws IOException {
        KsAuthManager.Session s = requireAdminAuth(exchange);
        if (s == null) return;
        Map<String, Object> req = gson.fromJson(KsPluginBridge.readBody(exchange), Map.class);
        if (req == null) { KsPluginBridge.sendJson(exchange, 400, "{\"error\":\"无效请求\"}"); return; }
        int updated = 0;
        for (var e : req.entrySet()) {
            double v = toDouble(e.getValue(), Double.NaN);
            if (Double.isNaN(v)) continue;
            Object ok = callLandPerk("setPerkValue", new Class[]{String.class, double.class}, e.getKey(), v);
            if (Boolean.TRUE.equals(ok)) updated++;
        }
        auditLog("LAND_PERK_CONFIG", s.playerUuid.toString(), s.playerName, "land_perk", "config",
                "更新了 " + updated + " 项地块福利配置");
        KsPluginBridge.sendJson(exchange, 200, "{\"message\":\"已更新 " + updated + " 项配置\"}");
    }

    /** 玩家端"我的地块福利"：统计名下（本人+所属企业）农业/工业地块数量 + 当前福利数值/生效模式，供 player.html 展示。 */
    private void handleMyLandPerks(HttpExchange exchange) throws IOException {
        KsAuthManager.Session s = requireAuth(exchange);
        if (s == null) return;
        List<Map<String, Object>> myPlots = new ArrayList<>();
        Object res = callRealEstate(null, "listPlots", new Class[]{String.class, String.class, String.class},
                null, "PLAYER", s.playerUuid.toString());
        if (res instanceof List<?> lst) for (Object o : lst) if (o instanceof Map<?, ?> m) myPlots.add(toStrMap(m));
        List<Map<String, Object>> ents = new ArrayList<>();
        queryRows(ents, "SELECT id FROM ks_ent_enterprises WHERE owner_uuids LIKE '%" +
                s.playerUuid.toString() + "%' OR id IN (SELECT enterprise_id FROM ks_ent_members WHERE player_uuid='" +
                s.playerUuid.toString() + "')", null);
        for (var ent : ents) {
            String entId = (String) ent.get("id");
            Object entRes = callRealEstate(null, "listPlots", new Class[]{String.class, String.class, String.class},
                    null, "ENTERPRISE", entId);
            if (entRes instanceof List<?> lst) for (Object o : lst) if (o instanceof Map<?, ?> m) myPlots.add(toStrMap(m));
        }
        int agriCount = 0, indCount = 0;
        for (var p : myPlots) {
            Object zoneObj = callRealEstate(null, "getZone", new Class[]{String.class}, p.get("zoneId"));
            if (zoneObj instanceof Map<?, ?> zm) {
                Object type = zm.get("type");
                if ("AGRICULTURAL".equals(type)) agriCount++;
                else if ("INDUSTRIAL".equals(type)) indCount++;
            }
        }
        Map<String, Object> config = new LinkedHashMap<>();
        Object cfgRes = callLandPerk("getAllPerkConfig", new Class[]{}, (Object[]) new Object[]{});
        if (cfgRes instanceof Map<?, ?> m) for (var e : m.entrySet()) config.put(e.getKey().toString(), e.getValue());
        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("agriculturalPlotCount", agriCount);
        resp.put("industrialPlotCount", indCount);
        resp.put("config", config);
        KsPluginBridge.sendJson(exchange, 200, gson.toJson(resp));
    }

    private void handleReZonesList(HttpExchange exchange, String query) throws IOException {
        Object res = callRealEstate(null, "listZones", new Class[]{}, (Object[]) new Object[]{});
        List<Map<String, Object>> out = new ArrayList<>();
        if (res instanceof List<?> lst) for (Object o : lst) if (o instanceof Map<?,?> m) out.add(toStrMap(m));
        KsPluginBridge.sendJson(exchange, 200, gson.toJson(Map.of(
                "zones", out,
                "moduleLoaded", hasExtra("ks-eco-realestate")
        )));
    }

    @SuppressWarnings("unchecked")
    private void handleMoList(HttpExchange exchange, boolean admin) throws IOException {
        KsAuthManager.Session s = admin ? requireAdminAuth(exchange) : requireAuth(exchange);
        if (s == null) return;
        KsPluginBridge.sendJson(exchange, 200, gson.toJson(Map.of(
                "orders", plugin.majorOrderManager().listOrders(admin)
        )));
    }

    @SuppressWarnings("unchecked")
    private void handleMoSave(HttpExchange exchange) throws IOException {
        KsAuthManager.Session s = requireAdminAuth(exchange);
        if (s == null) return;
        Map<String, Object> req = gson.fromJson(KsPluginBridge.readBody(exchange), Map.class);
        if (req == null) { KsPluginBridge.sendJson(exchange, 400, "{\"error\":\"无效请求\"}"); return; }
        if (plugin.majorOrderManager().saveOrder(req)) {
            auditLog("MO_SAVE", s.playerUuid.toString(), s.playerName, "major_order",
                    String.valueOf(req.getOrDefault("id", "")), "saved");
            KsPluginBridge.sendJson(exchange, 200, "{\"message\":\"MO 已保存\"}");
        } else {
            KsPluginBridge.sendJson(exchange, 500, "{\"error\":\"保存失败，标题/目标值检查一下\"}");
        }
    }

    @SuppressWarnings("unchecked")
    private void handleMoStatus(HttpExchange exchange) throws IOException {
        KsAuthManager.Session s = requireAdminAuth(exchange);
        if (s == null) return;
        Map<String, Object> req = gson.fromJson(KsPluginBridge.readBody(exchange), Map.class);
        if (req == null) { KsPluginBridge.sendJson(exchange, 400, "{\"error\":\"无效请求\"}"); return; }
        String id = String.valueOf(req.getOrDefault("id", ""));
        String status = String.valueOf(req.getOrDefault("status", "ARCHIVED"));
        if (plugin.majorOrderManager().setStatus(id, status)) {
            auditLog("MO_STATUS", s.playerUuid.toString(), s.playerName, "major_order", id, status);
            KsPluginBridge.sendJson(exchange, 200, "{\"message\":\"MO 状态已更新\"}");
        } else {
            KsPluginBridge.sendJson(exchange, 500, "{\"error\":\"更新失败\"}");
        }
    }

    @SuppressWarnings("unchecked")
    private void handleReZoneCreate(HttpExchange exchange) throws IOException {
        KsAuthManager.Session s = requireAdminAuth(exchange);
        if (s == null) return;
        String gate = checkPoliticGate();
        if (gate != null) { KsPluginBridge.sendJson(exchange, 403, gson.toJson(Map.of("error", gate))); return; }
        Map<String, Object> req = gson.fromJson(KsPluginBridge.readBody(exchange), Map.class);
        if (req == null) { KsPluginBridge.sendJson(exchange, 400, "{\"error\":\"无效请求\"}"); return; }
        String name = (String) req.get("name");
        String world = (String) req.getOrDefault("world", "world");
        int x1 = (int) toDouble(req.get("x1"), 0);
        int z1 = (int) toDouble(req.get("z1"), 0);
        int x2 = (int) toDouble(req.get("x2"), 0);
        int z2 = (int) toDouble(req.get("z2"), 0);
        String type = (String) req.getOrDefault("type", "RESIDENTIAL");
        double price = toDouble(req.get("basePrice"), 1000);
        double tax = toDouble(req.get("taxRate"), 0.05);
        String status = (String) req.getOrDefault("status", "STATE_OWNED");
        int maxPlots = (int) toDouble(req.get("maxPlots"), 0);
        String dungeonTemplateId = (String) req.get("dungeonTemplateId");
        if (name == null || x2 < x1 || z2 < z1) {
            KsPluginBridge.sendJson(exchange, 400, "{\"error\":\"name 必填且 x2>=x1, z2>=z1\"}"); return;
        }
        Object res = callRealEstate(null, "createZone",
                new Class[]{String.class, String.class, int.class, int.class, int.class, int.class,
                        String.class, double.class, double.class, String.class, int.class, String.class},
                name, world, x1, z1, x2, z2, type, price, tax, status, maxPlots, dungeonTemplateId);
        if (res instanceof String id && !id.isEmpty()) {
            auditLog("RE_ZONE_CREATE", s.playerUuid.toString(), s.playerName, "re_zone", id,
                    "name=" + name + ",type=" + type + ",[" + x1 + "," + z1 + "," + x2 + "," + z2 + "]");
            KsPluginBridge.sendJson(exchange, 200, "{\"message\":\"区域已创建\",\"id\":\"" + id + "\"}");
        } else {
            KsPluginBridge.sendJson(exchange, 500, "{\"error\":\"创建失败（模块未安装？）\"}");
        }
    }

    @SuppressWarnings("unchecked")
    private void handleReZoneSetPrice(HttpExchange exchange) throws IOException {
        KsAuthManager.Session s = requireAdminAuth(exchange);
        if (s == null) return;
        Map<String, Object> req = gson.fromJson(KsPluginBridge.readBody(exchange), Map.class);
        if (req == null) { KsPluginBridge.sendJson(exchange, 400, "{\"error\":\"无效请求\"}"); return; }
        String id = (String) req.get("id");
        double price = toDouble(req.get("price"), toDouble(req.get("basePrice"), -1));
        if (id == null || price < 0) { KsPluginBridge.sendJson(exchange, 400, "{\"error\":\"参数无效\"}"); return; }
        Object res = callRealEstate(null, "setZonePrice",
                new Class[]{String.class, double.class}, id, price);
        if (res instanceof Boolean b && b) {
            auditLog("RE_ZONE_PRICE", s.playerUuid.toString(), s.playerName, "re_zone", id, "price=" + price);
            KsPluginBridge.sendJson(exchange, 200, "{\"message\":\"已更新\"}");
        } else {
            KsPluginBridge.sendJson(exchange, 500, "{\"error\":\"更新失败\"}");
        }
    }

    @SuppressWarnings("unchecked")
    private void handleReZoneSetStatus(HttpExchange exchange) throws IOException {
        KsAuthManager.Session s = requireAdminAuth(exchange);
        if (s == null) return;
        Map<String, Object> req = gson.fromJson(KsPluginBridge.readBody(exchange), Map.class);
        if (req == null) { KsPluginBridge.sendJson(exchange, 400, "{\"error\":\"无效请求\"}"); return; }
        String id = (String) req.get("id");
        String status = (String) req.get("status");
        if (id == null || status == null) { KsPluginBridge.sendJson(exchange, 400, "{\"error\":\"参数无效\"}"); return; }
        Object res = callRealEstate(null, "setZoneStatus",
                new Class[]{String.class, String.class}, id, status);
        if (res instanceof Boolean b && b) {
            auditLog("RE_ZONE_STATUS", s.playerUuid.toString(), s.playerName, "re_zone", id, "status=" + status);
            KsPluginBridge.sendJson(exchange, 200, "{\"message\":\"已更新\"}");
        } else {
            KsPluginBridge.sendJson(exchange, 500, "{\"error\":\"更新失败\"}");
        }
    }

    @SuppressWarnings("unchecked")
    private void handleReZoneSetType(HttpExchange exchange) throws IOException {
        KsAuthManager.Session s = requireAdminAuth(exchange);
        if (s == null) return;
        Map<String, Object> req = gson.fromJson(KsPluginBridge.readBody(exchange), Map.class);
        if (req == null) { KsPluginBridge.sendJson(exchange, 400, "{\"error\":\"无效请求\"}"); return; }
        String id = (String) req.get("id");
        String type = (String) req.get("type");
        if (id == null || type == null) { KsPluginBridge.sendJson(exchange, 400, "{\"error\":\"参数无效\"}"); return; }
        Object res = callRealEstate(null, "setZoneType", new Class[]{String.class, String.class}, id, type);
        if (res instanceof Boolean b && b) {
            auditLog("RE_ZONE_TYPE", s.playerUuid.toString(), s.playerName, "re_zone", id, "type=" + type);
            KsPluginBridge.sendJson(exchange, 200, "{\"message\":\"规划类型已更新\"}");
        } else {
            KsPluginBridge.sendJson(exchange, 500, "{\"error\":\"更新失败\"}");
        }
    }

    @SuppressWarnings("unchecked")
    private void handleReZoneSetMaxPlots(HttpExchange exchange) throws IOException {
        KsAuthManager.Session s = requireAdminAuth(exchange);
        if (s == null) return;
        Map<String, Object> req = gson.fromJson(KsPluginBridge.readBody(exchange), Map.class);
        if (req == null) { KsPluginBridge.sendJson(exchange, 400, "{\"error\":\"无效请求\"}"); return; }
        String id = (String) req.get("id");
        int maxPlots = (int) toDouble(req.get("maxPlots"), 0);
        if (id == null) { KsPluginBridge.sendJson(exchange, 400, "{\"error\":\"参数无效\"}"); return; }
        Object res = callRealEstate(null, "setZoneMaxPlots", new Class[]{String.class, int.class}, id, maxPlots);
        if (res instanceof Boolean b && b) {
            auditLog("RE_ZONE_VOLUME", s.playerUuid.toString(), s.playerName, "re_zone", id, "maxPlots=" + maxPlots);
            KsPluginBridge.sendJson(exchange, 200, "{\"message\":\"容积率已更新\"}");
        } else {
            KsPluginBridge.sendJson(exchange, 500, "{\"error\":\"更新失败\"}");
        }
    }

    @SuppressWarnings("unchecked")
    private void handleReZoneSetDungeonLink(HttpExchange exchange) throws IOException {
        KsAuthManager.Session s = requireAdminAuth(exchange);
        if (s == null) return;
        Map<String, Object> req = gson.fromJson(KsPluginBridge.readBody(exchange), Map.class);
        if (req == null) { KsPluginBridge.sendJson(exchange, 400, "{\"error\":\"无效请求\"}"); return; }
        String id = (String) req.get("id");
        String templateId = (String) req.get("templateId");
        if (id == null) { KsPluginBridge.sendJson(exchange, 400, "{\"error\":\"参数无效\"}"); return; }
        Object res = callRealEstate(null, "setZoneDungeonLink",
                new Class[]{String.class, String.class}, id, templateId);
        if (res == null) {
            auditLog("RE_ZONE_DUNGEON_LINK", s.playerUuid.toString(), s.playerName, "re_zone", id, "templateId=" + templateId);
            KsPluginBridge.sendJson(exchange, 200, "{\"message\":\"副本权限已更新\"}");
        } else {
            KsPluginBridge.sendJson(exchange, 400, gson.toJson(Map.of("error", res)));
        }
    }

    @SuppressWarnings("unchecked")
    private void handleReZoneDelete(HttpExchange exchange) throws IOException {
        KsAuthManager.Session s = requireAdminAuth(exchange);
        if (s == null) return;
        String gate = checkPoliticGate();
        if (gate != null) { KsPluginBridge.sendJson(exchange, 403, gson.toJson(Map.of("error", gate))); return; }
        Map<String, Object> req = gson.fromJson(KsPluginBridge.readBody(exchange), Map.class);
        if (req == null) { KsPluginBridge.sendJson(exchange, 400, "{\"error\":\"无效请求\"}"); return; }
        String id = (String) req.get("id");
        if (id == null) { KsPluginBridge.sendJson(exchange, 400, "{\"error\":\"参数无效\"}"); return; }
        Object res = callRealEstate(null, "deleteZone", new Class[]{String.class}, id);
        if (res instanceof Boolean b && b) {
            auditLog("RE_ZONE_DELETE", s.playerUuid.toString(), s.playerName, "re_zone", id, "deleted");
            KsPluginBridge.sendJson(exchange, 200, "{\"message\":\"区域及其地块已删除\"}");
        } else {
            KsPluginBridge.sendJson(exchange, 500, "{\"error\":\"删除失败（区域不存在？）\"}");
        }
    }

    @SuppressWarnings("unchecked")
    private void handleReInstancePlotPurchase(HttpExchange exchange) throws IOException {
        KsAuthManager.Session s = requireAuth(exchange);
        if (s == null) return;
        if (!hasExtra("ks-eco-realestate")) { KsPluginBridge.sendJson(exchange, 400, "{\"error\":\"房地产模块未加载\"}"); return; }
        Map<String, Object> req = gson.fromJson(KsPluginBridge.readBody(exchange), Map.class);
        if (req == null) { KsPluginBridge.sendJson(exchange, 400, "{\"error\":\"无效请求\"}"); return; }
        String instanceId = (String) req.get("instanceId");
        if (instanceId == null) { KsPluginBridge.sendJson(exchange, 400, "{\"error\":\"instanceId 必填\"}"); return; }
        int x1 = (int) toDouble(req.get("x1"), 0);
        int z1 = (int) toDouble(req.get("z1"), 0);
        int x2 = (int) toDouble(req.get("x2"), 10);
        int z2 = (int) toDouble(req.get("z2"), 10);
        String func = (String) req.getOrDefault("propertyFunction", "RESIDENTIAL");
        Object res = callRealEstate(null, "purchaseInInstance",
                new Class[]{String.class, String.class, String.class, int.class, int.class, int.class, int.class, String.class},
                instanceId, s.playerUuid.toString(), s.playerName, x1, z1, x2, z2, func);
        if (res instanceof String id && !id.isEmpty()) {
            KsPluginBridge.sendJson(exchange, 200, gson.toJson(Map.of("id", id)));
        } else {
            KsPluginBridge.sendJson(exchange, 400, "{\"error\":\"购买失败（余额不足/功能无效）\"}");
        }
    }

    @SuppressWarnings("unchecked")
    private void handleReInstancePlotDevelop(HttpExchange exchange) throws IOException {
        KsAuthManager.Session s = requireAuth(exchange);
        if (s == null) return;
        if (!hasExtra("ks-eco-realestate")) { KsPluginBridge.sendJson(exchange, 400, "{\"error\":\"房地产模块未加载\"}"); return; }
        Map<String, Object> req = gson.fromJson(KsPluginBridge.readBody(exchange), Map.class);
        if (req == null) { KsPluginBridge.sendJson(exchange, 400, "{\"error\":\"无效请求\"}"); return; }
        String plotId = (String) req.get("plotId");
        if (plotId == null) { KsPluginBridge.sendJson(exchange, 400, "{\"error\":\"plotId 必填\"}"); return; }
        Object res = callRealEstate(null, "payInstanceDevelopmentFee",
                new Class[]{String.class, String.class}, plotId, s.playerUuid.toString());
        if (res == null) {
            KsPluginBridge.sendJson(exchange, 200, "{\"ok\":true}");
        } else {
            KsPluginBridge.sendJson(exchange, 400, gson.toJson(Map.of("error", res)));
        }
    }

    private void handleReMyInstancePlots(HttpExchange exchange, String query) throws IOException {
        KsAuthManager.Session s = requireAuth(exchange);
        if (s == null) return;
        Map<String, String> q = KsPluginBridge.parseQuery(query);
        String instanceId = q.get("instanceId");
        Object res = callRealEstate(null, "listInstancePlots",
                new Class[]{String.class, String.class}, s.playerUuid.toString(), instanceId);
        List<Map<String, Object>> out = new ArrayList<>();
        if (res instanceof List<?> lst) for (Object o : lst) if (o instanceof Map<?,?> m) out.add(toStrMap(m));
        KsPluginBridge.sendJson(exchange, 200, gson.toJson(Map.of("properties", out)));
    }

    private void handleRePlotsList(HttpExchange exchange, String query) throws IOException {
        Map<String, String> q = KsPluginBridge.parseQuery(query);
        String zoneId = q.get("zoneId");
        String ownerType = q.get("ownerType");
        String ownerId = q.get("ownerId");
        Object res = callRealEstate(null, "listPlots",
                new Class[]{String.class, String.class, String.class}, zoneId, ownerType, ownerId);
        List<Map<String, Object>> out = new ArrayList<>();
        if (res instanceof List<?> lst) for (Object o : lst) if (o instanceof Map<?,?> m) {
            Map<String, Object> plot = toStrMap(m);
            Object zoneObj = callRealEstate(null, "getZone", new Class[]{String.class}, plot.get("zoneId"));
            if (zoneObj instanceof Map<?, ?> zone) {
                Object type = zone.get("type");
                if (type != null) plot.put("zoneType", type);
            }
            Object perkObj = callLandPerk("getPlotPerkConfig", new Class[]{String.class}, String.valueOf(plot.get("id")));
            if (perkObj instanceof Map<?, ?> perk) {
                Map<String, Object> perkMap = new LinkedHashMap<>();
                for (var e : perk.entrySet()) perkMap.put(String.valueOf(e.getKey()), e.getValue());
                plot.put("perk", perkMap);
                boolean agriEffective = "AGRICULTURAL".equals(plot.get("zoneType")) || Boolean.TRUE.equals(perkMap.get("agriEnabled"));
                boolean industryEffective = "INDUSTRIAL".equals(plot.get("zoneType")) || Boolean.TRUE.equals(perkMap.get("industryEnabled"));
                plot.put("agriEffective", agriEffective);
                plot.put("industryEffective", industryEffective);
            }
            out.add(plot);
        }
        KsPluginBridge.sendJson(exchange, 200, gson.toJson(Map.of("plots", out)));
    }

    @SuppressWarnings("unchecked")
    private void handleRePlotPerkSet(HttpExchange exchange) throws IOException {
        KsAuthManager.Session s = requireAdminAuth(exchange);
        if (s == null) return;
        Map<String, Object> req = gson.fromJson(KsPluginBridge.readBody(exchange), Map.class);
        if (req == null) { KsPluginBridge.sendJson(exchange, 400, "{\"error\":\"无效请求\"}"); return; }
        String plotId = (String) req.get("plotId");
        if (plotId == null || plotId.isBlank()) {
            KsPluginBridge.sendJson(exchange, 400, "{\"error\":\"plotId 必填\"}");
            return;
        }
        Object ok = callLandPerk("setPlotPerkConfig", new Class[]{String.class, Map.class}, plotId, req);
        if (Boolean.TRUE.equals(ok)) {
            auditLog("RE_PLOT_PERK_SET", s.playerUuid.toString(), s.playerName, "re_plot", plotId,
                    "updated plot perk");
            KsPluginBridge.sendJson(exchange, 200, "{\"message\":\"地块福利已保存\"}");
        } else {
            KsPluginBridge.sendJson(exchange, 500, "{\"error\":\"保存失败（房地产模块未加载或地块不存在）\"}");
        }
    }

    @SuppressWarnings("unchecked")
    private void handleRePlotPurchase(HttpExchange exchange) throws IOException {
        KsAuthManager.Session s = requireAuth(exchange);
        if (s == null) return;
        Map<String, Object> req = gson.fromJson(KsPluginBridge.readBody(exchange), Map.class);
        if (req == null) { KsPluginBridge.sendJson(exchange, 400, "{\"error\":\"无效请求\"}"); return; }
        String zoneId = (String) req.get("zoneId");
        int x1 = (int) toDouble(req.get("x1"), 0);
        int z1 = (int) toDouble(req.get("z1"), 0);
        int x2 = (int) toDouble(req.get("x2"), 0);
        int z2 = (int) toDouble(req.get("z2"), 0);
        String buyerType = (String) req.getOrDefault("buyerType", "PLAYER");
        String buyerId = buyerType.equalsIgnoreCase("ENTERPRISE")
                ? (String) req.get("enterpriseId")
                : s.playerUuid.toString();
        if (zoneId == null || buyerId == null) {
            KsPluginBridge.sendJson(exchange, 400, "{\"error\":\"zoneId / buyerId 必填\"}"); return;
        }
        if (buyerType.equalsIgnoreCase("ENTERPRISE")) {
            try (var conn = plugin.ksCore().dataStore().getConnection()) {
                if (conn == null || !hasEnterprisePermission(conn, buyerId, s, EnterprisePermissionService.MANAGE_FUNDS)) {
                    KsPluginBridge.sendJson(exchange, 403, "{\"error\":\"需要企业资金管理权限才能以企业名义购地\"}"); return;
                }
            } catch (java.sql.SQLException e) {
                KsPluginBridge.sendJson(exchange, 500, "{\"error\":\"企业权限校验失败\"}"); return;
            }
        }
        Object res = callOnServerThread(() -> callRealEstate(null, "purchasePlot",
                new Class[]{String.class, int.class, int.class, int.class, int.class, String.class, String.class},
                zoneId, x1, z1, x2, z2, buyerType, buyerId));
        if (res instanceof String id && !id.isEmpty()) {
            auditLog("RE_PLOT_PURCHASE", s.playerUuid.toString(), s.playerName, "re_plot", id,
                    "zone=" + zoneId + ",buyer=" + buyerType + ":" + buyerId);
            KsPluginBridge.sendJson(exchange, 200, gson.toJson(Map.of("message", "已购入", "plotId", id)));
        } else {
            KsPluginBridge.sendJson(exchange, 400, "{\"error\":\"购买失败（区域不可售、余额不足、坐标越界或范围已占用）\"}");
        }
    }

    private void handleReMyPlots(HttpExchange exchange) throws IOException {
        KsAuthManager.Session s = requireAuth(exchange);
        if (s == null) return;
        List<Map<String, Object>> out = new ArrayList<>();
        Object res = callRealEstate(null, "listPlots",
                new Class[]{String.class, String.class, String.class}, null, "PLAYER", s.playerUuid.toString());
        if (res instanceof List<?> lst) for (Object o : lst) if (o instanceof Map<?,?> m) out.add(toStrMap(m));
        // 附加该玩家所属企业名下的地块（含所有者/合资人 + 普通成员）
        List<Map<String, Object>> ents = new ArrayList<>();
        queryRows(ents, "SELECT id FROM ks_ent_enterprises WHERE owner_uuids LIKE '%" +
            s.playerUuid.toString() + "%' OR id IN (SELECT enterprise_id FROM ks_ent_members WHERE player_uuid='" +
            s.playerUuid.toString() + "')", null);
        for (var ent : ents) {
            String entId = (String) ent.get("id");
            Object entRes = callRealEstate(null, "listPlots",
                    new Class[]{String.class, String.class, String.class}, null, "ENTERPRISE", entId);
            if (entRes instanceof List<?> lst) for (Object o : lst) if (o instanceof Map<?,?> m) out.add(toStrMap(m));
        }
        KsPluginBridge.sendJson(exchange, 200, gson.toJson(Map.of("plots", out)));
    }

    @SuppressWarnings("unchecked")
    private void handleReHouseRegister(HttpExchange exchange) throws IOException {
        KsAuthManager.Session session = requireAdminAuth(exchange);
        if (session == null) return;
        Map<String, Object> req = gson.fromJson(KsPluginBridge.readBody(exchange), Map.class);
        if (req == null) { KsPluginBridge.sendJson(exchange, 400, "{\"error\":\"无效请求\"}"); return; }
        String plotId = String.valueOf(req.getOrDefault("plotId", ""));
        String world = String.valueOf(req.getOrDefault("world", "world"));
        String name = String.valueOf(req.getOrDefault("name", "示范楼栋"));
        double showcasePrice = toDouble(req.get("showcasePrice"), 0);
        String showcaseMarker = String.valueOf(req.getOrDefault("showcaseMarker", "CYAN"));
        int x1 = (int) toDouble(req.get("x1"), 0), y1 = (int) toDouble(req.get("y1"), 0), z1 = (int) toDouble(req.get("z1"), 0);
        int x2 = (int) toDouble(req.get("x2"), 0), y2 = (int) toDouble(req.get("y2"), 0), z2 = (int) toDouble(req.get("z2"), 0);
        if (plotId.isBlank() || name.isBlank()) {
            KsPluginBridge.sendJson(exchange, 400, "{\"error\":\"plotId / name 必填\"}"); return;
        }
        Object result = callRealEstate(null, "registerHouseForWeb",
                new Class[]{String.class, UUID.class, String.class, int.class, int.class, int.class,
                        int.class, int.class, int.class, String.class},
                plotId, session.playerUuid, world, x1, y1, z1, x2, y2, z2, name);
        if (result instanceof Map<?, ?> map) {
            Map<String, Object> body = toStrMap(map);
            if (body.get("houseId") != null) {
                String houseId = String.valueOf(body.get("houseId"));
                if (showcasePrice > 0) {
                    Object showcaseSaved = callRealEstate(null, "setHouseShowcase",
                            new Class[]{String.class, double.class, String.class}, houseId, showcasePrice, showcaseMarker);
                    if (!Boolean.TRUE.equals(showcaseSaved)) {
                        body.put("showcaseWarning", "楼栋已登记，但展示售价/标识保存失败");
                    }
                }
                auditLog("RE_HOUSE_REGISTER", session.playerUuid.toString(), session.playerName,
                        "re_house", houseId, "plot=" + plotId + ",world=" + world);
                KsPluginBridge.sendJson(exchange, 200, gson.toJson(body));
            } else KsPluginBridge.sendJson(exchange, 400, gson.toJson(body));
            return;
        }
        KsPluginBridge.sendJson(exchange, 500, "{\"error\":\"房地产模块未加载\"}");
    }

    /** 在售商品房列表（玩家页面"商品房市场"浏览用，纯只读——下单购买仍走游戏内 /market）。 */
    private void handleReHousesForSale(HttpExchange exchange) throws IOException {
        var listings = plugin.listingManager().getActiveListings("SELL", null);
        List<Map<String, Object>> out = new ArrayList<>();
        for (var l : listings) {
            if (!l.isProperty()) continue;
            Object houseObj = callRealEstate(null, "getHouse", new Class[]{String.class}, l.assetRef());
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("listingId", l.id());
            entry.put("houseId", l.assetRef());
            entry.put("sellerName", l.sellerName());
            entry.put("price", l.unitPrice());
            if (houseObj instanceof Map<?, ?> house) {
                Map<String, Object> h = toStrMap(house);
                entry.put("name", h.get("name"));
                entry.put("world", h.get("world"));
                entry.put("x1", h.get("x1")); entry.put("y1", h.get("y1")); entry.put("z1", h.get("z1"));
                entry.put("x2", h.get("x2")); entry.put("y2", h.get("y2")); entry.put("z2", h.get("z2"));
            }
            out.add(entry);
        }
        KsPluginBridge.sendJson(exchange, 200, gson.toJson(Map.of("houses", out, "moduleLoaded", hasExtra("ks-eco-realestate"))));
    }

    /** 售楼处城区沙盘清单：先返回道路/地块/楼栋骨架，同时后台预热各单栋体素模型。 */
    private void handleReCityManifest(HttpExchange exchange, String query) throws IOException {
        KsAuthManager.Session session = requireAuth(exchange);
        if (session == null) return;
        Map<String, String> q = KsPluginBridge.parseQuery(query);
        String zoneId = q.get("zoneId");
        if (zoneId == null || zoneId.isBlank()) {
            KsPluginBridge.sendJson(exchange, 400, "{\"error\":\"缺少 zoneId\"}"); return;
        }
        Object zoneObject = callRealEstate(null, "getZone", new Class[]{String.class}, zoneId);
        if (!(zoneObject instanceof Map<?, ?> zoneMap)) {
            KsPluginBridge.sendJson(exchange, 404, "{\"error\":\"区域不存在或房地产模块未加载\"}"); return;
        }
        List<Map<String, Object>> plots = new ArrayList<>();
        Object plotObject = callRealEstate(null, "listPlots",
                new Class[]{String.class, String.class, String.class}, zoneId, null, null);
        if (plotObject instanceof List<?> list) {
            for (Object item : list) if (item instanceof Map<?, ?> map) plots.add(toStrMap(map));
        }

        Map<String, Map<String, Object>> listingsByHouse = new HashMap<>();
        for (var listing : plugin.listingManager().getActiveListings("SELL", null)) {
            if (!listing.isProperty()) continue;
            Map<String, Object> market = new LinkedHashMap<>();
            market.put("listingId", listing.id());
            market.put("price", listing.unitPrice());
            market.put("sellerName", listing.sellerName());
            listingsByHouse.put(listing.assetRef(), market);
        }

        List<Map<String, Object>> buildings = new ArrayList<>();
        Object housesObject = callRealEstate(null, "listHousesInZone", new Class[]{String.class}, zoneId);
        if (housesObject instanceof List<?> list) {
            for (Object item : list) {
                if (!(item instanceof Map<?, ?> map)) continue;
                Map<String, Object> building = toStrMap(map);
                String houseId = String.valueOf(building.get("id"));
                Map<String, Object> market = listingsByHouse.get(houseId);
                double showcasePrice = toDouble(building.get("showcasePrice"), 0);
                building.put("saleStatus", market != null ? "FOR_SALE" : (showcasePrice > 0 ? "SHOWCASE" : "OWNED"));
                if (market != null) building.put("market", market);
                building.put("modelUrl", "/api/realestate/house/voxels?houseId=" +
                        java.net.URLEncoder.encode(houseId, java.nio.charset.StandardCharsets.UTF_8));
                buildings.add(building);
                callRealEstate(null, "prewarmHouseVoxels", new Class[]{String.class}, houseId);
            }
        }

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("zone", toStrMap(zoneMap));
        response.put("plots", plots);
        response.put("buildings", buildings);
        response.put("renderMode", "BUILDING_ASSEMBLY");
        response.put("preRendered", true);
        response.put("async", true);
        KsPluginBridge.sendJson(exchange, 200, gson.toJson(response));
    }

    /** 导出某房屋的体素数据，供网页 3D 查看器渲染（只读，无需登录）。 */
    private void handleReHouseVoxels(HttpExchange exchange, String query) throws IOException {
        Map<String, String> q = KsPluginBridge.parseQuery(query);
        String houseId = q.get("houseId");
        if (houseId == null || houseId.isEmpty()) {
            KsPluginBridge.sendJson(exchange, 400, "{\"error\":\"缺少 houseId\"}"); return;
        }
        Object res = callRealEstate(null, "exportHouseVoxels", new Class[]{String.class}, houseId);
        if (!(res instanceof Map<?, ?> m)) {
            KsPluginBridge.sendJson(exchange, 404, "{\"error\":\"房屋不存在或房地产模块未加载\"}"); return;
        }
        KsPluginBridge.sendJson(exchange, 200, gson.toJson(toStrMap(m)));
    }

    private void handleReRegionVoxels(HttpExchange exchange, String query) throws IOException {
        KsAuthManager.Session session = requireAuth(exchange);
        if (session == null) return;
        if (!hasExtra("ks-eco-realestate")) {
            KsPluginBridge.sendJson(exchange, 400, "{\"error\":\"房地产模块未加载\"}"); return;
        }
        Map<String, String> q = KsPluginBridge.parseQuery(query);
        String world = q.getOrDefault("world", "world");
        int x1 = (int) toDouble(q.get("x1"), 0), z1 = (int) toDouble(q.get("z1"), 0);
        int x2 = (int) toDouble(q.get("x2"), 0), z2 = (int) toDouble(q.get("z2"), 0);
        if (Math.abs((long) x2 - x1) + 1 > 128 || Math.abs((long) z2 - z1) + 1 > 128) {
            KsPluginBridge.sendJson(exchange, 400, "{\"error\":\"范围过大，请选择不超过 128 x 128 的区域\"}"); return;
        }
        Object result = callRealEstate(null, "exportMapRegionVoxels",
                new Class[]{String.class, int.class, int.class, int.class, int.class}, world, x1, z1, x2, z2);
        if (!(result instanceof Map<?, ?> map)) {
            KsPluginBridge.sendJson(exchange, 500, "{\"error\":\"区域体素导出失败\"}"); return;
        }
        KsPluginBridge.sendJson(exchange, 200, gson.toJson(toStrMap(map)));
    }

    // ================================================================
    // SQLite 查询辅助方法
    // ================================================================

    private void queryInt(Map<String, Object> map, String sql, String key) {
        try (var conn = plugin.ksCore().dataStore().getConnection()) {
            if (conn != null) {
                try (var rs = conn.createStatement().executeQuery(sql)) {
                    map.put(key, rs.next() ? rs.getInt(1) : 0);
                }
            }
        } catch (java.sql.SQLException ignored) {}
    }

    private void queryDouble(Map<String, Object> map, String sql, String key) {
        try (var conn = plugin.ksCore().dataStore().getConnection()) {
            if (conn != null) {
                try (var rs = conn.createStatement().executeQuery(sql)) {
                    map.put(key, rs.next() ? rs.getDouble(1) : 0.0);
                }
            }
        } catch (java.sql.SQLException ignored) {}
    }

    private void queryRows(List<Map<String, Object>> list, String sql, Object ignore) {
        try (var conn = plugin.ksCore().dataStore().getConnection()) {
            if (conn == null) return;
            try (var rs = conn.createStatement().executeQuery(sql)) {
                var meta = rs.getMetaData();
                int cols = meta.getColumnCount();
                while (rs.next()) {
                    Map<String, Object> row = new LinkedHashMap<>();
                    for (int i = 1; i <= cols; i++) {
                        row.put(meta.getColumnName(i), rs.getObject(i));
                    }
                    list.add(row);
                }
            }
        } catch (java.sql.SQLException ignored) {}
    }

    private double toDouble(Object val, double defaultVal) {
        if (val instanceof Number n) return n.doubleValue();
        if (val instanceof String s) { try { return Double.parseDouble(s); } catch (Exception ignored) {} }
        return defaultVal;
    }

    // 将 enterprise_id/bidder_uuid 解析为可读名称（企业名 或 玩家名），写入每行的 bidderName 字段
    private void attachBidderNames(List<Map<String, Object>> bids) {
        for (var b : bids) {
            String entId = (String) b.get("enterprise_id");
            String bidderUuid = (String) b.get("bidder_uuid");
            b.put("bidderName", resolveBidderName(bidderUuid, entId));
        }
    }

    private String resolveBidderName(String bidderUuid, String enterpriseId) {
        if (enterpriseId != null && !enterpriseId.isEmpty()) {
            try (var conn = plugin.ksCore().dataStore().getConnection()) {
                if (conn != null) {
                    try (var rs = conn.createStatement().executeQuery(
                            "SELECT name FROM ks_ent_enterprises WHERE id='" + enterpriseId.replace("'", "''") + "'")) {
                        if (rs.next()) return rs.getString("name");
                    }
                }
            } catch (java.sql.SQLException ignored) {}
            return enterpriseId;
        }
        if (bidderUuid != null && !bidderUuid.isEmpty()) {
            try {
                String name = callOnServerThread(() -> Bukkit.getOfflinePlayer(UUID.fromString(bidderUuid)).getName());
                if (name != null) return name;
            } catch (Exception ignored) {}
            return bidderUuid;
        }
        return "";
    }

    // 惰性过期：保证金未在限时内缴纳的中标记录自动流标，恢复项目为 OPEN
    private void expireOverdueDeposits(java.sql.Connection conn) throws java.sql.SQLException {
        long now = System.currentTimeMillis() / 1000;
        try (var st = conn.createStatement()) {
            st.executeUpdate("UPDATE ks_ent_projects SET status='OPEN' WHERE status='PENDING_DEPOSIT' AND id IN (" +
                "SELECT project_id FROM ks_ent_bids WHERE status='PENDING_DEPOSIT' AND deposit_deadline>0 AND deposit_deadline<" + now + ")");
            st.executeUpdate("UPDATE ks_ent_bids SET status='REJECTED' WHERE status='PENDING_DEPOSIT' AND deposit_deadline>0 AND deposit_deadline<" + now);
        }
    }

    // ==================== Audit Log ====================

    /** 写入审计日志（异步，失败不影响主逻辑） */
    private void auditLog(String action, String playerUuid, String playerName, String targetType, String targetId, String details) {
        try (var conn = plugin.ksCore().dataStore().getConnection()) {
            if (conn == null) return;
            conn.createStatement().executeUpdate(
                "INSERT INTO ks_audit_log (action, player_uuid, player_name, target_type, target_id, details, created_at) VALUES ('" +
                action.replace("'", "''") + "', '" + playerUuid.replace("'", "''") + "', '" +
                playerName.replace("'", "''") + "', '" + (targetType != null ? targetType.replace("'", "''") : "") + "', '" +
                (targetId != null ? targetId.replace("'", "''") : "") + "', '" + (details != null ? details.replace("'", "''") : "") + "', " +
                (System.currentTimeMillis() / 1000) + ")");
        } catch (java.sql.SQLException ignored) {}
    }

    // ==================== 企业公户 (Corporate Account) ====================

    private static final String CORP_BANK_ID = "CORP-BANK";

    /** 确保企业公户存在 */
    private void ensureCorporateAccount(String enterpriseId) {
        try (var conn = plugin.ksCore().dataStore().getConnection()) {
            if (conn == null) return;
            long now = System.currentTimeMillis() / 1000;
            ensureCorporateAccount(conn, enterpriseId, CORP_BANK_ID, 0, now);
        } catch (java.sql.SQLException ignored) {}
    }

    /** 企业公户存款 */
    private boolean depositCorporate(String enterpriseId, double amount, String reason) {
        if (!Double.isFinite(amount) || amount <= 0) return false;
        ensureCorporateAccount(enterpriseId);
        try (var conn = plugin.ksCore().dataStore().getConnection()) {
            if (conn == null) return false;
            conn.setAutoCommit(false);
            String bankId = getCorporateBankId(conn, enterpriseId);
            if (bankId == null) { conn.rollback(); return false; }
            long now = System.currentTimeMillis() / 1000;
            try (var account = conn.prepareStatement("UPDATE ks_ent_corporate_accounts SET balance=balance+?, updated_at=? WHERE enterprise_id=?");
                 var enterprise = conn.prepareStatement("UPDATE ks_ent_enterprises SET current_assets=current_assets+? WHERE id=?");
                 var bank = conn.prepareStatement("UPDATE ks_bank_banks SET total_assets=total_assets+? WHERE id=?")) {
                account.setDouble(1, amount); account.setLong(2, now); account.setString(3, enterpriseId);
                enterprise.setDouble(1, amount); enterprise.setString(2, enterpriseId);
                bank.setDouble(1, amount); bank.setString(2, bankId);
                if (account.executeUpdate() != 1 || enterprise.executeUpdate() != 1 || bank.executeUpdate() != 1) {
                    conn.rollback();
                    return false;
                }
            }
            conn.commit();
            auditLog("CORPORATE_DEPOSIT", "SYSTEM", "企业银行", "enterprise", enterpriseId,
                reason + " | +" + String.format("%.2f", amount));
            return true;
        } catch (java.sql.SQLException e) { return false; }
    }

    /** 企业公户取款 */
    private boolean withdrawCorporate(String enterpriseId, double amount, String reason) {
        if (!Double.isFinite(amount) || amount <= 0) return false;
        try (var conn = plugin.ksCore().dataStore().getConnection()) {
            if (conn == null) return false;
            conn.setAutoCommit(false);
            String bankId = getCorporateBankId(conn, enterpriseId);
            if (bankId == null) { conn.rollback(); return false; }
            long now = System.currentTimeMillis() / 1000;
            try (var account = conn.prepareStatement("UPDATE ks_ent_corporate_accounts SET balance=balance-?, updated_at=? WHERE enterprise_id=? AND balance>=?");
                 var enterprise = conn.prepareStatement("UPDATE ks_ent_enterprises SET current_assets=current_assets-? WHERE id=? AND current_assets>=?");
                 var bank = conn.prepareStatement("UPDATE ks_bank_banks SET total_assets=total_assets-? WHERE id=? AND total_assets>=?")) {
                account.setDouble(1, amount); account.setLong(2, now); account.setString(3, enterpriseId); account.setDouble(4, amount);
                enterprise.setDouble(1, amount); enterprise.setString(2, enterpriseId); enterprise.setDouble(3, amount);
                bank.setDouble(1, amount); bank.setString(2, bankId); bank.setDouble(3, amount);
                if (account.executeUpdate() != 1 || enterprise.executeUpdate() != 1 || bank.executeUpdate() != 1) {
                    conn.rollback();
                    return false;
                }
            }
            conn.commit();
            auditLog("CORPORATE_WITHDRAW", "SYSTEM", "企业银行", "enterprise", enterpriseId,
                reason + " | -" + String.format("%.2f", amount));
            return true;
        } catch (java.sql.SQLException e) { return false; }
    }

    /** 企业公户余额 */
    private double getCorporateBalance(String enterpriseId) {
        try (var conn = plugin.ksCore().dataStore().getConnection()) {
            if (conn == null) return 0;
            return getCorporateBalance(conn, enterpriseId);
        } catch (java.sql.SQLException e) { return 0; }
    }

    private double getCorporateBalance(java.sql.Connection conn, String enterpriseId) throws java.sql.SQLException {
        try (var ps = conn.prepareStatement("SELECT balance FROM ks_ent_corporate_accounts WHERE enterprise_id=?")) {
            ps.setString(1, enterpriseId);
            try (var rs = ps.executeQuery()) { return rs.next() ? rs.getDouble(1) : 0; }
        }
    }

    private String getCorporateBankId(java.sql.Connection conn, String enterpriseId) throws java.sql.SQLException {
        try (var ps = conn.prepareStatement("SELECT bank_id FROM ks_ent_corporate_accounts WHERE enterprise_id=?")) {
            ps.setString(1, enterpriseId);
            try (var rs = ps.executeQuery()) { return rs.next() ? rs.getString(1) : null; }
        }
    }

    private String getBankName(java.sql.Connection conn, String bankId) throws java.sql.SQLException {
        try (var ps = conn.prepareStatement("SELECT name FROM ks_bank_banks WHERE id=?")) {
            ps.setString(1, bankId);
            try (var rs = ps.executeQuery()) { return rs.next() ? rs.getString(1) : null; }
        }
    }

    private void handleAuditLog(HttpExchange exchange, String query) throws IOException {
        KsAuthManager.Session session = requireAdminAuth(exchange);
        if (session == null) return;
        Map<String, String> params = KsPluginBridge.parseQuery(query);
        String action = params.getOrDefault("action", "");
        int limit;
        try { limit = Math.max(1, Math.min(200, Integer.parseInt(params.getOrDefault("limit", "50")))); }
        catch (NumberFormatException ignored) { limit = 50; }
        List<Map<String, Object>> list = new ArrayList<>();
        StringBuilder sql = new StringBuilder("SELECT * FROM ks_audit_log WHERE 1=1");
        if (!action.isEmpty()) sql.append(" AND action='").append(action.replace("'", "''")).append("'");
        sql.append(" ORDER BY created_at DESC LIMIT ").append(limit);
        queryRows(list, sql.toString(), null);
        KsPluginBridge.sendJson(exchange, 200, gson.toJson(Map.of("logs", list, "count", list.size())));
    }

    /** Enterprise founders and managers can inspect actions affecting their enterprise. */
    private void handleEnterpriseAudit(HttpExchange exchange, String query) throws IOException {
        KsAuthManager.Session session = requireAuth(exchange);
        if (session == null) return;
        Map<String, String> params = KsPluginBridge.parseQuery(query);
        String enterpriseId = params.get("enterpriseId");
        if (enterpriseId == null || enterpriseId.isBlank()) {
            KsPluginBridge.sendJson(exchange, 400, "{\"error\":\"缺少 enterpriseId\"}"); return;
        }
        int limit;
        try { limit = Math.max(1, Math.min(200, Integer.parseInt(params.getOrDefault("limit", "80")))); }
        catch (NumberFormatException ignored) { limit = 80; }

        List<Map<String, Object>> logs = new ArrayList<>();
        try (var conn = plugin.ksCore().dataStore().getConnection()) {
            if (conn == null) { KsPluginBridge.sendJson(exchange, 500, "{\"error\":\"数据库未连接\"}"); return; }
            if (!canManageEnterpriseMembers(conn, enterpriseId, session)) {
                KsPluginBridge.sendJson(exchange, 403, "{\"error\":\"仅企业创始人或经理可查看审计日志\"}"); return;
            }
            try (var ps = conn.prepareStatement(
                    "SELECT id,action,player_uuid,player_name,details,created_at FROM ks_audit_log " +
                    "WHERE target_type='enterprise' AND target_id=? ORDER BY created_at DESC, id DESC LIMIT ?")) {
                ps.setString(1, enterpriseId);
                ps.setInt(2, limit);
                try (var rs = ps.executeQuery()) {
                    while (rs.next()) {
                        Map<String, Object> row = new LinkedHashMap<>();
                        row.put("id", rs.getLong("id"));
                        row.put("action", rs.getString("action"));
                        row.put("playerUuid", rs.getString("player_uuid"));
                        row.put("playerName", rs.getString("player_name"));
                        row.put("details", rs.getString("details"));
                        row.put("createdAt", rs.getLong("created_at"));
                        logs.add(row);
                    }
                }
            }
        } catch (java.sql.SQLException e) {
            KsPluginBridge.sendJson(exchange, 500, gson.toJson(Map.of("error", "审计日志查询失败: " + e.getMessage()))); return;
        }
        KsPluginBridge.sendJson(exchange, 200, gson.toJson(Map.of("logs", logs, "count", logs.size())));
    }

    // ================================================================
    // 内联 HTML 页面（含完整管理表单）
    // ================================================================

    private static final String NAV_HTML = """
        <div class="nav"><a href="/ks-Eco/">市场面板</a><a href="/ks-Eco/bank">银行系统</a><a href="/ks-Eco/enterprise">企业系统</a><a href="/ks-Eco/tax">税法系统</a><a href="/ks-Eco/prices">官方定价</a></div>
        <script>// 导航链接自动携带 token
        (function(){var m=location.search.match(/token=([^&]+)/);if(m){var t=m[1];document.querySelectorAll('.nav a').forEach(function(a){var u=new URL(a.href,location.origin);u.searchParams.set('token',t);a.href=u.toString();});}})();
        </script>""";

    private static final String BASE_STYLE = """
        <style>
        *{box-sizing:border-box;}
        body{font-family:'Segoe UI',sans-serif;max-width:1100px;margin:20px auto;padding:0 20px;background:#1a1a2e;color:#e0e0e0;}
        h1{color:#00d4ff;border-bottom:2px solid #00d4ff33;padding-bottom:10px;}
        h2{color:#00d4ff;font-size:16px;margin:20px 0 8px;}
        h3{color:#ddd;font-size:14px;margin:12px 0 6px;}
        .card{background:#16213e;border-radius:8px;padding:16px;margin:12px 0;border-left:4px solid #00d4ff;}
        .nav{display:flex;gap:8px;margin-bottom:16px;flex-wrap:wrap;}
        .nav a{color:#00d4ff;text-decoration:none;padding:6px 12px;border:1px solid #00d4ff33;border-radius:4px;font-size:13px;}
        .nav a:hover,.nav a.active{background:#00d4ff22;}
        table{width:100%;border-collapse:collapse;margin:10px 0;font-size:12px;}
        th,td{padding:6px 10px;text-align:left;border-bottom:1px solid #2a2a4a;}
        th{color:#00d4ff;position:sticky;top:0;background:#16213e;}
        tr:hover{background:#ffffff08;}
        .btn{background:#00d4ff33;color:#00d4ff;border:1px solid #00d4ff;padding:6px 14px;border-radius:4px;cursor:pointer;font-size:12px;}
        .btn:hover{background:#00d4ff66;}
        .btn-sm{padding:4px 10px;font-size:11px;}
        .btn-danger{background:#f4433633;color:#f44;border-color:#f44;}
        .btn-danger:hover{background:#f4433666;}
        input,select{background:#0a0a1a;border:1px solid #2a2a4a;color:#e0e0e0;padding:6px 10px;border-radius:4px;font-size:12px;margin:2px;}
        input:focus,select:focus{border-color:#00d4ff;outline:none;}
        .stat{font-size:22px;font-weight:bold;color:#00d4ff;}
        .stat-label{font-size:10px;color:#888;}
        .form-row{display:flex;gap:8px;align-items:flex-end;flex-wrap:wrap;margin:6px 0;}
        .form-row label{font-size:11px;color:#888;display:block;}
        .msg{font-size:11px;padding:4px 8px;border-radius:4px;margin:4px 0;}
        .msg-ok{background:#00d4ff22;color:#00d4ff;}
        .msg-err{background:#f4433622;color:#f44;}
        .tabs{display:flex;gap:4px;margin:10px 0;border-bottom:2px solid #2a2a4a;padding-bottom:0;}
        .tab{padding:6px 14px;cursor:pointer;border-radius:4px 4px 0 0;font-size:12px;color:#888;background:transparent;border:none;}
        .tab.active{color:#00d4ff;background:#00d4ff11;}
        .tab:hover{color:#00d4ff;}
        .tab-content{display:none;}
        .tab-content.active{display:block;}
        </style>""";

    private String buildBankHtml() {
        return """
        <!DOCTYPE html><html lang="zh-CN"><head><meta charset="UTF-8">
        <title>银行系统 | ks-Eco</title>""" + BASE_STYLE + """
        </head><body>
        """ + NAV_HTML + """
        <h1>🏦 中央银行与商业银行系统</h1>
        <div class="card" id="statsCard"><table><tr><td><div class="stat" id="bankCount">—</div><div class="stat-label">注册银行</div></td><td><div class="stat" id="accountCount">—</div><div class="stat-label">账户总数</div></td><td><div class="stat" id="totalAssets">—</div><div class="stat-label">系统总资产</div></td><td><div class="stat" id="totalLoans">—</div><div class="stat-label">未还贷款</div></td></tr></table></div>

        <div id="msg"></div>

        <h2>🏛 央行宏观调控</h2>
        <div class="card">
        <div class="form-row">
        <span><b>基准利率:</b> <span id="baseRate">—</span></span>
        <span style="margin-left:16px;"><b>准备金率:</b> <span id="reserveRequirement">—</span></span>
        </div>
        <div class="form-row">
        <input id="cbBaseRate" placeholder="基准利率 (0.001~0.20)" style="width:160px;"/>
        <input id="cbReserveReq" placeholder="准备金率 (0.05~0.30)" style="width:160px;"/>
        <button class="btn btn-sm" onclick="setCbRates()">更新央行利率</button>
        </div>
        </div>

        <h2>🏦 创建银行</h2>
        <div class="card">
        <div class="form-row"><input id="bkName" placeholder="银行名称"/><input id="bkOwners" placeholder="所有者UUID (逗号分隔)"/><input id="bkCapital" placeholder="初始资本" type="number" step="1"/><select id="bkType"><option value="COMMERCIAL">商业银行</option><option value="CENTRAL">中央银行</option></select><button class="btn btn-sm" onclick="createBank()">创建银行</button></div>
        </div>

        <h2>💰 放贷</h2>
        <div class="card">
        <div class="form-row"><input id="loanBankId" placeholder="银行ID"/><input id="loanBorrower" placeholder="借款人UUID"/><input id="loanPrincipal" placeholder="本金" type="number" step="1"/><input id="loanTerm" placeholder="期限(天)" type="number" step="1" value="30"/><button class="btn btn-sm" onclick="issueLoan()">发放贷款</button></div>
        </div>

        <h2>📋 银行列表</h2>
        <div class="card" style="max-height:300px;overflow-y:auto;"><table id="bankTable"><thead><tr><th>ID</th><th>名称</th><th>类型</th><th>总资产</th><th>状态</th></tr></thead><tbody></tbody></table></div>

        <h2>📋 贷款列表</h2>
        <div class="card" style="max-height:300px;overflow-y:auto;"><table id="loanTable"><thead><tr><th>ID</th><th>银行</th><th>借款人</th><th>本金</th><th>剩余</th><th>利率</th><th>状态</th></tr></thead><tbody></tbody></table></div>

        <script>
        var TOKEN=(new URL(location)).searchParams.get('token')||'';
        function authHeaders(){return TOKEN?{Authorization:'Bearer '+TOKEN}:{};}
        function msg(s,ok){var e=document.getElementById('msg');e.innerHTML='<div class="msg '+(ok?'msg-ok':'msg-err')+'">'+s+'</div>';setTimeout(function(){e.innerHTML='';},4000);}
        function loadStats(){fetch('/ks-Eco/api/bank/stats').then(r=>r.json()).then(d=>{
        document.getElementById('bankCount').textContent=d.bankCount||0;
        document.getElementById('accountCount').textContent=d.accountCount||0;
        document.getElementById('totalAssets').textContent=(d.totalAssets||0).toLocaleString();
        document.getElementById('totalLoans').textContent=(d.totalLoans||0).toLocaleString();
        document.getElementById('baseRate').textContent=((d.baseRate||0)*100).toFixed(2)+'%';
        document.getElementById('reserveRequirement').textContent=((d.reserveRequirement||0)*100).toFixed(2)+'%';
        });}
        function loadBanks(){fetch('/ks-Eco/api/bank/list').then(r=>r.json()).then(d=>{
        var t='';(d.banks||[]).forEach(function(b){t+='<tr><td>'+b.id+'</td><td>'+b.name+'</td><td>'+b.type+'</td><td>'+(b.capital||b.balance||'—')+'</td><td>'+b.status+'</td></tr>';});
        document.getElementById('bankTable').querySelector('tbody').innerHTML=t;
        });}
        function loadLoans(){fetch('/ks-Eco/api/bank/loans').then(r=>r.json()).then(d=>{
        var t='';(d.loans||[]).forEach(function(l){t+='<tr><td>'+l.id+'</td><td>'+l.bank_id+'</td><td>'+l.borrower_uuid+'</td><td>'+l.principal+'</td><td>'+l.remaining+'</td><td>'+l.interest_rate+'</td><td>'+l.status+'</td></tr>';});
        document.getElementById('loanTable').querySelector('tbody').innerHTML=t;
        });}
        function setCbRates(){var b=document.getElementById('cbBaseRate').value,r=document.getElementById('cbReserveReq').value;var o={};if(b)o.baseRate=parseFloat(b);if(r)o.reserveRequirement=parseFloat(r);fetch('/ks-Eco/api/bank/cb/set-rates',{method:'POST',headers:authHeaders(),body:JSON.stringify(o)}).then(r=>r.json()).then(d=>{msg(d.message||d.error,d.message?true:false);loadStats();});}
        function createBank(){var n=document.getElementById('bkName').value,o=document.getElementById('bkOwners').value,c=document.getElementById('bkCapital').value,t=document.getElementById('bkType').value;fetch('/ks-Eco/api/bank/create',{method:'POST',headers:authHeaders(),body:JSON.stringify({name:n,ownerUuids:o.split(','),initialCapital:parseFloat(c)||0,type:t})}).then(r=>r.json()).then(d=>{if(d.id){msg('银行创建成功: '+d.id,true);loadBanks();loadStats();}else msg(d.error||'失败',false);});}
        function issueLoan(){var b=document.getElementById('loanBankId').value,u=document.getElementById('loanBorrower').value,p=document.getElementById('loanPrincipal').value,t=document.getElementById('loanTerm').value;fetch('/ks-Eco/api/bank/loan/issue',{method:'POST',headers:authHeaders(),body:JSON.stringify({bankId:b,borrowerUuid:u,principal:parseFloat(p)||0,termDays:parseInt(t)||30})}).then(r=>r.json()).then(d=>{if(d.message){msg(d.message,true);loadLoans();loadStats();}else msg(d.error||'失败',false);});}
        loadStats();loadBanks();loadLoans();
        setInterval(loadStats,10000);
        </script></body></html>
        """;
    }

    private String buildEnterpriseHtml() {
        return """
        <!DOCTYPE html><html lang="zh-CN"><head><meta charset="UTF-8">
        <title>企业系统 | ks-Eco</title>""" + BASE_STYLE + """
        </head><body>
        """ + NAV_HTML + """
        <h1>🏢 企业与招投标系统</h1>
        <div class="card" id="statsCard"><table><tr><td><div class="stat" id="entCount">—</div><div class="stat-label">注册企业</div></td><td><div class="stat" id="activeCount">—</div><div class="stat-label">活跃企业</div></td><td><div class="stat" id="totalCapital">—</div><div class="stat-label">注册资本</div></td><td><div class="stat" id="openProjects">—</div><div class="stat-label">开放项目</div></td></tr></table></div>
        <div id="msg"></div>

        <h2>🏢 注册企业</h2>
        <div class="card">
        <div class="form-row"><input id="entName" placeholder="企业名称"/><input id="entOwners" placeholder="所有者UUID (逗号分隔)"/><input id="entCapital" placeholder="注册资本" type="number" step="1"/><select id="entType"><option value="PRIVATE">私有企业</option><option value="STATE">国有企业</option></select><input id="entRegion" placeholder="注册区域"/><button class="btn btn-sm" onclick="registerEnterprise()">注册企业</button></div>
        <div class="form-row"><input id="dissolveEntId" placeholder="企业ID"/><input id="dissolveOwner" placeholder="请求者UUID"/><button class="btn btn-sm btn-danger" onclick="dissolveEnterprise()">注销企业</button></div>
        </div>

        <h2>📢 发布招标项目</h2>
        <div class="card">
        <div class="form-row"><input id="projTitle" placeholder="项目标题"/><input id="projPublisher" placeholder="发布者UUID"/><select id="projPubType"><option value="OFFICIAL">官方</option><option value="ENTERPRISE">企业</option></select><input id="projBudget" placeholder="预算" type="number" step="1"/></div>
        <div class="form-row"><input id="projPrepay" placeholder="预付款比例" value="0.3" type="number" step="0.01"/><input id="projPenalty" placeholder="违约金比例" value="0.1" type="number" step="0.01"/><input id="projDeadline" placeholder="截止时间(Unix秒)" type="number" step="1"/><input id="projLocation" placeholder="地点"/><button class="btn btn-sm" onclick="publishProject()">发布项目</button></div>
        </div>

        <h2>🎯 投标 & 评标</h2>
        <div class="card">
        <div class="form-row"><input id="bidProjectId" placeholder="项目ID"/><input id="bidEntId" placeholder="企业ID"/><input id="bidAmount" placeholder="投标金额" type="number" step="1"/><button class="btn btn-sm" onclick="submitBid()">投标</button></div>
        <div class="form-row"><input id="awardProjectId" placeholder="项目ID"/><button class="btn btn-sm" onclick="awardProject()">评标（最低价中标）</button></div>
        </div>

        <h2>📋 企业列表</h2>
        <div class="card" style="max-height:250px;overflow-y:auto;"><table id="entTable"><thead><tr><th>ID</th><th>名称</th><th>类型</th><th>注册资本</th><th>当前资产</th><th>区域</th><th>状态</th></tr></thead><tbody></tbody></table></div>

        <h2>📋 项目列表</h2>
        <div class="card" style="max-height:250px;overflow-y:auto;"><table id="projTable"><thead><tr><th>ID</th><th>标题</th><th>预算</th><th>投标数</th><th>状态</th></tr></thead><tbody></tbody></table></div>

        <script>
        var TOKEN=(new URL(location)).searchParams.get('token')||'';
        function authHeaders(){return TOKEN?{Authorization:'Bearer '+TOKEN}:{};}
        function msg(s,ok){var e=document.getElementById('msg');e.innerHTML='<div class="msg '+(ok?'msg-ok':'msg-err')+'">'+s+'</div>';setTimeout(function(){e.innerHTML='';},4000);}
        function loadStats(){fetch('/ks-Eco/api/enterprise/stats').then(r=>r.json()).then(d=>{
        document.getElementById('entCount').textContent=d.enterpriseCount||0;
        document.getElementById('activeCount').textContent=d.activeCount||0;
        document.getElementById('totalCapital').textContent=(d.totalCapital||0).toLocaleString();
        document.getElementById('openProjects').textContent=d.openProjects||0;
        });}
        function loadEnts(){fetch('/ks-Eco/api/enterprise/list').then(r=>r.json()).then(d=>{
        var t='';(d.enterprises||[]).filter(function(e){return TOKEN||e.type!=='STATE_OWNED';}).forEach(function(e){t+='<tr><td>'+e.id+'</td><td>'+e.name+'</td><td>'+e.type+'</td><td>'+e.registered_capital+'</td><td>'+e.current_assets+'</td><td>'+(e.region||'')+'</td><td>'+e.status+'</td></tr>';});
        document.getElementById('entTable').querySelector('tbody').innerHTML=t;
        });}
        function loadProjs(){fetch('/ks-Eco/api/enterprise/projects').then(r=>r.json()).then(d=>{
        var t='';(d.projects||[]).forEach(function(p){t+='<tr><td>'+p.id+'</td><td>'+p.title+'</td><td>'+p.budget+'</td><td>'+(p.bidCount||0)+'</td><td>'+p.status+'</td></tr>';});
        document.getElementById('projTable').querySelector('tbody').innerHTML=t;
        });}
        function registerEnterprise(){var n=document.getElementById('entName').value,o=document.getElementById('entOwners').value,c=document.getElementById('entCapital').value,t=document.getElementById('entType').value,r=document.getElementById('entRegion').value;fetch('/ks-Eco/api/enterprise/register',{method:'POST',headers:authHeaders(),body:JSON.stringify({name:n,ownerUuids:o.split(','),registeredCapital:parseFloat(c)||0,type:t,region:r})}).then(r=>r.json()).then(d=>{if(d.id){msg('企业注册成功: '+d.id,true);loadEnts();loadStats();}else msg(d.error||'失败',false);});}
        function dissolveEnterprise(){var e=document.getElementById('dissolveEntId').value,u=document.getElementById('dissolveOwner').value;fetch('/ks-Eco/api/enterprise/dissolve',{method:'POST',headers:authHeaders(),body:JSON.stringify({enterpriseId:e,requesterUuid:u})}).then(r=>r.json()).then(d=>{if(d.message){msg(d.message,true);loadEnts();loadStats();}else msg(d.error||'失败',false);});}
        function publishProject(){var t=document.getElementById('projTitle').value,p=document.getElementById('projPublisher').value,pt=document.getElementById('projPubType').value,b=document.getElementById('projBudget').value,pp=document.getElementById('projPrepay').value,pn=document.getElementById('projPenalty').value;var d=document.getElementById('projDeadline').value,l=document.getElementById('projLocation').value;fetch('/ks-Eco/api/enterprise/project/publish',{method:'POST',headers:authHeaders(),body:JSON.stringify({title:t,publisherUuid:p,publisherType:pt,budget:parseFloat(b)||0,prepaymentRatio:parseFloat(pp)||0.3,penaltyRatio:parseFloat(pn)||0.1,deadline:parseInt(d)||0,location:l,allowSubcontract:true,allowConsortium:true})}).then(r=>r.json()).then(d=>{if(d.id){msg('项目发布成功: '+d.id,true);loadProjs();loadStats();}else msg(d.error||'失败',false);});}
        function submitBid(){var p=document.getElementById('bidProjectId').value,e=document.getElementById('bidEntId').value,a=document.getElementById('bidAmount').value;fetch('/ks-Eco/api/enterprise/bid/submit',{method:'POST',headers:authHeaders(),body:JSON.stringify({projectId:p,enterpriseId:e,bidAmount:parseFloat(a)||0})}).then(r=>r.json()).then(d=>{if(d.id){msg('投标成功: '+d.id,true);loadProjs();}else msg(d.error||'失败',false);});}
        function awardProject(){var p=document.getElementById('awardProjectId').value;fetch('/ks-Eco/api/enterprise/project/award',{method:'POST',headers:authHeaders(),body:JSON.stringify({projectId:p})}).then(r=>r.json()).then(d=>{if(d.id){msg('中标: '+d.id+' 企业: '+d.enterprise_id,true);loadProjs();loadStats();}else msg(d.error||'失败',false);});}
        loadStats();loadEnts();loadProjs();
        setInterval(loadStats,10000);
        </script></body></html>
        """;
    }

    // ==================== Player Search API ====================

    private void handlePlayerSearch(HttpExchange exchange, String query) throws IOException {
        KsAuthManager.Session session = requireAuth(exchange);
        if (session == null) return;
        Map<String, String> params = KsPluginBridge.parseQuery(query);
        String q = params.getOrDefault("q", "").trim().toLowerCase(Locale.ROOT);
        try {
            List<Map<String, Object>> results = plugin.scheduler().callGlobal(() -> {
                List<Map<String, Object>> matches = new ArrayList<>();
                for (org.bukkit.OfflinePlayer player : Bukkit.getOfflinePlayers()) {
                    String name = player.getName();
                    if (name == null || name.isBlank()) continue;
                    if (q.isEmpty() || name.toLowerCase(Locale.ROOT).contains(q)
                            || player.getUniqueId().toString().contains(q)) {
                        Map<String, Object> entry = new LinkedHashMap<>();
                        entry.put("uuid", player.getUniqueId().toString());
                        entry.put("name", name);
                        entry.put("online", player.isOnline());
                        matches.add(entry);
                        if (matches.size() >= 20) break;
                    }
                }
                return matches;
            }, Duration.ofSeconds(3));
            KsPluginBridge.sendJson(exchange, 200, gson.toJson(Map.of("players", results, "count", results.size())));
        } catch (Exception e) {
            KsPluginBridge.sendJson(exchange, 503, "{\"error\":\"玩家搜索暂时不可用\"}");
        }
    }

    // ==================== Price Configurator ====================

    private void servePriceConfigPage(HttpExchange exchange) throws IOException {
        String html = buildPriceConfigHtml();
        KsPluginBridge.sendHtml(exchange, 200, html);
    }

    private void handleItemList(HttpExchange exchange) throws IOException {
        // 返回所有可交易的物品列表（供管理填写官方价格）
        List<Map<String, Object>> items = new ArrayList<>();
        for (org.bukkit.Material mat : org.bukkit.Material.values()) {
            if (!mat.isItem() || mat.isAir() || mat.isLegacy()) continue;
            if (mat.name().contains("SPAWN_EGG") || mat.name().contains("COMMAND_BLOCK") || mat.name().contains("STRUCTURE_")) continue;
            if (mat.name().contains("POTION") || mat.name().contains("TIPPED_ARROW") || mat.name().contains("PLAYER_HEAD")) continue;
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id", mat.name());
            item.put("category", mat.isBlock() ? "方块" : "物品");
            items.add(item);
        }
        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("items", items); resp.put("count", items.size());
        KsPluginBridge.sendJson(exchange, 200, gson.toJson(resp));
    }

    private void handleOfficialPricesGet(HttpExchange exchange) throws IOException {
        Map<String, Object> resp = new LinkedHashMap<>();
        List<Map<String, Object>> prices = new ArrayList<>();
        try (var conn = plugin.ksCore().dataStore().getConnection()) {
            if (conn != null) {
                var rs = conn.createStatement().executeQuery("SELECT * FROM ks_official_prices ORDER BY material");
                while (rs.next()) {
                    Map<String, Object> p = new LinkedHashMap<>();
                    p.put("material", rs.getString("material"));
                    p.put("buyPrice", rs.getDouble("buy_price"));
                    p.put("category", rs.getString("category"));
                    prices.add(p);
                }
            }
        } catch (java.sql.SQLException e) {
            plugin.getLogger().warning("读取官方价格失败: " + e.getMessage());
        }
        resp.put("prices", prices); resp.put("count", prices.size());
        KsPluginBridge.sendJson(exchange, 200, gson.toJson(resp));
    }

    @SuppressWarnings("unchecked")
    private void handleOfficialPriceSave(HttpExchange exchange) throws IOException {
        KsAuthManager.Session session = requireAdminAuth(exchange);
        if (session == null) return;
        String gate = checkPoliticGate();
        if (gate != null) { KsPluginBridge.sendJson(exchange, 403, gson.toJson(Map.of("error", gate))); return; }
        Map<String, Object> req = gson.fromJson(KsPluginBridge.readBody(exchange), Map.class);
        if (req == null) { KsPluginBridge.sendJson(exchange, 400, "{\"error\":\"无效请求\"}"); return; }
        // 支持批量保存: {"prices": [{"material":"...","buyPrice":...,"category":"..."}]}
        // 官方只收购、不直售（直售已由盲盒系统取代），因此这里不再接受 sellPrice。
        List<Map<String, Object>> priceList;
        if (req.containsKey("prices")) {
            priceList = (List<Map<String, Object>>) req.get("prices");
        } else {
            priceList = List.of(req);
        }
        int saved = 0;
        for (var p : priceList) {
            String mat = (String) p.get("material");
            double price = toDouble(p.get("buyPrice"), -1);
            if (mat != null && plugin.priceEngine().setOfficialBuyPrice(mat, price)) saved++;
        }
        plugin.refreshPricesCoordinated();
        KsPluginBridge.sendJson(exchange, 200, "{\"message\":\"已保存 " + saved + " 条官方价格，市场价格已刷新\"}");
    }

    // ==================== Private Bank Rate Adjustment ====================

    private void handleBankRateSet(HttpExchange exchange) throws IOException {
        KsAuthManager.Session session = requireAuth(exchange);
        if (session == null) return;
        @SuppressWarnings("unchecked")
        Map<String, Object> req = gson.fromJson(KsPluginBridge.readBody(exchange), Map.class);
        if (req == null) { KsPluginBridge.sendJson(exchange, 400, "{\"error\":\"无效请求\"}"); return; }
        String bankId = (String) req.get("bankId");
        double loanRate = toDouble(req.get("loanRate"), -1);
        double depositRate = toDouble(req.get("depositRate"), -1);
        double reserveRatio = toDouble(req.get("reserveRatio"), -1);
        if (bankId == null) { KsPluginBridge.sendJson(exchange, 400, "{\"error\":\"缺少 bankId\"}"); return; }
        // 权限：只有银行所有者或管理员能改该行利率
        try (var conn = plugin.ksCore().dataStore().getConnection()) {
            if (conn == null || (!session.isAdmin && !(plugin.bankAccessProvider() != null
                    ? plugin.bankAccessProvider().hasPermission(bankId, session.playerUuid, "SET_RATES")
                    : isBankOwnerOrAdmin(conn, bankId, session)))) {
                KsPluginBridge.sendJson(exchange, 403, "{\"error\":\"只有银行所有者或管理员才能设置利率\"}"); return;
            }
        } catch (java.sql.SQLException e) {
            KsPluginBridge.sendJson(exchange, 500, "{\"error\":\"权限检查失败: " + e.getMessage() + "\"}"); return;
        }
        // 获取央行基准利率、浮动限制和利率区间（rate_min/rate_max 是 cb/set-rate-range 配的全局硬边界，
        // 之前只在页面上展示从未真正校验——admin 面板"商业银行设置利率时会校验落在此区间内"的承诺一直是空话）
        double baseRate = 0.035, adjustLimit = 0.02;
        double rateMin = 0.01, rateMax = 0.20; // 与 CentralBankManager 默认值一致
        try (var conn = plugin.ksCore().dataStore().getConnection()) {
            if (conn != null) {
                var rs = conn.createStatement().executeQuery("SELECT base_rate FROM ks_bank_cb_rates ORDER BY set_at DESC LIMIT 1");
                if (rs.next()) baseRate = rs.getDouble("base_rate");
                try {
                    var rs2 = conn.createStatement().executeQuery(
                        "SELECT config_key, config_value FROM ks_bank_cb_config WHERE config_key IN ('rate_adjust_limit','rate_min','rate_max')");
                    while (rs2.next()) {
                        switch (rs2.getString("config_key")) {
                            case "rate_adjust_limit" -> adjustLimit = rs2.getDouble("config_value");
                            case "rate_min" -> rateMin = rs2.getDouble("config_value");
                            case "rate_max" -> rateMax = rs2.getDouble("config_value");
                        }
                    }
                } catch (java.sql.SQLException ignored) {}
            }
        } catch (java.sql.SQLException ignored) {}
        if (loanRate >= 0 && (loanRate < baseRate - adjustLimit || loanRate > baseRate + adjustLimit)) {
            KsPluginBridge.sendJson(exchange, 400, "{\"error\":\"贷款利率超出央行允许范围 [" + String.format("%.1f", (baseRate - adjustLimit) * 100) + "%, " + String.format("%.1f", (baseRate + adjustLimit) * 100) + "%]\"}");
            return;
        }
        if (loanRate >= 0 && (loanRate < rateMin || loanRate > rateMax)) {
            KsPluginBridge.sendJson(exchange, 400, "{\"error\":\"贷款利率超出央行利率区间 [" + String.format("%.1f", rateMin * 100) + "%, " + String.format("%.1f", rateMax * 100) + "%]\"}");
            return;
        }
        if (depositRate >= 0 && (depositRate < 0 || depositRate > baseRate)) {
            KsPluginBridge.sendJson(exchange, 400, "{\"error\":\"存款利率应在 0 到 " + String.format("%.1f", baseRate * 100) + "% 之间\"}");
            return;
        }
        if (depositRate >= 0 && depositRate > rateMax) {
            KsPluginBridge.sendJson(exchange, 400, "{\"error\":\"存款利率超出央行利率上限 " + String.format("%.1f", rateMax * 100) + "%\"}");
            return;
        }
        if (reserveRatio >= 0 && reserveRatio > 1) {
            KsPluginBridge.sendJson(exchange, 400, "{\"error\":\"准备金率必须在 0 到 1 之间\"}");
            return;
        }
        // 保存到数据库
        try (var conn = plugin.ksCore().dataStore().getConnection()) {
            if (conn != null) {
                EcoWebBusinessSchema.ensureBankRates(conn);
                upsertBankRate(conn, bankId, loanRate >= 0 ? loanRate : null,
                        depositRate >= 0 ? depositRate : null, System.currentTimeMillis() / 1000);
                // 同步写 ks_bank_banks —— 放贷（loan_rate）和利息结算（interest_rate）
                // 实际读的是 banks 表；ks_bank_rates 只为兼容旧前端展示而保留
                if (loanRate >= 0) conn.createStatement().executeUpdate(
                    "UPDATE ks_bank_banks SET loan_rate=" + loanRate + " WHERE id='" + bankId.replace("'", "''") + "'");
                if (depositRate >= 0) conn.createStatement().executeUpdate(
                    "UPDATE ks_bank_banks SET interest_rate=" + depositRate + " WHERE id='" + bankId.replace("'", "''") + "'");
                if (reserveRatio >= 0) conn.createStatement().executeUpdate(
                    "UPDATE ks_bank_banks SET reserve_ratio=" + reserveRatio + " WHERE id='" + bankId.replace("'", "''") + "'");
            }
        } catch (java.sql.SQLException e) {
            KsPluginBridge.sendJson(exchange, 500, "{\"error\":\"保存失败: " + e.getMessage() + "\"}");
            return;
        }
        KsPluginBridge.sendJson(exchange, 200, "{\"message\":\"银行经营参数已更新\"}");
        auditLog("BANK_RATE_SET", session.playerUuid.toString(), session.playerName, "bank", bankId,
            "贷款率: " + String.format("%.1f", loanRate*100) + "% 存款率: " + String.format("%.1f", depositRate*100) + "% 准备金率: " + (reserveRatio < 0 ? "未改" : String.format("%.1f%%", reserveRatio * 100)));
    }

    private void handleBankRateGet(HttpExchange exchange, String query) throws IOException {
        Map<String, String> params = KsPluginBridge.parseQuery(query);
        String bankId = params.get("bankId");
        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("loanRate", 0.05);
        resp.put("depositRate", 0.01);
        if (bankId != null) {
            try (var conn = plugin.ksCore().dataStore().getConnection()) {
                if (conn != null) {
                    // banks 表是实际生效的利率来源（放贷/利息结算都读它）
                    var rs = conn.createStatement().executeQuery(
                        "SELECT loan_rate, interest_rate FROM ks_bank_banks WHERE id='" + bankId.replace("'", "''") + "'");
                    if (rs.next()) {
                        resp.put("loanRate", rs.getDouble("loan_rate"));
                        resp.put("depositRate", rs.getDouble("interest_rate"));
                    }
                }
            } catch (java.sql.SQLException ignored) {}
        }
        // 附加央行基准利率和浮动限制
        try (var conn = plugin.ksCore().dataStore().getConnection()) {
            if (conn != null) {
                var rs = conn.createStatement().executeQuery("SELECT base_rate, reserve_requirement FROM ks_bank_cb_rates ORDER BY set_at DESC LIMIT 1");
                if (rs.next()) {
                    resp.put("baseRate", rs.getDouble("base_rate"));
                    resp.put("reserveRequirement", rs.getDouble("reserve_requirement"));
                }
                try {
                    var rs2 = conn.createStatement().executeQuery("SELECT config_value FROM ks_bank_cb_config WHERE config_key='rate_adjust_limit'");
                    if (rs2.next()) resp.put("adjustLimit", rs2.getDouble("config_value"));
                    else resp.put("adjustLimit", 0.02);
                } catch (java.sql.SQLException ignored) {
                    resp.put("adjustLimit", 0.02);
                }
            }
        } catch (java.sql.SQLException ignored) {}
        KsPluginBridge.sendJson(exchange, 200, gson.toJson(resp));
    }

    // ==================== Bank Members / Permissions ====================

    private void handleBankMembers(HttpExchange exchange, String query) throws IOException {
        KsAuthManager.Session session = requireAuth(exchange);
        if (session == null) return;
        Map<String, String> params = KsPluginBridge.parseQuery(query);
        String bankId = params.get("bankId");
        if (bankId == null) { KsPluginBridge.sendJson(exchange, 400, "{\"error\":\"缺少 bankId\"}"); return; }
        List<Map<String, Object>> members = new ArrayList<>();
        try (var conn = plugin.ksCore().dataStore().getConnection()) {
            if (conn == null) { KsPluginBridge.sendJson(exchange, 500, "{\"error\":\"数据库未连接\"}"); return; }
            if (!canManageBankMembers(conn, bankId, session)) {
                KsPluginBridge.sendJson(exchange, 403, "{\"error\":\"只有银行所有者或经理可查看成员\"}"); return;
            }
            {
                EcoWebBusinessSchema.ensureBankMembers(conn);
                ensureBankOwnerMembers(conn, bankId);
                var rs = conn.createStatement().executeQuery(
                    "SELECT player_uuid, player_name, role, joined_at FROM ks_bank_members WHERE bank_id='" + bankId.replace("'", "''") + "'");
                while (rs.next()) {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("uuid", rs.getString("player_uuid"));
                    m.put("name", rs.getString("player_name"));
                    m.put("role", rs.getString("role"));
                    m.put("joinedAt", rs.getLong("joined_at"));
                    members.add(m);
                }
            }
        } catch (java.sql.SQLException e) {
            KsPluginBridge.sendJson(exchange, 500, "{\"error\":\"查询失败: " + e.getMessage() + "\"}");
            return;
        }
        KsPluginBridge.sendJson(exchange, 200, gson.toJson(Map.of("members", members, "count", members.size())));
    }

    @SuppressWarnings("unchecked")
    private void handleBankMemberAdd(HttpExchange exchange) throws IOException {
        KsAuthManager.Session session = requireAuth(exchange);
        if (session == null) return;
        Map<String, Object> req = gson.fromJson(KsPluginBridge.readBody(exchange), Map.class);
        if (req == null) { KsPluginBridge.sendJson(exchange, 400, "{\"error\":\"无效请求\"}"); return; }
        String bankId = (String) req.get("bankId");
        String playerUuid = (String) req.get("playerUuid");
        String playerName = (String) req.getOrDefault("playerName", "");
        String role = String.valueOf(req.getOrDefault("role", "MEMBER")).toUpperCase(Locale.ROOT);
        if (bankId == null || playerUuid == null) {
            KsPluginBridge.sendJson(exchange, 400, "{\"error\":\"缺少 bankId/playerUuid\"}"); return;
        }
        if (!"MEMBER".equals(role)) {
            KsPluginBridge.sendJson(exchange, 400, "{\"error\":\"新增成员只能使用 MEMBER；请在成员加入后通过岗位权限面板调整\"}"); return;
        }
        try (var conn = plugin.ksCore().dataStore().getConnection()) {
            if (conn == null) { KsPluginBridge.sendJson(exchange, 500, "{\"error\":\"数据库未连接\"}"); return; }
            if (!canManageBankMembers(conn, bankId, session)) {
                KsPluginBridge.sendJson(exchange, 403, "{\"error\":\"只有银行所有者或管理员才能添加成员\"}"); return;
            }
            EcoWebBusinessSchema.ensureBankMembers(conn);
            UUID memberUuid = resolvePlayerReference(playerUuid);
            String canonicalName = resolvePlayerName(memberUuid);
            upsertBankMember(conn, bankId, memberUuid.toString(), canonicalName, role,
                    System.currentTimeMillis() / 1000, true);
        } catch (java.sql.SQLException e) {
            KsPluginBridge.sendJson(exchange, 500, "{\"error\":\"添加失败: " + e.getMessage() + "\"}");
            return;
        }
        auditLog("BANK_MEMBER_ADD", session.playerUuid.toString(), session.playerName, "bank", bankId,
            "添加成员 " + playerUuid + " 角色=" + role);
        KsPluginBridge.sendJson(exchange, 200, "{\"message\":\"成员已添加\"}");
    }

    @SuppressWarnings("unchecked")
    private void handleBankMemberRemove(HttpExchange exchange) throws IOException {
        KsAuthManager.Session session = requireAuth(exchange);
        if (session == null) return;
        Map<String, Object> req = gson.fromJson(KsPluginBridge.readBody(exchange), Map.class);
        if (req == null) { KsPluginBridge.sendJson(exchange, 400, "{\"error\":\"无效请求\"}"); return; }
        String bankId = (String) req.get("bankId");
        String playerUuid = (String) req.get("playerUuid");
        if (bankId == null || playerUuid == null) {
            KsPluginBridge.sendJson(exchange, 400, "{\"error\":\"缺少 bankId/playerUuid\"}"); return;
        }
        try (var conn = plugin.ksCore().dataStore().getConnection()) {
            if (conn == null) { KsPluginBridge.sendJson(exchange, 500, "{\"error\":\"数据库未连接\"}"); return; }
            if (!canManageBankMembers(conn, bankId, session)) {
                KsPluginBridge.sendJson(exchange, 403, "{\"error\":\"只有银行所有者或管理员才能移除成员\"}"); return;
            }
            UUID memberUuid = resolvePlayerReference(playerUuid);
            if (isBankMemberOwner(conn, bankId, memberUuid)) {
                KsPluginBridge.sendJson(exchange, 400, "{\"error\":\"不能通过成员接口移除银行所有者\"}"); return;
            }
            conn.createStatement().executeUpdate(
                "DELETE FROM ks_bank_members WHERE bank_id='" + bankId.replace("'", "''") + "' AND player_uuid='" + memberUuid + "'");
        } catch (java.sql.SQLException e) {
            KsPluginBridge.sendJson(exchange, 500, "{\"error\":\"移除失败: " + e.getMessage() + "\"}");
            return;
        }
        auditLog("BANK_MEMBER_REMOVE", session.playerUuid.toString(), session.playerName, "bank", bankId,
            "移除成员 " + playerUuid);
        KsPluginBridge.sendJson(exchange, 200, "{\"message\":\"成员已移除\"}");
    }

    /** 银行所有者或管理员校验：owner_uuids 包含当前玩家，或 session 为管理员 */
    private boolean isBankOwnerOrAdmin(java.sql.Connection conn, String bankId, KsAuthManager.Session session) throws java.sql.SQLException {
        if (session.isAdmin) return true;
        try (var st = conn.createStatement();
             var rs = st.executeQuery("SELECT owner_uuids FROM ks_bank_banks WHERE id='" + bankId.replace("'", "''") + "'")) {
            if (!rs.next()) return false;
            return ownerListContains(rs.getString("owner_uuids"), session.playerUuid);
        }
    }

    // ==================== Bank Permissions ====================

    private void handleBankPermissions(HttpExchange exchange, String query) throws IOException {
        KsAuthManager.Session session = requireAuth(exchange);
        if (session == null) return;
        Map<String, String> params = KsPluginBridge.parseQuery(query);
        String bankId = params.get("bankId");
        if (bankId == null) { KsPluginBridge.sendJson(exchange, 400, "{\"error\":\"缺少 bankId\"}"); return; }
        try (var conn = plugin.ksCore().dataStore().getConnection()) {
            if (conn == null) { KsPluginBridge.sendJson(exchange, 500, "{\"error\":\"数据库未连接\"}"); return; }
            if (!canManageBankMembers(conn, bankId, session)) {
                KsPluginBridge.sendJson(exchange, 403, "{\"error\":\"只有银行所有者或经理可查看权限\"}"); return;
            }
        } catch (java.sql.SQLException e) {
            KsPluginBridge.sendJson(exchange, 500, "{\"error\":\"权限校验失败\"}"); return;
        }
        List<Map<String, Object>> list = new ArrayList<>();
        queryRows(list, "SELECT * FROM ks_bank_permissions WHERE bank_id='" +
            bankId.replace("'", "''") + "' ORDER BY granted_at DESC", null);
        KsPluginBridge.sendJson(exchange, 200, gson.toJson(Map.of("permissions", list, "count", list.size())));
    }

    @SuppressWarnings("unchecked")
    private void handleBankPermissionSet(HttpExchange exchange) throws IOException {
        KsAuthManager.Session session = requireAuth(exchange);
        if (session == null) return;
        Map<String, Object> req = gson.fromJson(KsPluginBridge.readBody(exchange), Map.class);
        if (req == null) { KsPluginBridge.sendJson(exchange, 400, "{\"error\":\"无效请求\"}"); return; }
        String bankId = (String) req.get("bankId");
        String playerUuid = (String) req.get("playerUuid");
        String permission = (String) req.get("permission");
        boolean enabled = !Boolean.FALSE.equals(req.get("enabled"));
        if (bankId == null || playerUuid == null || permission == null) {
            KsPluginBridge.sendJson(exchange, 400, "{\"error\":\"缺少 bankId/playerUuid/permission\"}"); return;
        }
        permission = permission.toUpperCase(Locale.ROOT);
        if (!Set.of("MANAGE_MEMBERS", "MANAGE_PERMISSIONS", "VIEW_FINANCE", "SET_RATES",
                "ISSUE_LOAN", "APPROVE_LOAN").contains(permission)) {
            KsPluginBridge.sendJson(exchange, 400, "{\"error\":\"不支持的银行权限\"}"); return;
        }
        try (var conn = plugin.ksCore().dataStore().getConnection()) {
            if (conn == null) { KsPluginBridge.sendJson(exchange, 500, "{\"error\":\"数据库未连接\"}"); return; }
            // Admins manage all banks; normal callers must own the target bank.
            var rs = conn.createStatement().executeQuery(
                "SELECT owner_uuids FROM ks_bank_banks WHERE id='" + bankId.replace("'", "''") + "'");
            if (!rs.next()) { KsPluginBridge.sendJson(exchange, 404, "{\"error\":\"银行不存在\"}"); return; }
            if (!session.isAdmin && !ownerListContains(rs.getString("owner_uuids"), session.playerUuid)) {
                KsPluginBridge.sendJson(exchange, 403, "{\"error\":\"只有银行所有者才能设置权限\"}"); return;
            }
            UUID targetUuid = resolvePlayerReference(playerUuid);
            try (var member = conn.prepareStatement("SELECT 1 FROM ks_bank_members WHERE bank_id=? AND player_uuid=?")) {
                member.setString(1, bankId);
                member.setString(2, targetUuid.toString());
                if (enabled && !member.executeQuery().next()) {
                    KsPluginBridge.sendJson(exchange, 400, "{\"error\":\"目标必须是该银行成员\"}"); return;
                }
            }
            if (enabled) {
                upsertBankPermission(conn, bankId, targetUuid.toString(), permission,
                        session.playerUuid.toString(), System.currentTimeMillis() / 1000);
            } else {
                try (var ps = conn.prepareStatement("DELETE FROM ks_bank_permissions WHERE bank_id=? AND player_uuid=? AND permission=?")) {
                    ps.setString(1, bankId); ps.setString(2, targetUuid.toString()); ps.setString(3, permission); ps.executeUpdate();
                }
            }
        } catch (java.sql.SQLException e) {
            KsPluginBridge.sendJson(exchange, 500, "{\"error\":\"设置权限失败: " + e.getMessage() + "\"}"); return;
        }
        KsPluginBridge.sendJson(exchange, 200, gson.toJson(Map.of("message", enabled ? "银行权限已授予" : "银行权限已撤销")));
        auditLog("BANK_PERMISSION_SET", session.playerUuid.toString(), session.playerName, "bank", bankId,
            (enabled ? "授予 " : "撤销 ") + playerUuid + " 权限: " + permission);
    }

    // ==================== Enterprise Members / Permissions ====================

    private void handleEnterpriseMembers(HttpExchange exchange, String query) throws IOException {
        KsAuthManager.Session session = requireAuth(exchange);
        if (session == null) return;
        Map<String, String> params = KsPluginBridge.parseQuery(query);
        String enterpriseId = params.get("enterpriseId");
        if (enterpriseId == null) { KsPluginBridge.sendJson(exchange, 400, "{\"error\":\"缺少 enterpriseId\"}"); return; }
        List<Map<String, Object>> members = new ArrayList<>();
        try (var conn = plugin.ksCore().dataStore().getConnection()) {
            if (conn == null) { KsPluginBridge.sendJson(exchange, 500, "{\"error\":\"数据库未连接\"}"); return; }
            if (!canManageEnterpriseMembers(conn, enterpriseId, session)) {
                KsPluginBridge.sendJson(exchange, 403, "{\"error\":\"仅企业创始人或经理可查看成员\"}"); return;
            }
            {
                EcoWebBusinessSchema.ensureEnterpriseMembers(conn);
                conn.createStatement().executeUpdate("UPDATE ks_ent_members SET role='MEMBER' WHERE enterprise_id='" + enterpriseId.replace("'", "''") + "' AND role='EMPLOYEE'");
                ensureEnterpriseOwnerMembers(conn, enterpriseId);
                var rs = conn.createStatement().executeQuery(
                    "SELECT player_uuid, player_name, role, salary, joined_at FROM ks_ent_members WHERE enterprise_id='" + enterpriseId.replace("'", "''") + "'");
                while (rs.next()) {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("uuid", rs.getString("player_uuid"));
                    m.put("name", rs.getString("player_name"));
                    m.put("role", rs.getString("role"));
                    m.put("salary", rs.getDouble("salary"));
                    m.put("joinedAt", rs.getLong("joined_at"));
                    members.add(m);
                }
            }
        } catch (java.sql.SQLException e) {
            KsPluginBridge.sendJson(exchange, 500, "{\"error\":\"查询失败: " + e.getMessage() + "\"}");
            return;
        }
        KsPluginBridge.sendJson(exchange, 200, gson.toJson(Map.of("members", members, "count", members.size())));
    }

    @SuppressWarnings("unchecked")
    private void handleEnterpriseMemberAdd(HttpExchange exchange) throws IOException {
        KsAuthManager.Session session = requireAuth(exchange);
        if (session == null) return;
        Map<String, Object> req = gson.fromJson(KsPluginBridge.readBody(exchange), Map.class);
        if (req == null) { KsPluginBridge.sendJson(exchange, 400, "{\"error\":\"无效请求\"}"); return; }
        String enterpriseId = (String) req.get("enterpriseId");
        String playerUuid = (String) req.get("playerUuid");
        String playerName = (String) req.getOrDefault("playerName", "");
        String role = enterprisePermissions.normalizedRole(String.valueOf(req.getOrDefault("role", "EMPLOYEE")));
        double salary = toDouble(req.get("salary"), 0);
        if (enterpriseId == null || playerUuid == null) {
            KsPluginBridge.sendJson(exchange, 400, "{\"error\":\"缺少 enterpriseId/playerUuid\"}"); return;
        }
        if (!enterprisePermissions.editableRoles().contains(role) || salary < 0 || !Double.isFinite(salary)) {
            KsPluginBridge.sendJson(exchange, 400, "{\"error\":\"成员角色或薪资无效\"}"); return;
        }
        try (var conn = plugin.ksCore().dataStore().getConnection()) {
            if (conn == null) { KsPluginBridge.sendJson(exchange, 500, "{\"error\":\"数据库未连接\"}"); return; }
            if (!canManageEnterpriseMembers(conn, enterpriseId, session)) {
                KsPluginBridge.sendJson(exchange, 403, "{\"error\":\"只有企业所有者或管理员才能添加成员\"}"); return;
            }
            if (!session.isAdmin && !enterprisePermissions.canAssignRole(conn, enterpriseId, session.playerUuid, role)) {
                KsPluginBridge.sendJson(exchange, 403, "{\"error\":\"当前层级不能授予该岗位\"}"); return;
            }
            EcoWebBusinessSchema.ensureEnterpriseMembers(conn);
            UUID memberUuid = resolvePlayerReference(playerUuid);
            String canonicalName = resolvePlayerName(memberUuid);
            var existing = conn.createStatement().executeQuery("SELECT COUNT(*) FROM ks_ent_members WHERE enterprise_id='" + enterpriseId.replace("'", "''") + "' AND player_uuid='" + memberUuid + "'");
            if (existing.next() && existing.getInt(1) == 0) {
                var count = conn.createStatement().executeQuery("SELECT COUNT(*) FROM ks_ent_members WHERE enterprise_id='" + enterpriseId.replace("'", "''") + "'");
                int maxMembers = (int) getEconomicSetting("enterprise_max_members", 50);
                if (count.next() && count.getInt(1) >= maxMembers) {
                    KsPluginBridge.sendJson(exchange, 400, gson.toJson(Map.of("error", "企业成员名额已满，最大人数为 " + maxMembers))); return;
                }
            }
            upsertEnterpriseMember(conn, enterpriseId, memberUuid.toString(), canonicalName, role, salary,
                    System.currentTimeMillis() / 1000, true);
            updateEnterpriseMemberCount(conn, enterpriseId);
        } catch (java.sql.SQLException e) {
            KsPluginBridge.sendJson(exchange, 500, "{\"error\":\"添加失败: " + e.getMessage() + "\"}");
            return;
        }
        auditLog("ENT_MEMBER_ADD", session.playerUuid.toString(), session.playerName, "enterprise", enterpriseId,
            "添加成员 " + playerUuid + " 角色=" + role);
        KsPluginBridge.sendJson(exchange, 200, "{\"message\":\"成员已添加\"}");
    }

    @SuppressWarnings("unchecked")
    private void handleEnterpriseMemberRoleSet(HttpExchange exchange) throws IOException {
        KsAuthManager.Session session = requireAuth(exchange);
        if (session == null) return;
        Map<String, Object> req = gson.fromJson(KsPluginBridge.readBody(exchange), Map.class);
        String enterpriseId = req == null ? null : (String) req.get("enterpriseId");
        String playerUuid = req == null ? null : (String) req.get("playerUuid");
        String role = enterprisePermissions.normalizedRole(req == null ? null : String.valueOf(req.get("role")));
        if (enterpriseId == null || playerUuid == null || !enterprisePermissions.editableRoles().contains(role)) {
            KsPluginBridge.sendJson(exchange, 400, "{\"error\":\"无效的企业、成员或岗位\"}"); return;
        }
        try (var conn = plugin.ksCore().dataStore().getConnection()) {
            if (conn == null) { KsPluginBridge.sendJson(exchange, 500, "{\"error\":\"数据库未连接\"}"); return; }
            UUID target = resolvePlayerReference(playerUuid);
            if (enterprisePermissions.isOwner(conn, enterpriseId, target)) {
                KsPluginBridge.sendJson(exchange, 400, "{\"error\":\"不能调整企业所有者岗位\"}"); return;
            }
            if (!session.isAdmin && (!canManageEnterpriseMembers(conn, enterpriseId, session)
                    || !enterprisePermissions.canAssignRole(conn, enterpriseId, session.playerUuid, role))) {
                KsPluginBridge.sendJson(exchange, 403, "{\"error\":\"当前层级不能设置该岗位\"}"); return;
            }
            try (var ps = conn.prepareStatement("UPDATE ks_ent_members SET role=? WHERE enterprise_id=? AND player_uuid=?")) {
                ps.setString(1, role); ps.setString(2, enterpriseId); ps.setString(3, target.toString());
                if (ps.executeUpdate() == 0) { KsPluginBridge.sendJson(exchange, 404, "{\"error\":\"目标不是企业成员\"}"); return; }
            }
        } catch (Exception e) { KsPluginBridge.sendJson(exchange, 500, gson.toJson(Map.of("error", "岗位更新失败: " + e.getMessage()))); return; }
        auditLog("ENT_MEMBER_ROLE_SET", session.playerUuid.toString(), session.playerName, "enterprise", enterpriseId,
                "成员 " + playerUuid + " 岗位=" + role);
        KsPluginBridge.sendJson(exchange, 200, "{\"message\":\"成员岗位已更新\"}");
    }

    private void handleEnterpriseRolePermissions(HttpExchange exchange, String query) throws IOException {
        KsAuthManager.Session session = requireAuth(exchange);
        if (session == null) return;
        String enterpriseId = KsPluginBridge.parseQuery(query).get("enterpriseId");
        if (enterpriseId == null || enterpriseId.isBlank()) { KsPluginBridge.sendJson(exchange, 400, "{\"error\":\"缺少 enterpriseId\"}"); return; }
        Map<String, Set<String>> roles = new LinkedHashMap<>();
        for (String role : enterprisePermissions.editableRoles()) roles.put(role, new LinkedHashSet<>());
        try (var conn = plugin.ksCore().dataStore().getConnection()) {
            if (conn == null || enterprisePermissions.roleOf(conn, enterpriseId, session.playerUuid) == null) {
                KsPluginBridge.sendJson(exchange, 403, "{\"error\":\"不是该企业成员\"}"); return;
            }
            enterprisePermissions.ensureTemplates(conn, enterpriseId);
            try (var ps = conn.prepareStatement("SELECT role,permission FROM ks_ent_role_permissions WHERE enterprise_id=?")) {
                ps.setString(1, enterpriseId); var rs = ps.executeQuery();
                while (rs.next()) {
                    String role = enterprisePermissions.normalizedRole(rs.getString(1));
                    String permission = rs.getString(2);
                    if (roles.containsKey(role) && enterprisePermissions.allowedPermissions().contains(permission)) roles.get(role).add(permission);
                }
            }
        } catch (SQLException e) { KsPluginBridge.sendJson(exchange, 500, gson.toJson(Map.of("error", "岗位模板读取失败"))); return; }
        KsPluginBridge.sendJson(exchange, 200, gson.toJson(Map.of("roles", roles, "permissions", enterprisePermissions.allowedPermissions())));
    }

    @SuppressWarnings("unchecked")
    private void handleEnterpriseRolePermissionsSet(HttpExchange exchange) throws IOException {
        KsAuthManager.Session session = requireAuth(exchange);
        if (session == null) return;
        Map<String, Object> req = gson.fromJson(KsPluginBridge.readBody(exchange), Map.class);
        String enterpriseId = req == null ? null : (String) req.get("enterpriseId");
        String role = enterprisePermissions.normalizedRole(req == null ? null : String.valueOf(req.get("role")));
        List<?> requested = req != null && req.get("permissions") instanceof List<?> list ? list : List.of();
        Set<String> permissions = new LinkedHashSet<>();
        for (Object value : requested) if (value != null) permissions.add(String.valueOf(value).toUpperCase(Locale.ROOT));
        if (enterpriseId == null || !enterprisePermissions.editableRoles().contains(role)
                || !enterprisePermissions.allowedPermissions().containsAll(permissions)) {
            KsPluginBridge.sendJson(exchange, 400, "{\"error\":\"无效的岗位权限模板\"}"); return;
        }
        try (var conn = plugin.ksCore().dataStore().getConnection()) {
            if (conn == null || (!session.isAdmin && !enterprisePermissions.isOwner(conn, enterpriseId, session.playerUuid))) {
                KsPluginBridge.sendJson(exchange, 403, "{\"error\":\"只有企业所有者可编辑岗位模板\"}"); return;
            }
            try (var delete = conn.prepareStatement("DELETE FROM ks_ent_role_permissions WHERE enterprise_id=? AND role=?")) {
                delete.setString(1, enterpriseId); delete.setString(2, role); delete.executeUpdate();
            }
            try (var insert = conn.prepareStatement("INSERT INTO ks_ent_role_permissions (enterprise_id,role,permission) VALUES (?,?,?)")) {
                for (String permission : permissions) { insert.setString(1, enterpriseId); insert.setString(2, role); insert.setString(3, permission); insert.addBatch(); }
                if (permissions.isEmpty()) { insert.setString(1, enterpriseId); insert.setString(2, role); insert.setString(3, "__NONE__"); insert.addBatch(); }
                insert.executeBatch();
            }
        } catch (SQLException e) { KsPluginBridge.sendJson(exchange, 500, gson.toJson(Map.of("error", "岗位模板保存失败"))); return; }
        auditLog("ENT_ROLE_TEMPLATE_SET", session.playerUuid.toString(), session.playerName, "enterprise", enterpriseId,
                "岗位=" + role + " 权限=" + String.join(",", permissions));
        KsPluginBridge.sendJson(exchange, 200, "{\"message\":\"岗位权限模板已保存\"}");
    }

    @SuppressWarnings("unchecked")
    private void handleEnterpriseMemberRemove(HttpExchange exchange) throws IOException {
        KsAuthManager.Session session = requireAuth(exchange);
        if (session == null) return;
        Map<String, Object> req = gson.fromJson(KsPluginBridge.readBody(exchange), Map.class);
        if (req == null) { KsPluginBridge.sendJson(exchange, 400, "{\"error\":\"无效请求\"}"); return; }
        String enterpriseId = (String) req.get("enterpriseId");
        String playerUuid = (String) req.get("playerUuid");
        if (enterpriseId == null || playerUuid == null) {
            KsPluginBridge.sendJson(exchange, 400, "{\"error\":\"缺少 enterpriseId/playerUuid\"}"); return;
        }
        try (var conn = plugin.ksCore().dataStore().getConnection()) {
            if (conn == null) { KsPluginBridge.sendJson(exchange, 500, "{\"error\":\"数据库未连接\"}"); return; }
            if (!canManageEnterpriseMembers(conn, enterpriseId, session)) {
                KsPluginBridge.sendJson(exchange, 403, "{\"error\":\"只有企业所有者或管理员才能移除成员\"}"); return;
            }
            UUID memberUuid = resolvePlayerReference(playerUuid);
            if (isEnterpriseMemberOwner(conn, enterpriseId, memberUuid)) {
                KsPluginBridge.sendJson(exchange, 400, "{\"error\":\"不能通过成员接口移除企业所有者\"}"); return;
            }
            String targetRole = enterprisePermissions.roleOf(conn, enterpriseId, memberUuid);
            if (!session.isAdmin && !enterprisePermissions.canAssignRole(conn, enterpriseId, session.playerUuid, targetRole)) {
                KsPluginBridge.sendJson(exchange, 403, "{\"error\":\"当前层级不能移除该成员\"}"); return;
            }
            conn.createStatement().executeUpdate(
                "DELETE FROM ks_ent_members WHERE enterprise_id='" + enterpriseId.replace("'", "''") + "' AND player_uuid='" + memberUuid + "'");
            updateEnterpriseMemberCount(conn, enterpriseId);
        } catch (java.sql.SQLException e) {
            KsPluginBridge.sendJson(exchange, 500, "{\"error\":\"移除失败: " + e.getMessage() + "\"}");
            return;
        }
        auditLog("ENT_MEMBER_REMOVE", session.playerUuid.toString(), session.playerName, "enterprise", enterpriseId,
            "移除成员 " + playerUuid);
        KsPluginBridge.sendJson(exchange, 200, "{\"message\":\"成员已移除\"}");
    }

    /** 企业所有者或管理员校验：owner_uuids 包含当前玩家，或 session 为管理员 */
    private boolean isEnterpriseOwnerOrAdmin(java.sql.Connection conn, String enterpriseId, KsAuthManager.Session session) throws java.sql.SQLException {
        if (session.isAdmin) return true;
        try (var st = conn.createStatement();
             var rs = st.executeQuery("SELECT owner_uuids FROM ks_ent_enterprises WHERE id='" + enterpriseId.replace("'", "''") + "'")) {
            if (!rs.next()) return false;
            return ownerListContains(rs.getString("owner_uuids"), session.playerUuid);
        }
    }

    private boolean hasEnterprisePermission(java.sql.Connection conn, String enterpriseId,
                                            KsAuthManager.Session session, String permission) throws java.sql.SQLException {
        if (session.isAdmin) return true;
        var provider = plugin.enterpriseAccessProvider();
        return provider != null
                ? provider.hasPermission(enterpriseId, session.playerUuid, permission)
                : enterprisePermissions.hasPermission(conn, enterpriseId, session.playerUuid, permission);
    }

    private boolean canManageBankMembers(java.sql.Connection conn, String bankId, KsAuthManager.Session session) throws java.sql.SQLException {
        if (isBankOwnerOrAdmin(conn, bankId, session)) return true;
        // Prefer the Bank Extra access provider's explicit MANAGE_MEMBERS permission.
        var provider = plugin.bankAccessProvider();
        if (provider != null) return provider.hasPermission(bankId, session.playerUuid, "MANAGE_MEMBERS");
        // Fallback (provider not loaded): require an explicit MANAGE_MEMBERS grant, not a legacy MANAGER role.
        try (var ps = conn.prepareStatement(
                "SELECT COUNT(*) FROM ks_bank_permissions WHERE bank_id=? AND player_uuid=? AND permission='MANAGE_MEMBERS'")) {
            ps.setString(1, bankId); ps.setString(2, session.playerUuid.toString());
            var rs = ps.executeQuery();
            return rs.next() && rs.getInt(1) > 0;
        }
    }

    private boolean canManageEnterpriseMembers(java.sql.Connection conn, String enterpriseId, KsAuthManager.Session session) throws java.sql.SQLException {
        if (session.isAdmin) return true;
        var provider = plugin.enterpriseAccessProvider();
        return provider != null
                ? provider.hasPermission(enterpriseId, session.playerUuid, EnterprisePermissionService.MANAGE_MEMBERS)
                : enterprisePermissions.hasPermission(conn, enterpriseId, session.playerUuid,
                EnterprisePermissionService.MANAGE_MEMBERS);
    }

    private static boolean ownerListContains(String owners, UUID playerUuid) {
        if (owners == null || playerUuid == null) return false;
        return Arrays.stream(owners.split(","))
                .map(String::trim)
                .anyMatch(value -> value.equalsIgnoreCase(playerUuid.toString()));
    }

    private boolean isBankMemberOwner(java.sql.Connection conn, String bankId, UUID playerUuid) throws java.sql.SQLException {
        try (var ps = conn.prepareStatement("SELECT owner_uuids FROM ks_bank_banks WHERE id=?")) {
            ps.setString(1, bankId); var rs = ps.executeQuery();
            return rs.next() && ownerListContains(rs.getString(1), playerUuid);
        }
    }

    private boolean isEnterpriseMemberOwner(java.sql.Connection conn, String enterpriseId, UUID playerUuid) throws java.sql.SQLException {
        try (var ps = conn.prepareStatement("SELECT owner_uuids FROM ks_ent_enterprises WHERE id=?")) {
            ps.setString(1, enterpriseId); var rs = ps.executeQuery();
            return rs.next() && ownerListContains(rs.getString(1), playerUuid);
        }
    }

    private UUID resolvePlayerReference(String reference) {
        if (reference == null || reference.isBlank()) throw new IllegalArgumentException("玩家 ID 不能为空");
        try { return UUID.fromString(reference.trim()); } catch (IllegalArgumentException ignored) {}
        String value = reference.trim();
        try {
            UUID resolved = plugin.scheduler().callGlobal(() -> {
                for (org.bukkit.OfflinePlayer player : Bukkit.getOfflinePlayers()) {
                    if (player.getName() != null && player.getName().equalsIgnoreCase(value)) return player.getUniqueId();
                }
                return null;
            }, Duration.ofSeconds(3));
            if (resolved != null) return resolved;
        } catch (Exception e) {
            throw new IllegalArgumentException("无法解析玩家 ID: " + value);
        }
        throw new IllegalArgumentException("未找到玩家 ID: " + reference);
    }

    private String resolvePlayerName(UUID playerUuid) {
        try {
            String name = plugin.scheduler().callGlobal(
                    () -> Bukkit.getOfflinePlayer(playerUuid).getName(), Duration.ofSeconds(3));
            return name == null || name.isBlank() ? playerUuid.toString() : name;
        } catch (Exception e) {
            return playerUuid.toString();
        }
    }

    private double getEconomicSetting(String key, double fallback) {
        try (var conn = plugin.ksCore().dataStore().getConnection()) {
            if (conn == null) return fallback;
            try (var ps = conn.prepareStatement("SELECT config_value FROM ks_eco_settings WHERE config_key=?")) {
                ps.setString(1, key);
                var rs = ps.executeQuery();
                return rs.next() ? Double.parseDouble(rs.getString(1)) : fallback;
            }
        } catch (Exception ignored) {
            return fallback;
        }
    }

    private void handleEconomicSettingsGet(HttpExchange exchange) throws IOException {
        KsAuthManager.Session session = requireAdminAuth(exchange);
        if (session == null) return;
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("enterpriseMinCapital", getEconomicSetting("enterprise_min_capital", 50000));
        result.put("bankMinCapital", getBankSetting("bank_min_capital", 50000));
        result.put("enterpriseMaxOwners", getEconomicSetting("enterprise_max_owners", 4));
        result.put("enterpriseMaxMembers", getEconomicSetting("enterprise_max_members", 50));
        KsPluginBridge.sendJson(exchange, 200, gson.toJson(result));
    }

    @SuppressWarnings("unchecked")
    private void handleEconomicSettingsSet(HttpExchange exchange) throws IOException {
        KsAuthManager.Session session = requireAdminAuth(exchange);
        if (session == null) return;
        Map<String, Object> req = gson.fromJson(KsPluginBridge.readBody(exchange), Map.class);
        double enterpriseMin = req == null ? -1 : toDouble(req.get("enterpriseMinCapital"), -1);
        double bankMin = req == null ? -1 : toDouble(req.get("bankMinCapital"), -1);
        int maxOwners = req == null ? -1 : (int) toDouble(req.get("enterpriseMaxOwners"), -1);
        int maxMembers = req == null ? -1 : (int) toDouble(req.get("enterpriseMaxMembers"), -1);
        if (!Double.isFinite(enterpriseMin) || !Double.isFinite(bankMin) || enterpriseMin < 0 || bankMin < 0
                || maxOwners < 1 || maxMembers < 1 || maxOwners > maxMembers) {
            KsPluginBridge.sendJson(exchange, 400, "{\"error\":\"成立门槛必须是非负数字\"}"); return;
        }
        long now = System.currentTimeMillis() / 1000;
        try (var conn = plugin.ksCore().dataStore().getConnection()) {
            if (conn == null) { KsPluginBridge.sendJson(exchange, 500, "{\"error\":\"数据库未连接\"}"); return; }
            upsertEconomicSetting(conn, "enterprise_min_capital", Double.toString(enterpriseMin), now);
            upsertBankConfig(conn, "bank_min_capital", Double.toString(bankMin));
            upsertEconomicSetting(conn, "enterprise_max_owners", Integer.toString(maxOwners), now);
            upsertEconomicSetting(conn, "enterprise_max_members", Integer.toString(maxMembers), now);
        } catch (java.sql.SQLException e) {
            KsPluginBridge.sendJson(exchange, 500, "{\"error\":\"保存门槛失败\"}"); return;
        }
        auditLog("ECONOMIC_THRESHOLDS_SET", session.playerUuid.toString(), session.playerName, "settings", "economic",
                "企业=" + enterpriseMin + " 银行=" + bankMin + " 最大所有者=" + maxOwners + " 最大成员=" + maxMembers);
        KsPluginBridge.sendJson(exchange, 200, "{\"message\":\"成立门槛已保存\"}");
    }

    @SuppressWarnings("unchecked")
    private void handleEnterpriseAdminEdit(HttpExchange exchange) throws IOException {
        KsAuthManager.Session session = requireAdminAuth(exchange);
        if (session == null) return;
        Map<String, Object> req = gson.fromJson(KsPluginBridge.readBody(exchange), Map.class);
        String enterpriseId = req == null ? null : String.valueOf(req.get("enterpriseId"));
        String name = req == null || req.get("name") == null ? "" : req.get("name").toString().trim();
        String description = req == null || req.get("description") == null ? "" : req.get("description").toString().trim();
        String type = req == null || req.get("type") == null ? "PRIVATE" : req.get("type").toString().trim().toUpperCase(Locale.ROOT);
        String region = req == null || req.get("region") == null ? "" : req.get("region").toString().trim();
        String industry = req == null || req.get("industry") == null ? "OTHER" : req.get("industry").toString().trim().toUpperCase(Locale.ROOT);
        String ownerInput = req == null || req.get("ownerUuids") == null ? "" : req.get("ownerUuids").toString().trim();
        String status = req == null || req.get("status") == null ? "ACTIVE" : req.get("status").toString().trim().toUpperCase(Locale.ROOT);
        double registeredCapital = toDouble(req == null ? null : req.get("registeredCapital"), Double.NaN);
        double corporateBalance = toDouble(req == null ? null : req.get("corporateBalance"), Double.NaN);
        double dividendRate = toDouble(req == null ? null : req.get("dividendRate"), Double.NaN);
        int level = (int) toDouble(req == null ? null : req.get("level"), 1);
        if (enterpriseId == null || enterpriseId.isBlank() || name.length() < 2 || name.length() > 32
                || description.length() > 240 || region.length() > 64) {
            KsPluginBridge.sendJson(exchange, 400, "{\"error\":\"企业ID、名称或描述长度无效\"}"); return;
        }
        if (!Set.of("ACTIVE", "SUSPENDED", "FROZEN").contains(status)
                || !Set.of("PRIVATE", "STATE", "STATE_OWNED").contains(type)
                || !Set.of("OTHER", "INDUSTRY", "AGRICULTURE", "REAL_ESTATE").contains(industry)) {
            KsPluginBridge.sendJson(exchange, 400, "{\"error\":\"企业状态无效\"}"); return;
        }
        if (!Double.isFinite(registeredCapital) || registeredCapital < 0 || registeredCapital > 1_000_000_000_000d
                || !Double.isFinite(corporateBalance) || corporateBalance < 0 || corporateBalance > 1_000_000_000_000d
                || !Double.isFinite(dividendRate) || dividendRate < 0 || dividendRate > 100) {
            KsPluginBridge.sendJson(exchange, 400, "{\"error\":\"资本、公户余额或分红比例无效\"}"); return;
        }
        LinkedHashSet<String> owners = new LinkedHashSet<>();
        for (String value : ownerInput.split(",")) {
            String owner = value.trim();
            if (owner.isEmpty()) continue;
            if ("SYSTEM".equalsIgnoreCase(owner) && Set.of("STATE", "STATE_OWNED").contains(type)) owners.add("SYSTEM");
            else {
                try { owners.add(UUID.fromString(owner).toString()); }
                catch (IllegalArgumentException e) { KsPluginBridge.sendJson(exchange, 400, "{\"error\":\"所有者必须是有效 UUID；国有企业可使用 SYSTEM\"}"); return; }
            }
        }
        if (owners.isEmpty() || owners.size() > (int) getEconomicSetting("enterprise_max_owners", 4)) {
            KsPluginBridge.sendJson(exchange, 400, "{\"error\":\"所有者数量无效\"}"); return;
        }
        if (level < 1 || level > plugin.enterpriseLevelManager().getMaxLevel()) {
            KsPluginBridge.sendJson(exchange, 400, gson.toJson(Map.of("error", "企业等级必须在 1-" + plugin.enterpriseLevelManager().getMaxLevel() + " 之间"))); return;
        }
        try (var conn = plugin.ksCore().dataStore().getConnection()) {
            if (conn == null) { KsPluginBridge.sendJson(exchange, 500, "{\"error\":\"数据库未连接\"}"); return; }
            conn.setAutoCommit(false);
            try {
                double oldBalance = 0;
                String bankId = "CORP-BANK";
                try (var account = conn.prepareStatement("SELECT bank_id,balance FROM ks_ent_corporate_accounts WHERE enterprise_id=?")) {
                    account.setString(1, enterpriseId);
                    try (var rs = account.executeQuery()) {
                        if (rs.next()) { bankId = rs.getString("bank_id"); oldBalance = rs.getDouble("balance"); }
                    }
                }
                try (var ps = conn.prepareStatement("UPDATE ks_ent_enterprises SET name=?,description=?,type=?,owner_uuids=?,registered_capital=?,current_assets=?,region=?,industry=?,dividend_rate=?,status=? WHERE id=?")) {
                    ps.setString(1, name); ps.setString(2, description); ps.setString(3, type);
                    ps.setString(4, String.join(",", owners)); ps.setDouble(5, registeredCapital);
                    ps.setDouble(6, corporateBalance); ps.setString(7, region); ps.setString(8, industry);
                    ps.setDouble(9, dividendRate); ps.setString(10, status); ps.setString(11, enterpriseId);
                    if (ps.executeUpdate() != 1) throw new java.sql.SQLException("企业不存在");
                }
                long now = System.currentTimeMillis() / 1000;
                setCorporateAccountBalance(conn, enterpriseId,
                        bankId == null || bankId.isBlank() ? "CORP-BANK" : bankId, corporateBalance, now);
                try (var bank = conn.prepareStatement("UPDATE ks_bank_banks SET total_assets=MAX(total_assets+?,0) WHERE id=?")) {
                    bank.setDouble(1, corporateBalance - oldBalance); bank.setString(2, bankId == null || bankId.isBlank() ? "CORP-BANK" : bankId); bank.executeUpdate();
                }
                try (var demote = conn.prepareStatement("UPDATE ks_ent_members SET role='EMPLOYEE' WHERE enterprise_id=? AND role='OWNER'")) {
                    demote.setString(1, enterpriseId); demote.executeUpdate();
                }
                for (String owner : owners) {
                    if ("SYSTEM".equals(owner)) continue;
                    upsertEnterpriseMember(conn, enterpriseId, owner, owner, "OWNER", 0, now, false);
                }
                try (var clearShares = conn.prepareStatement("DELETE FROM ks_ent_dividend_shares WHERE enterprise_id=?")) {
                    clearShares.setString(1, enterpriseId); clearShares.executeUpdate();
                }
                updateEnterpriseMemberCount(conn, enterpriseId);
                conn.commit();
            } catch (Exception e) {
                conn.rollback();
                throw new java.sql.SQLException("企业资料事务失败", e);
            } finally {
                try { conn.setAutoCommit(true); } catch (java.sql.SQLException ignored) {}
            }
        } catch (java.sql.SQLException e) {
            KsPluginBridge.sendJson(exchange, 500, gson.toJson(Map.of("error", "保存企业信息失败: " + e.getMessage()))); return;
        }
        if (!plugin.enterpriseLevelManager().setLevel(enterpriseId, level, session.playerUuid.toString(), session.playerName)) {
            KsPluginBridge.sendJson(exchange, 500, "{\"error\":\"保存企业等级失败\"}"); return;
        }
        auditLog("ENTERPRISE_ADMIN_EDIT", session.playerUuid.toString(), session.playerName, "enterprise", enterpriseId,
                "名称=" + name + " | 类型=" + type + " | 所有者=" + owners.size() + " | 注册资本=" + registeredCapital
                        + " | 公户=" + corporateBalance + " | 地区=" + region + " | 行业=" + industry
                        + " | 分红比例=" + dividendRate + "% | 状态=" + status + " | 等级=" + level);
        KsPluginBridge.sendJson(exchange, 200, "{\"message\":\"企业资料已保存\"}");
    }

    private double getBankSetting(String key, double fallback) {
        try (var conn = plugin.ksCore().dataStore().getConnection();
             var ps = conn == null ? null : conn.prepareStatement("SELECT config_value FROM ks_bank_cb_config WHERE config_key=?")) {
            if (ps == null) return fallback;
            ps.setString(1, key);
            var rs = ps.executeQuery();
            return rs.next() ? Double.parseDouble(rs.getString(1)) : fallback;
        } catch (Exception ignored) {
            return fallback;
        }
    }

    private static void upsertBankConfig(java.sql.Connection conn, String key, String value) throws java.sql.SQLException {
        PortableSqlMutation.upsert(conn,
                "UPDATE ks_bank_cb_config SET config_value=? WHERE config_key=?",
                ps -> { ps.setString(1, value); ps.setString(2, key); },
                "INSERT INTO ks_bank_cb_config (config_key,config_value) VALUES (?,?)",
                ps -> { ps.setString(1, key); ps.setString(2, value); });
    }

    private static void upsertEconomicSetting(
            java.sql.Connection conn, String key, String value, long updatedAt) throws java.sql.SQLException {
        PortableSqlMutation.upsert(conn,
                "UPDATE ks_eco_settings SET config_value=?,updated_at=? WHERE config_key=?",
                ps -> { ps.setString(1, value); ps.setLong(2, updatedAt); ps.setString(3, key); },
                "INSERT INTO ks_eco_settings (config_key,config_value,updated_at) VALUES (?,?,?)",
                ps -> { ps.setString(1, key); ps.setString(2, value); ps.setLong(3, updatedAt); });
    }

    private static boolean ensureCorporateAccount(
            java.sql.Connection conn, String enterpriseId, String bankId, double balance, long updatedAt)
            throws java.sql.SQLException {
        return PortableSqlMutation.insertIfAbsent(conn,
                "SELECT 1 FROM ks_ent_corporate_accounts WHERE enterprise_id=?",
                ps -> ps.setString(1, enterpriseId),
                "INSERT INTO ks_ent_corporate_accounts (enterprise_id,bank_id,balance,updated_at) VALUES (?,?,?,?)",
                ps -> {
                    ps.setString(1, enterpriseId); ps.setString(2, bankId);
                    ps.setDouble(3, balance); ps.setLong(4, updatedAt);
                });
    }

    private static void setCorporateAccountBalance(
            java.sql.Connection conn, String enterpriseId, String bankId, double balance, long updatedAt)
            throws java.sql.SQLException {
        PortableSqlMutation.upsert(conn,
                "UPDATE ks_ent_corporate_accounts SET balance=?,updated_at=? WHERE enterprise_id=?",
                ps -> { ps.setDouble(1, balance); ps.setLong(2, updatedAt); ps.setString(3, enterpriseId); },
                "INSERT INTO ks_ent_corporate_accounts (enterprise_id,bank_id,balance,updated_at) VALUES (?,?,?,?)",
                ps -> {
                    ps.setString(1, enterpriseId); ps.setString(2, bankId);
                    ps.setDouble(3, balance); ps.setLong(4, updatedAt);
                });
    }

    private static void upsertEnterpriseMember(java.sql.Connection conn, String enterpriseId, String playerUuid,
            String playerName, String role, double salary, long joinedAt, boolean updateProfile)
            throws java.sql.SQLException {
        String updateSql = updateProfile
                ? "UPDATE ks_ent_members SET player_name=?,role=?,salary=? WHERE enterprise_id=? AND player_uuid=?"
                : "UPDATE ks_ent_members SET role=? WHERE enterprise_id=? AND player_uuid=?";
        PortableSqlMutation.upsert(conn,
                updateSql,
                updateProfile
                        ? ps -> {
                            ps.setString(1, playerName); ps.setString(2, role); ps.setDouble(3, salary);
                            ps.setString(4, enterpriseId); ps.setString(5, playerUuid);
                        }
                        : ps -> { ps.setString(1, role); ps.setString(2, enterpriseId); ps.setString(3, playerUuid); },
                "INSERT INTO ks_ent_members (enterprise_id,player_uuid,player_name,role,salary,joined_at) VALUES (?,?,?,?,?,?)",
                ps -> {
                    ps.setString(1, enterpriseId); ps.setString(2, playerUuid); ps.setString(3, playerName);
                    ps.setString(4, role); ps.setDouble(5, salary); ps.setLong(6, joinedAt);
                });
    }

    private static void upsertBankMember(java.sql.Connection conn, String bankId, String playerUuid,
            String playerName, String role, long joinedAt, boolean updateProfile) throws java.sql.SQLException {
        String updateSql = updateProfile
                ? "UPDATE ks_bank_members SET player_name=?,role=? WHERE bank_id=? AND player_uuid=?"
                : "UPDATE ks_bank_members SET role=? WHERE bank_id=? AND player_uuid=?";
        PortableSqlMutation.upsert(conn,
                updateSql,
                updateProfile
                        ? ps -> {
                            ps.setString(1, playerName); ps.setString(2, role);
                            ps.setString(3, bankId); ps.setString(4, playerUuid);
                        }
                        : ps -> { ps.setString(1, role); ps.setString(2, bankId); ps.setString(3, playerUuid); },
                "INSERT INTO ks_bank_members (bank_id,player_uuid,player_name,role,joined_at) VALUES (?,?,?,?,?)",
                ps -> {
                    ps.setString(1, bankId); ps.setString(2, playerUuid); ps.setString(3, playerName);
                    ps.setString(4, role); ps.setLong(5, joinedAt);
                });
    }

    private static void upsertEnterprisePermission(java.sql.Connection conn, String enterpriseId, String playerUuid,
            String permission, String grantedBy, long grantedAt) throws java.sql.SQLException {
        PortableSqlMutation.upsert(conn,
                "UPDATE ks_ent_permissions SET granted_by=?,granted_at=? WHERE enterprise_id=? AND player_uuid=? AND permission=?",
                ps -> {
                    ps.setString(1, grantedBy); ps.setLong(2, grantedAt); ps.setString(3, enterpriseId);
                    ps.setString(4, playerUuid); ps.setString(5, permission);
                },
                "INSERT INTO ks_ent_permissions (enterprise_id,player_uuid,permission,granted_by,granted_at) VALUES (?,?,?,?,?)",
                ps -> {
                    ps.setString(1, enterpriseId); ps.setString(2, playerUuid); ps.setString(3, permission);
                    ps.setString(4, grantedBy); ps.setLong(5, grantedAt);
                });
    }

    private static void upsertBankPermission(java.sql.Connection conn, String bankId, String playerUuid,
            String permission, String grantedBy, long grantedAt) throws java.sql.SQLException {
        PortableSqlMutation.upsert(conn,
                "UPDATE ks_bank_permissions SET granted_by=?,granted_at=? WHERE bank_id=? AND player_uuid=? AND permission=?",
                ps -> {
                    ps.setString(1, grantedBy); ps.setLong(2, grantedAt); ps.setString(3, bankId);
                    ps.setString(4, playerUuid); ps.setString(5, permission);
                },
                "INSERT INTO ks_bank_permissions (bank_id,player_uuid,permission,granted_by,granted_at) VALUES (?,?,?,?,?)",
                ps -> {
                    ps.setString(1, bankId); ps.setString(2, playerUuid); ps.setString(3, permission);
                    ps.setString(4, grantedBy); ps.setLong(5, grantedAt);
                });
    }

    private static void upsertBankRate(java.sql.Connection conn, String bankId, Double loanRate,
            Double depositRate, long updatedAt) throws java.sql.SQLException {
        if (loanRate != null) {
            PortableSqlMutation.upsert(conn,
                    "UPDATE ks_bank_rates SET loan_rate=?,updated_at=? WHERE bank_id=?",
                    ps -> { ps.setDouble(1, loanRate); ps.setLong(2, updatedAt); ps.setString(3, bankId); },
                    "INSERT INTO ks_bank_rates (bank_id,loan_rate,deposit_rate,updated_at) VALUES (?,?,?,?)",
                    ps -> { ps.setString(1, bankId); ps.setDouble(2, loanRate); ps.setDouble(3, 0.01d); ps.setLong(4, updatedAt); });
        }
        if (depositRate != null) {
            PortableSqlMutation.upsert(conn,
                    "UPDATE ks_bank_rates SET deposit_rate=?,updated_at=? WHERE bank_id=?",
                    ps -> { ps.setDouble(1, depositRate); ps.setLong(2, updatedAt); ps.setString(3, bankId); },
                    "INSERT INTO ks_bank_rates (bank_id,loan_rate,deposit_rate,updated_at) VALUES (?,?,?,?)",
                    ps -> { ps.setString(1, bankId); ps.setDouble(2, 0.05d); ps.setDouble(3, depositRate); ps.setLong(4, updatedAt); });
        }
    }

    private void ensureEnterpriseOwnerMembers(java.sql.Connection conn, String enterpriseId, List<UUID> owners, long now) throws java.sql.SQLException {
        for (UUID owner : owners) {
            var player = Bukkit.getOfflinePlayer(owner);
            String playerName = player.getName() == null ? owner.toString() : player.getName();
            upsertEnterpriseMember(conn, enterpriseId, owner.toString(), playerName, "OWNER", 0, now, false);
        }
        updateEnterpriseMemberCount(conn, enterpriseId);
    }

    private void ensureEnterpriseOwnerMembers(java.sql.Connection conn, String enterpriseId) throws java.sql.SQLException {
        try (var ps = conn.prepareStatement("SELECT owner_uuids FROM ks_ent_enterprises WHERE id=?")) {
            ps.setString(1, enterpriseId);
            var rs = ps.executeQuery();
            if (!rs.next()) return;
            ensureEnterpriseOwnerMembers(conn, enterpriseId, parseOwnerList(rs.getString(1)), System.currentTimeMillis() / 1000);
        }
    }

    private void ensureBankOwnerMembers(java.sql.Connection conn, String bankId) throws java.sql.SQLException {
        try (var ps = conn.prepareStatement("SELECT owner_uuids FROM ks_bank_banks WHERE id=?")) {
            ps.setString(1, bankId);
            var rs = ps.executeQuery();
            if (!rs.next()) return;
            long now = System.currentTimeMillis() / 1000;
            for (UUID owner : parseOwnerList(rs.getString(1))) {
                var player = Bukkit.getOfflinePlayer(owner);
                String playerName = player.getName() == null ? owner.toString() : player.getName();
                upsertBankMember(conn, bankId, owner.toString(), playerName, "OWNER", now, false);
            }
        }
    }

    private static List<UUID> parseOwnerList(String owners) {
        List<UUID> result = new ArrayList<>();
        if (owners == null) return result;
        for (String owner : owners.split(",")) {
            try { result.add(UUID.fromString(owner.trim())); } catch (IllegalArgumentException ignored) {}
        }
        return result;
    }

    private void updateEnterpriseMemberCount(java.sql.Connection conn, String enterpriseId) throws java.sql.SQLException {
        try (var ps = conn.prepareStatement(
                "UPDATE ks_ent_enterprises SET employee_count=(SELECT COUNT(*) FROM ks_ent_members WHERE enterprise_id=?) WHERE id=?")) {
            ps.setString(1, enterpriseId);
            ps.setString(2, enterpriseId);
            ps.executeUpdate();
        }
    }

    // ==================== Page Builder: Price Configurator ====================

    private String buildPriceConfigHtml() {
        return """
        <!DOCTYPE html><html lang="zh-CN"><head><meta charset="UTF-8">
        <title>官方定价配置 | ks-Eco</title>""" + BASE_STYLE + """
        <style>.item-row{cursor:pointer;padding:4px 8px;border-bottom:1px solid #2a2a4a;display:flex;align-items:center;}
        .item-row:hover{background:#1a1a3e;}
        .item-row input{width:80px;padding:4px;background:#0a0a1e;border:1px solid #2a2a4a;color:#e0e0e0;border-radius:4px;text-align:right;}
        .item-row .mat{flex:1;font-size:0.85em;}
        .prices-grid{max-height:70vh;overflow-y:auto;}
        .filter-row{display:flex;gap:8px;margin-bottom:12px;align-items:center;}
        </style></head><body>
        """ + NAV_HTML + """
        <h1>🏷 官方定价配置</h1>
        <div class="card">
        <p style="color:#aaa;margin-bottom:8px;">设置所有物品的官方收购价（官方只收购，不直售 — 直售已由盲盒系统取代）。留空或填0表示不设官方价。</p>
        <div class="filter-row">
        <input id="searchBox" class="search-box" placeholder="搜索物品..." style="flex:1;" oninput="filterItems()"/>
        <button class="btn" onclick="loadPrices()">刷新价格</button>
        <button class="btn btn-danger" onclick="saveAllPrices()">💾 批量保存</button>
        </div>
        <div class="stats" style="margin-bottom:12px;">
        <span>共 <b id="itemCount">0</b> 件物品 | 已定价 <b id="pricedCount">0</b> 件</span>
        </div>
        <div id="msg"></div>
        <div class="prices-grid" id="pricesGrid"></div>
        </div>
        <script>
        var ALL_ITEMS=[], OFFICIAL={}, TOKEN='';
        // 从 URL 获取 token
        (function(){var m=location.search.match(/token=([^&]+)/);if(m)TOKEN=m[1];})();
        function msg(s,ok){var e=document.getElementById('msg');e.innerHTML='<div class="msg '+(ok?'msg-ok':'msg-err')+'">'+s+'</div>';setTimeout(function(){e.innerHTML='';},4000);}
        function loadItems(){fetch('/ks-Eco/api/prices/items').then(r=>r.json()).then(d=>{ALL_ITEMS=d.items||[];document.getElementById('itemCount').textContent=ALL_ITEMS.length;loadPrices();});}
        function loadPrices(){fetch('/ks-Eco/api/prices/official').then(r=>r.json()).then(d=>{var prices=d.prices||[];OFFICIAL={};prices.forEach(function(p){OFFICIAL[p.material]=p;});renderGrid();document.getElementById('pricedCount').textContent=prices.length;});}
        function renderGrid(filter){
        var html='<div style="display:grid;grid-template-columns:repeat(auto-fill,minmax(280px,1fr));gap:4px;">';
        var q=(filter||'').toLowerCase();
        var shown=0;
        ALL_ITEMS.forEach(function(item){
        if(q && item.id.toLowerCase().indexOf(q)<0)return;
        var off=OFFICIAL[item.id]||{};
        html+='<div class="item-row"><span class="mat" title="'+item.id+'">'+item.id.substring(item.id.lastIndexOf('_')+1)+'</span>';
        html+='<span style="font-size:0.7em;color:#666;">收</span><input id="buy_'+item.id+'" value="'+(off.buyPrice||'')+'" placeholder="收购价"/>';
        html+='</div>';
        shown++;
        });
        html+='</div>';
        document.getElementById('pricesGrid').innerHTML=html||'<p style="color:#666;">无匹配物品</p>';
        }
        function saveAllPrices(){
        var prices=[];
        ALL_ITEMS.forEach(function(item){
        var buy=document.getElementById('buy_'+item.id);
        var buyV=buy?parseFloat(buy.value)||0:0;
        if(buyV>0) prices.push({material:item.id,buyPrice:buyV,category:item.category});
        });
        if(prices.length===0){msg('请至少填写一个价格',false);return;}
        fetch('/ks-Eco/api/prices/official/save',{method:'POST',body:JSON.stringify({prices:prices}),headers:{'Authorization':'Bearer '+TOKEN}}).then(r=>r.json()).then(d=>{
        if(d.message){msg(d.message,true);loadPrices();}else msg(d.error||'失败',false);
        });
        }
        function filterItems(){renderGrid(document.getElementById('searchBox').value);}
        loadItems();
        </script></body></html>
        """;
    }

    // ==================== Page Builder: Tax ====================

    private String buildTaxHtml() {
        return """
        <!DOCTYPE html><html lang="zh-CN"><head><meta charset="UTF-8">
        <title>税法系统 | ks-Eco</title>""" + BASE_STYLE + """
        </head><body>
        """ + NAV_HTML + """
        <h1>💰 税法与宏观调控系统</h1>
        <div class="card" id="statsCard"><table><tr><td><div class="stat" id="totalTx">—</div><div class="stat-label">纳税笔数</div></td><td><div class="stat" id="totalCollected">—</div><div class="stat-label">税收总额</div></td><td><div class="stat" id="unpaidPenalties">—</div><div class="stat-label">未缴罚单</div></td><td><div class="stat" id="unpaidAmount">—</div><div class="stat-label">未缴罚金</div></td></tr></table></div>
        <div id="msg"></div>
        <h2>📊 动态税率管理（范围 0-1）</h2>
        <div class="card">
        <table id="rateTable"><thead><tr><th>税种</th><th>当前税率</th><th>新税率</th><th>操作</th></tr></thead><tbody></tbody></table>
        </div>
        <h2>⚠ 发出罚单</h2>
        <div class="card">
        <div class="form-row"><input id="penTarget" placeholder="目标UUID"/><input id="penName" placeholder="目标名称"/><select id="penType"><option value="TAX_EVASION">逃税</option><option value="CONTRACT_BREACH">违约</option><option value="FRAUD">欺诈</option></select><input id="penBase" placeholder="基数金额" type="number" step="1"/><input id="penReason" placeholder="原因"/><button class="btn btn-sm btn-danger" onclick="issuePenalty()">发出罚单</button></div>
        </div>
        <h2>📋 纳税记录</h2>
        <div class="card" style="max-height:250px;overflow-y:auto;"><table id="recordTable"><thead><tr><th>ID</th><th>纳税人</th><th>类别</th><th>基数</th><th>税率</th><th>税额</th><th>时间</th></tr></thead><tbody></tbody></table></div>
        <script>
        var TOKEN=(new URL(location)).searchParams.get('token')||'';
        function authHeaders(){return TOKEN?{Authorization:'Bearer '+TOKEN}:{};}
        var CATEGORIES=['MARKET_TRADE','OFFICIAL_TRADE','ENTERPRISE_SMALL','ENTERPRISE_MEDIUM','ENTERPRISE_LARGE','BANK_INTEREST','TAX_PENALTY'];
        var LABELS={MARKET_TRADE:'市场交易税（玩家间交易）',OFFICIAL_TRADE:'官方交易税（系统收售）',ENTERPRISE_SMALL:'小微企业税（注册资本<50万）',ENTERPRISE_MEDIUM:'中型企业税（注册资本50-500万）',ENTERPRISE_LARGE:'大型企业税（注册资本>500万）',BANK_INTEREST:'银行利息税',TAX_PENALTY:'税务罚款（滞纳金/逃税）'};
        // 企业注册资本阈值（可在下方自定义）
        var ENTERPRISE_THRESHOLDS={SMALL_MAX:500000,MEDIUM_MAX:5000000};
        function msg(s,ok){var e=document.getElementById('msg');e.innerHTML='<div class="msg '+(ok?'msg-ok':'msg-err')+'">'+s+'</div>';setTimeout(function(){e.innerHTML='';},4000);}
        function loadStats(){fetch('/ks-Eco/api/tax/stats').then(r=>r.json()).then(d=>{
        document.getElementById('totalTx').textContent=d.totalTransactions||0;
        document.getElementById('totalCollected').textContent=(d.totalCollected||0).toLocaleString();
        document.getElementById('unpaidPenalties').textContent=d.unpaidPenalties||0;
        document.getElementById('unpaidAmount').textContent=(d.unpaidAmount||0).toLocaleString();
        });}
        function loadRates(){fetch('/ks-Eco/api/tax/rates').then(r=>r.json()).then(d=>{
        var t='';CATEGORIES.forEach(function(c){
        var rate=d[c]!=null?d[c]:0;
        var label=LABELS[c];
        if(c==='ENTERPRISE_SMALL')label=LABELS[c].replace('50万',(ENTERPRISE_THRESHOLDS.SMALL_MAX/10000).toFixed(0)+'万');
        if(c==='ENTERPRISE_MEDIUM')label=LABELS[c].replace('50万',(ENTERPRISE_THRESHOLDS.SMALL_MAX/10000).toFixed(0)+'万').replace('500万',(ENTERPRISE_THRESHOLDS.MEDIUM_MAX/10000).toFixed(0)+'万');
        if(c==='ENTERPRISE_LARGE')label=LABELS[c].replace('500万',(ENTERPRISE_THRESHOLDS.MEDIUM_MAX/10000).toFixed(0)+'万');
        t+='<tr><td>'+label+'</td><td>'+(rate*100).toFixed(2)+'%</td><td><input id="rate_'+c+'" placeholder="0-1" type="number" step="0.001" min="0" max="1" style="width:80px;"/></td><td><button class="btn btn-sm" onclick="setRate(\\''+c+'\\')">更新</button></td></tr>';
        });
        document.getElementById('rateTable').querySelector('tbody').innerHTML=t;
        });}
        function setRate(c){var v=document.getElementById('rate_'+c).value;var r=parseFloat(v);if(isNaN(r)||r<0||r>1){msg('税率必须在 0-1 范围内',false);return;}fetch('/ks-Eco/api/tax/rates/set',{method:'POST',headers:authHeaders(),body:JSON.stringify({category:c,rate:r})}).then(r=>r.json()).then(d=>{if(d.message){msg(d.message,true);loadRates();}else msg(d.error||'失败',false);});}
        function loadRecords(){fetch('/ks-Eco/api/tax/records').then(r=>r.json()).then(d=>{
        var t='';(d.records||[]).forEach(function(r){t+='<tr><td>'+r.id+'</td><td>'+r.payer_name+'</td><td>'+r.category+'</td><td>'+r.base_amount+'</td><td>'+r.tax_rate+'</td><td>'+r.tax_amount+'</td><td>'+new Date(r.collected_at*1000).toLocaleString()+'</td></tr>';});
        document.getElementById('recordTable').querySelector('tbody').innerHTML=t;
        });}
        function issuePenalty(){var u=document.getElementById('penTarget').value,n=document.getElementById('penName').value,t=document.getElementById('penType').value,b=document.getElementById('penBase').value,r=document.getElementById('penReason').value;fetch('/ks-Eco/api/tax/penalty/issue',{method:'POST',headers:authHeaders(),body:JSON.stringify({targetUuid:u,targetName:n,penaltyType:t,baseAmount:parseFloat(b)||0,reason:r})}).then(r=>r.json()).then(d=>{if(d.id){msg('罚单已发出: '+d.id,true);loadStats();}else msg(d.error||'失败',false);});}
        loadStats();loadRates();loadRecords();
        setInterval(loadStats,10000);
        </script></body></html>
        """;
    }

    // ================================================================
    // Wealth Rankings
    // ================================================================

    /** 管理员直接设置玩家余额 */
    @SuppressWarnings("unchecked")
    private void handleAdminSetBalance(HttpExchange exchange) throws IOException {
        KsAuthManager.Session session = requireAdminAuth(exchange);
        if (session == null) return;
        Map<String, Object> req = gson.fromJson(KsPluginBridge.readBody(exchange), Map.class);
        if (req == null) { KsPluginBridge.sendJson(exchange, 400, "{\"error\":\"无效请求\"}"); return; }
        String targetUuid = (String) req.getOrDefault("uuid", session.playerUuid.toString());
        double balance = toDouble(req.get("balance"), 0);
        boolean relative = (boolean) req.getOrDefault("relative", false);
        if (!Double.isFinite(balance)) { KsPluginBridge.sendJson(exchange, 400, "{\"error\":\"余额无效\"}"); return; }
        UUID targetPlayer = resolvePlayerReference(targetUuid);
        double oldBal = walletBalance(targetPlayer);
        boolean changed;
        if (relative) {
            changed = balance >= 0 ? depositWallet(targetPlayer, balance) : withdrawWallet(targetPlayer, -balance);
        } else {
            boolean withdrewOriginal = oldBal <= 0 || withdrawWallet(targetPlayer, oldBal);
            changed = withdrewOriginal;
            if (changed && balance > 0) changed = depositWallet(targetPlayer, balance);
            if (!changed && withdrewOriginal && oldBal > 0) depositWallet(targetPlayer, oldBal);
        }
        if (!changed) { KsPluginBridge.sendJson(exchange, 500, "{\"error\":\"余额更新失败\"}"); return; }
        double newBal = walletBalance(targetPlayer);
        KsPluginBridge.sendJson(exchange, 200, gson.toJson(Map.of(
            "message", "余额已更新", "uuid", targetUuid, "oldBalance", oldBal, "newBalance", newBal)));
    }

    private void handlePlayerRankings(HttpExchange exchange) throws IOException {
        refreshPlayerRankingSnapshotIfStale();
        List<Map<String, Object>> rankings = playerRankingSnapshot;
        KsPluginBridge.sendJson(exchange, 200, gson.toJson(Map.of("rankings", rankings, "count", rankings.size())));
    }

    private void handleEnterpriseRankings(HttpExchange exchange) throws IOException {
        List<Map<String, Object>> rankings = new ArrayList<>();
        queryRows(rankings,
                "SELECT e.id, e.name, COALESCE(e.description,'') AS description, e.type, e.registered_capital, e.current_assets, " +
                "COUNT(DISTINCT m.player_uuid) AS player_count " +
                "FROM ks_ent_enterprises e LEFT JOIN ks_ent_members m ON m.enterprise_id=e.id " +
                "WHERE e.status='ACTIVE' GROUP BY e.id ORDER BY e.current_assets DESC, e.registered_capital DESC LIMIT 50", null);
        KsPluginBridge.sendJson(exchange, 200, gson.toJson(Map.of("rankings", rankings, "count", rankings.size())));
    }

    private void handleBankRankings(HttpExchange exchange) throws IOException {
        List<Map<String, Object>> rankings = new ArrayList<>();
        queryRows(rankings, """
                SELECT b.id,b.name,b.type,b.total_assets,b.owner_uuids,
                  COALESCE((SELECT SUM(a.balance) FROM ks_bank_accounts a WHERE a.bank_id=b.id),0) AS deposit_volume,
                  b.total_assets-COALESCE((SELECT SUM(a.balance) FROM ks_bank_accounts a WHERE a.bank_id=b.id),0) AS net_assets,
                  COALESCE((SELECT COUNT(*) FROM ks_bank_enterprise_loans el WHERE el.bank_id=b.id AND el.status IN ('ACTIVE','OVERDUE','DEFAULTED','PAID')),0) AS served_enterprises,
                  COALESCE((SELECT SUM(el.remaining) FROM ks_bank_enterprise_loans el WHERE el.bank_id=b.id AND el.status='DEFAULTED'),0) AS bad_debt,
                  COALESCE((SELECT SUM(el.remaining) FROM ks_bank_enterprise_loans el WHERE el.bank_id=b.id),0)
                    + COALESCE((SELECT SUM(pl.remaining) FROM ks_bank_loans pl WHERE pl.bank_id=b.id),0) AS total_credit
                FROM ks_bank_banks b WHERE b.status='ACTIVE' ORDER BY net_assets DESC LIMIT 50
                """, null);
        for (Map<String, Object> row : rankings) {
            double badDebt = row.get("bad_debt") instanceof Number n ? n.doubleValue() : 0;
            double totalCredit = row.get("total_credit") instanceof Number n ? n.doubleValue() : 0;
            row.put("badDebtRate", totalCredit <= 0 ? 0 : badDebt / totalCredit);
        }
        KsPluginBridge.sendJson(exchange, 200, gson.toJson(Map.of("rankings", rankings, "count", rankings.size())));
    }
}
