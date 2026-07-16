package org.kseco;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public final class MajorOrderManager {

    private final KsEco plugin;

    public MajorOrderManager(KsEco plugin) {
        this.plugin = plugin;
    }

    public void init() {
        try (var conn = plugin.ksCore().dataStore().getConnection()) {
            if (conn == null) return;
            try (var s = conn.createStatement()) {
                s.execute("""
                    CREATE TABLE IF NOT EXISTS ks_major_orders (
                        id TEXT PRIMARY KEY,
                        title TEXT NOT NULL,
                        description TEXT DEFAULT '',
                        metric_type TEXT NOT NULL DEFAULT 'MANUAL',
                        target_value REAL NOT NULL DEFAULT 1,
                        manual_value REAL NOT NULL DEFAULT 0,
                        status TEXT NOT NULL DEFAULT 'ACTIVE',
                        starts_at INTEGER NOT NULL DEFAULT 0,
                        ends_at INTEGER NOT NULL DEFAULT 0,
                        created_at INTEGER NOT NULL,
                        updated_at INTEGER NOT NULL
                    )
                """);
                try { s.execute("ALTER TABLE ks_major_orders ADD COLUMN policy_industry TEXT NOT NULL DEFAULT 'ALL'"); } catch (SQLException ignored) {}
                try { s.execute("ALTER TABLE ks_major_orders ADD COLUMN policy_purpose TEXT NOT NULL DEFAULT 'ALL'"); } catch (SQLException ignored) {}
                try { s.execute("ALTER TABLE ks_major_orders ADD COLUMN policy_loan_rate_multiplier REAL NOT NULL DEFAULT 1.0"); } catch (SQLException ignored) {}
                try { s.execute("ALTER TABLE ks_major_orders ADD COLUMN policy_reserve_delta REAL NOT NULL DEFAULT 0.0"); } catch (SQLException ignored) {}
            }
        } catch (SQLException e) {
            plugin.getLogger().warning("[MO] init table failed: " + e.getMessage());
        }
    }

    public List<Map<String, Object>> listOrders(boolean includeArchived) {
        List<Map<String, Object>> out = new ArrayList<>();
        String sql = includeArchived
                ? "SELECT * FROM ks_major_orders ORDER BY status='ACTIVE' DESC, created_at DESC"
                : "SELECT * FROM ks_major_orders WHERE status!='ARCHIVED' ORDER BY status='ACTIVE' DESC, created_at DESC";
        try (var conn = plugin.ksCore().dataStore().getConnection()) {
            if (conn == null) return out;
            try (var ps = conn.prepareStatement(sql);
                 var rs = ps.executeQuery()) {
                while (rs.next()) {
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("id", rs.getString("id"));
                    row.put("title", rs.getString("title"));
                    row.put("description", rs.getString("description"));
                    row.put("metricType", rs.getString("metric_type"));
                    double target = rs.getDouble("target_value");
                    double current = currentValue(rs.getString("metric_type"), rs.getDouble("manual_value"));
                    String status = rs.getString("status");
                    if ("ACTIVE".equals(status) && target > 0 && current >= target) status = "COMPLETED";
                    row.put("targetValue", target);
                    row.put("currentValue", current);
                    row.put("progressPct", target <= 0 ? 0 : Math.min(1.0, current / target));
                    row.put("status", status);
                    row.put("startsAt", rs.getLong("starts_at"));
                    row.put("endsAt", rs.getLong("ends_at"));
                    row.put("createdAt", rs.getLong("created_at"));
                    row.put("updatedAt", rs.getLong("updated_at"));
                    row.put("policyIndustry", rs.getString("policy_industry"));
                    row.put("policyPurpose", rs.getString("policy_purpose"));
                    row.put("policyLoanRateMultiplier", rs.getDouble("policy_loan_rate_multiplier"));
                    row.put("policyReserveDelta", rs.getDouble("policy_reserve_delta"));
                    out.add(row);
                }
            }
        } catch (SQLException e) {
            plugin.getLogger().warning("[MO] list failed: " + e.getMessage());
        }
        return out;
    }

    public boolean saveOrder(Map<String, Object> req) {
        String id = str(req.get("id"));
        if (id.isBlank()) id = "MO-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();
        String title = str(req.get("title"));
        if (title.isBlank()) return false;
        String description = str(req.get("description"));
        String metricType = str(req.get("metricType"));
        if (metricType.isBlank()) metricType = str(req.get("metric"));
        metricType = metricType.isBlank() ? "MANUAL" : metricType.toUpperCase();
        double target = Math.max(0.0001, toDouble(req.get("targetValue"), 1));
        double manual = Math.max(0, toDouble(req.get("manualValue"), toDouble(req.get("currentValue"), 0)));
        String status = str(req.get("status")).isBlank() ? "ACTIVE" : str(req.get("status")).toUpperCase();
        long startsAt = (long) toDouble(req.get("startsAt"), 0);
        long endsAt = (long) toDouble(req.get("endsAt"), 0);
        String policyIndustry = str(req.get("policyIndustry")).isBlank() ? "ALL" : str(req.get("policyIndustry")).trim().toUpperCase();
        String policyPurpose = str(req.get("policyPurpose")).isBlank() ? "ALL" : str(req.get("policyPurpose")).trim().toUpperCase();
        double loanRateMultiplier = Math.max(0.1, Math.min(3.0, toDouble(req.get("policyLoanRateMultiplier"), 1.0)));
        double reserveDelta = Math.max(-0.5, Math.min(0.5, toDouble(req.get("policyReserveDelta"), 0.0)));
        long now = System.currentTimeMillis() / 1000;

        try (var conn = plugin.ksCore().dataStore().getConnection()) {
            if (conn == null) return false;
            try (var ps = conn.prepareStatement(
                    "INSERT INTO ks_major_orders (id,title,description,metric_type,target_value,manual_value,status,starts_at,ends_at,created_at,updated_at,policy_industry,policy_purpose,policy_loan_rate_multiplier,policy_reserve_delta) " +
                    "VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?) " +
                    "ON CONFLICT(id) DO UPDATE SET title=excluded.title, description=excluded.description, metric_type=excluded.metric_type, target_value=excluded.target_value, manual_value=excluded.manual_value, status=excluded.status, starts_at=excluded.starts_at, ends_at=excluded.ends_at, updated_at=excluded.updated_at, policy_industry=excluded.policy_industry, policy_purpose=excluded.policy_purpose, policy_loan_rate_multiplier=excluded.policy_loan_rate_multiplier, policy_reserve_delta=excluded.policy_reserve_delta")) {
                ps.setString(1, id);
                ps.setString(2, title);
                ps.setString(3, description);
                ps.setString(4, metricType);
                ps.setDouble(5, target);
                ps.setDouble(6, manual);
                ps.setString(7, status);
                ps.setLong(8, startsAt);
                ps.setLong(9, endsAt);
                ps.setLong(10, now);
                ps.setLong(11, now);
                ps.setString(12, policyIndustry);
                ps.setString(13, policyPurpose);
                ps.setDouble(14, loanRateMultiplier);
                ps.setDouble(15, reserveDelta);
                ps.executeUpdate();
                return true;
            }
        } catch (SQLException e) {
            plugin.getLogger().warning("[MO] save failed: " + e.getMessage());
            return false;
        }
    }

    public boolean setStatus(String id, String status) {
        if (id == null || id.isBlank()) return false;
        long now = System.currentTimeMillis() / 1000;
        try (var conn = plugin.ksCore().dataStore().getConnection()) {
            if (conn == null) return false;
            try (var ps = conn.prepareStatement("UPDATE ks_major_orders SET status=?, updated_at=? WHERE id=?")) {
                ps.setString(1, status == null ? "ARCHIVED" : status.toUpperCase());
                ps.setLong(2, now);
                ps.setString(3, id);
                return ps.executeUpdate() > 0;
            }
        } catch (SQLException e) {
            plugin.getLogger().warning("[MO] status failed: " + e.getMessage());
            return false;
        }
    }

    public Map<String, Object> moneySupplyBreakdown() {
        Map<String, Object> out = new LinkedHashMap<>();
        EconomyStatsFilter.BalanceSum builtin = new EconomyStatsFilter.BalanceSum(0, 0, 0, 0);
        EconomyStatsFilter.BalanceSum bank = new EconomyStatsFilter.BalanceSum(0, 0, 0, 0);
        double corp = 0;
        double snapshot = 0;
        try (var conn = plugin.ksCore().dataStore().getConnection()) {
            if (conn != null) {
                builtin = EconomyStatsFilter.sumPlayerBalances(plugin, conn,
                        "ks_builtin_economy", "uuid", "name", "balance");
                bank = EconomyStatsFilter.sumPlayerBalances(plugin, conn,
                        "ks_bank_accounts", "player_uuid", null, "balance");
                corp = sum(conn, "ks_ent_corporate_accounts", "balance");
                try (var ps = conn.prepareStatement("SELECT m2 FROM ks_bank_money_supply ORDER BY snapshot_at DESC LIMIT 1");
                     var rs = ps.executeQuery()) {
                    if (rs.next()) snapshot = rs.getDouble(1);
                } catch (SQLException ignored) {}
            }
        } catch (SQLException ignored) {}
        double onlineVault = EconomyStatsFilter.onlineVaultBalances(plugin);
        double wallet = Math.max(builtin.included(), onlineVault);
        double total = wallet + bank.included() + corp;
        out.put("vaultProvider", plugin.vaultHook().getName());
        out.put("wallet", wallet);
        out.put("builtin", builtin.included());
        out.put("builtinExcluded", builtin.excluded());
        out.put("builtinExcludedRows", builtin.excludedRows());
        out.put("bank", bank.included());
        out.put("bankExcluded", bank.excluded());
        out.put("bankExcludedRows", bank.excludedRows());
        out.put("corporate", corp);
        out.put("onlineVault", onlineVault);
        out.put("snapshotM2", snapshot);
        out.put("total", total);
        return out;
    }

    private double currentValue(String metricType, double manualValue) {
        if ("MONEY_SUPPLY".equalsIgnoreCase(metricType)) return currentMoneySupply();
        return manualValue;
    }

    private double currentMoneySupply() {
        Object total = moneySupplyBreakdown().get("total");
        return total instanceof Number n ? n.doubleValue() : 0;
    }

    private double sum(java.sql.Connection conn, String table, String column) {
        try (var s = conn.createStatement();
             var rs = s.executeQuery("SELECT COALESCE(SUM(" + column + "),0) FROM " + table)) {
            return rs.next() ? rs.getDouble(1) : 0;
        } catch (SQLException ignored) {
            return 0;
        }
    }

    private static String str(Object value) {
        return value == null ? "" : String.valueOf(value).trim();
    }

    private static double toDouble(Object value, double def) {
        if (value instanceof Number n) return n.doubleValue();
        if (value instanceof String s) {
            try { return Double.parseDouble(s); } catch (NumberFormatException ignored) {}
        }
        return def;
    }
}
