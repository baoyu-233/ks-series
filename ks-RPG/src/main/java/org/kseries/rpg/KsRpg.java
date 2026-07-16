package org.kseries.rpg;

import org.bukkit.command.PluginCommand;
import org.bukkit.plugin.ServicePriority;
import org.bukkit.plugin.java.JavaPlugin;
import org.kseries.rpg.api.RpgContentApi;
import org.kseries.rpg.api.RpgProgressionApi;

import java.io.IOException;
import java.nio.file.Path;

public final class KsRpg extends JavaPlugin {
    private final MmoItemsBridge mmoItems = new MmoItemsBridge();
    private Catalog catalog;
    private volatile CombatCatalog combatCatalog = CombatCatalog.empty();
    private MaterialExchangeService exchangeService;
    private RpgContentService contentService;
    private ProgressionService progressionService;
    private MmoInventoryBridge mmoInventory;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        ensureDefaultCombatContent();
        progressionService = new ProgressionService(this);
        if (!reloadCatalog()) throw new IllegalStateException("ks-RPG combat content could not be loaded");
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
        getServer().getPluginManager().registerEvents(new CombatSkillListener(this, mmoItems, mmoInventory), this);
        getServer().getPluginManager().registerEvents(new ConfiguredMobDropListener(this, mmoItems), this);
        if (!mmoItems.reload()) getLogger().warning("MMOItems is unavailable; material exchanges will remain disabled.");
        if (!mmoInventory.reload()) getLogger().warning("MMOInventory is unavailable; talisman and ring skills remain inactive.");
    }

    @Override
    public void onDisable() {
        getServer().getServicesManager().unregisterAll(this);
    }

    boolean reloadCatalog() {
        reloadConfig();
        Catalog nextCatalog = Catalog.load(getConfig());
        CombatCatalog nextCombat;
        try {
            nextCombat = CombatCatalog.load(combatContentDirectory());
        } catch (IOException | IllegalArgumentException ex) {
            getLogger().severe("Combat content reload failed; keeping the previous active catalog: " + ex.getMessage());
            return false;
        }
        progressionService.reload();
        catalog = nextCatalog;
        combatCatalog = nextCombat;
        mmoItems.reload();
        if (mmoInventory != null) mmoInventory.reload();
        getLogger().info("Combat content catalog reloaded.");
        return true;
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

    private void ensureDefaultCombatContent() {
        for (String resource : new String[]{
                "content/weapons/first-wave.yml",
                "content/talismans/first-wave.yml",
                "content/rings/first-wave.yml",
                "content/caches/first-wave.yml",
                "content/world-drops/first-wave.yml"}) {
            saveResource(resource, false);
        }
    }

    private Path combatContentDirectory() {
        return getDataFolder().toPath().resolve("content");
    }
}
