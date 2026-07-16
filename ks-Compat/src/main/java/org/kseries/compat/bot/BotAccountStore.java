package org.kseries.compat.bot;

import org.bukkit.Location;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

public final class BotAccountStore {

    private final JavaPlugin plugin;
    private final File file;
    private YamlConfiguration data;

    public BotAccountStore(JavaPlugin plugin) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), "bots.yml");
    }

    public synchronized void load() {
        if (!plugin.getDataFolder().exists() && !plugin.getDataFolder().mkdirs()) {
            plugin.getLogger().warning("Could not create data folder for ks-Compat.");
        }
        data = YamlConfiguration.loadConfiguration(file);
    }

    public synchronized void save() {
        try {
            data.save(file);
        } catch (IOException e) {
            plugin.getLogger().warning("Could not save bots.yml: " + e.getMessage());
        }
    }

    public synchronized int adminSlots(UUID player) {
        return data.getInt("players." + player + ".admin-slots", 0);
    }

    public synchronized int purchasedSlots(UUID player) {
        return data.getInt("players." + player + ".purchased-slots", 0);
    }

    public synchronized void addAdminSlots(UUID player, int amount) {
        String path = "players." + player + ".admin-slots";
        data.set(path, Math.max(0, data.getInt(path, 0) + amount));
        save();
    }

    public synchronized void setAdminSlots(UUID player, int amount) {
        data.set("players." + player + ".admin-slots", Math.max(0, amount));
        save();
    }

    public synchronized void addPurchasedSlots(UUID player, int amount) {
        String path = "players." + player + ".purchased-slots";
        data.set(path, Math.max(0, data.getInt(path, 0) + amount));
        save();
    }

    public synchronized int usedPermanentSlots(UUID player) {
        int count = 0;
        for (BotSlot slot : records()) {
            if (slot.ownerUuid != null && slot.ownerUuid.equals(player) && slot.kind == BotSlot.Kind.PERMANENT
                && !slot.revokedOnRelease) {
                count++;
            }
        }
        return count;
    }

    public synchronized int revokedAssignedBots(UUID player) {
        int count = 0;
        for (BotSlot slot : records()) {
            if (slot.ownerUuid != null && slot.ownerUuid.equals(player) && slot.revokedOnRelease && slot.hasBot()) {
                count++;
            }
        }
        return count;
    }

    public synchronized int availablePermanentSlots(UUID player, int defaultSlots) {
        return Math.max(0, defaultSlots + adminSlots(player) + purchasedSlots(player) - usedPermanentSlots(player));
    }

    /** Slot capacity while paid purchases and rentals are disabled. */
    public synchronized int availableConfiguredSlots(UUID player, int defaultSlots) {
        return Math.max(0, defaultSlots + adminSlots(player) - activeBotCount(player));
    }

    public synchronized int activeBotCount(UUID player) {
        int count = 0;
        for (BotSlot slot : records()) {
            if (slot.ownerUuid != null && slot.ownerUuid.equals(player) && slot.hasBot()) count++;
        }
        return count;
    }

    public synchronized BotSlot createRental(Player owner, int days) {
        BotSlot slot = BotSlot.rented(owner.getUniqueId(), owner.getName(), days);
        write(slot);
        save();
        return slot;
    }

    public synchronized BotSlot claimSlot(Player owner, boolean preferRented, int defaultPermanentSlots,
                                          boolean economyEnabled) {
        long now = System.currentTimeMillis();
        if (economyEnabled && preferRented) {
            for (BotSlot slot : records()) {
                if (slot.ownerUuid != null && slot.ownerUuid.equals(owner.getUniqueId())
                    && slot.kind == BotSlot.Kind.RENTED && !slot.revokedOnRelease
                    && !slot.hasBot() && !slot.isExpired(now)) {
                    return slot;
                }
            }
        }
        int available = economyEnabled
            ? availablePermanentSlots(owner.getUniqueId(), defaultPermanentSlots)
            : availableConfiguredSlots(owner.getUniqueId(), defaultPermanentSlots);
        if (available > 0) {
            BotSlot slot = BotSlot.permanent(owner.getUniqueId(), owner.getName());
            write(slot);
            save();
            return slot;
        }
        if (economyEnabled && !preferRented) {
            for (BotSlot slot : records()) {
                if (slot.ownerUuid != null && slot.ownerUuid.equals(owner.getUniqueId())
                    && slot.kind == BotSlot.Kind.RENTED && !slot.revokedOnRelease
                    && !slot.hasBot() && !slot.isExpired(now)) {
                    return slot;
                }
            }
        }
        return null;
    }

    public synchronized void assign(BotSlot slot, Player owner, String botName, String botUuid, Location location, String skinName) {
        slot.ownerUuid = owner.getUniqueId();
        slot.ownerName = owner.getName();
        assignBot(slot, botName, botUuid, location, skinName);
    }

    public synchronized void assignBot(BotSlot slot, String botName, String botUuid, Location location, String skinName) {
        slot.botName = botName;
        slot.botUuid = botUuid;
        slot.skinName = skinName;
        setLocation(slot, location);
        slot.updatedAt = System.currentTimeMillis();
        write(slot);
        save();
    }

    public synchronized void setLocation(BotSlot slot, Location location) {
        if (location == null || location.getWorld() == null) return;
        slot.world = location.getWorld().getName();
        slot.x = location.getX();
        slot.y = location.getY();
        slot.z = location.getZ();
        slot.yaw = location.getYaw();
        slot.pitch = location.getPitch();
        slot.updatedAt = System.currentTimeMillis();
        write(slot);
    }

    public synchronized void setInvincible(BotSlot slot, boolean invincible) {
        slot.invincible = invincible;
        slot.updatedAt = System.currentTimeMillis();
        write(slot);
        save();
    }

    public synchronized void setHungerLocked(BotSlot slot, boolean hungerLocked) {
        slot.hungerLocked = hungerLocked;
        slot.updatedAt = System.currentTimeMillis();
        write(slot);
        save();
    }

    public synchronized void release(BotSlot slot) {
        if (slot.revokedOnRelease || slot.kind == BotSlot.Kind.PERMANENT) {
            data.set("records." + slot.id, null);
        } else {
            slot.botName = null;
            slot.botUuid = null;
            slot.skinName = null;
            slot.updatedAt = System.currentTimeMillis();
            write(slot);
        }
        save();
    }

    /** Removes unused entitlements and revokes currently assigned bots on their next release. */
    public synchronized SlotResetResult resetSlotsPreservingAssignedBots() {
        int preserved = 0;
        int removed = 0;
        data.set("players", null);
        for (BotSlot slot : records()) {
            if (slot.hasBot()) {
                slot.revokedOnRelease = true;
                slot.updatedAt = System.currentTimeMillis();
                write(slot);
                preserved++;
            } else {
                data.set("records." + slot.id, null);
                removed++;
            }
        }
        save();
        return new SlotResetResult(preserved, removed);
    }

    public synchronized void delete(BotSlot slot) {
        data.set("records." + slot.id, null);
        save();
    }

    public synchronized BotSlot findByBotName(String name) {
        for (BotSlot slot : records()) {
            if (slot.botName != null && slot.botName.equalsIgnoreCase(name)) return slot;
        }
        return null;
    }

    public synchronized BotSlot findByBot(Player player) {
        String uuid = player.getUniqueId().toString();
        for (BotSlot slot : records()) {
            if (slot.botUuid != null && slot.botUuid.equalsIgnoreCase(uuid)) return slot;
            if (slot.botName != null && slot.botName.equalsIgnoreCase(player.getName())) return slot;
        }
        return null;
    }

    public synchronized List<BotSlot> recordsFor(UUID owner) {
        List<BotSlot> out = new ArrayList<>();
        for (BotSlot slot : records()) {
            if (slot.ownerUuid != null && slot.ownerUuid.equals(owner)) out.add(slot);
        }
        out.sort(Comparator.comparing(s -> s.botName == null ? "" : s.botName));
        return out;
    }

    public synchronized List<BotSlot> records() {
        List<BotSlot> out = new ArrayList<>();
        ConfigurationSection root = data.getConfigurationSection("records");
        if (root == null) return out;
        for (String id : root.getKeys(false)) {
            ConfigurationSection sec = root.getConfigurationSection(id);
            if (sec != null) out.add(BotSlot.from(sec));
        }
        out.sort(Comparator.comparing(s -> s.id));
        return out;
    }

    public synchronized int purgeExpired(LeavesBotBridge leaves) {
        long now = System.currentTimeMillis();
        int removed = 0;
        for (BotSlot slot : records()) {
            if (slot.revokedOnRelease || !slot.isExpired(now)) continue;
            if (slot.botName != null) {
                Player bot = leaves.getBot(slot.botName);
                if (bot != null) leaves.remove(bot, false);
            }
            data.set("records." + slot.id, null);
            removed++;
        }
        if (removed > 0) save();
        return removed;
    }

    private void write(BotSlot slot) {
        String path = "records." + slot.id;
        data.set(path + ".owner-uuid", slot.ownerUuid != null ? slot.ownerUuid.toString() : "");
        data.set(path + ".owner-name", slot.ownerName);
        data.set(path + ".kind", slot.kind != null ? slot.kind.name() : BotSlot.Kind.PERMANENT.name());
        data.set(path + ".expires-at", slot.expiresAt);
        data.set(path + ".bot-name", slot.botName);
        data.set(path + ".bot-uuid", slot.botUuid);
        data.set(path + ".skin-name", slot.skinName);
        data.set(path + ".invincible", slot.invincible);
        data.set(path + ".hunger-locked", slot.hungerLocked);
        data.set(path + ".revoked-on-release", slot.revokedOnRelease);
        data.set(path + ".location.world", slot.world);
        data.set(path + ".location.x", slot.x);
        data.set(path + ".location.y", slot.y);
        data.set(path + ".location.z", slot.z);
        data.set(path + ".location.yaw", slot.yaw);
        data.set(path + ".location.pitch", slot.pitch);
        data.set(path + ".created-at", slot.createdAt);
        data.set(path + ".updated-at", slot.updatedAt);
    }

    public record SlotResetResult(int preservedAssignedBots, int removedUnusedSlots) {
    }
}
