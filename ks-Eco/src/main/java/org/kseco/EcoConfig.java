package org.kseco;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.*;

/**
 * ks-Eco 配置管理。
 */
public final class EcoConfig {

    private final JavaPlugin plugin;

    // 市场配置
    private int maxListingsPerPlayer;
    private int listingExpireHours;
    private double taxRate;
    private double minTax;
    private boolean marketGuiEnabled;

    // 官方收购配置
    private boolean officialBuyEnabled;
    private double priceFluctuation;
    private int priceRefreshMinutes;
    private final List<OfficialItem> defaultBuyItems = new ArrayList<>();

    // 市场波动模拟配置（仅作为 DB 无记录时的启动默认值，运行时以 web/DB 为准）
    private boolean volatilityEnabled;
    private double volatilityMaxFluctuation;
    private double volatilityStepStdDev;
    private double volatilityPullStrength;
    private double volatilityReversionRate;
    private int volatilityOversupplyWindowHours;
    private int volatilityBaselineLookbackDays;
    private double volatilityDefaultBaselineQty;
    private int volatilityReportIntervalHours;
    private int volatilityReportTopN;

    // 潜影盒配置
    private int maxRecursionDepth;
    private boolean countShulkerContents;
    private double emptyBoxValue;

    // 暂存箱配置
    private int maxStorageSlots;
    private int maxStorageDays;

    // Market protection and official warehouse
    private boolean marketProtectionEnabled;
    private double internalFallbackUnitValue;
    private final Map<String, Double> internalReferencePrices = new HashMap<>();
    private double feBasicEnchantPerLevel;
    private double feMidEnchantPerLevel;
    private double feAdvancedEnchantPerLevel;
    private final Map<String, String> feEnchantTiers = new HashMap<>();
    private boolean officialSweepEnabled;
    private int officialSweepIntervalSeconds;
    private int officialSweepSampleSize;

    // Extra 模块配置
    private boolean extraEnabled;
    private String extraDirectory;
    private final List<String> enabledModules = new ArrayList<>();

    public EcoConfig(JavaPlugin plugin) {
        this.plugin = plugin;
        reload();
    }

    public void reload() {
        plugin.reloadConfig();
        var cfg = plugin.getConfig();

        // 市场
        var market = cfg.getConfigurationSection("market");
        if (market != null) {
            maxListingsPerPlayer = market.getInt("max-listings-per-player", 20);
            listingExpireHours = market.getInt("listing-expire-hours", 168);
            taxRate = market.getDouble("tax-rate", 0.02);
            minTax = market.getDouble("min-tax", 1.0);
            marketGuiEnabled = market.getBoolean("gui-enabled", true);
        }

        // 官方收购
        var buy = cfg.getConfigurationSection("official-buy");
        if (buy != null) {
            officialBuyEnabled = buy.getBoolean("enabled", true);
            priceFluctuation = buy.getDouble("price-fluctuation", 0.3);
            priceRefreshMinutes = buy.getInt("price-refresh-minutes", 60);
            defaultBuyItems.clear();
            var items = buy.getMapList("default-items");
            for (var item : items) {
                String mat = (String) item.get("material");
                double price = ((Number) item.get("base-price")).doubleValue();
                defaultBuyItems.add(new OfficialItem(mat, price));
            }
        }

        // 市场波动模拟
        var vol = cfg.getConfigurationSection("price-volatility");
        if (vol != null) {
            volatilityEnabled = vol.getBoolean("enabled", true);
            volatilityMaxFluctuation = vol.getDouble("max-fluctuation", 0.3);
            volatilityStepStdDev = vol.getDouble("step-std-dev", 0.03);
            volatilityPullStrength = vol.getDouble("pull-strength", 0.15);
            volatilityReversionRate = vol.getDouble("natural-reversion-rate", 0.05);
            volatilityOversupplyWindowHours = vol.getInt("oversupply-window-hours", 24);
            volatilityBaselineLookbackDays = vol.getInt("baseline-lookback-days", 14);
            volatilityDefaultBaselineQty = vol.getDouble("default-baseline-qty", 64);
            volatilityReportIntervalHours = vol.getInt("report-interval-hours", 6);
            volatilityReportTopN = vol.getInt("report-top-n", 5);
        } else {
            volatilityEnabled = true;
            volatilityMaxFluctuation = 0.3;
            volatilityStepStdDev = 0.03;
            volatilityPullStrength = 0.15;
            volatilityReversionRate = 0.05;
            volatilityOversupplyWindowHours = 24;
            volatilityBaselineLookbackDays = 14;
            volatilityDefaultBaselineQty = 64;
            volatilityReportIntervalHours = 6;
            volatilityReportTopN = 5;
        }

        // 潜影盒
        var sb = cfg.getConfigurationSection("shulker-box");
        if (sb != null) {
            maxRecursionDepth = sb.getInt("max-recursion-depth", 3);
            countShulkerContents = sb.getBoolean("count-contents", true);
            emptyBoxValue = sb.getDouble("empty-box-value", 5.0);
        }

        // 暂存箱
        var st = cfg.getConfigurationSection("storage");
        if (st != null) {
            maxStorageSlots = st.getInt("max-slots", 54);
            maxStorageDays = st.getInt("max-days", 30);
        }

        var protection = cfg.getConfigurationSection("market-protection");
        marketProtectionEnabled = protection == null || protection.getBoolean("enabled", true);
        internalFallbackUnitValue = protection == null ? 1.0 : protection.getDouble("internal-fallback-unit-value", 1.0);
        internalReferencePrices.clear();
        if (protection != null) {
            var prices = protection.getConfigurationSection("internal-reference-prices");
            if (prices != null) {
                for (String material : prices.getKeys(false)) {
                    double value = prices.getDouble(material, -1.0);
                    if (Double.isFinite(value) && value > 0) {
                        internalReferencePrices.put(material.toUpperCase(Locale.ROOT), value);
                    }
                }
            }
        }
        feBasicEnchantPerLevel = protection == null ? 30.0 : protection.getDouble("fe-basic-per-level", 30.0);
        feMidEnchantPerLevel = protection == null ? 60.0 : protection.getDouble("fe-mid-per-level", 60.0);
        feAdvancedEnchantPerLevel = protection == null ? 100.0 : protection.getDouble("fe-advanced-per-level", 100.0);
        feEnchantTiers.clear();
        if (protection != null) {
            var tiers = protection.getConfigurationSection("fe-enchantment-tiers");
            if (tiers != null) {
                for (String id : tiers.getKeys(false)) {
                    feEnchantTiers.put(id.toLowerCase(Locale.ROOT), tiers.getString(id, "MID").toUpperCase(Locale.ROOT));
                }
            }
        }

        var sweep = cfg.getConfigurationSection("official-market-sweep");
        officialSweepEnabled = sweep == null || sweep.getBoolean("enabled", true);
        officialSweepIntervalSeconds = sweep == null ? 90 : Math.max(15, sweep.getInt("interval-seconds", 90));
        officialSweepSampleSize = sweep == null ? 8 : Math.max(1, sweep.getInt("sample-size", 8));

        // Extra 模块
        var extra = cfg.getConfigurationSection("extra-modules");
        if (extra != null) {
            extraEnabled = extra.getBoolean("enabled", true);
            extraDirectory = extra.getString("directory", "extra");
            enabledModules.clear();
            enabledModules.addAll(extra.getStringList("enabled-modules"));
        } else {
            extraEnabled = true;
            extraDirectory = "extra";
        }
    }

    // ---- Getters ----

    public int getMaxListingsPerPlayer() { return maxListingsPerPlayer; }
    public int getListingExpireHours() { return listingExpireHours; }
    public double getTaxRate() { return taxRate; }
    public double getMinTax() { return minTax; }
    public boolean isMarketGuiEnabled() { return marketGuiEnabled; }

    public boolean isOfficialBuyEnabled() { return officialBuyEnabled; }
    public double getPriceFluctuation() { return priceFluctuation; }
    public int getPriceRefreshMinutes() { return priceRefreshMinutes; }
    public List<OfficialItem> getDefaultBuyItems() { return defaultBuyItems; }

    public boolean isVolatilityEnabled() { return volatilityEnabled; }
    public double getVolatilityMaxFluctuation() { return volatilityMaxFluctuation; }
    public double getVolatilityStepStdDev() { return volatilityStepStdDev; }
    public double getVolatilityPullStrength() { return volatilityPullStrength; }
    public double getVolatilityReversionRate() { return volatilityReversionRate; }
    public int getVolatilityOversupplyWindowHours() { return volatilityOversupplyWindowHours; }
    public int getVolatilityBaselineLookbackDays() { return volatilityBaselineLookbackDays; }
    public double getVolatilityDefaultBaselineQty() { return volatilityDefaultBaselineQty; }
    public int getVolatilityReportIntervalHours() { return volatilityReportIntervalHours; }
    public int getVolatilityReportTopN() { return volatilityReportTopN; }

    public int getMaxRecursionDepth() { return maxRecursionDepth; }
    public boolean isCountShulkerContents() { return countShulkerContents; }
    public double getEmptyBoxValue() { return emptyBoxValue; }

    public int getMaxStorageSlots() { return maxStorageSlots; }
    public int getMaxStorageDays() { return maxStorageDays; }

    public boolean isMarketProtectionEnabled() { return marketProtectionEnabled; }
    public double getInternalFallbackUnitValue() { return internalFallbackUnitValue; }
    public double getInternalReferencePrice(String material) {
        if (material == null) return internalFallbackUnitValue;
        return internalReferencePrices.getOrDefault(material.toUpperCase(Locale.ROOT), internalFallbackUnitValue);
    }
    public boolean hasInternalReferencePrice(String material) {
        return material != null && internalReferencePrices.containsKey(material.toUpperCase(Locale.ROOT));
    }
    public double getVanillaEnchantPerLevel() { return feMidEnchantPerLevel; }
    public double getFeEnchantPerLevel(String enchantId) {
        String tier = feEnchantTiers.getOrDefault(enchantId == null ? "" : enchantId.toLowerCase(Locale.ROOT), "MID");
        return switch (tier) {
            case "BASIC" -> feBasicEnchantPerLevel;
            case "ADVANCED", "HIGH" -> feAdvancedEnchantPerLevel;
            default -> feMidEnchantPerLevel;
        };
    }
    public boolean isOfficialSweepEnabled() { return officialSweepEnabled; }
    public int getOfficialSweepIntervalSeconds() { return officialSweepIntervalSeconds; }
    public int getOfficialSweepSampleSize() { return officialSweepSampleSize; }

    public boolean isExtraEnabled() { return extraEnabled; }
    public String getExtraDirectory() { return extraDirectory; }
    public List<String> getEnabledModules() { return enabledModules; }

    // ---- 数据类 ----

    public record OfficialItem(String material, double basePrice) {}
}
