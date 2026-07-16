package org.kseries.compat.bot;

import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

/** Persistent owner-only storage for inventory recovered from KS-managed bots. */
public final class BotStorageStore {

    public record StoredItem(String id, ItemStack item, long storedAt, String source) {}

    private final JavaPlugin plugin;
    private final File file;
    private YamlConfiguration data;

    public BotStorageStore(JavaPlugin plugin) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), "bot-storage.yml");
    }

    public synchronized void load() {
        if (!plugin.getDataFolder().exists() && !plugin.getDataFolder().mkdirs()) {
            plugin.getLogger().warning("Could not create data folder for bot storage.");
        }
        data = YamlConfiguration.loadConfiguration(file);
    }

    public synchronized boolean save() {
        if (data == null) load();
        File temp = new File(file.getParentFile(), file.getName() + ".tmp");
        try {
            data.save(temp);
            try {
                Files.move(temp.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException ignored) {
                Files.move(temp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING);
            }
            return true;
        } catch (IOException e) {
            plugin.getLogger().warning("Could not save bot storage: " + e.getMessage());
            return false;
        } finally {
            try {
                Files.deleteIfExists(temp.toPath());
            } catch (IOException ignored) {
            }
        }
    }

    public synchronized int store(UUID ownerUuid, List<ItemStack> items, String source) {
        if (ownerUuid == null || items == null || items.isEmpty()) return 0;
        if (data == null) load();
        int stored = 0;
        List<String> storedIds = new ArrayList<>();
        long now = System.currentTimeMillis();
        for (ItemStack item : items) {
            if (isEmpty(item)) continue;
            String id = UUID.randomUUID().toString();
            storedIds.add(id);
            String path = itemPath(ownerUuid, id);
            data.set(path + ".data", Base64.getEncoder().encodeToString(item.serializeAsBytes()));
            data.set(path + ".stored-at", now);
            data.set(path + ".source", source == null ? "unknown" : source);
            stored++;
        }
        if (stored > 0 && !save()) {
            for (String id : storedIds) data.set(itemPath(ownerUuid, id), null);
            return 0;
        }
        return stored;
    }

    public synchronized List<StoredItem> page(UUID ownerUuid, int page, int pageSize) {
        if (ownerUuid == null || page < 0 || pageSize < 1) return List.of();
        List<StoredItem> all = items(ownerUuid);
        int from = Math.min(all.size(), page * pageSize);
        int to = Math.min(all.size(), from + pageSize);
        return new ArrayList<>(all.subList(from, to));
    }

    public synchronized int count(UUID ownerUuid) {
        return items(ownerUuid).size();
    }

    /** Removes and returns the item atomically from this store. Caller restores it on delivery failure. */
    public synchronized ItemStack take(UUID ownerUuid, String id) {
        if (ownerUuid == null || id == null || id.isBlank() || data == null) return null;
        String path = itemPath(ownerUuid, id);
        String encoded = data.getString(path + ".data", "");
        long storedAt = data.getLong(path + ".stored-at", 0L);
        String source = data.getString(path + ".source", "unknown");
        ItemStack item = decode(encoded);
        if (isEmpty(item)) return null;
        data.set(path, null);
        if (!save()) {
            data.set(path + ".data", encoded);
            data.set(path + ".stored-at", storedAt);
            data.set(path + ".source", source);
            return null;
        }
        return item;
    }

    public synchronized void restore(UUID ownerUuid, String id, ItemStack item, String source) {
        if (ownerUuid == null || id == null || id.isBlank() || isEmpty(item)) return;
        if (data == null) load();
        String path = itemPath(ownerUuid, id);
        data.set(path + ".data", Base64.getEncoder().encodeToString(item.serializeAsBytes()));
        data.set(path + ".stored-at", System.currentTimeMillis());
        data.set(path + ".source", source == null ? "undelivered" : source);
        save();
    }

    private List<StoredItem> items(UUID ownerUuid) {
        if (ownerUuid == null) return List.of();
        if (data == null) load();
        ConfigurationSection root = data.getConfigurationSection("owners." + ownerUuid + ".items");
        if (root == null) return List.of();
        List<StoredItem> out = new ArrayList<>();
        for (String id : root.getKeys(false)) {
            String path = itemPath(ownerUuid, id);
            ItemStack item = decode(data.getString(path + ".data", ""));
            if (!isEmpty(item)) {
                out.add(new StoredItem(id, item, data.getLong(path + ".stored-at", 0L), data.getString(path + ".source", "unknown")));
            }
        }
        out.sort(Comparator.comparingLong(StoredItem::storedAt).thenComparing(StoredItem::id));
        return out;
    }

    private ItemStack decode(String encoded) {
        if (encoded == null || encoded.isBlank()) return null;
        try {
            return ItemStack.deserializeBytes(Base64.getDecoder().decode(encoded));
        } catch (IllegalArgumentException e) {
            plugin.getLogger().warning("Skipped unreadable KSBot stored item: " + e.getMessage());
            return null;
        }
    }

    private String itemPath(UUID ownerUuid, String id) {
        return "owners." + ownerUuid + ".items." + id;
    }

    private boolean isEmpty(ItemStack item) {
        return item == null || item.getType() == Material.AIR || item.getAmount() <= 0;
    }
}
