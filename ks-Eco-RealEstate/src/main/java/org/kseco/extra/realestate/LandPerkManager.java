package org.kseco.extra.realestate;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.Furnace;
import org.bukkit.block.data.Ageable;
import org.bukkit.scheduler.BukkitTask;
import org.kseco.KsEco;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Land perks for agricultural/industrial plots.
 *
 * Zone type still grants the default perk, but admins can now enable both
 * functions on a single purchased plot and override that plot's numbers.
 */
public final class LandPerkManager {

    private final KsEco eco;
    private final RealEstateManager realEstate;

    private static final Map<String, Double> DEFAULTS = new HashMap<>();
    static {
        DEFAULTS.put("agri_growth_boost_chance", 1.0);
        DEFAULTS.put("agri_growth_interval_ticks", 100.0);
        DEFAULTS.put("agri_growth_steps", 1.0);
        DEFAULTS.put("agri_growth_samples", 64.0);
        DEFAULTS.put("agri_growth_max_samples_per_second", 4096.0);
        DEFAULTS.put("agri_growth_min_tps", 18.5);
        DEFAULTS.put("agri_harvest_yield_bonus_chance", 0.20);
        DEFAULTS.put("agri_official_premium_pct", 0.10);
        DEFAULTS.put("industry_furnace_speed_pct", 0.20);
        DEFAULTS.put("industry_furnace_bonus_output_chance", 0.10);
        DEFAULTS.put("industry_bidding_reputation_bonus_pct", 0.05);
        DEFAULTS.put("industry_max_events_per_second", 512.0);
        DEFAULTS.put("industry_min_tps", 18.0);
        DEFAULTS.put("agri_scope_mode", 0.0);
        DEFAULTS.put("industry_scope_mode", 0.0);
        DEFAULTS.put("perk_sync_time_budget_ms", 8.0);
    }

    private static final long OWNERSHIP_CACHE_TTL_MS = 30_000;
    private final Map<String, CacheEntry> ownershipCache = new ConcurrentHashMap<>();
    private volatile Map<String, Double> perkConfig = new ConcurrentHashMap<>(DEFAULTS);
    private volatile Map<String, PlotPerk> plotPerks = new ConcurrentHashMap<>();
    private final Map<String, List<FarmSpot>> farmSpotCache = new ConcurrentHashMap<>();
    private final Map<String, List<FurnaceSpot>> furnaceSpotCache = new ConcurrentHashMap<>();
    private final Map<String, Integer> farmPlayerCursor = new ConcurrentHashMap<>();
    private final Map<String, Long> nextGrowthRunAt = new ConcurrentHashMap<>();
    private BukkitTask growthTask;
    private long perfWindowStartedAt = System.currentTimeMillis();
    private int agriSamplesThisWindow = 0;
    private int industryEventsThisWindow = 0;
    private volatile int lastAgriSamplesPerSecond = 0;
    private volatile int lastIndustryEventsPerSecond = 0;
    private volatile double lastTps = 20.0;
    private volatile long lastTpsCheckedAt = 0L;
    private volatile boolean agriPausedByTps = false;
    private volatile boolean industryPausedByTps = false;
    private final AtomicBoolean perkTickQueued = new AtomicBoolean(false);

    private static final class CacheEntry {
        final boolean value;
        final long expiresAt;
        CacheEntry(boolean value, long expiresAt) { this.value = value; this.expiresAt = expiresAt; }
    }

    private record PlotPerk(
            String plotId,
            boolean agriEnabled,
            boolean industryEnabled,
            int agriGrowthIntervalTicks,
            int agriGrowthSteps,
            int agriGrowthSamples,
            Double agriHarvestYieldBonusChance,
            Double agriOfficialPremiumPct,
            Double industryFurnaceSpeedPct,
            Double industryFurnaceBonusOutputChance,
            Double industryBiddingReputationBonusPct
    ) {}

    private record FarmSpot(String world, int x, int y, int z) {}

    private record FurnaceSpot(String world, int x, int y, int z) {}

    private record CropWork(String plotId, String world, int x1, int z1, int x2, int z2, int steps, int samples) {}

    private record FurnaceWork(String plotId, String world, int x1, int z1, int x2, int z2) {}

    public LandPerkManager(KsEco eco, RealEstateManager realEstate) {
        this.eco = eco;
        this.realEstate = realEstate;
    }

    public void init() {
        ensureTable();
        refreshPerkConfigCache();
        refreshPlotPerkCache();
    }

    public void startCropGrowthTask() {
        stopCropGrowthTask();
        // Bukkit block access must remain on the server thread. Every scan is bounded
        // by the shared per-run deadline in the apply phase.
        growthTask = Bukkit.getScheduler().runTaskTimer(eco, this::planPerkTickAsync, 40L, 20L);
    }

    public void stopCropGrowthTask() {
        if (growthTask != null) {
            growthTask.cancel();
            growthTask = null;
        }
        perkTickQueued.set(false);
    }

    private void ensureTable() {
        try (var conn = eco.ksCore().dataStore().getConnection()) {
            if (conn == null) return;
            try (var s = conn.createStatement()) {
                s.execute("""
                    CREATE TABLE IF NOT EXISTS ks_re_land_perk_config (
                        perk_key TEXT PRIMARY KEY,
                        perk_value REAL NOT NULL,
                        updated_at INTEGER NOT NULL
                    )
                """);
                s.execute("""
                    CREATE TABLE IF NOT EXISTS ks_re_plot_perks (
                        plot_id TEXT PRIMARY KEY,
                        agri_enabled INTEGER NOT NULL DEFAULT 0,
                        industry_enabled INTEGER NOT NULL DEFAULT 0,
                        agri_growth_interval_ticks INTEGER NOT NULL DEFAULT 0,
                        agri_growth_steps INTEGER NOT NULL DEFAULT 0,
                        agri_growth_samples INTEGER NOT NULL DEFAULT 0,
                        agri_harvest_yield_bonus_chance REAL,
                        agri_official_premium_pct REAL,
                        industry_furnace_speed_pct REAL,
                        industry_furnace_bonus_output_chance REAL,
                        industry_bidding_reputation_bonus_pct REAL,
                        updated_at INTEGER NOT NULL
                    )
                """);
                try { s.execute("ALTER TABLE ks_re_plot_perks ADD COLUMN agri_growth_samples INTEGER NOT NULL DEFAULT 0"); } catch (SQLException ignored) {}
            }
        } catch (SQLException e) {
            eco.getLogger().warning("[LandPerk] init table failed: " + e.getMessage());
        }
    }

    public double getPerkValue(String key, double def) {
        Double cached = perkConfig.get(key);
        if (cached != null) return cached;
        if (DEFAULTS.containsKey(key)) return DEFAULTS.get(key);
        return def;
    }

    private void refreshPerkConfigCache() {
        Map<String, Double> next = new ConcurrentHashMap<>(DEFAULTS);
        try (var conn = eco.ksCore().dataStore().getConnection()) {
            if (conn == null) { perkConfig = next; return; }
            try (var s = conn.createStatement();
                 var rs = s.executeQuery("SELECT perk_key, perk_value FROM ks_re_land_perk_config")) {
                while (rs.next()) next.put(rs.getString(1), rs.getDouble(2));
            }
        } catch (SQLException ignored) {}
        perkConfig = next;
    }

    public boolean setPerkValue(String key, double value) {
        long now = System.currentTimeMillis() / 1000;
        try (var conn = eco.ksCore().dataStore().getConnection()) {
            if (conn == null) return false;
            try (var ps = conn.prepareStatement(
                    "INSERT INTO ks_re_land_perk_config (perk_key, perk_value, updated_at) VALUES (?,?,?) " +
                    "ON CONFLICT(perk_key) DO UPDATE SET perk_value=excluded.perk_value, updated_at=excluded.updated_at")) {
                ps.setString(1, key);
                ps.setDouble(2, value);
                ps.setLong(3, now);
                ps.executeUpdate();
            }
        } catch (SQLException e) {
            eco.getLogger().warning("[LandPerk] set config failed: " + e.getMessage());
            return false;
        }
        Map<String, Double> next = new ConcurrentHashMap<>(perkConfig);
        next.put(key, value);
        perkConfig = next;
        ownershipCache.clear();
        nextGrowthRunAt.clear();
        return true;
    }

    public Map<String, Double> getAllPerkConfig() {
        Map<String, Double> out = new LinkedHashMap<>(DEFAULTS);
        out.putAll(perkConfig);
        return out;
    }

    public Map<String, Object> getRuntimeStats() {
        long now = System.currentTimeMillis();
        double tps = currentTps();
        lastTps = tps;
        Map<String, Object> out = new LinkedHashMap<>();
        synchronized (this) {
            resetPerfWindow(now);
            out.put("agriSamplesThisSecond", agriSamplesThisWindow);
            out.put("industryEventsThisSecond", industryEventsThisWindow);
        }
        out.put("lastAgriSamplesPerSecond", lastAgriSamplesPerSecond);
        out.put("lastIndustryEventsPerSecond", lastIndustryEventsPerSecond);
        out.put("currentTps", tps);
        out.put("agriPausedByTps", tpsBlocked("agri_growth_min_tps", 18.5, tps));
        out.put("industryPausedByTps", tpsBlocked("industry_min_tps", 18.0, tps));
        return out;
    }

    public Map<String, Object> getPlotPerkConfig(String plotId) {
        PlotPerk perk = plotPerks.get(plotId);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("plotId", plotId);
        out.put("configured", perk != null);
        out.put("agriEnabled", perk != null && perk.agriEnabled);
        out.put("industryEnabled", perk != null && perk.industryEnabled);
        out.put("agriGrowthIntervalTicks", perk != null && perk.agriGrowthIntervalTicks > 0
                ? perk.agriGrowthIntervalTicks : intDefault("agri_growth_interval_ticks", 100));
        out.put("agriGrowthSteps", perk != null && perk.agriGrowthSteps > 0
                ? perk.agriGrowthSteps : intDefault("agri_growth_steps",
                Math.max(1, (int) Math.ceil(getPerkValue("agri_growth_boost_chance", 1.0)))));
        out.put("agriGrowthSamples", perk != null && perk.agriGrowthSamples > 0
                ? perk.agriGrowthSamples : intDefault("agri_growth_samples", 64));
        out.put("agriHarvestYieldBonusChance", valueOrDefault(perk == null ? null : perk.agriHarvestYieldBonusChance,
                "agri_harvest_yield_bonus_chance", 0.20));
        out.put("agriOfficialPremiumPct", valueOrDefault(perk == null ? null : perk.agriOfficialPremiumPct,
                "agri_official_premium_pct", 0.10));
        out.put("industryFurnaceSpeedPct", valueOrDefault(perk == null ? null : perk.industryFurnaceSpeedPct,
                "industry_furnace_speed_pct", 0.20));
        out.put("industryFurnaceBonusOutputChance", valueOrDefault(perk == null ? null : perk.industryFurnaceBonusOutputChance,
                "industry_furnace_bonus_output_chance", 0.10));
        out.put("industryBiddingReputationBonusPct", valueOrDefault(perk == null ? null : perk.industryBiddingReputationBonusPct,
                "industry_bidding_reputation_bonus_pct", 0.05));
        return out;
    }

    public boolean setPlotPerkConfig(String plotId, Map<String, Object> req) {
        if (plotId == null || plotId.isBlank()) return false;
        long now = System.currentTimeMillis() / 1000;
        boolean agri = toBool(req.get("agriEnabled"));
        boolean industry = toBool(req.get("industryEnabled"));
        int intervalTicks = clampInt((int) Math.round(toDouble(req.get("agriGrowthIntervalTicks"),
                toDouble(req.get("agriGrowthIntervalSeconds"), 5.0) * 20.0)), 20, 20 * 60);
        int steps = clampInt((int) Math.round(toDouble(req.get("agriGrowthSteps"), 1)), 1, 8);
        int samples = clampInt((int) Math.round(toDouble(req.get("agriGrowthSamples"), 64)), 1, 2048);
        double harvest = clamp01(toDouble(req.get("agriHarvestYieldBonusChance"), getPerkValue("agri_harvest_yield_bonus_chance", 0.20)));
        double premium = Math.max(0, toDouble(req.get("agriOfficialPremiumPct"), getPerkValue("agri_official_premium_pct", 0.10)));
        double furnaceSpeed = Math.min(0.95, Math.max(0, toDouble(req.get("industryFurnaceSpeedPct"), getPerkValue("industry_furnace_speed_pct", 0.20))));
        double furnaceBonus = clamp01(toDouble(req.get("industryFurnaceBonusOutputChance"), getPerkValue("industry_furnace_bonus_output_chance", 0.10)));
        double bidBonus = Math.max(0, toDouble(req.get("industryBiddingReputationBonusPct"), getPerkValue("industry_bidding_reputation_bonus_pct", 0.05)));

        try (var conn = eco.ksCore().dataStore().getConnection()) {
            if (conn == null) return false;
            try (var ps = conn.prepareStatement(
                    "INSERT INTO ks_re_plot_perks (plot_id, agri_enabled, industry_enabled, agri_growth_interval_ticks, agri_growth_steps, agri_growth_samples, agri_harvest_yield_bonus_chance, agri_official_premium_pct, industry_furnace_speed_pct, industry_furnace_bonus_output_chance, industry_bidding_reputation_bonus_pct, updated_at) " +
                    "VALUES (?,?,?,?,?,?,?,?,?,?,?,?) " +
                    "ON CONFLICT(plot_id) DO UPDATE SET agri_enabled=excluded.agri_enabled, industry_enabled=excluded.industry_enabled, agri_growth_interval_ticks=excluded.agri_growth_interval_ticks, agri_growth_steps=excluded.agri_growth_steps, agri_growth_samples=excluded.agri_growth_samples, agri_harvest_yield_bonus_chance=excluded.agri_harvest_yield_bonus_chance, agri_official_premium_pct=excluded.agri_official_premium_pct, industry_furnace_speed_pct=excluded.industry_furnace_speed_pct, industry_furnace_bonus_output_chance=excluded.industry_furnace_bonus_output_chance, industry_bidding_reputation_bonus_pct=excluded.industry_bidding_reputation_bonus_pct, updated_at=excluded.updated_at")) {
                ps.setString(1, plotId);
                ps.setInt(2, agri ? 1 : 0);
                ps.setInt(3, industry ? 1 : 0);
                ps.setInt(4, intervalTicks);
                ps.setInt(5, steps);
                ps.setInt(6, samples);
                ps.setDouble(7, harvest);
                ps.setDouble(8, premium);
                ps.setDouble(9, furnaceSpeed);
                ps.setDouble(10, furnaceBonus);
                ps.setDouble(11, bidBonus);
                ps.setLong(12, now);
                ps.executeUpdate();
            }
        } catch (SQLException e) {
            eco.getLogger().warning("[LandPerk] set plot perk failed: " + e.getMessage());
            return false;
        }
        refreshPlotPerkCache();
        nextGrowthRunAt.remove(plotId);
        return true;
    }

    public void refreshPlotPerkCache() {
        Map<String, PlotPerk> next = new ConcurrentHashMap<>();
        try (var conn = eco.ksCore().dataStore().getConnection()) {
            if (conn == null) { plotPerks = next; return; }
            try (var s = conn.createStatement();
                 var rs = s.executeQuery("SELECT * FROM ks_re_plot_perks")) {
                while (rs.next()) {
                    PlotPerk perk = new PlotPerk(
                            rs.getString("plot_id"),
                            rs.getInt("agri_enabled") != 0,
                            rs.getInt("industry_enabled") != 0,
                            rs.getInt("agri_growth_interval_ticks"),
                            rs.getInt("agri_growth_steps"),
                            readOptionalInt(rs, "agri_growth_samples"),
                            readOptionalDouble(rs, "agri_harvest_yield_bonus_chance"),
                            readOptionalDouble(rs, "agri_official_premium_pct"),
                            readOptionalDouble(rs, "industry_furnace_speed_pct"),
                            readOptionalDouble(rs, "industry_furnace_bonus_output_chance"),
                            readOptionalDouble(rs, "industry_bidding_reputation_bonus_pct")
                    );
                    next.put(perk.plotId, perk);
                }
            }
        } catch (SQLException ignored) {}
        plotPerks = next;
    }

    public boolean isEligibleForZonePerk(UUID actor, String world, int x, int z, String zoneType) {
        if (actor == null) return false;
        if (isOwnershipMode(zoneType)) {
            return ownsAnyZoneType(actor, zoneType);
        }
        if (world == null) return false;
        Map<String, Object> plot = realEstate.findPlotAt(world, x, z);
        if (plot == null) return false;
        if (!ownsPlot(plot, actor)) return false;
        return isPlotEnabledFor(plot, zoneType);
    }

    public boolean isBlockEligibleForZonePerk(String world, int x, int z, String zoneType) {
        if (world == null) return false;
        Map<String, Object> plot = realEstate.findPlotAt(world, x, z);
        if (plot == null) return false;
        if (isOwnershipMode(zoneType)) {
            String ownerType = (String) plot.get("ownerType");
            String ownerId = (String) plot.get("ownerId");
            if (RealEstateManager.OWNER_PLAYER.equals(ownerType)) {
                try { return ownsAnyZoneType(UUID.fromString(ownerId), zoneType); } catch (Exception e) { return false; }
            }
            if (RealEstateManager.OWNER_ENTERPRISE.equals(ownerType)) {
                return enterpriseOwnsZoneType(ownerId, zoneType);
            }
            return false;
        }
        return isPlotEnabledFor(plot, zoneType);
    }

    public double getBlockPerkValue(String world, int x, int z, String zoneType, String key, double def) {
        Map<String, Object> plot = realEstate.findPlotAt(world, x, z);
        if (plot == null) return def;
        if (!isPlotEnabledFor(plot, zoneType)) {
            double value = isOwnershipMode(zoneType) ? getPerkValue(key, def) : def;
            return applyEnterpriseLevelMultiplier(plot, key, value);
        }
        PlotPerk perk = plotPerks.get(String.valueOf(plot.get("id")));
        double value = perk == null ? getPerkValue(key, def) : switch (key) {
            case "agri_harvest_yield_bonus_chance" -> valueOrDefault(perk.agriHarvestYieldBonusChance, key, def);
            case "agri_official_premium_pct" -> valueOrDefault(perk.agriOfficialPremiumPct, key, def);
            case "industry_furnace_speed_pct" -> valueOrDefault(perk.industryFurnaceSpeedPct, key, def);
            case "industry_furnace_bonus_output_chance" -> valueOrDefault(perk.industryFurnaceBonusOutputChance, key, def);
            case "industry_bidding_reputation_bonus_pct" -> valueOrDefault(perk.industryBiddingReputationBonusPct, key, def);
            default -> getPerkValue(key, def);
        };
        return applyEnterpriseLevelMultiplier(plot, key, value);
    }

    private double applyEnterpriseLevelMultiplier(Map<String, Object> plot, String key, double value) {
        if (value <= 0 || !isLevelScaledPerk(key)
                || !RealEstateManager.OWNER_ENTERPRISE.equals(plot.get("ownerType"))) return value;
        String enterpriseId = String.valueOf(plot.get("ownerId"));
        double scaled = value * eco.enterpriseLevelManager().getLandPerkMultiplier(enterpriseId);
        return switch (key) {
            case "industry_furnace_speed_pct" -> Math.min(0.95, scaled);
            case "agri_harvest_yield_bonus_chance", "industry_furnace_bonus_output_chance",
                 "agri_official_premium_pct", "industry_bidding_reputation_bonus_pct" -> Math.min(1.0, scaled);
            default -> scaled;
        };
    }

    private static boolean isLevelScaledPerk(String key) {
        return switch (key) {
            case "agri_harvest_yield_bonus_chance", "agri_official_premium_pct",
                 "industry_furnace_speed_pct", "industry_furnace_bonus_output_chance",
                 "industry_bidding_reputation_bonus_pct" -> true;
            default -> false;
        };
    }

    public int getBlockGrowthSteps(String world, int x, int z) {
        Map<String, Object> plot = realEstate.findPlotAt(world, x, z);
        if (plot == null || !isPlotEnabledFor(plot, RealEstateManager.ZONE_TYPE_AGRICULTURAL)) return 0;
        return getPlotGrowthSteps(String.valueOf(plot.get("id")));
    }

    private void planPerkTickAsync() {
        if (!perkTickQueued.compareAndSet(false, true)) return;
        List<CropWork> crops = new ArrayList<>();
        List<FurnaceWork> furnaces = new ArrayList<>();
        try {
            long now = System.currentTimeMillis();
            crops = planCropGrowth(now);
            furnaces = planIndustrialFurnaces();
            if (crops.isEmpty() && furnaces.isEmpty()) {
                perkTickQueued.set(false);
                return;
            }
            List<CropWork> cropWork = List.copyOf(crops);
            List<FurnaceWork> furnaceWork = List.copyOf(furnaces);
            Bukkit.getScheduler().runTask(eco, () -> applyPerkTickSync(cropWork, furnaceWork));
        } catch (Throwable t) {
            perkTickQueued.set(false);
            eco.getLogger().warning("[LandPerk] async perk plan failed: " + t.getMessage());
        }
    }

    private void applyPerkTickSync(List<CropWork> crops, List<FurnaceWork> furnaces) {
        try {
            long deadline = System.nanoTime() + (long) (getPerkValue("perk_sync_time_budget_ms", 8.0) * 1_000_000L);
            for (CropWork work : crops) {
                if (System.nanoTime() > deadline) return;
                growPlotCrops(work, deadline);
            }
            for (FurnaceWork work : furnaces) {
                if (System.nanoTime() > deadline) return;
                tickIndustrialFurnacePlot(work, deadline);
            }
        } finally {
            perkTickQueued.set(false);
        }
    }

    private List<CropWork> planCropGrowth(long now) {
        List<CropWork> work = new ArrayList<>();
        if (!allowAgricultureByTps()) return work;
        for (Map<String, Object> plot : realEstate.cachedPlotsView()) {
            if (!isPlotEnabledFor(plot, RealEstateManager.ZONE_TYPE_AGRICULTURAL)) continue;
            String plotId = String.valueOf(plot.get("id"));
            int intervalTicks = getPlotGrowthIntervalTicks(plotId);
            long intervalMs = Math.max(1000L, intervalTicks * 50L);
            long nextAt = nextGrowthRunAt.getOrDefault(plotId, 0L);
            if (nextAt > now) continue;
            int allowedSamples = reserveAgriSamples(getPlotGrowthSamples(plotId), now);
            if (allowedSamples <= 0) break;
            nextGrowthRunAt.put(plotId, now + intervalMs);
            work.add(new CropWork(
                    plotId,
                    String.valueOf(plot.get("world")),
                    asInt(plot.get("x1")),
                    asInt(plot.get("z1")),
                    asInt(plot.get("x2")),
                    asInt(plot.get("z2")),
                    getPlotGrowthSteps(plotId),
                    allowedSamples
            ));
        }
        return work;
    }

    public boolean allowAgricultureEvent() {
        return allowAgricultureByTps();
    }

    public boolean allowIndustryEvent() {
        if (!allowIndustryByTps()) return false;
        return reserveIndustryEvent(System.currentTimeMillis());
    }

    public void rememberFurnaceBlock(Block block) {
        if (block == null || !isFurnaceBlock(block.getType())) return;
        Map<String, Object> plot = realEstate.findPlotAt(block.getWorld().getName(), block.getX(), block.getZ());
        if (plot == null || !isPlotEnabledFor(plot, RealEstateManager.ZONE_TYPE_INDUSTRIAL)) return;
        rememberFurnaceSpot(String.valueOf(plot.get("id")),
                new FurnaceSpot(block.getWorld().getName(), block.getX(), block.getY(), block.getZ()));
    }

    private List<FurnaceWork> planIndustrialFurnaces() {
        List<FurnaceWork> work = new ArrayList<>();
        if (!allowIndustryByTps()) return work;
        for (Map<String, Object> plot : realEstate.cachedPlotsView()) {
            if (!isPlotEnabledFor(plot, RealEstateManager.ZONE_TYPE_INDUSTRIAL)) continue;
            work.add(new FurnaceWork(
                    String.valueOf(plot.get("id")),
                    String.valueOf(plot.get("world")),
                    asInt(plot.get("x1")),
                    asInt(plot.get("z1")),
                    asInt(plot.get("x2")),
                    asInt(plot.get("z2"))
            ));
        }
        return work;
    }

    private void tickIndustrialFurnacePlot(FurnaceWork work, long deadline) {
        World world = Bukkit.getWorld(work.world());
        if (world == null) return;
        // FurnaceStartSmelt/FurnaceSmelt keep this cache current. Scanning a 25x25x13
        // volume around every player for every industrial plot was the dominant tick cost.
        boostCachedFurnaces(work.plotId(), world, work.x1(), work.z1(), work.x2(), work.z2(), deadline);
    }

    private boolean boostCachedFurnaces(String plotId, World world, int x1, int z1, int x2, int z2, long deadline) {
        List<FurnaceSpot> spots = furnaceSpotCache.get(plotId);
        if (spots == null || spots.isEmpty()) return true;
        List<FurnaceSpot> invalid = new ArrayList<>();
        for (FurnaceSpot spot : List.copyOf(spots)) {
            if (System.nanoTime() > deadline) return false;
            if (!world.getName().equals(spot.world()) || spot.x() < x1 || spot.x() > x2 || spot.z() < z1 || spot.z() > z2) {
                invalid.add(spot);
                continue;
            }
            if (!world.isChunkLoaded(spot.x() >> 4, spot.z() >> 4)) continue;
            Block block = world.getBlockAt(spot.x(), spot.y(), spot.z());
            if (!isFurnaceBlock(block.getType())) {
                invalid.add(spot);
                continue;
            }
            if (!boostFurnace(block)) return false;
        }
        if (!invalid.isEmpty()) spots.removeAll(invalid);
        return true;
    }

    private boolean boostFurnace(Block block) {
        if (!(block.getState() instanceof Furnace furnace)) return true;
        if (furnace.getCookTimeTotal() <= 0 || furnace.getCookTime() <= 0) return true;
        if (!reserveIndustryEvent(System.currentTimeMillis())) return false;
        double speedPct = getBlockPerkValue(block.getWorld().getName(), block.getX(), block.getZ(),
                RealEstateManager.ZONE_TYPE_INDUSTRIAL, "industry_furnace_speed_pct", 0.20);
        int extraTicks = Math.max(1, (int) Math.round(20.0 * Math.min(0.95, Math.max(0, speedPct))));
        int nextCook = Math.min(furnace.getCookTimeTotal() - 1, furnace.getCookTime() + extraTicks);
        if (nextCook > furnace.getCookTime()) {
            furnace.setCookTime((short) nextCook);
            furnace.update(true, false);
        }
        return true;
    }

    private void rememberFurnaceSpot(String plotId, FurnaceSpot spot) {
        List<FurnaceSpot> spots = furnaceSpotCache.computeIfAbsent(plotId, k -> new ArrayList<>());
        if (spots.contains(spot)) return;
        if (spots.size() >= 2048) spots.remove(0);
        spots.add(spot);
    }

    private boolean isFurnaceBlock(Material material) {
        return material == Material.FURNACE || material == Material.BLAST_FURNACE || material == Material.SMOKER;
    }

    private void growPlotCrops(CropWork work, long deadline) {
        World world = Bukkit.getWorld(work.world());
        if (world == null) return;
        String plotId = work.plotId();
        int x1 = work.x1(), x2 = work.x2();
        int z1 = work.z1(), z2 = work.z2();
        if (x2 < x1 || z2 < z1) return;
        int remaining = work.samples();
        remaining -= growCachedFarmSpots(plotId, world, work.steps(), remaining, x1, z1, x2, z2, deadline);
        if (remaining <= 0) return;
        remaining -= discoverFarmSpotsNearPlayers(plotId, world, work.steps(), remaining, x1, z1, x2, z2, deadline);
        if (remaining <= 0) return;
        ThreadLocalRandom random = ThreadLocalRandom.current();
        for (int i = 0; i < remaining; i++) {
            if (System.nanoTime() > deadline) return;
            int x = random.nextInt(x1, x2 + 1);
            int z = random.nextInt(z1, z2 + 1);
            if (!world.isChunkLoaded(x >> 4, z >> 4)) continue;
            if (!growFarmColumn(plotId, world, x, z, work.steps())) {
                growColumn(world, x, z, work.steps());
            }
        }
    }

    private int growCachedFarmSpots(String plotId, World world, int steps, int budget,
                                    int x1, int z1, int x2, int z2, long deadline) {
        if (budget <= 0) return 0;
        List<FarmSpot> spots = farmSpotCache.get(plotId);
        if (spots == null || spots.isEmpty()) return 0;
        int used = 0;
        List<FarmSpot> invalid = new ArrayList<>();
        for (FarmSpot spot : List.copyOf(spots)) {
            if (System.nanoTime() > deadline) break;
            if (used >= budget) break;
            if (!world.getName().equals(spot.world()) || spot.x() < x1 || spot.x() > x2 || spot.z() < z1 || spot.z() > z2) {
                invalid.add(spot);
                continue;
            }
            if (!world.isChunkLoaded(spot.x() >> 4, spot.z() >> 4)) continue;
            Block block = world.getBlockAt(spot.x(), spot.y(), spot.z());
            if (!(block.getBlockData() instanceof Ageable ageable)) {
                invalid.add(spot);
                continue;
            }
            if (ageable.getAge() < ageable.getMaximumAge()) {
                ageable.setAge(Math.min(ageable.getAge() + steps, ageable.getMaximumAge()));
                block.setBlockData(ageable, true);
            }
            used++;
        }
        if (!invalid.isEmpty()) spots.removeAll(invalid);
        return used;
    }

    private int discoverFarmSpotsNearPlayers(String plotId, World world, int steps, int budget,
                                             int x1, int z1, int x2, int z2, long deadline) {
        if (budget <= 0) return 0;
        int used = 0;
        List<Player> players = new ArrayList<>(Bukkit.getOnlinePlayers());
        if (players.isEmpty()) return 0;
        int start = Math.floorMod(farmPlayerCursor.getOrDefault(plotId, 0), players.size());
        int inspectedPlayers = Math.min(3, players.size());
        for (int playerIndex = 0; playerIndex < inspectedPlayers; playerIndex++) {
            Player player = players.get((start + playerIndex) % players.size());
            if (System.nanoTime() > deadline) return used;
            if (!player.getWorld().equals(world)) continue;
            int px = player.getLocation().getBlockX();
            int pz = player.getLocation().getBlockZ();
            if (px < x1 || px > x2 || pz < z1 || pz > z2) continue;
            // This is only cache discovery. Cached farmland is handled first, so a
            // bounded rotating sample is enough to find new farms without scanning
            // every player's full surroundings for every agricultural plot.
            int radius = 6;
            for (int r = 0; r <= radius && used < budget; r++) {
                if (System.nanoTime() > deadline) return used;
                for (int dx = -r; dx <= r && used < budget; dx++) {
                    if (System.nanoTime() > deadline) return used;
                    int x = px + dx;
                    if (x < x1 || x > x2) continue;
                    for (int dz = -r; dz <= r && used < budget; dz++) {
                        if (Math.max(Math.abs(dx), Math.abs(dz)) != r) continue;
                        int z = pz + dz;
                        if (z < z1 || z > z2) continue;
                        if (!world.isChunkLoaded(x >> 4, z >> 4)) continue;
                        used++;
                        growFarmColumn(plotId, world, x, z, steps);
                    }
                }
            }
        }
        farmPlayerCursor.put(plotId, (start + inspectedPlayers) % players.size());
        return used;
    }

    private boolean growFarmColumn(String plotId, World world, int x, int z, int steps) {
        int highest = Math.min(world.getHighestBlockYAt(x, z) + 2, world.getMaxHeight() - 1);
        int min = Math.max(world.getMinHeight(), highest - 48);
        for (int y = highest; y >= min; y--) {
            Block base = world.getBlockAt(x, y, z);
            if (!isFarmBase(base.getType())) continue;
            Block crop = world.getBlockAt(x, y + 1, z);
            rememberFarmSpot(plotId, new FarmSpot(world.getName(), x, y + 1, z));
            if (!(crop.getBlockData() instanceof Ageable ageable)) return true;
            if (ageable.getAge() >= ageable.getMaximumAge()) return true;
            ageable.setAge(Math.min(ageable.getAge() + steps, ageable.getMaximumAge()));
            crop.setBlockData(ageable, true);
            return true;
        }
        return false;
    }

    private void rememberFarmSpot(String plotId, FarmSpot spot) {
        List<FarmSpot> spots = farmSpotCache.computeIfAbsent(plotId, k -> new ArrayList<>());
        if (spots.contains(spot)) return;
        if (spots.size() >= 4096) spots.remove(0);
        spots.add(spot);
    }

    private boolean isFarmBase(Material material) {
        return material == Material.FARMLAND || material == Material.SOUL_SAND || material == Material.MUD;
    }

    private void growColumn(World world, int x, int z, int steps) {
        int highest = Math.min(world.getHighestBlockYAt(x, z) + 2, world.getMaxHeight() - 1);
        int min = Math.max(world.getMinHeight(), highest - 32);
        for (int y = highest; y >= min; y--) {
            Block block = world.getBlockAt(x, y, z);
            if (!(block.getBlockData() instanceof Ageable ageable)) continue;
            int before = ageable.getAge();
            if (before >= ageable.getMaximumAge()) return;
            ageable.setAge(Math.min(before + steps, ageable.getMaximumAge()));
            block.setBlockData(ageable, true);
            return;
        }
    }

    private boolean isOwnershipMode(String zoneType) {
        String key = RealEstateManager.ZONE_TYPE_AGRICULTURAL.equals(zoneType) ? "agri_scope_mode" : "industry_scope_mode";
        return getPerkValue(key, DEFAULTS.getOrDefault(key, 0.0)) >= 0.5;
    }

    private boolean isPlotEnabledFor(Map<String, Object> plot, String zoneType) {
        String plotId = String.valueOf(plot.get("id"));
        PlotPerk perk = plotPerks.get(plotId);
        if (RealEstateManager.ZONE_TYPE_AGRICULTURAL.equals(zoneType)) {
            return (perk != null && perk.agriEnabled) || zoneType.equals(plot.get("zoneType"));
        }
        if (RealEstateManager.ZONE_TYPE_INDUSTRIAL.equals(zoneType)) {
            return (perk != null && perk.industryEnabled) || zoneType.equals(plot.get("zoneType"));
        }
        return zoneType.equals(plot.get("zoneType"));
    }

    private boolean ownsPlot(Map<String, Object> plot, UUID actor) {
        String ownerType = (String) plot.get("ownerType");
        String ownerId = (String) plot.get("ownerId");
        if (RealEstateManager.OWNER_PLAYER.equals(ownerType)) return actor.toString().equals(ownerId);
        if (RealEstateManager.OWNER_ENTERPRISE.equals(ownerType)) return realEstate.isEnterpriseMember(ownerId, actor);
        return false;
    }

    public boolean ownsAnyZoneType(UUID actor, String zoneType) {
        if (actor == null || zoneType == null) return false;
        String cacheKey = "P:" + actor + ":" + zoneType;
        CacheEntry cached = ownershipCache.get(cacheKey);
        long now = System.currentTimeMillis();
        if (cached != null && cached.expiresAt > now) return cached.value;
        boolean result = false;
        try (var conn = eco.ksCore().dataStore().getConnection()) {
            if (conn != null) {
                try (var ps = conn.prepareStatement(
                        "SELECT 1 FROM ks_re_plots p JOIN ks_re_zones z ON z.id=p.zone_id " +
                        "WHERE p.owner_type='PLAYER' AND p.owner_id=? AND z.type=? LIMIT 1")) {
                    ps.setString(1, actor.toString());
                    ps.setString(2, zoneType);
                    try (var rs = ps.executeQuery()) { result = rs.next(); }
                }
                if (!result && RealEstateManager.ZONE_TYPE_AGRICULTURAL.equals(zoneType)) {
                    result = ownsFunctionPlot(conn, "PLAYER", actor.toString(), "agri_enabled");
                }
                if (!result && RealEstateManager.ZONE_TYPE_INDUSTRIAL.equals(zoneType)) {
                    result = ownsFunctionPlot(conn, "PLAYER", actor.toString(), "industry_enabled");
                }
                if (!result) {
                    try (var ps = conn.prepareStatement(
                            "SELECT 1 FROM ks_re_plots p JOIN ks_re_zones z ON z.id=p.zone_id " +
                            "JOIN ks_ent_members m ON m.enterprise_id=p.owner_id " +
                            "WHERE p.owner_type='ENTERPRISE' AND m.player_uuid=? AND z.type=? LIMIT 1")) {
                        ps.setString(1, actor.toString());
                        ps.setString(2, zoneType);
                        try (var rs = ps.executeQuery()) { result = rs.next(); }
                    }
                }
            }
        } catch (SQLException ignored) {}
        ownershipCache.put(cacheKey, new CacheEntry(result, now + OWNERSHIP_CACHE_TTL_MS));
        return result;
    }

    public boolean enterpriseOwnsZoneType(String enterpriseId, String zoneType) {
        if (enterpriseId == null || zoneType == null) return false;
        String cacheKey = "E:" + enterpriseId + ":" + zoneType;
        CacheEntry cached = ownershipCache.get(cacheKey);
        long now = System.currentTimeMillis();
        if (cached != null && cached.expiresAt > now) return cached.value;
        boolean result = false;
        try (var conn = eco.ksCore().dataStore().getConnection()) {
            if (conn != null) {
                try (var ps = conn.prepareStatement(
                        "SELECT 1 FROM ks_re_plots p JOIN ks_re_zones z ON z.id=p.zone_id " +
                        "WHERE p.owner_type='ENTERPRISE' AND p.owner_id=? AND z.type=? LIMIT 1")) {
                    ps.setString(1, enterpriseId);
                    ps.setString(2, zoneType);
                    try (var rs = ps.executeQuery()) { result = rs.next(); }
                }
                if (!result && RealEstateManager.ZONE_TYPE_AGRICULTURAL.equals(zoneType)) {
                    result = ownsFunctionPlot(conn, "ENTERPRISE", enterpriseId, "agri_enabled");
                }
                if (!result && RealEstateManager.ZONE_TYPE_INDUSTRIAL.equals(zoneType)) {
                    result = ownsFunctionPlot(conn, "ENTERPRISE", enterpriseId, "industry_enabled");
                }
            }
        } catch (SQLException ignored) {}
        ownershipCache.put(cacheKey, new CacheEntry(result, now + OWNERSHIP_CACHE_TTL_MS));
        return result;
    }

    private boolean ownsFunctionPlot(java.sql.Connection conn, String ownerType, String ownerId, String column) throws SQLException {
        String sql = "SELECT 1 FROM ks_re_plots p JOIN ks_re_plot_perks pp ON pp.plot_id=p.id " +
                "WHERE p.owner_type=? AND p.owner_id=? AND pp." + column + "!=0 LIMIT 1";
        try (var ps = conn.prepareStatement(sql)) {
            ps.setString(1, ownerType);
            ps.setString(2, ownerId);
            try (var rs = ps.executeQuery()) { return rs.next(); }
        }
    }

    private int getPlotGrowthIntervalTicks(String plotId) {
        PlotPerk perk = plotPerks.get(plotId);
        return perk != null && perk.agriGrowthIntervalTicks > 0
                ? perk.agriGrowthIntervalTicks
                : intDefault("agri_growth_interval_ticks", 100);
    }

    private int getPlotGrowthSteps(String plotId) {
        PlotPerk perk = plotPerks.get(plotId);
        if (perk != null && perk.agriGrowthSteps > 0) return perk.agriGrowthSteps;
        return intDefault("agri_growth_steps", Math.max(1, (int) Math.ceil(getPerkValue("agri_growth_boost_chance", 1.0))));
    }

    private int getPlotGrowthSamples(String plotId) {
        PlotPerk perk = plotPerks.get(plotId);
        return perk != null && perk.agriGrowthSamples > 0 ? perk.agriGrowthSamples : intDefault("agri_growth_samples", 64);
    }

    private int intDefault(String key, int def) {
        return Math.max(1, (int) Math.round(getPerkValue(key, def)));
    }

    private int intConfig(String key, int def, int min, int max) {
        return clampInt((int) Math.round(getPerkValue(key, def)), min, max);
    }

    private boolean allowAgricultureByTps() {
        double tps = currentTps();
        lastTps = tps;
        agriPausedByTps = tpsBlocked("agri_growth_min_tps", 18.5, tps);
        return !agriPausedByTps;
    }

    private boolean allowIndustryByTps() {
        double tps = currentTps();
        lastTps = tps;
        industryPausedByTps = tpsBlocked("industry_min_tps", 18.0, tps);
        return !industryPausedByTps;
    }

    private boolean tpsBlocked(String key, double def, double tps) {
        double minTps = getPerkValue(key, def);
        return minTps > 0 && tps < minTps;
    }

    private int reserveAgriSamples(int requested, long now) {
        int maxPerSecond = intConfig("agri_growth_max_samples_per_second", 4096, 0, 1_000_000);
        if (maxPerSecond <= 0) return requested;
        synchronized (this) {
            resetPerfWindow(now);
            int remaining = maxPerSecond - agriSamplesThisWindow;
            if (remaining <= 0) return 0;
            int allowed = Math.min(requested, remaining);
            agriSamplesThisWindow += allowed;
            return allowed;
        }
    }

    private boolean reserveIndustryEvent(long now) {
        int maxPerSecond = intConfig("industry_max_events_per_second", 512, 0, 1_000_000);
        if (maxPerSecond <= 0) return true;
        synchronized (this) {
            resetPerfWindow(now);
            if (industryEventsThisWindow >= maxPerSecond) return false;
            industryEventsThisWindow++;
            return true;
        }
    }

    private void resetPerfWindow(long now) {
        if (now - perfWindowStartedAt < 1000L) return;
        lastAgriSamplesPerSecond = agriSamplesThisWindow;
        lastIndustryEventsPerSecond = industryEventsThisWindow;
        agriSamplesThisWindow = 0;
        industryEventsThisWindow = 0;
        perfWindowStartedAt = now;
    }

    private double currentTps() {
        long now = System.currentTimeMillis();
        if (now - lastTpsCheckedAt < 1000L) return lastTps;
        synchronized (this) {
            if (now - lastTpsCheckedAt < 1000L) return lastTps;
            lastTpsCheckedAt = now;
        }
        double tps = 20.0;
        try {
            Object result = Bukkit.getServer().getClass().getMethod("getTPS").invoke(Bukkit.getServer());
            if (result instanceof double[] values && values.length > 0) {
                tps = Math.max(0.0, Math.min(20.0, values[0]));
            }
        } catch (Throwable ignored) {}
        lastTps = tps;
        return tps;
    }

    private double valueOrDefault(Double value, String key, double def) {
        return value != null ? value : getPerkValue(key, def);
    }

    private static Double readOptionalDouble(java.sql.ResultSet rs, String column) throws SQLException {
        double value = rs.getDouble(column);
        return rs.wasNull() ? null : value;
    }

    private static int readOptionalInt(java.sql.ResultSet rs, String column) throws SQLException {
        try {
            return rs.getInt(column);
        } catch (SQLException ignored) {
            return 0;
        }
    }

    private static int asInt(Object value) {
        return value instanceof Number n ? n.intValue() : Integer.parseInt(String.valueOf(value));
    }

    private static int clampInt(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    private static double clamp01(double value) {
        return Math.max(0, Math.min(1, value));
    }

    private static boolean toBool(Object value) {
        if (value instanceof Boolean b) return b;
        if (value instanceof Number n) return n.intValue() != 0;
        if (value == null) return false;
        return Boolean.parseBoolean(String.valueOf(value)) || "1".equals(String.valueOf(value));
    }

    private static double toDouble(Object value, double def) {
        if (value instanceof Number n) return n.doubleValue();
        if (value instanceof String s) {
            try { return Double.parseDouble(s); } catch (NumberFormatException ignored) {}
        }
        return def;
    }
}
