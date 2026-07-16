package org.ksinherit;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.*;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.*;

/**
 * /inherit command handler.
 * <p>
 * Player:  /inherit open     — open item storage GUI
 * Player:  /inherit token    — get web access token (admin → admin page, player → player page)
 * Admin:   /inherit slots    — set slot limit per player
 * Admin:   /inherit reload   — hot-reload database after copying items.db from 1.20.6
 * Admin:   /inherit testitem — get a complex test item with all NBT features
 */
public final class InheritCommand implements CommandExecutor {

    private final KsInherit plugin;

    InheritCommand(KsInherit plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        if (args.length == 0) {
            sender.sendMessage("§6[ks-Inherit] §7/v" + plugin.getDescription().getVersion());
            sender.sendMessage("§e/inherit open §7- open item storage GUI");
            sender.sendMessage("§e/inherit token §7- get web access token (admin→管理页, player→状态页)");
            if (sender.hasPermission("ksinherit.admin")) {
                sender.sendMessage("§e/inherit slots <player> <N> §7- set slot limit (1-54)");
                sender.sendMessage("§e/inherit reload §7- hot-reload database from 1.20.6 backup");
                sender.sendMessage("§e/inherit testitem §7- get complex test item");
            }
            return true;
        }

        String sub = args[0].toLowerCase(Locale.ROOT);

        switch (sub) {
            case "open" -> {
                if (!(sender instanceof Player player)) {
                    sender.sendMessage("§cOnly players can open GUI");
                    return true;
                }
                if (!player.hasPermission("ksinherit.use")) {
                    player.sendMessage("§cNo permission");
                    return true;
                }
                new InheritGUI(plugin, player).open();
            }
            case "token" -> {
                if (!(sender instanceof Player player)) {
                    sender.sendMessage("§cOnly players can get token");
                    return true;
                }
                try {
                    var core = org.bukkit.Bukkit.getPluginManager().getPlugin("ks-core");
                    if (core == null) {
                        sender.sendMessage("§cks-core not found"); return true;
                    }
                    boolean isAdmin = player.hasPermission("ksinherit.admin");
                    var bridge = core.getClass().getMethod("bridge").invoke(core);
                    // KsPluginBridge.createToken(UUID, String, boolean) → Session
                    var m = bridge.getClass().getMethod("createToken",
                            java.util.UUID.class, String.class, boolean.class);
                    var session = m.invoke(bridge, player.getUniqueId(), player.getName(), isAdmin);
                    String token = (String) session.getClass().getField("token").get(session);

                    // 从 ks-core 配置读取对外地址/端口，而非硬编码 127.0.0.1
                    var ksConfig = core.getClass().getMethod("ksConfig").invoke(core);
                    String host = (String) ksConfig.getClass().getMethod("getPublicAddress").invoke(ksConfig);
                    int port = (int) ksConfig.getClass().getMethod("getPort").invoke(ksConfig);

                    // Admin → /admin page, player → / page (player self-view)
                    String subPath = isAdmin ? "/admin" : "";
                    String webUrl = "http://" + host + ":" + port + "/ks-Inherit" + subPath + "?token=" + token;
                    String role = isAdmin ? " (管理员)" : "";

                    // Clickable link using Adventure ClickEvent
                    player.sendMessage(Component.text()
                        .append(Component.text("[ks-Inherit] ", TextColor.color(0xFFAA00)))
                        .append(Component.text("您的 Web 访问令牌" + role + ":", TextColor.color(0x55FF55)))
                        .build());
                    player.sendMessage(Component.text()
                        .append(Component.text(webUrl, TextColor.color(0x55FFFF), TextDecoration.UNDERLINED)
                            .clickEvent(ClickEvent.clickEvent(ClickEvent.Action.OPEN_URL, webUrl)))
                        .build());
                    if (isAdmin) {
                        player.sendMessage(Component.text("(点击上方链接打开管理页 — 审阅/批准/发放全部玩家的物品)",
                            TextColor.color(0xAAAAAA), TextDecoration.ITALIC));
                    } else {
                        player.sendMessage(Component.text("(点击上方链接打开状态页 — 查看您的物品提交状态)",
                            TextColor.color(0xAAAAAA), TextDecoration.ITALIC));
                    }
                } catch (Exception e) {
                    sender.sendMessage("§cToken generation failed: " + e.getMessage());
                }
            }
            case "reload" -> {
                if (!sender.hasPermission("ksinherit.admin")) {
                    sender.sendMessage("§cNeed ksinherit.admin permission");
                    return true;
                }
                try {
                    String result = plugin.reloadDatabase();
                    sender.sendMessage("§a[ks-Inherit] " + result);
                    if (result.startsWith("已从")) {
                        sender.sendMessage("§7§o提示: items_new.db 已导入并删除，原 items.db 不受影响");
                    }
                } catch (Exception e) {
                    sender.sendMessage("§cReload failed: " + e.getMessage());
                }
            }
            case "slots" -> {
                if (!sender.hasPermission("ksinherit.admin")) {
                    sender.sendMessage("§cNeed ksinherit.admin permission");
                    return true;
                }
                if (args.length < 3) {
                    sender.sendMessage("§cUsage: /inherit slots <player> <amount>");
                    return true;
                }
                String targetName = args[1];
                int slots;
                try { slots = Integer.parseInt(args[2]); }
                catch (NumberFormatException e) {
                    sender.sendMessage("§cAmount must be a number"); return true;
                }
                if (slots < 1 || slots > 54) {
                    sender.sendMessage("§cSlot count must be 1-54"); return true;
                }
                @SuppressWarnings("deprecation")
                OfflinePlayer target = Bukkit.getOfflinePlayer(targetName);
                if (target == null || target.getUniqueId() == null) {
                    sender.sendMessage("§cPlayer not found: " + targetName); return true;
                }
                plugin.setMaxSlots(target.getUniqueId(), slots);
                sender.sendMessage("§aSet " + targetName + " slot limit to " + slots);
            }
            case "testitem" -> {
                if (!(sender instanceof Player player)) {
                    sender.sendMessage("§cOnly players can use this"); return true;
                }
                if (!player.hasPermission("ksinherit.admin")) {
                    sender.sendMessage("§cNeed ksinherit.admin permission"); return true;
                }
                player.getInventory().addItem(createTestItem());
                player.sendMessage("§a✦ Dragon Soul Blade added to your inventory!");
            }
            default -> {
                sender.sendMessage("§cUnknown subcommand: /" + label + " " + sub);
                sender.sendMessage("§7Available: /inherit open, /inherit token, /inherit slots, /inherit testitem");
            }
        }
        return true;
    }

    /** Try old (1.20.6 "GENERIC_*") then new (1.21+ plain) Attribute field names. */
    @SuppressWarnings({"deprecation", "unchecked"})
    private void addAttrCompat(ItemMeta meta, String oldName, String newName,
            String uuidStr, String name, double amount,
            org.bukkit.attribute.AttributeModifier.Operation op) {
        try {
            // Try old name first (1.20.6)
            var field = org.bukkit.attribute.Attribute.class.getField(oldName);
            var attr = (org.bukkit.attribute.Attribute) field.get(null);
            var mod = new org.bukkit.attribute.AttributeModifier(
                UUID.fromString(uuidStr), name, amount, op,
                org.bukkit.inventory.EquipmentSlot.HAND);
            meta.addAttributeModifier(attr, mod);
        } catch (NoSuchFieldException e1) {
            try {
                // Try new name (1.21+)
                var field = org.bukkit.attribute.Attribute.class.getField(newName);
                var attr = (org.bukkit.attribute.Attribute) field.get(null);
                var mod = new org.bukkit.attribute.AttributeModifier(
                    UUID.fromString(uuidStr), name, amount, op,
                    org.bukkit.inventory.EquipmentSlot.HAND);
                meta.addAttributeModifier(attr, mod);
            } catch (Exception e2) {
                // Attribute not available on this version
            }
        } catch (Exception e) {
            // skip
        }
    }

    /** Build a complex test item purely via API — no string encoding issues. */
    private ItemStack createTestItem() {
        ItemStack item = new ItemStack(Material.DIAMOND_SWORD);
        ItemMeta meta = item.getItemMeta();

        // Custom name
        meta.displayName(Component.text("✦ Dragon Soul Blade ✦")
            .color(TextColor.color(0xFFAA00))
            .decoration(TextDecoration.ITALIC, false));

        // Multi-line lore
        List<Component> lore = new ArrayList<>();
        lore.add(Component.text("Legendary - Forged by ancient dragon souls")
            .color(TextColor.color(0xAAAAAA)).decoration(TextDecoration.ITALIC, false));
        lore.add(Component.empty());
        lore.add(Component.text("❄ Frost Nova ")
            .color(TextColor.color(0x55FFFF)).decoration(TextDecoration.ITALIC, false)
            .append(Component.text("- Releases ice blast on attack")
                .color(TextColor.color(0x55FF55))));
        lore.add(Component.text("🔥 Flame Infusion ")
            .color(TextColor.color(0xFF5555)).decoration(TextDecoration.ITALIC, false)
            .append(Component.text("- Deals +999 fire damage")
                .color(TextColor.color(0x55FF55))));
        lore.add(Component.text("💀 Soul Reaper ")
            .color(TextColor.color(0xFF55FF)).decoration(TextDecoration.ITALIC, false)
            .append(Component.text("- Heal 20% HP on kill")
                .color(TextColor.color(0x55FF55))));
        lore.add(Component.text("🌀 Gale Blade ")
            .color(TextColor.color(0x55FF55)).decoration(TextDecoration.ITALIC, false)
            .append(Component.text("- Attack speed greatly increased")
                .color(TextColor.color(0x55FF55))));
        lore.add(Component.empty());
        lore.add(Component.text("⚔ Attack Damage: +999")
            .color(TextColor.color(0xFFAA00)).decoration(TextDecoration.ITALIC, false));
        lore.add(Component.text("🛡 Armor Penetration: +50%")
            .color(TextColor.color(0x5555FF)).decoration(TextDecoration.ITALIC, false));
        lore.add(Component.text("💚 Life Steal: +15%")
            .color(TextColor.color(0x55FF55)).decoration(TextDecoration.ITALIC, false));
        lore.add(Component.empty());
        lore.add(Component.text("Soulbound: baoyu_233")
            .color(TextColor.color(0x555555)).decoration(TextDecoration.ITALIC, false));
        lore.add(Component.text("◆ Ancient Dragon Soul Limited ◆")
            .color(TextColor.color(0xAA00AA)).decoration(TextDecoration.ITALIC, false));
        meta.lore(lore);

        // Enchantments
        meta.addEnchant(Enchantment.SHARPNESS, 255, true);
        meta.addEnchant(Enchantment.FIRE_ASPECT, 10, true);
        meta.addEnchant(Enchantment.LOOTING, 100, true);
        meta.addEnchant(Enchantment.UNBREAKING, 10, true);
        meta.addEnchant(Enchantment.SWEEPING_EDGE, 50, true);
        meta.addEnchant(Enchantment.KNOCKBACK, 20, true);
        meta.addEnchant(Enchantment.MENDING, 1, true);
        meta.addEnchant(Enchantment.BANE_OF_ARTHROPODS, 100, true);

        // Attribute modifiers — cross-version (1.20.6 generic.* / 1.21 plain names)
        addAttrCompat(meta, "GENERIC_ATTACK_DAMAGE", "ATTACK_DAMAGE",
            "c1a2b3d4-e5f6-7890-abcd-ef1234567890", "atk", 999,
            org.bukkit.attribute.AttributeModifier.Operation.ADD_NUMBER);
        addAttrCompat(meta, "GENERIC_ATTACK_SPEED", "ATTACK_SPEED",
            "d2b3c4e5-f6a7-8901-bcde-f12345678901", "spd", -2.4,
            org.bukkit.attribute.AttributeModifier.Operation.ADD_NUMBER);
        addAttrCompat(meta, "GENERIC_MOVEMENT_SPEED", "MOVEMENT_SPEED",
            "e3c4d5f6-a7b8-9012-cdef-123456789012", "mov", 0.5,
            org.bukkit.attribute.AttributeModifier.Operation.MULTIPLY_SCALAR_1);
        addAttrCompat(meta, "GENERIC_MAX_HEALTH", "MAX_HEALTH",
            "f4d5e6f7-b8c9-0123-defa-234567890123", "hp", 20,
            org.bukkit.attribute.AttributeModifier.Operation.ADD_NUMBER);

        // Unbreakable
        meta.setUnbreakable(true);

        // CustomModelData
        meta.setCustomModelData(10001);

        // Hide vanilla "When in main hand" for the default +7 attack, but keep custom attributes visible
        meta.addItemFlags(ItemFlag.HIDE_ADDITIONAL_TOOLTIP);

        item.setItemMeta(meta);
        return item;
    }
}
