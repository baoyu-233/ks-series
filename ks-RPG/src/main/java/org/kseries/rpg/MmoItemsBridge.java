package org.kseries.rpg;

import org.bukkit.Bukkit;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Locale;

/** Reflection keeps ks-RPG optional when MMOItems is unavailable or upgraded. */
final class MmoItemsBridge {
    private Method typeGet;
    private Method pluginGetItem;
    private Method getId;
    private Method getTypeName;
    private Object mmoItems;

    boolean reload() {
        clear();
        Plugin plugin = Bukkit.getPluginManager().getPlugin("MMOItems");
        if (plugin == null || !plugin.isEnabled()) return false;
        try {
            ClassLoader loader = plugin.getClass().getClassLoader();
            Class<?> mmoItemsClass = Class.forName("net.Indyuce.mmoitems.MMOItems", true, loader);
            Class<?> typeClass = Class.forName("net.Indyuce.mmoitems.api.Type", true, loader);
            Field pluginField = mmoItemsClass.getField("plugin");
            mmoItems = pluginField.get(null);
            typeGet = typeClass.getMethod("get", String.class);
            pluginGetItem = mmoItemsClass.getMethod("getItem", typeClass, String.class);
            getId = mmoItemsClass.getMethod("getID", ItemStack.class);
            getTypeName = mmoItemsClass.getMethod("getTypeName", ItemStack.class);
            return mmoItems != null;
        } catch (ReflectiveOperationException | LinkageError ignored) {
            clear();
            return false;
        }
    }

    ItemStack create(String type, String id) {
        if (mmoItems == null && !reload()) return null;
        try {
            Object itemType = typeGet.invoke(null, type.toUpperCase(Locale.ROOT));
            Object stack = itemType == null ? null : pluginGetItem.invoke(mmoItems, itemType, id.toUpperCase(Locale.ROOT));
            return stack instanceof ItemStack item && !item.getType().isAir() ? item : null;
        } catch (ReflectiveOperationException | LinkageError ignored) {
            return null;
        }
    }

    boolean matches(ItemStack item, String type, String id) {
        if (item == null || item.getType().isAir()) return false;
        if (mmoItems == null && !reload()) return false;
        try {
            Object itemType = getTypeName.invoke(null, item);
            Object itemId = getId.invoke(null, item);
            return itemType instanceof String actualType && itemId instanceof String actualId
                    && actualType.equalsIgnoreCase(type) && actualId.equalsIgnoreCase(id);
        } catch (ReflectiveOperationException | LinkageError ignored) {
            return false;
        }
    }

    private void clear() {
        typeGet = null;
        pluginGetItem = null;
        getId = null;
        getTypeName = null;
        mmoItems = null;
    }
}
