package org.kseco.extra.tax;

import org.kseco.KsEco;
import org.kseco.scheduler.EcoScheduler;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;

/** Dynamic, industry-specific and bracket tax-rate management. */
public final class TaxRateManager {

    private static final long RATE_REFRESH_TICKS = 20L * 30L;

    private final KsEco eco;
    private final AtomicBoolean refreshQueued = new AtomicBoolean();

    private volatile Map<String, Double> rates = defaultRates();
    private volatile Map<String, Map<String, Double>> industryRates = defaultIndustryRates();
    private volatile List<TaxBracket> brackets = List.of();
    private EcoScheduler.TaskHandle refreshTask;

    public TaxRateManager(KsEco eco) {
        this.eco = eco;
    }

    public void init() {
        rates = defaultRates();
        industryRates = defaultIndustryRates();
        brackets = List.of();
        queueRefresh();
        refreshTask = eco.scheduler().runGlobalTimer(
                this::queueRefresh,
                RATE_REFRESH_TICKS,
                RATE_REFRESH_TICKS);
    }

    public void shutdown() {
        if (refreshTask != null) {
            refreshTask.cancel();
            refreshTask = null;
        }
    }

    private void queueRefresh() {
        if (!refreshQueued.compareAndSet(false, true)) return;
        try {
            eco.asyncWorkPool().executeDatabase(() -> {
                try {
                    refreshFromDatabase();
                } finally {
                    refreshQueued.set(false);
                }
            });
        } catch (RejectedExecutionException exception) {
            refreshQueued.set(false);
            eco.getLogger().warning("[TaxRate] Database queue rejected a rate refresh");
        }
    }

    private synchronized void refreshFromDatabase() {
        Map<String, Double> loadedRates = new LinkedHashMap<>(defaultRates());
        Map<String, Map<String, Double>> loadedIndustryRates = mutableIndustryCopy(defaultIndustryRates());
        List<TaxBracket> loadedBrackets = new ArrayList<>();

        try (Connection conn = eco.ksCore().dataStore().getConnection()) {
            if (conn == null) return;
            ensureTables(conn);
            migrateLegacyIndustryRates(conn);

            String generalRateQuery = TaxJdbcSupport.hasColumn(conn, "ks_tax_rates", "industry")
                    ? "SELECT category, rate FROM ks_tax_rates WHERE industry IS NULL OR industry=''"
                    : "SELECT category, rate FROM ks_tax_rates";
            try (Statement stmt = conn.createStatement();
                 ResultSet rs = stmt.executeQuery(generalRateQuery)) {
                while (rs.next()) {
                    String category = normalizeKey(rs.getString("category"));
                    if (!category.isEmpty()) {
                        loadedRates.put(category,
                                TaxValuePolicy.normalizeRate(rs.getDouble("rate"),
                                        loadedRates.getOrDefault(category, 0.02d)));
                    }
                }
            }

            try (Statement stmt = conn.createStatement();
                 ResultSet rs = stmt.executeQuery(
                         "SELECT category, industry, rate FROM ks_tax_industry_rates")) {
                while (rs.next()) {
                    String category = normalizeKey(rs.getString("category"));
                    String industry = normalizeKey(rs.getString("industry"));
                    if (category.isEmpty() || industry.isEmpty()) continue;
                    Map<String, Double> categoryRates = loadedIndustryRates.computeIfAbsent(
                            industry, ignored -> new LinkedHashMap<>());
                    categoryRates.put(category,
                            TaxValuePolicy.normalizeRate(rs.getDouble("rate"),
                                    categoryRates.getOrDefault(category,
                                            loadedRates.getOrDefault(category, 0.02d))));
                }
            }

            try (Statement stmt = conn.createStatement();
                 ResultSet rs = stmt.executeQuery(
                         "SELECT id, industry, scope, profit_min, profit_max, rate "
                                 + "FROM ks_tax_brackets")) {
                while (rs.next()) {
                    double minimum = rs.getDouble("profit_min");
                    double maximum = rs.getDouble("profit_max");
                    if (!Double.isFinite(minimum) || !Double.isFinite(maximum) || maximum <= minimum) {
                        continue;
                    }
                    loadedBrackets.add(new TaxBracket(
                            rs.getString("id"),
                            normalizeKey(rs.getString("industry")),
                            normalizeKey(rs.getString("scope")),
                            minimum,
                            maximum,
                            TaxValuePolicy.normalizeRate(rs.getDouble("rate"), 0.0d)));
                }
            }
        } catch (SQLException | RuntimeException exception) {
            eco.getLogger().warning("[TaxRate] Failed to refresh rates: " + exception.getMessage());
            return;
        }

        loadedBrackets.sort(Comparator.comparing(TaxBracket::industry)
                .thenComparing(TaxBracket::scope)
                .thenComparingDouble(TaxBracket::profitMin));
        rates = Collections.unmodifiableMap(loadedRates);
        industryRates = immutableIndustryCopy(loadedIndustryRates);
        brackets = List.copyOf(loadedBrackets);
    }

    private void ensureTables(Connection conn) throws SQLException {
        TaxJdbcSupport.ensureTables(conn);
    }

    private void migrateLegacyIndustryRates(Connection conn) {
        try {
            if (!TaxJdbcSupport.hasColumn(conn, "ks_tax_rates", "industry")) return;
        } catch (SQLException ignored) {
            return;
        }
        List<LegacyIndustryRate> legacyRates = new ArrayList<>();
        try (Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery(
                     "SELECT category, industry, rate FROM ks_tax_rates "
                             + "WHERE industry IS NOT NULL AND industry<>''")) {
            while (rs.next()) {
                String category = normalizeKey(rs.getString("category"));
                String industry = normalizeKey(rs.getString("industry"));
                if (!category.isEmpty() && !industry.isEmpty()) {
                    legacyRates.add(new LegacyIndustryRate(category, industry,
                            TaxValuePolicy.normalizeRate(rs.getDouble("rate"), 0.0d)));
                }
            }
        } catch (SQLException ignored) {
            return;
        }

        long now = System.currentTimeMillis() / 1000L;
        for (LegacyIndustryRate legacy : legacyRates) {
            try {
                TaxJdbcSupport.insertIndustryRateIfAbsent(
                        conn, legacy.category(), legacy.industry(), legacy.rate(), now);
            } catch (SQLException exception) {
                eco.getLogger().warning("[TaxRate] Failed to migrate legacy industry rate "
                        + legacy.industry() + "/" + legacy.category() + ": " + exception.getMessage());
            }
        }
    }

    public double getRate(String category) {
        String normalizedCategory = normalizeKey(category);
        return rates.getOrDefault(normalizedCategory, 0.02d);
    }

    public double getRate(String category, String industry) {
        String normalizedCategory = normalizeKey(category);
        String normalizedIndustry = normalizeKey(industry);
        Map<String, Double> configured = industryRates.get(normalizedIndustry);
        if (configured != null && configured.containsKey(normalizedCategory)) {
            return configured.get(normalizedCategory);
        }
        return getRate(normalizedCategory);
    }

    public void setRate(String category, double rate, String industry) {
        String normalizedCategory = normalizeKey(category);
        String normalizedIndustry = normalizeKey(industry);
        if (!isValidKey(normalizedCategory) || (!normalizedIndustry.isEmpty()
                && !isValidKey(normalizedIndustry)) || !Double.isFinite(rate) || rate < 0.0d) {
            eco.getLogger().warning("[TaxRate] Rejected invalid rate update");
            return;
        }

        double normalizedRate = TaxValuePolicy.normalizeRate(rate, 0.0d);
        Runnable persist = () -> persistRateUpdate(
                normalizedCategory, normalizedIndustry, normalizedRate);
        try {
            eco.asyncWorkPool().executeDatabase(persist);
        } catch (RejectedExecutionException exception) {
            eco.getLogger().warning("[TaxRate] Database queue rejected a rate update");
        }
    }

    public void setRate(String category, double rate) {
        setRate(category, rate, null);
    }

    private synchronized void persistRateUpdate(String category, String industry, double rate) {
        long now = System.currentTimeMillis() / 1000L;
        try (Connection conn = eco.ksCore().dataStore().getConnection()) {
            if (conn == null) return;
            ensureTables(conn);
            if (industry.isEmpty()) {
                TaxJdbcSupport.upsertGeneralRate(conn, category, rate, now);
            } else {
                TaxJdbcSupport.upsertIndustryRate(conn, category, industry, rate, now);
            }
        } catch (SQLException | RuntimeException exception) {
            eco.getLogger().warning("[TaxRate] Failed to save rate: " + exception.getMessage());
            return;
        }

        if (industry.isEmpty()) {
            Map<String, Double> updated = new LinkedHashMap<>(rates);
            updated.put(category, rate);
            rates = Collections.unmodifiableMap(updated);
        } else {
            Map<String, Map<String, Double>> updated = mutableIndustryCopy(industryRates);
            updated.computeIfAbsent(industry, ignored -> new LinkedHashMap<>()).put(category, rate);
            industryRates = immutableIndustryCopy(updated);
        }
    }

    public Map<String, Double> getAllRates() {
        Map<String, Double> merged = new LinkedHashMap<>(rates);
        for (Map.Entry<String, Map<String, Double>> industry : industryRates.entrySet()) {
            for (Map.Entry<String, Double> category : industry.getValue().entrySet()) {
                merged.put(industry.getKey() + ":" + category.getKey(), category.getValue());
            }
        }
        return merged;
    }

    public Map<String, Double> getIndustryRates(String industry) {
        return industryRates.getOrDefault(normalizeKey(industry), Map.of());
    }

    public Map<String, Map<String, Double>> getAllIndustryRates() {
        return industryRates;
    }

    public double getBracketRate(String industry, String scope, double profit) {
        if (!Double.isFinite(profit) || profit < 0.0d) return -1.0d;
        String normalizedIndustry = normalizeKey(industry);
        String normalizedScope = normalizeKey(scope);
        TaxBracket match = null;
        for (TaxBracket bracket : brackets) {
            if (!bracket.industry().equals(normalizedIndustry)
                    || !bracket.scope().equals(normalizedScope)
                    || profit < bracket.profitMin()
                    || profit >= bracket.profitMax()) continue;
            if (match == null || bracket.profitMin() > match.profitMin()) match = bracket;
        }
        return match == null ? -1.0d : match.rate();
    }

    public List<Map<String, Object>> listBrackets(String industry) {
        String normalizedIndustry = normalizeKey(industry);
        List<Map<String, Object>> result = new ArrayList<>();
        for (TaxBracket bracket : brackets) {
            if (!bracket.industry().equals(normalizedIndustry)) continue;
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", bracket.id());
            row.put("industry", bracket.industry());
            row.put("scope", bracket.scope());
            row.put("profitMin", bracket.profitMin());
            row.put("profitMax", bracket.profitMax());
            row.put("rate", bracket.rate());
            result.add(row);
        }
        return result;
    }

    public synchronized boolean upsertBracket(String id, String industry, String scope,
                                 double profitMin, double profitMax, double rate) {
        String normalizedId = id == null ? "" : id.trim();
        String normalizedIndustry = normalizeKey(industry);
        String normalizedScope = normalizeKey(scope);
        if (!isValidId(normalizedId) || !isValidKey(normalizedIndustry)
                || !isValidKey(normalizedScope)
                || !Double.isFinite(profitMin) || !Double.isFinite(profitMax)
                || profitMin < 0.0d || profitMax <= profitMin
                || !Double.isFinite(rate) || rate < 0.0d) return false;

        double normalizedRate = TaxValuePolicy.normalizeRate(rate, 0.0d);
        long now = System.currentTimeMillis() / 1000L;
        try (Connection conn = eco.ksCore().dataStore().getConnection()) {
            if (conn == null) return false;
            ensureTables(conn);
            TaxJdbcSupport.upsertBracket(conn, normalizedId, normalizedIndustry, normalizedScope,
                    profitMin, profitMax, normalizedRate, now);
        } catch (SQLException | RuntimeException exception) {
            eco.getLogger().warning("[TaxRate] Failed to save bracket: " + exception.getMessage());
            return false;
        }
        refreshFromDatabase();
        return true;
    }

    public synchronized boolean deleteBracket(String id) {
        if (id == null || !isValidId(id.trim())) return false;
        boolean deleted;
        try (Connection conn = eco.ksCore().dataStore().getConnection()) {
            if (conn == null) return false;
            ensureTables(conn);
            try (PreparedStatement ps = conn.prepareStatement(
                    "DELETE FROM ks_tax_brackets WHERE id=?")) {
                ps.setString(1, id.trim());
                deleted = ps.executeUpdate() > 0;
            }
        } catch (SQLException | RuntimeException exception) {
            eco.getLogger().warning("[TaxRate] Failed to delete bracket: " + exception.getMessage());
            return false;
        }
        if (deleted) refreshFromDatabase();
        return deleted;
    }

    private static String normalizeKey(String value) {
        return value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
    }

    private static boolean isValidId(String value) {
        return value != null && !value.isEmpty() && value.length() <= TaxJdbcSupport.ID_MAX_LENGTH;
    }

    private static boolean isValidKey(String value) {
        return value != null && !value.isEmpty() && value.length() <= TaxJdbcSupport.KEY_MAX_LENGTH;
    }

    private static Map<String, Double> defaultRates() {
        Map<String, Double> defaults = new LinkedHashMap<>();
        defaults.put("MARKET_TRADE", 0.02d);
        defaults.put("OFFICIAL_TRADE", 0.0d);
        defaults.put("ENTERPRISE_TAX", 0.08d);
        defaults.put("ENTERPRISE_SMALL", 0.05d);
        defaults.put("ENTERPRISE_MEDIUM", 0.08d);
        defaults.put("ENTERPRISE_LARGE", 0.12d);
        defaults.put("BANK_INTEREST", 0.10d);
        defaults.put("PLAYER_TRANSFER", 0.01d);
        defaults.put("PENALTY_TAX", 0.20d);
        defaults.put("TAX_PENALTY", 0.20d);
        defaults.put("DIVIDEND_TAX", 0.10d);
        return Collections.unmodifiableMap(defaults);
    }

    private static Map<String, Map<String, Double>> defaultIndustryRates() {
        Map<String, Map<String, Double>> defaults = new LinkedHashMap<>();
        defaults.put("REAL_ESTATE", Map.of(
                "DIVIDEND_TAX", 0.05d,
                "ENTERPRISE_TAX", 0.10d));
        defaults.put("AGRICULTURE", Map.of(
                "ENTERPRISE_TAX", 0.05d,
                "MARKET_TRADE", 0.01d));
        defaults.put("INDUSTRY", Map.of("DIVIDEND_TAX", 0.12d));
        return immutableIndustryCopy(defaults);
    }

    private static Map<String, Map<String, Double>> mutableIndustryCopy(
            Map<String, Map<String, Double>> source) {
        Map<String, Map<String, Double>> copy = new LinkedHashMap<>();
        for (Map.Entry<String, Map<String, Double>> entry : source.entrySet()) {
            copy.put(entry.getKey(), new LinkedHashMap<>(entry.getValue()));
        }
        return copy;
    }

    private static Map<String, Map<String, Double>> immutableIndustryCopy(
            Map<String, Map<String, Double>> source) {
        Map<String, Map<String, Double>> copy = new LinkedHashMap<>();
        for (Map.Entry<String, Map<String, Double>> entry : source.entrySet()) {
            copy.put(entry.getKey(), Collections.unmodifiableMap(
                    new LinkedHashMap<>(entry.getValue())));
        }
        return Collections.unmodifiableMap(copy);
    }

    private record TaxBracket(String id, String industry, String scope,
                              double profitMin, double profitMax, double rate) {
    }

    private record LegacyIndustryRate(String category, String industry, double rate) {
    }
}
