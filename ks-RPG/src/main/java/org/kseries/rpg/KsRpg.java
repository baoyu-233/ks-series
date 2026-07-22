package org.kseries.rpg;

import org.bukkit.command.PluginCommand;
import org.bukkit.plugin.ServicePriority;
import org.bukkit.plugin.java.JavaPlugin;
import org.kseries.rpg.api.RpgContentApi;
import org.kseries.rpg.api.RpgProgressionApi;
import org.kseries.rpg.api.RpgSeasonStatusApi;
import org.kseries.rpg.season.SeasonService;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.jar.JarFile;
import java.util.stream.Stream;

public final class KsRpg extends JavaPlugin {
    private static final Set<String> COMBAT_CONTENT_CATEGORIES = Set.of(
            "weapons", "talismans", "rings", "caches", "world-drops");

    private final MmoItemsBridge mmoItems = new MmoItemsBridge();
    private Catalog catalog;
    private volatile CombatCatalog combatCatalog = CombatCatalog.empty();
    private MaterialExchangeService exchangeService;
    private RpgContentService contentService;
    private ProgressionService progressionService;
    private MmoInventoryBridge mmoInventory;
    private SeasonRuntimeConfig seasonConfig;
    private SeasonRuntime seasonRuntime;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        ensureDefaultCombatContent();
        progressionService = new ProgressionService(this);
        if (!reloadCatalog()) throw new IllegalStateException("ks-RPG combat content could not be loaded");
        seasonRuntime = new SeasonRuntime(getLogger());
        seasonRuntime.reload(seasonConfig);
        exchangeService = new MaterialExchangeService(mmoItems);
        contentService = new RpgContentService(this, mmoItems);
        mmoInventory = new MmoInventoryBridge();
        PluginCommand command = getCommand("ksrpg");
        if (command == null) throw new IllegalStateException("ksrpg command missing from plugin.yml");
        RpgCommand handler = new RpgCommand(this);
        command.setExecutor(handler);
        command.setTabCompleter(handler);
        getServer().getServicesManager().register(RpgProgressionApi.class, progressionService, this,
                ServicePriority.Normal);
        getServer().getServicesManager().register(RpgContentApi.class, contentService, this,
                ServicePriority.Normal);
        getServer().getServicesManager().register(RpgSeasonStatusApi.class, seasonRuntime, this,
                ServicePriority.Normal);
        getServer().getPluginManager().registerEvents(new CombatSkillListener(this, mmoItems, mmoInventory), this);
        getServer().getPluginManager().registerEvents(new ConfiguredMobDropListener(this, mmoItems), this);
        if (!mmoItems.reload()) getLogger().warning("MMOItems is unavailable; material exchanges will remain disabled.");
        if (!mmoInventory.reload()) getLogger().warning("MMOInventory is unavailable; talisman and ring skills remain inactive.");
    }

    @Override
    public void onDisable() {
        if (seasonRuntime != null) seasonRuntime.close();
        getServer().getServicesManager().unregisterAll(this);
    }

    boolean reloadCatalog() {
        final org.bukkit.configuration.file.FileConfiguration candidate;
        try {
            candidate = loadConfigCandidate();
        } catch (Exception ex) {
            getLogger().severe("RPG config reload failed; keeping all previous active catalogs: " + ex.getMessage());
            return false;
        }
        final Catalog nextCatalog;
        final ProgressionCatalog nextProgressionCatalog;
        final SeasonRuntimeConfig nextSeasonConfig;
        CombatCatalog nextCombat;
        try {
            nextCatalog = Catalog.load(candidate);
            nextProgressionCatalog = ProgressionCatalog.load(candidate);
            nextCombat = CombatCatalog.load(combatContentDirectory());
            nextSeasonConfig = SeasonRuntimeConfig.load(candidate, getDataFolder().toPath());
        } catch (IOException | IllegalArgumentException ex) {
            getLogger().severe("RPG catalog reload failed; keeping all previous active catalogs: " + ex.getMessage());
            return false;
        }
        // Validation passed. Promote the candidate into the live plugin config without
        // calling reloadConfig() first, which can replace a broken file with defaults.
        replaceLiveConfig(candidate);
        catalog = nextCatalog;
        progressionService.replaceCatalog(nextProgressionCatalog);
        combatCatalog = nextCombat;
        seasonConfig = nextSeasonConfig;
        if (seasonRuntime != null) seasonRuntime.reload(nextSeasonConfig);
        mmoItems.reload();
        if (mmoInventory != null) mmoInventory.reload();
        getLogger().info("Combat content catalog reloaded.");
        return true;
    }

    private org.bukkit.configuration.file.FileConfiguration loadConfigCandidate() throws Exception {
        java.io.File configFile = new java.io.File(getDataFolder(), "config.yml");
        if (!configFile.isFile()) {
            saveDefaultConfig();
        }
        org.bukkit.configuration.file.YamlConfiguration loaded = new org.bukkit.configuration.file.YamlConfiguration();
        // load() throws on syntax errors; loadConfiguration() would fail open to empty/default.
        loaded.load(configFile);
        if (loaded.getKeys(false).isEmpty() && configFile.length() > 0) {
            throw new IllegalArgumentException("config.yml parsed empty while file is non-empty");
        }
        return loaded;
    }

    private void replaceLiveConfig(org.bukkit.configuration.file.FileConfiguration candidate) {
        org.bukkit.configuration.file.FileConfiguration live = getConfig();
        for (String key : new java.util.ArrayList<>(live.getKeys(false))) {
            live.set(key, null);
        }
        for (String key : candidate.getKeys(false)) {
            live.set(key, candidate.get(key));
        }
    }

    Catalog catalog() {
        return catalog;
    }

    CombatCatalog combatCatalog() {
        return combatCatalog;
    }

    MaterialExchangeService exchangeService() {
        return exchangeService;
    }

    RpgProgressionApi progression() {
        return progressionService;
    }

    RpgContentApi content() {
        return contentService;
    }

    /**
     * Returns the staged season domain service. When enabled, all storage methods must be called from
     * a database worker; server-thread callers should use {@link #seasonStatus()} instead.
     */
    public SeasonService seasonService() {
        return seasonRuntime == null ? new SeasonService() : seasonRuntime.service();
    }

    /** Cached read-only status; this method never performs database I/O. */
    public RpgSeasonStatusApi.RuntimeStatus seasonStatus() {
        return seasonRuntime == null
                ? new RpgSeasonStatusApi.RuntimeStatus(false, false,
                        RpgSeasonStatusApi.RuntimeState.DISABLED,
                        "赛季系统未启用。", "")
                : seasonRuntime.status();
    }

    /** Public read-only status surface for direct plugin integrations. */
    public RpgSeasonStatusApi seasonStatusApi() {
        return this::seasonStatus;
    }


    private void ensureDefaultCombatContent() {
        try (JarFile jar = new JarFile(getFile())) {
            List<String> entries = jar.stream().map(entry -> entry.getName()).toList();
            for (String resource : bundledCombatResources(entries.stream())) {
                saveResource(resource, false);
            }
        } catch (IOException ex) {
            throw new IllegalStateException("Cannot inspect bundled RPG combat content", ex);
        }
    }

    static List<String> bundledCombatResources(Stream<String> entryNames) {
        return entryNames.filter(KsRpg::isBundledCombatResource).sorted().toList();
    }

    private static boolean isBundledCombatResource(String name) {
        if (name == null || name.contains("\\")) return false;
        String[] segments = name.split("/", -1);
        if (segments.length < 3 || !segments[0].equals("content")) return false;
        if (Arrays.stream(segments).anyMatch(segment -> segment.isBlank()
                || segment.equals(".") || segment.equals(".."))) return false;
        return COMBAT_CONTENT_CATEGORIES.contains(segments[1].toLowerCase(Locale.ROOT))
                && segments[segments.length - 1].toLowerCase(Locale.ROOT).endsWith(".yml");
    }

    private Path combatContentDirectory() {
        return getDataFolder().toPath().resolve("content");
    }

}
