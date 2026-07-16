package org.kseries.compat.fotia;

import org.bukkit.Bukkit;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;

import java.lang.reflect.Method;

public final class FotiaBridge {

    private final JavaPlugin plugin;
    private Object pdcManager;
    private Method getLevel;
    private boolean available;

    public FotiaBridge(JavaPlugin plugin) {
        this.plugin = plugin;
        refresh();
    }

    public void refresh() {
        available = false;
        pdcManager = null;
        getLevel = null;
        try {
            Plugin fe = Bukkit.getPluginManager().getPlugin("FotiaEnchantment");
            if (fe == null || !fe.isEnabled()) return;
            Object enchantManager = fe.getClass().getMethod("getEnchantmentManager").invoke(fe);
            pdcManager = enchantManager.getClass().getMethod("getPdcManager").invoke(enchantManager);
            getLevel = pdcManager.getClass().getMethod("getEnchantmentLevel", ItemStack.class, String.class);
            available = true;
        } catch (Exception e) {
            plugin.getLogger().fine("FotiaEnchantment bridge unavailable: " + e.getMessage());
        }
    }

    public boolean isAvailable() {
        return available && pdcManager != null && getLevel != null;
    }

    public int getLevel(ItemStack item, String enchantId) {
        if (!isAvailable() || item == null || item.getType().isAir()) return 0;
        try {
            return ((Number) getLevel.invoke(pdcManager, item, enchantId)).intValue();
        } catch (Exception e) {
            return 0;
        }
    }
}
