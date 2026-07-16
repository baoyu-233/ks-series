package org.kseries.rpg;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** Reads only MMOInventory's equipped custom-slot items through its runtime API. */
final class MmoInventoryBridge {
    private Object dataManager;
    private Method lookup;
    private Method allCustom;
    private Method retrieveItems;
    private Method itemStack;

    boolean reload() {
        clear();
        Plugin plugin = Bukkit.getPluginManager().getPlugin("MMOInventory");
        if (plugin == null || !plugin.isEnabled()) return false;
        try {
            Method managerMethod = plugin.getClass().getMethod("getDataManager");
            dataManager = managerMethod.invoke(plugin);
            lookup = findLookup(dataManager.getClass());
            if (lookup == null) throw new ReflectiveOperationException("MMOInventory player lookup missing");
            ClassLoader loader = plugin.getClass().getClassLoader();
            Class<?> playerData = Class.forName("net.Indyuce.inventory.player.PlayerData", true, loader);
            Class<?> customData = Class.forName("net.Indyuce.inventory.player.CustomInventoryData", true, loader);
            Class<?> inventoryItem = Class.forName("net.Indyuce.inventory.player.InventoryItem", true, loader);
            allCustom = playerData.getMethod("getAllCustom");
            retrieveItems = customData.getMethod("retrieveItems");
            itemStack = inventoryItem.getMethod("getItemStack");
            return true;
        } catch (ReflectiveOperationException | LinkageError ignored) {
            clear();
            return false;
        }
    }

    List<ItemStack> equipped(Player player) {
        if (dataManager == null && !reload()) return List.of();
        try {
            Object data = lookup.invoke(dataManager, lookupArgument(lookup.getParameterTypes()[0], player));
            if (data == null) return List.of();
            List<ItemStack> items = new ArrayList<>();
            Object customInventories = allCustom.invoke(data);
            if (!(customInventories instanceof Iterable<?> customs)) return List.of();
            for (Object custom : customs) {
                Object equipped = retrieveItems.invoke(custom);
                if (!(equipped instanceof Iterable<?> entries)) continue;
                for (Object entry : entries) {
                    Object stack = itemStack.invoke(entry);
                    if (stack instanceof ItemStack item && !item.getType().isAir()) items.add(item);
                }
            }
            return List.copyOf(items);
        } catch (ReflectiveOperationException | LinkageError ignored) {
            return List.of();
        }
    }

    private Method findLookup(Class<?> type) {
        for (Method method : type.getMethods()) {
            if (!method.getName().equals("get") || method.getParameterCount() != 1) continue;
            Class<?> parameter = method.getParameterTypes()[0];
            if (parameter == UUID.class || parameter.isAssignableFrom(Player.class)) return method;
        }
        return null;
    }

    private Object lookupArgument(Class<?> parameter, Player player) {
        return parameter == UUID.class ? player.getUniqueId() : player;
    }

    private void clear() {
        dataManager = null;
        lookup = null;
        allCustom = null;
        retrieveItems = null;
        itemStack = null;
    }
}
