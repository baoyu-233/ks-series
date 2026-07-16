package org.kseries.bosscombat;

import org.bukkit.Bukkit;
import org.bukkit.plugin.java.JavaPlugin;
import org.kseries.bosscombat.frostbound.FrostboundWeaponAdaptationListener;

public final class KsBossCombat extends JavaPlugin {

    private FrostboundWeaponAdaptationListener frostboundWeaponAdaptationListener;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        frostboundWeaponAdaptationListener = new FrostboundWeaponAdaptationListener(this);
        frostboundWeaponAdaptationListener.reload();
        Bukkit.getPluginManager().registerEvents(frostboundWeaponAdaptationListener, this);
        getLogger().info("ks-BossCombat enabled.");
    }

    @Override
    public void onDisable() {
        if (frostboundWeaponAdaptationListener != null) {
            frostboundWeaponAdaptationListener.disable();
        }
    }
}
