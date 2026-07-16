package org.kseco.extra.tax;

import org.kseco.KsEco;

import java.sql.*;
import java.util.*;

/**
 * 动态税率管理器（支持行业差异化 + 阶梯）。
 *
 * 税种类别（category）：
 * - MARKET_TRADE: 市场交易税
 * - OFFICIAL_TRADE: 官方交易税
 * - ENTERPRISE_TAX: 企业所得税
 * - BANK_INTEREST: 银行利息税
 * - PENALTY_TAX: 罚金税
 * - DIVIDEND_TAX: 分红税
 *
 * 行业（industry）：INDUSTRY/AGRICULTURE/REAL_ESTATE/OTHER/NULL=通用
 * 阶梯：ks_tax_brackets (scope=ENTERPRISE_TAX, industry, profit_min, profit_max, rate)
 *   查表时按 industry 找 (profit_min <= amount < profit_max) 选 rate；无阶梯则用 ks_tax_rates 基础税率
 */
public final class TaxRateManager {

    private final KsEco eco;
    private final Map<String, Double> rates = new LinkedHashMap<>();  // 兼容旧 API：category -> rate（通用）
    // 新 API：industryRates[industry] -> [category -> rate]
    private final Map<String, Map<String, Double>> industryRates = new LinkedHashMap<>();

    public TaxRateManager(KsEco eco) {
        this.eco = eco;
    }

    public void init() {
        // 默认通用税率
        rates.put("MARKET_TRADE", 0.02);
        rates.put("OFFICIAL_TRADE", 0.0);
        rates.put("ENTERPRISE_TAX", 0.08);
        rates.put("BANK_INTEREST", 0.10);
        rates.put("PLAYER_TRANSFER", 0.01);
        rates.put("PENALTY_TAX", 0.20);
        rates.put("DIVIDEND_TAX", 0.10);
        // 默认行业差异：房地产行业分红税低，鼓励投资
        Map<String, Double> re = new LinkedHashMap<>();
        re.put("DIVIDEND_TAX", 0.05);
        re.put("ENTERPRISE_TAX", 0.10);
        industryRates.put("REAL_ESTATE", re);
        // 农业：低企业税
        Map<String, Double> ag = new LinkedHashMap<>();
        ag.put("ENTERPRISE_TAX", 0.05);
        ag.put("MARKET_TRADE", 0.01);
        industryRates.put("AGRICULTURE", ag);
        // 工业：标准
        Map<String, Double> ind = new LinkedHashMap<>();
        ind.put("DIVIDEND_TAX", 0.12);
        industryRates.put("INDUSTRY", ind);

        ensureTable();
        loadRates();
        loadIndustryRates();
        ensureBracketsTable();
    }

    private void ensureTable() {
        try (Connection conn = eco.ksCore().dataStore().getConnection()) {
            if (conn == null) {
                eco.getLogger().warning("[税率] 数据库连接未就绪，延迟建表");
                return;
            }
            try (Statement stmt = conn.createStatement()) {
                stmt.execute("""
                    CREATE TABLE IF NOT EXISTS ks_tax_rates (
                        category TEXT NOT NULL,
                        industry TEXT,
                        rate REAL NOT NULL,
                        updated_at INTEGER NOT NULL,
                        PRIMARY KEY(category, industry)
                    )
                """);
                // 兼容旧 DB（缺 industry 列）— 2026-06-27 修复
                try {
                    stmt.execute("ALTER TABLE ks_tax_rates ADD COLUMN industry TEXT");
                } catch (SQLException ignore) { /* 已存在 */ }
            }
        } catch (SQLException e) {
            eco.getLogger().warning("[税率] 创建表失败: " + e.getMessage());
        }
    }

    private void ensureBracketsTable() {
        try (Connection conn = eco.ksCore().dataStore().getConnection()) {
            if (conn == null) return;
            try (Statement stmt = conn.createStatement()) {
                stmt.execute("""
                    CREATE TABLE IF NOT EXISTS ks_tax_brackets (
                        id TEXT PRIMARY KEY,
                        industry TEXT NOT NULL,
                        scope TEXT NOT NULL DEFAULT 'ENTERPRISE_TAX',
                        profit_min REAL NOT NULL,
                        profit_max REAL NOT NULL,
                        rate REAL NOT NULL,
                        updated_at INTEGER NOT NULL
                    )
                """);
            }
        } catch (SQLException e) {
            eco.getLogger().warning("[税率阶梯] 建表失败: " + e.getMessage());
        }
    }

    private void loadRates() {
        try (Connection conn = eco.ksCore().dataStore().getConnection()) {
            if (conn == null) return;
            try (Statement stmt = conn.createStatement();
                 ResultSet rs = stmt.executeQuery("SELECT category, rate, industry FROM ks_tax_rates WHERE industry IS NULL OR industry=''")) {
                while (rs.next()) {
                    rates.put(rs.getString("category"), rs.getDouble("rate"));
                }
            }
        } catch (SQLException e) {
            eco.getLogger().warning("[税率] 加载失败: " + e.getMessage());
        }
    }

    private void loadIndustryRates() {
        try (Connection conn = eco.ksCore().dataStore().getConnection()) {
            if (conn == null) return;
            try (Statement stmt = conn.createStatement();
                 ResultSet rs = stmt.executeQuery("SELECT category, rate, industry FROM ks_tax_rates WHERE industry IS NOT NULL AND industry<>''")) {
                while (rs.next()) {
                    String ind = rs.getString("industry");
                    industryRates.computeIfAbsent(ind, k -> new LinkedHashMap<>())
                            .put(rs.getString("category"), rs.getDouble("rate"));
                }
            }
        } catch (SQLException e) {
            eco.getLogger().warning("[税率] 行业税率加载失败: " + e.getMessage());
        }
    }

    /** 旧 API：取通用税率 */
    public double getRate(String category) {
        return rates.getOrDefault(category, 0.02);
    }

    /** 新 API：按行业取税率（先查 industry，没有就回退到通用） */
    public double getRate(String category, String industry) {
        if (industry != null && !industry.isEmpty()) {
            Map<String, Double> m = industryRates.get(industry);
            if (m != null && m.containsKey(category)) return m.get(category);
        }
        return getRate(category);
    }

    /**
     * 设置税率（管理员操作）。
     * industry=null 表示通用；否则是具体行业。
     */
    public void setRate(String category, double rate, String industry) {
        double v = Math.max(0.0, Math.min(1.0, rate));
        if (industry == null || industry.isEmpty()) {
            rates.put(category, v);
        } else {
            industryRates.computeIfAbsent(industry, k -> new LinkedHashMap<>()).put(category, v);
        }
        try (Connection conn = eco.ksCore().dataStore().getConnection()) {
            if (conn == null) return;
            ensureTable();
            try (PreparedStatement ps = conn.prepareStatement(
                    "INSERT INTO ks_tax_rates (category, industry, rate, updated_at) VALUES (?,?,?,?) " +
                    "ON CONFLICT(category, industry) DO UPDATE SET rate=excluded.rate, updated_at=excluded.updated_at")) {
                ps.setString(1, category);
                if (industry == null || industry.isEmpty()) ps.setNull(2, Types.VARCHAR); else ps.setString(2, industry);
                ps.setDouble(3, v);
                ps.setLong(4, System.currentTimeMillis() / 1000);
                ps.executeUpdate();
            }
        } catch (SQLException e) {
            eco.getLogger().warning("[税率] 保存失败: " + e.getMessage());
        }
    }

    /** 兼容旧 setRate(category, rate) */
    public void setRate(String category, double rate) {
        setRate(category, rate, null);
    }

    public Map<String, Double> getAllRates() {
        Map<String, Double> merged = new LinkedHashMap<>(rates);
        for (var e : industryRates.entrySet()) {
            for (var c : e.getValue().entrySet()) {
                merged.put(e.getKey() + ":" + c.getKey(), c.getValue());
            }
        }
        return merged;
    }

    /** 列出某 industry 税率表 */
    public Map<String, Double> getIndustryRates(String industry) {
        return industryRates.getOrDefault(industry, Map.of());
    }

    public Map<String, Map<String, Double>> getAllIndustryRates() {
        return new LinkedHashMap<>(industryRates);
    }

    // ================================================================
    // 阶梯税率
    // ================================================================

    /**
     * 按利润查阶梯税率。industry 和 scope 共同决定。
     * 返回 -1 表示未配置阶梯，调用方应回退到 getRate(scope, industry)。
     */
    public double getBracketRate(String industry, String scope, double profit) {
        try (Connection conn = eco.ksCore().dataStore().getConnection()) {
            if (conn == null) return -1;
            try (PreparedStatement ps = conn.prepareStatement(
                    "SELECT rate FROM ks_tax_brackets " +
                    "WHERE industry=? AND scope=? AND profit_min<=? AND profit_max>? " +
                    "ORDER BY profit_min DESC LIMIT 1")) {
                ps.setString(1, industry != null ? industry : "OTHER");
                ps.setString(2, scope);
                ps.setDouble(3, profit);
                ps.setDouble(4, profit); // profit_max>? — 必须绑定 profit，否则 SQLite 当 NULL → WHERE 永远不命中
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) return rs.getDouble(1);
                }
            }
        } catch (SQLException ignored) {}
        return -1;
    }

    public List<Map<String, Object>> listBrackets(String industry) {
        List<Map<String, Object>> out = new ArrayList<>();
        try (Connection conn = eco.ksCore().dataStore().getConnection()) {
            if (conn == null) return out;
            try (PreparedStatement ps = conn.prepareStatement(
                    "SELECT id, industry, scope, profit_min, profit_max, rate FROM ks_tax_brackets " +
                    "WHERE industry=? ORDER BY profit_min ASC")) {
                ps.setString(1, industry);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        Map<String, Object> m = new LinkedHashMap<>();
                        m.put("id", rs.getString("id"));
                        m.put("industry", rs.getString("industry"));
                        m.put("scope", rs.getString("scope"));
                        m.put("profitMin", rs.getDouble("profit_min"));
                        m.put("profitMax", rs.getDouble("profit_max"));
                        m.put("rate", rs.getDouble("rate"));
                        out.add(m);
                    }
                }
            }
        } catch (SQLException e) {
            eco.getLogger().warning("查阶梯失败: " + e.getMessage());
        }
        return out;
    }

    public boolean upsertBracket(String id, String industry, String scope,
                                 double profitMin, double profitMax, double rate) {
        try (Connection conn = eco.ksCore().dataStore().getConnection()) {
            if (conn == null) return false;
            try (PreparedStatement ps = conn.prepareStatement(
                    "INSERT INTO ks_tax_brackets (id, industry, scope, profit_min, profit_max, rate, updated_at) " +
                    "VALUES (?,?,?,?,?,?,?) " +
                    "ON CONFLICT(id) DO UPDATE SET industry=excluded.industry, scope=excluded.scope, " +
                    "profit_min=excluded.profit_min, profit_max=excluded.profit_max, rate=excluded.rate, updated_at=excluded.updated_at")) {
                ps.setString(1, id);
                ps.setString(2, industry);
                ps.setString(3, scope);
                ps.setDouble(4, profitMin);
                ps.setDouble(5, profitMax);
                ps.setDouble(6, Math.max(0.0, Math.min(1.0, rate)));
                ps.setLong(7, System.currentTimeMillis() / 1000);
                ps.executeUpdate();
                return true;
            }
        } catch (SQLException e) {
            eco.getLogger().warning("保存阶梯失败: " + e.getMessage());
        }
        return false;
    }

    public boolean deleteBracket(String id) {
        try (Connection conn = eco.ksCore().dataStore().getConnection()) {
            if (conn == null) return false;
            try (PreparedStatement ps = conn.prepareStatement("DELETE FROM ks_tax_brackets WHERE id=?")) {
                ps.setString(1, id);
                return ps.executeUpdate() > 0;
            }
        } catch (SQLException e) {
            eco.getLogger().warning("删阶梯失败: " + e.getMessage());
        }
        return false;
    }
}
