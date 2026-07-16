package org.kseries.compat.bot;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.configuration.ConfigurationSection;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

public final class BotSlot {
    public enum Kind { PERMANENT, RENTED }

    public String id;
    public UUID ownerUuid;
    public String ownerName;
    public Kind kind;
    public long expiresAt;
    public String botName;
    public String botUuid;
    public String skinName;
    public String world;
    public double x;
    public double y;
    public double z;
    public float yaw;
    public float pitch;
    public long createdAt;
    public long updatedAt;
    public boolean invincible = true;
    /** Keeps food, saturation, and exhaustion at their healthy values while enabled. */
    public boolean hungerLocked;
    /** A global slot reset keeps this bot only until it next leaves the server. */
    public boolean revokedOnRelease;

    public boolean isExpired(long now) {
        return kind == Kind.RENTED && expiresAt > 0L && expiresAt <= now;
    }

    public boolean hasBot() {
        return botName != null && !botName.isBlank();
    }

    public Location location() {
        World w = world == null ? null : Bukkit.getWorld(world);
        return w == null ? null : new Location(w, x, y, z, yaw, pitch);
    }

    public Map<String, Object> view() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", id);
        out.put("ownerUuid", ownerUuid != null ? ownerUuid.toString() : "");
        out.put("ownerName", ownerName);
        out.put("kind", kind != null ? kind.name() : "");
        out.put("expiresAt", expiresAt);
        out.put("expiresAtText", expiresAt > 0L ? new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm").format(new java.util.Date(expiresAt)) : "永久");
        out.put("botName", botName);
        out.put("botUuid", botUuid);
        out.put("skinName", skinName);
        out.put("world", world);
        out.put("invincible", invincible);
        out.put("hungerLocked", hungerLocked);
        out.put("revokedOnRelease", revokedOnRelease);
        return out;
    }

    static BotSlot rented(UUID ownerUuid, String ownerName, int days) {
        BotSlot slot = new BotSlot();
        long now = System.currentTimeMillis();
        slot.id = UUID.randomUUID().toString();
        slot.ownerUuid = ownerUuid;
        slot.ownerName = ownerName;
        slot.kind = Kind.RENTED;
        slot.expiresAt = now + days * 86_400_000L;
        slot.createdAt = now;
        slot.updatedAt = now;
        return slot;
    }

    static BotSlot permanent(UUID ownerUuid, String ownerName) {
        BotSlot slot = new BotSlot();
        long now = System.currentTimeMillis();
        slot.id = UUID.randomUUID().toString();
        slot.ownerUuid = ownerUuid;
        slot.ownerName = ownerName;
        slot.kind = Kind.PERMANENT;
        slot.createdAt = now;
        slot.updatedAt = now;
        return slot;
    }

    static BotSlot from(ConfigurationSection section) {
        BotSlot slot = new BotSlot();
        slot.id = section.getName();
        String owner = section.getString("owner-uuid", "");
        if (!owner.isBlank()) slot.ownerUuid = UUID.fromString(owner);
        slot.ownerName = section.getString("owner-name", "");
        slot.kind = Kind.valueOf(section.getString("kind", "PERMANENT"));
        slot.expiresAt = section.getLong("expires-at", 0L);
        slot.botName = section.getString("bot-name", "");
        if (slot.botName != null && slot.botName.isBlank()) slot.botName = null;
        slot.botUuid = section.getString("bot-uuid", "");
        if (slot.botUuid != null && slot.botUuid.isBlank()) slot.botUuid = null;
        slot.skinName = section.getString("skin-name", "");
        if (slot.skinName != null && slot.skinName.isBlank()) slot.skinName = null;
        slot.world = section.getString("location.world", "");
        if (slot.world != null && slot.world.isBlank()) slot.world = null;
        slot.x = section.getDouble("location.x", 0.0);
        slot.y = section.getDouble("location.y", 0.0);
        slot.z = section.getDouble("location.z", 0.0);
        slot.yaw = (float) section.getDouble("location.yaw", 0.0);
        slot.pitch = (float) section.getDouble("location.pitch", 0.0);
        slot.createdAt = section.getLong("created-at", System.currentTimeMillis());
        slot.updatedAt = section.getLong("updated-at", slot.createdAt);
        slot.invincible = section.getBoolean("invincible", true);
        slot.hungerLocked = section.getBoolean("hunger-locked", false);
        slot.revokedOnRelease = section.getBoolean("revoked-on-release", false);
        return slot;
    }
}
