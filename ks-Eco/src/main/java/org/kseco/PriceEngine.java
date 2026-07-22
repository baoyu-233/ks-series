package org.kseco;

import org.bukkit.Material;
import org.kseco.database.PortableSqlMutation;
import org.kseco.database.PriceEngineSchema;

import java.sql.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 动态定价引擎。
 *
 * 官方只收购、不直售（直售已由盲盒系统取代）。收购价由两个物理隔离的分量叠加：
 *
 * 1. supplyPressure — 真实供需压力（∈[-1,1]，双向）。从 ks_eco_trades 交易流水表按滑动
 *    时间窗口统计"近期真实卖给官方的量"相对"该物品历史日均卖出量"的偏离比例：
 *    近期卖得比平时多 = 供过于求 = 打折；近期卖得比平时少（没人卖，东西变少见了）= 供不应求 = 加价。
 *    随时间自然消退（旧记录滑出窗口 = 被市场消耗掉），不需要额外的衰减系数或定时任务。
 * 2. driftValue — 均值回归的随机游走（∈[-1,1]），模拟"市场自己在波动"。管理员可以给某个
 *    物品设置 trendBias 做"大手导向"：driftValue 不会瞬间跳变，而是每次刷新被慢慢拉过去，
 *    叠加随机扰动，看起来像自然波动而不是人为改价。
 *
 * totalOffset = clamp(driftValue×maxFluctuation − supplyPressure×maxFluctuation, ±maxFluctuation)
 * buyPrice    = basePrice × (1 + totalOffset)
 *
 * 真实供需压力永远压得住"大手导向"——避免"导向"变成无视真实供需的作弊开关。
 *
 * 测试模式（testModeEnabled）：开启后，`recordAdminTrade`（供 /kseco void-trade 和 web
 * simulate-trade 使用）只做纯预览计算，不修改真实价格状态；流水仍会写入但打上 is_test 标记，
 * 真实的供需压力/市场均价查询永远排除 is_test 行，避免测试数据污染真实定价。
 */
public final class PriceEngine {

    private final KsEco plugin;
    /** 内存缓存：material → PricePoint（当前价格快照） */
    private volatile Map<String, PricePoint> priceCache = new ConcurrentHashMap<>();
    /** 内存缓存：material → VolatilityState（漂移/导向/供需压力状态） */
    private volatile Map<String, VolatilityState> volatilityState = new ConcurrentHashMap<>();

    private final Random random = new Random();

    private volatile boolean volatilityEnabled;
    private volatile double maxFluctuation;
    private volatile int priceRefreshMinutes;
    private volatile boolean testModeEnabled;

    public PriceEngine(KsEco plugin) {
        this.plugin = plugin;
        ensureTables();
        loadGlobalSettings();
        // 初始化默认物品价格
        for (var item : plugin.ecoConfig().getDefaultBuyItems()) {
            priceCache.put(item.material(), new PricePoint(
                    item.basePrice(), item.basePrice(), item.basePrice()));
        }
        loadPersistedOfficialPrices();
        loadVolatilityStates();
    }

    private void ensureTables() {
        try (var conn = plugin.ksCore().dataStore().getConnection()) {
            if (conn == null) throw new SQLException("database unavailable");
            PriceEngineSchema.initialize(conn);
        } catch (SQLException failure) {
            throw new IllegalStateException("Dynamic price schema initialization failed", failure);
        }
    }

    private void loadGlobalSettings() {
        var cfg = plugin.ecoConfig();
        volatilityEnabled = cfg.isVolatilityEnabled();
        maxFluctuation = cfg.getVolatilityMaxFluctuation();
        priceRefreshMinutes = cfg.getPriceRefreshMinutes();
        testModeEnabled = false;
        try (var conn = plugin.ksCore().dataStore().getConnection()) {
            if (conn == null) return;
            try (var stmt = conn.createStatement()) {
                ResultSet rs = stmt.executeQuery("SELECT config_key,config_value FROM ks_eco_price_settings");
                while (rs.next()) {
                    String key = rs.getString(1);
                    String value = rs.getString(2);
                    switch (key) {
                        case "volatility_enabled" -> volatilityEnabled = Boolean.parseBoolean(value);
                        case "max_fluctuation" -> maxFluctuation = Double.parseDouble(value);
                        case "price_refresh_minutes" -> priceRefreshMinutes = Integer.parseInt(value);
                        case "test_mode_enabled" -> testModeEnabled = Boolean.parseBoolean(value);
                    }
                }
            }
        } catch (SQLException e) {
            plugin.getLogger().warning("读取市场波动全局设置失败: " + e.getMessage());
        }
    }

    private void loadVolatilityStates() {
        loadVolatilityStates(priceCache, volatilityState);
    }

    private void loadVolatilityStates(
            Map<String, PricePoint> prices,
            Map<String, VolatilityState> states
    ) {
        try (var conn = plugin.ksCore().dataStore().getConnection()) {
            if (conn == null) return;
            try (var stmt = conn.createStatement()) {
                ResultSet rs = stmt.executeQuery("SELECT material, drift_value, trend_bias, last_buy_price,"
                        + "current_buy_price,market_average FROM ks_eco_price_volatility");
                while (rs.next()) {
                    String mat = rs.getString(1).toUpperCase();
                    states.put(mat, new VolatilityState(
                            rs.getDouble(2), rs.getDouble(3), 0.0, 0.0, rs.getDouble(4)));
                    PricePoint configured = prices.get(mat);
                    double current = rs.getDouble(5);
                    if (configured != null && current > 0.0d) {
                        prices.put(mat, new PricePoint(
                                configured.basePrice, current, Math.max(0.0d, rs.getDouble(6))));
                    }
                }
            }
        } catch (SQLException e) {
            plugin.getLogger().warning("读取物品波动状态失败: " + e.getMessage());
        }
    }

    private void persistSetting(String key, String value) {
        try (var conn = plugin.ksCore().dataStore().getConnection()) {
            if (conn == null) return;
            PortableSqlMutation.upsert(conn,
                    "UPDATE ks_eco_price_settings SET config_value=? WHERE config_key=?",
                    ps -> { ps.setString(1, value); ps.setString(2, key); },
                    "INSERT INTO ks_eco_price_settings (config_key,config_value) VALUES (?,?)",
                    ps -> { ps.setString(1, key); ps.setString(2, value); });
        } catch (SQLException e) {
            plugin.getLogger().warning("保存市场波动设置(" + key + ")失败: " + e.getMessage());
        }
    }

    private void persistGlobalSettings() {
        persistSetting("volatility_enabled", String.valueOf(volatilityEnabled));
        persistSetting("max_fluctuation", String.valueOf(maxFluctuation));
    }

    private void persistVolatilityState(String material, VolatilityState state) {
        try (var conn = plugin.ksCore().dataStore().getConnection()) {
            if (conn == null) return;
            upsertVolatility(conn, material, state, priceCache.get(material), System.currentTimeMillis() / 1000);
        } catch (SQLException e) {
            plugin.getLogger().warning("保存物品波动状态失败: " + e.getMessage());
        }
    }

    /** 注册/更新物品基础价格（从官方价格配置器调用）。 */
    public synchronized void registerItem(String material, double buyPrice) {
        if (material == null || material.isBlank() || !Double.isFinite(buyPrice) || buyPrice < 0) return;
        String mat = material.toUpperCase();
        if (buyPrice == 0.0d) {
            priceCache.remove(mat);
            return;
        }
        PricePoint old = priceCache.get(mat);
        priceCache.put(mat, new PricePoint(buyPrice, buyPrice, old != null ? old.marketAvg : buyPrice));
        volatilityState.putIfAbsent(mat, VolatilityState.EMPTY);
    }

    /** 持久化设置官方收购价；price=0 表示停止收购该材质。 */
    public synchronized boolean setOfficialBuyPrice(String material, double price) {
        if (material == null || material.isBlank() || !Double.isFinite(price)
                || price < 0 || price > 1_000_000_000_000d) return false;
        String mat = material.toUpperCase();
        try {
            org.bukkit.Material parsed = org.bukkit.Material.valueOf(mat);
            if (!parsed.isItem() || parsed.isAir() || parsed.isLegacy()) return false;
        } catch (IllegalArgumentException e) {
            return false;
        }
        try (var conn = plugin.ksCore().dataStore().getConnection()) {
            if (conn == null) return false;
            String category = parsedCategory(mat);
            long now = System.currentTimeMillis() / 1000;
            PortableSqlMutation.upsert(conn,
                    "UPDATE ks_official_prices SET buy_price=?,category=?,updated_at=? WHERE material=?",
                    ps -> { ps.setDouble(1, price); ps.setString(2, category); ps.setLong(3, now); ps.setString(4, mat); },
                    "INSERT INTO ks_official_prices (material,buy_price,category,updated_at) VALUES (?,?,?,?)",
                    ps -> { ps.setString(1, mat); ps.setDouble(2, price); ps.setString(3, category); ps.setLong(4, now); });
        } catch (SQLException e) {
            plugin.getLogger().warning("保存官方收购价失败: " + e.getMessage());
            return false;
        }
        registerItem(mat, price);
        plugin.publishCrossServerInvalidation("price", mat);
        return true;
    }

    /** Rebuilds all database-owned price state after a remote invalidation. */
    public void reloadSharedState() {
        loadGlobalSettings();
        Map<String, PricePoint> loadedPrices = new ConcurrentHashMap<>();
        for (var item : plugin.ecoConfig().getDefaultBuyItems()) {
            loadedPrices.put(item.material(), new PricePoint(
                    item.basePrice(), item.basePrice(), item.basePrice()));
        }
        loadPersistedOfficialPrices(loadedPrices);
        Map<String, VolatilityState> loadedStates = new ConcurrentHashMap<>();
        loadVolatilityStates(loadedPrices, loadedStates);
        priceCache = loadedPrices;
        volatilityState = loadedStates;
    }

    private void loadPersistedOfficialPrices() {
        loadPersistedOfficialPrices(priceCache);
    }

    private void loadPersistedOfficialPrices(Map<String, PricePoint> prices) {
        try (var conn = plugin.ksCore().dataStore().getConnection()) {
            if (conn == null) return;
            try (var ps = conn.prepareStatement("SELECT material,buy_price FROM ks_official_prices");
                 var rs = ps.executeQuery()) {
                while (rs.next()) registerItem(prices, rs.getString(1), rs.getDouble(2));
            }
        } catch (SQLException e) {
            plugin.getLogger().warning("读取官方收购价覆盖配置失败: " + e.getMessage());
        }
    }

    private static void registerItem(Map<String, PricePoint> prices, String material, double buyPrice) {
        if (material == null || material.isBlank() || !Double.isFinite(buyPrice) || buyPrice < 0) return;
        String mat = material.toUpperCase(Locale.ROOT);
        if (buyPrice == 0.0d) {
            prices.remove(mat);
            return;
        }
        PricePoint old = prices.get(mat);
        prices.put(mat, new PricePoint(buyPrice, buyPrice, old != null ? old.marketAvg : buyPrice));
    }

    private static String parsedCategory(String material) {
        try { return org.bukkit.Material.valueOf(material).isBlock() ? "方块" : "物品"; }
        catch (IllegalArgumentException ignored) { return ""; }
    }

    /** 获取所有在缓存的物品价格（用于管理查询/测试）。 */
    public Map<String, PricePoint> getAllPrices() {
        return Map.copyOf(priceCache);
    }

    /** 获取某物品当前的供需压力（-1=极端供不应求/加价，0=正常，1=极端供过于求/打折）。 */
    public double getSupplyPressure(String material) {
        VolatilityState state = volatilityState.get(material.toUpperCase());
        if (state == null) return 0.0;
        return computeSupplyPressure(state.recentQty, state.baseline);
    }

    /** 获取某物品当前的价格趋势：UP / DOWN / FLAT（对比上一次定时刷新前后的价格）。 */
    public String getTrend(String material) {
        String mat = material.toUpperCase();
        PricePoint pp = priceCache.get(mat);
        VolatilityState state = volatilityState.get(mat);
        if (pp == null || state == null) return "FLAT";
        double diff = pp.buyPrice - state.previousBuyPrice;
        if (Math.abs(diff) < 0.005) return "FLAT";
        return diff > 0 ? "UP" : "DOWN";
    }

    /**
     * 获取某物品的当前官方收购价。
     */
    public double getOfficialBuyPrice(String material) {
        PricePoint pp = priceCache.get(material.toUpperCase());
        return pp != null ? pp.buyPrice : 0.0;
    }

    /**
     * 获取某物品的市场均价（从交易历史计算，排除测试模式产生的流水）。
     */
    public double getMarketAveragePrice(String material) {
        try (Connection conn = plugin.ksCore().dataStore().getConnection()) {
            if (conn == null) return 0.0;

            try (PreparedStatement ps = conn.prepareStatement(
                    "SELECT AVG(unit_price), COUNT(*) FROM ks_eco_trades " +
                    "WHERE item_material = ? AND timestamp > ? AND (is_test IS NULL OR is_test = 0)")) {
                ps.setString(1, material.toUpperCase());
                ps.setLong(2, System.currentTimeMillis() / 1000 - 7 * 24 * 3600); // 过去7天
                ResultSet rs = ps.executeQuery();
                if (rs.next()) {
                    double avg = rs.getDouble(1);
                    return rs.getInt(2) > 0 ? avg : 0.0;
                }
            }
        } catch (SQLException e) {
            plugin.getLogger().warning("查询市场均价失败: " + e.getMessage());
        }
        return 0.0;
    }

    /**
     * 计算含潜影盒内容的物品总价值。
     */
    public double calculateTotalValue(org.bukkit.inventory.ItemStack item) {
        if (item == null || item.getType().isAir()) return 0.0;
        return plugin.marketValueService().officialBuyValue(item);
    }

    /**
     * 查询某物品的近期卖出量与自校准基线（一次查询，索引友好，排除测试流水）。
     * 返回 {recentQty, baseline}。
     */
    private double[] querySupplyStats(String material) {
        var cfg = plugin.ecoConfig();
        int windowHours = cfg.getVolatilityOversupplyWindowHours();
        int lookbackDays = cfg.getVolatilityBaselineLookbackDays();
        double defaultBaseline = cfg.getVolatilityDefaultBaselineQty();

        double recentQty = 0.0;
        double totalQty = 0.0;
        try (Connection conn = plugin.ksCore().dataStore().getConnection()) {
            if (conn == null) return new double[]{0.0, defaultBaseline};
            long now = System.currentTimeMillis() / 1000;
            long recentCutoff = now - windowHours * 3600L;
            long lookbackCutoff = now - lookbackDays * 86400L;
            try (PreparedStatement ps = conn.prepareStatement(
                    "SELECT SUM(CASE WHEN timestamp > ? THEN quantity ELSE 0 END), SUM(quantity) " +
                    "FROM ks_eco_trades WHERE item_material = ? AND trade_type LIKE '%SELL%' " +
                    "AND timestamp > ? AND (is_test IS NULL OR is_test = 0)")) {
                ps.setLong(1, recentCutoff);
                ps.setString(2, material.toUpperCase());
                ps.setLong(3, lookbackCutoff);
                ResultSet rs = ps.executeQuery();
                if (rs.next()) {
                    recentQty = rs.getDouble(1);
                    totalQty = rs.getDouble(2);
                }
            }
        } catch (SQLException e) {
            plugin.getLogger().warning("查询供需压力失败: " + e.getMessage());
            return new double[]{0.0, defaultBaseline};
        }
        double dailyAvg = totalQty / lookbackDays;
        double baseline = Math.max(dailyAvg * (windowHours / 24.0), defaultBaseline);
        return new double[]{recentQty, baseline};
    }

    /** 近期卖量偏离历史基线的比例，双向：>0 供过于求（打折），<0 供不应求（加价）。 */
    private double getMarketAveragePrice(Connection conn, String material) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT AVG(unit_price), COUNT(*) FROM ks_eco_trades " +
                "WHERE item_material = ? AND timestamp > ? AND (is_test IS NULL OR is_test = 0)")) {
            ps.setString(1, material.toUpperCase());
            ps.setLong(2, System.currentTimeMillis() / 1000 - 7 * 24 * 3600L);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) return 0.0;
                double avg = rs.getDouble(1);
                return rs.getInt(2) > 0 ? avg : 0.0;
            }
        }
    }

    private double[] querySupplyStats(Connection conn, String material) throws SQLException {
        var cfg = plugin.ecoConfig();
        int windowHours = cfg.getVolatilityOversupplyWindowHours();
        int lookbackDays = cfg.getVolatilityBaselineLookbackDays();
        double defaultBaseline = cfg.getVolatilityDefaultBaselineQty();
        long now = System.currentTimeMillis() / 1000;
        long recentCutoff = now - windowHours * 3600L;
        long lookbackCutoff = now - lookbackDays * 86400L;
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT SUM(CASE WHEN timestamp > ? THEN quantity ELSE 0 END), SUM(quantity) " +
                "FROM ks_eco_trades WHERE item_material = ? AND trade_type LIKE '%SELL%' " +
                "AND timestamp > ? AND (is_test IS NULL OR is_test = 0)")) {
            ps.setLong(1, recentCutoff);
            ps.setString(2, material.toUpperCase());
            ps.setLong(3, lookbackCutoff);
            try (ResultSet rs = ps.executeQuery()) {
                double recentQty = 0.0;
                double totalQty = 0.0;
                if (rs.next()) {
                    recentQty = rs.getDouble(1);
                    totalQty = rs.getDouble(2);
                }
                double dailyAvg = totalQty / lookbackDays;
                double baseline = Math.max(dailyAvg * (windowHours / 24.0), defaultBaseline);
                return new double[]{recentQty, baseline};
            }
        }
    }

    /** Loads every configured material's refresh inputs in one indexed aggregate query. */
    private Map<String, double[]> loadRefreshStats(Connection conn, Collection<String> materials, long now) throws SQLException {
        if (materials.isEmpty()) return Map.of();
        var cfg = plugin.ecoConfig();
        int windowHours = cfg.getVolatilityOversupplyWindowHours();
        int lookbackDays = cfg.getVolatilityBaselineLookbackDays();
        long recentCutoff = now - windowHours * 3600L;
        long lookbackCutoff = now - lookbackDays * 86400L;
        long marketCutoff = now - 7L * 24 * 3600;
        long scanCutoff = Math.min(lookbackCutoff, marketCutoff);
        String placeholders = String.join(",", Collections.nCopies(materials.size(), "?"));
        String sql = "SELECT item_material, " +
                "SUM(CASE WHEN trade_type LIKE '%SELL%' AND timestamp>? THEN quantity ELSE 0 END), " +
                "SUM(CASE WHEN trade_type LIKE '%SELL%' AND timestamp>? THEN quantity ELSE 0 END), " +
                "AVG(CASE WHEN timestamp>? THEN unit_price END), " +
                "COUNT(CASE WHEN timestamp>? THEN 1 END) " +
                "FROM ks_eco_trades WHERE timestamp>? AND (is_test IS NULL OR is_test=0) " +
                "AND item_material IN (" + placeholders + ") GROUP BY item_material";
        Map<String, double[]> stats = new HashMap<>();
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, recentCutoff);
            ps.setLong(2, lookbackCutoff);
            ps.setLong(3, marketCutoff);
            ps.setLong(4, marketCutoff);
            ps.setLong(5, scanCutoff);
            int index = 6;
            for (String material : materials) ps.setString(index++, material);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    double recentQty = rs.getDouble(2);
                    double totalQty = rs.getDouble(3);
                    double baseline = Math.max((totalQty / lookbackDays) * (windowHours / 24.0),
                            cfg.getVolatilityDefaultBaselineQty());
                    double average = rs.getInt(5) > 0 ? rs.getDouble(4) : 0.0;
                    stats.put(rs.getString(1).toUpperCase(Locale.ROOT), new double[]{recentQty, baseline, average});
                }
            }
        }
        return stats;
    }

    private void bindVolatilityState(PreparedStatement ps, String material, VolatilityState state, long now) throws SQLException {
        ps.setString(1, material);
        ps.setDouble(2, state.driftValue);
        ps.setDouble(3, state.trendBias);
        ps.setDouble(4, state.previousBuyPrice);
        ps.setLong(5, now);
    }

    private double computeSupplyPressure(double recentQty, double baseline) {
        if (baseline <= 0) return 0.0;
        return Math.max(-1.0, Math.min(1.0, (recentQty - baseline) / baseline));
    }

    /** 均值回归随机游走推进一步，得到新的 driftValue。 */
    private double stepDrift(double driftValue, double trendBias) {
        if (!volatilityEnabled) return 0.0;
        var cfg = plugin.ecoConfig();
        double step = random.nextGaussian() * cfg.getVolatilityStepStdDev();
        double pull = (trendBias - driftValue) * cfg.getVolatilityPullStrength();
        double reversion = -driftValue * cfg.getVolatilityReversionRate();
        return Math.max(-1.0, Math.min(1.0, driftValue + step + pull + reversion));
    }

    private double computeBuyPrice(double base, double driftValue, double supplyPressure) {
        double totalOffset = driftValue * maxFluctuation - supplyPressure * maxFluctuation;
        totalOffset = Math.max(-maxFluctuation, Math.min(maxFluctuation, totalOffset));
        return Math.round(base * (1.0 + totalOffset) * 100.0) / 100.0;
    }

    /**
     * 刷新所有价格（定时任务调用）：重新统计供需压力、推进一步随机游走、重算价格。
     */
    public void refreshAllPrices() {
        try (Connection conn = plugin.ksCore().dataStore().getConnection()) {
            if (conn == null) return;
            conn.setAutoCommit(false);
            try {
                RefreshResult result = stageRefresh(conn);
                conn.commit();
                applyRefresh(result);
                plugin.publishCrossServerInvalidation("price", "all");
            } catch (SQLException e) {
                conn.rollback();
                throw e;
            } finally {
                try { conn.setAutoCommit(true); } catch (SQLException ignored) {}
            }
        } catch (SQLException e) {
            plugin.getLogger().warning("Price refresh failed: " + e.getMessage());
        }
    }

    /** Stages a price refresh inside the caller-owned fenced transaction. */
    public RefreshResult stageRefresh(Connection conn) throws SQLException {
        Objects.requireNonNull(conn, "conn");
        Map<String, PricePoint> priceSnapshot = Map.copyOf(priceCache);
        Map<String, VolatilityState> stateSnapshot = Map.copyOf(volatilityState);
        long now = System.currentTimeMillis() / 1000;
        Map<String, double[]> refreshStats = loadRefreshStats(conn, priceSnapshot.keySet(), now);
        Map<String, PricePoint> stagedPrices = new HashMap<>();
        Map<String, VolatilityState> stagedStates = new HashMap<>();
        for (var entry : priceSnapshot.entrySet()) {
            String mat = entry.getKey();
            double base = entry.getValue().basePrice;
            double oldBuyPrice = entry.getValue().buyPrice;
            VolatilityState state = stateSnapshot.getOrDefault(mat, VolatilityState.EMPTY);
            double[] stats = refreshStats.getOrDefault(mat, new double[]{0.0,
                    plugin.ecoConfig().getVolatilityDefaultBaselineQty(), 0.0});
            double recentQty = stats[0], baseline = stats[1];
            double newDrift = stepDrift(state.driftValue, state.trendBias);
            double newBuyPrice = computeBuyPrice(base, newDrift, computeSupplyPressure(recentQty, baseline));
            double marketAvg = stats[2];
            VolatilityState newState = new VolatilityState(
                    newDrift, state.trendBias, recentQty, baseline, oldBuyPrice);
            stagedPrices.put(mat, new PricePoint(base, newBuyPrice, marketAvg));
            stagedStates.put(mat, newState);
            upsertVolatility(conn, mat, newState, stagedPrices.get(mat), now);
        }
        return new RefreshResult(stagedPrices, stagedStates);
    }

    /** Applies a refresh only after its database transaction has committed. */
    public void applyRefresh(RefreshResult result) {
        Objects.requireNonNull(result, "result");
        priceCache.putAll(result.prices());
        volatilityState.putAll(result.states());
    }

    private static void upsertVolatility(Connection connection, String material, VolatilityState state,
                                         PricePoint price, long now)
            throws SQLException {
        double currentBuyPrice = price == null ? 0.0d : price.buyPrice;
        double marketAverage = price == null ? 0.0d : price.marketAvg;
        PortableSqlMutation.upsert(connection,
                "UPDATE ks_eco_price_volatility SET drift_value=?,trend_bias=?,last_buy_price=?,"
                        + "current_buy_price=?,market_average=?,updated_at=? "
                        + "WHERE material=?",
                ps -> {
                    ps.setDouble(1, state.driftValue); ps.setDouble(2, state.trendBias);
                    ps.setDouble(3, state.previousBuyPrice); ps.setDouble(4, currentBuyPrice);
                    ps.setDouble(5, marketAverage); ps.setLong(6, now); ps.setString(7, material);
                },
                "INSERT INTO ks_eco_price_volatility "
                        + "(material,drift_value,trend_bias,last_buy_price,current_buy_price,market_average,updated_at) "
                        + "VALUES (?,?,?,?,?,?,?)",
                ps -> {
                    ps.setString(1, material); ps.setDouble(2, state.driftValue); ps.setDouble(3, state.trendBias);
                    ps.setDouble(4, state.previousBuyPrice); ps.setDouble(5, currentBuyPrice);
                    ps.setDouble(6, marketAverage); ps.setLong(7, now);
                });
    }

    private void insertTradeRow(String material, int quantity, double unitPrice,
                                 String buyerUuid, String sellerUuid, String tradeType, boolean isTest) {
        try (Connection conn = plugin.ksCore().dataStore().getConnection()) {
            if (conn == null) return;
            try (PreparedStatement ps = conn.prepareStatement(
                    "INSERT INTO ks_eco_trades (id, item_material, item_signature, quantity, unit_price, " +
                    "buyer_uuid, seller_uuid, trade_type, timestamp, is_test) VALUES (?,?,?,?,?,?,?,?,?,?)")) {
                ps.setString(1, java.util.UUID.randomUUID().toString());
                ps.setString(2, material.toUpperCase());
                ps.setString(3, material.toLowerCase());
                ps.setInt(4, quantity);
                ps.setDouble(5, unitPrice);
                ps.setString(6, buyerUuid);
                ps.setString(7, sellerUuid);
                ps.setString(8, tradeType);
                ps.setLong(9, System.currentTimeMillis() / 1000);
                ps.setInt(10, isTest ? 1 : 0);
                ps.executeUpdate();
            }
        } catch (SQLException e) {
            plugin.getLogger().warning("记录交易历史失败: " + e.getMessage());
        }
    }

    /**
     * 记录一笔真实交易（写入流水；若为卖给官方的真实交易，立即在内存里估算一次供需压力，
     * 让玩家卖完东西马上能看到价格变化，不用等下一次定时刷新）。
     */
    public void recordTrade(String material, int quantity, double unitPrice,
                            String buyerUuid, String sellerUuid, String tradeType) {
        recordTrades(List.of(new TradeRecord(material, quantity, unitPrice,
                buyerUuid, sellerUuid, tradeType)));
    }

    /** Writes a completed trade batch with one connection and atomically applies supply deltas. */
    public void recordTrades(Collection<TradeRecord> trades) {
        if (trades == null || trades.isEmpty()) return;
        List<TradeRecord> valid = trades.stream()
                .filter(trade -> trade != null && trade.material() != null && trade.quantity() > 0
                        && Double.isFinite(trade.unitPrice()) && trade.unitPrice() >= 0.0)
                .toList();
        if (valid.isEmpty()) return;

        try (Connection conn = plugin.ksCore().dataStore().getConnection()) {
            if (conn == null) return;
            conn.setAutoCommit(false);
            try (PreparedStatement ps = conn.prepareStatement(
                    "INSERT INTO ks_eco_trades (id,item_material,item_signature,quantity,unit_price,"
                            + "buyer_uuid,seller_uuid,trade_type,timestamp,is_test) VALUES (?,?,?,?,?,?,?,?,?,0)")) {
                long now = System.currentTimeMillis() / 1000;
                for (TradeRecord trade : valid) {
                    String material = trade.material().toUpperCase(Locale.ROOT);
                    ps.setString(1, UUID.randomUUID().toString());
                    ps.setString(2, material);
                    ps.setString(3, material.toLowerCase(Locale.ROOT));
                    ps.setInt(4, trade.quantity());
                    ps.setDouble(5, trade.unitPrice());
                    ps.setString(6, trade.buyerUuid());
                    ps.setString(7, trade.sellerUuid());
                    ps.setString(8, trade.tradeType());
                    ps.setLong(9, now);
                    ps.addBatch();
                }
                ps.executeBatch();
                conn.commit();
            } catch (SQLException exception) {
                conn.rollback();
                throw exception;
            } finally {
                try { conn.setAutoCommit(true); } catch (SQLException ignored) {}
            }
        } catch (SQLException exception) {
            plugin.getLogger().warning("记录交易历史失败: " + exception.getMessage());
            return;
        }

        // Trade history is authoritative. Cached prices change only in the fenced
        // cluster refresh, otherwise two nodes could apply different local deltas.
    }

    /**
     * 供 /kseco void-trade 和 web simulate-trade 管理员测试工具调用。
     * 测试模式开启时：只做纯预览计算，不修改真实价格状态，流水打 is_test 标记（永久排除出真实定价统计）。
     * 测试模式关闭时：等价于一次真实的 {@link #recordTrade}，返回值是变更后的真实价格。
     * @return 预览价格（测试模式）或变更后的真实价格（非测试模式）
     */
    public double recordAdminTrade(String material, int quantity, double unitPrice, String tradeType,
                                    String buyerUuid, String sellerUuid) {
        String mat = material.toUpperCase();
        if (!testModeEnabled) {
            recordTrade(mat, quantity, unitPrice, buyerUuid, sellerUuid, tradeType);
            return getOfficialBuyPrice(mat);
        }

        insertTradeRow(mat, quantity, unitPrice, buyerUuid, sellerUuid, tradeType, true);
        VolatilityState state = volatilityState.getOrDefault(mat, VolatilityState.EMPTY);
        PricePoint pp = priceCache.get(mat);
        if (pp == null) return 0.0;
        double baseline = state.baseline > 0 ? state.baseline : plugin.ecoConfig().getVolatilityDefaultBaselineQty();
        double hypotheticalQty = state.recentQty;
        if (tradeType != null && tradeType.contains("SELL")) hypotheticalQty += quantity;
        double supplyPressure = computeSupplyPressure(hypotheticalQty, baseline);
        return computeBuyPrice(pp.basePrice, state.driftValue, supplyPressure);
    }

    /**
     * 管理员强制设定某物品价格。
     */
    public void forcePrice(String material, double price) {
        setOfficialBuyPrice(material, price);
    }

    // ---- 市场波动管理接口（供 web 管理端调用） ----

    /** 设置某物品的"大手导向"目标值（-1~1），driftValue 会在后续刷新里逐渐被拉向这个值。 */
    public void setTrendBias(String material, double bias) {
        String mat = material.toUpperCase();
        double clamped = Math.max(-1.0, Math.min(1.0, bias));
        VolatilityState old = volatilityState.getOrDefault(mat, VolatilityState.EMPTY);
        VolatilityState updated = new VolatilityState(old.driftValue, clamped, old.recentQty, old.baseline, old.previousBuyPrice);
        volatilityState.put(mat, updated);
        persistVolatilityState(mat, updated);
        plugin.publishCrossServerInvalidation("price", mat);
    }

    public void clearTrendBias(String material) {
        setTrendBias(material, 0.0);
    }

    /** 设置全局波动开关和波动率上限。关闭时立即清零所有物品的 driftValue 并冻结。 */
    public void setGlobalVolatility(boolean enabled, double maxFluctuationValue) {
        this.volatilityEnabled = enabled;
        this.maxFluctuation = Math.max(0.0, Math.min(1.0, maxFluctuationValue));
        if (!enabled) {
            for (var e : volatilityState.entrySet()) {
                VolatilityState s = e.getValue();
                if (s.driftValue != 0.0) {
                    VolatilityState frozen = new VolatilityState(0.0, s.trendBias, s.recentQty, s.baseline, s.previousBuyPrice);
                    volatilityState.put(e.getKey(), frozen);
                    persistVolatilityState(e.getKey(), frozen);
                }
            }
        }
        persistGlobalSettings();
        plugin.publishCrossServerInvalidation("price", "settings");
    }

    public boolean isVolatilityEnabled() { return volatilityEnabled; }
    public double getMaxFluctuation() { return maxFluctuation; }

    /** 获取当前价格刷新间隔（分钟）。 */
    public int getPriceRefreshMinutes() { return priceRefreshMinutes; }

    /** 设置价格刷新间隔（分钟）并持久化；调用方（KsEco）需要自行重启定时任务才会立即生效。 */
    public void setPriceRefreshMinutes(int minutes) {
        this.priceRefreshMinutes = Math.max(1, minutes);
        persistSetting("price_refresh_minutes", String.valueOf(this.priceRefreshMinutes));
        plugin.publishCrossServerInvalidation("price", "settings");
    }

    public boolean isTestModeEnabled() { return testModeEnabled; }

    /** 开启/关闭测试模式：开启后 void-trade/simulate-trade 只做预览，不影响真实价格。 */
    public void setTestModeEnabled(boolean enabled) {
        this.testModeEnabled = enabled;
        persistSetting("test_mode_enabled", String.valueOf(enabled));
        plugin.publishCrossServerInvalidation("price", "settings");
    }

    /** 给 web 管理端用的完整快照：全局设置 + 每个物品的漂移/导向/供需压力/趋势状态。 */
    public Map<String, Object> getVolatilitySnapshot() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("enabled", volatilityEnabled);
        result.put("maxFluctuation", maxFluctuation);
        result.put("priceRefreshMinutes", priceRefreshMinutes);
        result.put("testModeEnabled", testModeEnabled);
        List<Map<String, Object>> items = new ArrayList<>();
        for (var entry : priceCache.entrySet()) {
            String mat = entry.getKey();
            var pp = entry.getValue();
            VolatilityState state = volatilityState.getOrDefault(mat, VolatilityState.EMPTY);
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("material", mat);
            m.put("basePrice", pp.basePrice);
            m.put("buyPrice", pp.buyPrice);
            m.put("driftValue", state.driftValue);
            m.put("trendBias", state.trendBias);
            m.put("supplyPressure", computeSupplyPressure(state.recentQty, state.baseline));
            m.put("trend", getTrend(mat));
            items.add(m);
        }
        result.put("items", items);
        return result;
    }

    // ---- 内部类 ----

    public record TradeRecord(String material, int quantity, double unitPrice,
                              String buyerUuid, String sellerUuid, String tradeType) {}

    public record RefreshResult(Map<String, PricePoint> prices, Map<String, VolatilityState> states) {
        public RefreshResult {
            prices = Map.copyOf(prices);
            states = Map.copyOf(states);
        }
    }

    public static class PricePoint {
        public final double basePrice;
        public final double buyPrice;   // 官方收购价
        public final double marketAvg;  // 市场均价

        public PricePoint(double basePrice, double buyPrice, double marketAvg) {
            this.basePrice = basePrice;
            this.buyPrice = buyPrice;
            this.marketAvg = marketAvg;
        }
    }

    /** 单个物品的波动状态：随机漂移值、管理员导向目标、近期供需统计、上一轮价格（算趋势用）。 */
    public static class VolatilityState {
        public static final VolatilityState EMPTY = new VolatilityState(0.0, 0.0, 0.0, 0.0, 0.0);

        public final double driftValue;
        public final double trendBias;
        public final double recentQty;
        public final double baseline;
        public final double previousBuyPrice;

        public VolatilityState(double driftValue, double trendBias, double recentQty, double baseline, double previousBuyPrice) {
            this.driftValue = driftValue;
            this.trendBias = trendBias;
            this.recentQty = recentQty;
            this.baseline = baseline;
            this.previousBuyPrice = previousBuyPrice;
        }
    }
}
