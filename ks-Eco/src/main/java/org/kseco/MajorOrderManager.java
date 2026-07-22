package org.kseco;

import org.kseco.database.PortableSqlMutation;
import org.kseco.database.EconomicFeatureSchema;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.OptionalDouble;
import java.util.UUID;

public final class MajorOrderManager {

    public static final String RPG_PROJECT_METRIC = "RPG_PROJECT";

    private final KsEco plugin;
    private volatile RpgProjectProgressSource rpgProjectProgressSource;

    public MajorOrderManager(KsEco plugin) {
        this.plugin = plugin;
    }

    /**
     * Installs a read-only source owned by the RPG integration. The source owns persistence and must return
     * absolute project progress; this manager only projects that value into the major-order view.
     */
    public void setRpgProjectProgressSource(RpgProjectProgressSource source) {
        this.rpgProjectProgressSource = Objects.requireNonNull(source, "source");
    }

    public void clearRpgProjectProgressSource() {
        this.rpgProjectProgressSource = null;
    }

    public boolean isRpgProjectProgressEnabled() {
        return rpgProjectProgressSource != null;
    }

    public void init() {
        try (var conn = plugin.ksCore().dataStore().getConnection()) {
            if (conn == null) return;
            EconomicFeatureSchema.initializeMajorOrders(conn);
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
                    String id = rs.getString("id");
                    row.put("id", id);
                    row.put("title", rs.getString("title"));
                    row.put("description", rs.getString("description"));
                    String metricType = rs.getString("metric_type");
                    row.put("metricType", metricType);
                    double target = rs.getDouble("target_value");
                    MetricValue metric = currentValue(id, metricType, rs.getDouble("manual_value"));
                    double current = metric.value();
                    String status = effectiveStatus(rs.getString("status"), target, metric);
                    row.put("targetValue", target);
                    row.put("currentValue", current);
                    row.put("progressPct", progressPercentage(target, metric));
                    row.put("metricAvailable", metric.available());
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
        metricType = metricType.isBlank() ? "MANUAL" : metricType.toUpperCase(Locale.ROOT);
        double target = normalizeTargetValue(toDouble(req.get("targetValue"), 1));
        double requestedManual = toDouble(req.get("manualValue"), toDouble(req.get("currentValue"), 0));
        double manual = storedManualValue(metricType, requestedManual);
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
            String orderId = id;
            String orderMetricType = metricType;
            String orderStatus = status;
            PortableSqlMutation.upsert(conn,
                    "UPDATE ks_major_orders SET title=?,description=?,metric_type=?,target_value=?,manual_value=?,"
                            + "status=?,starts_at=?,ends_at=?,updated_at=?,policy_industry=?,policy_purpose=?,"
                            + "policy_loan_rate_multiplier=?,policy_reserve_delta=? WHERE id=?",
                    ps -> bindOrderUpdate(ps, orderId, title, description, orderMetricType, target, manual,
                            orderStatus, startsAt, endsAt, now, policyIndustry, policyPurpose,
                            loanRateMultiplier, reserveDelta),
                    "INSERT INTO ks_major_orders (id,title,description,metric_type,target_value,manual_value,status,"
                            + "starts_at,ends_at,created_at,updated_at,policy_industry,policy_purpose,"
                            + "policy_loan_rate_multiplier,policy_reserve_delta) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                    ps -> bindOrderInsert(ps, orderId, title, description, orderMetricType, target, manual,
                            orderStatus, startsAt, endsAt, now, policyIndustry, policyPurpose,
                            loanRateMultiplier, reserveDelta));
            return true;
        } catch (SQLException e) {
            plugin.getLogger().warning("[MO] save failed: " + e.getMessage());
            return false;
        }
    }

    private static void bindOrderUpdate(java.sql.PreparedStatement ps, String id, String title, String description,
                                        String metricType, double target, double manual, String status,
                                        long startsAt, long endsAt, long now, String policyIndustry,
                                        String policyPurpose, double loanRateMultiplier, double reserveDelta)
            throws SQLException {
        bindOrderValues(ps, 1, title, description, metricType, target, manual, status, startsAt, endsAt);
        ps.setLong(9, now);
        ps.setString(10, policyIndustry); ps.setString(11, policyPurpose);
        ps.setDouble(12, loanRateMultiplier); ps.setDouble(13, reserveDelta); ps.setString(14, id);
    }

    private static void bindOrderInsert(java.sql.PreparedStatement ps, String id, String title, String description,
                                        String metricType, double target, double manual, String status,
                                        long startsAt, long endsAt, long now, String policyIndustry,
                                        String policyPurpose, double loanRateMultiplier, double reserveDelta)
            throws SQLException {
        ps.setString(1, id);
        bindOrderValues(ps, 2, title, description, metricType, target, manual, status, startsAt, endsAt);
        ps.setLong(10, now); ps.setLong(11, now);
        ps.setString(12, policyIndustry); ps.setString(13, policyPurpose);
        ps.setDouble(14, loanRateMultiplier); ps.setDouble(15, reserveDelta);
    }

    private static void bindOrderValues(java.sql.PreparedStatement ps, int offset, String title, String description,
                                        String metricType, double target, double manual, String status,
                                        long startsAt, long endsAt) throws SQLException {
        ps.setString(offset, title); ps.setString(offset + 1, description); ps.setString(offset + 2, metricType);
        ps.setDouble(offset + 3, target); ps.setDouble(offset + 4, manual); ps.setString(offset + 5, status);
        ps.setLong(offset + 6, startsAt); ps.setLong(offset + 7, endsAt);
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

    MetricValue currentValue(String orderId, String metricType, double manualValue) {
        if ("MONEY_SUPPLY".equalsIgnoreCase(metricType)) {
            return MetricValue.available(currentMoneySupply());
        }
        if (RPG_PROJECT_METRIC.equalsIgnoreCase(metricType)) {
            return currentRpgProjectValue(orderId);
        }
        return MetricValue.available(manualValue);
    }

    private MetricValue currentRpgProjectValue(String projectId) {
        RpgProjectProgressSource source = rpgProjectProgressSource;
        if (source == null || projectId == null || projectId.isBlank()) return MetricValue.unavailable();
        try {
            OptionalDouble progress = source.absoluteProgress(projectId);
            if (progress == null || progress.isEmpty()) return MetricValue.unavailable();
            double value = progress.getAsDouble();
            return isValidAbsoluteProgress(value) ? MetricValue.available(value) : MetricValue.unavailable();
        } catch (RuntimeException ignored) {
            return MetricValue.unavailable();
        }
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

    static double storedManualValue(String metricType, double requestedValue) {
        if (RPG_PROJECT_METRIC.equalsIgnoreCase(metricType)) return 0;
        if (!Double.isFinite(requestedValue)) return 0;
        return Math.max(0, requestedValue);
    }

    static double normalizeTargetValue(double requestedValue) {
        if (!Double.isFinite(requestedValue)) return 1;
        return Math.max(0.0001, requestedValue);
    }

    static String effectiveStatus(String storedStatus, double target, MetricValue metric) {
        if ("ACTIVE".equals(storedStatus) && metric.available() && target > 0 && metric.value() >= target) {
            return "COMPLETED";
        }
        return storedStatus;
    }

    static double progressPercentage(double target, MetricValue metric) {
        if (!metric.available() || !Double.isFinite(target) || target <= 0) return 0;
        return Math.min(1.0, metric.value() / target);
    }

    private static boolean isValidAbsoluteProgress(double value) {
        return Double.isFinite(value) && value >= 0;
    }

    @FunctionalInterface
    public interface RpgProjectProgressSource {
        OptionalDouble absoluteProgress(String projectId);
    }

    record MetricValue(double value, boolean available) {
        static MetricValue available(double value) {
            return new MetricValue(value, true);
        }

        static MetricValue unavailable() {
            return new MetricValue(0, false);
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
