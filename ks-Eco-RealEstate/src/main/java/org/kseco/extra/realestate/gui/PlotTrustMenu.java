package org.kseco.extra.realestate.gui;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.player.AsyncPlayerChatEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.SkullMeta;
import org.jetbrains.annotations.NotNull;
import org.kseco.KsEco;
import org.kseco.extra.realestate.RealEstateManager;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 单块地的信任名单管理 GUI（仅 PLAYER 地块）。
 * 左键=切换破坏权限，右键=切换容器权限，Shift+右键=切换互动权限，Shift+左键=移除信任。
 */
public final class PlotTrustMenu implements InventoryHolder {

    private final KsEco eco;
    private final RealEstateManager mgr;
    private final String plotId;
    private Inventory inventory;
    private final List<Map<String, Object>> trust = new ArrayList<>();

    private static final int ADD_SLOT = 49;
    private static final int BACK_SLOT = 45;

    // 等待聊天栏输入"要信任的玩家名"的玩家 -> 目标地块 ID
    static final Map<UUID, String> pendingTrustInput = new HashMap<>();

    public PlotTrustMenu(KsEco eco, RealEstateManager mgr, String plotId) {
        this.eco = eco;
        this.mgr = mgr;
        this.plotId = plotId;
    }

    public void open(Player player) {
        trust.clear();
        trust.addAll(mgr.listTrust(plotId));
        build();
        player.openInventory(inventory);
    }

    private void build() {
        inventory = Bukkit.createInventory(this, 54, Component.text("§8地块信任 - " + plotId));
        for (int i = 0; i < trust.size() && i < 45; i++) {
            Map<String, Object> t = trust.get(i);
            inventory.setItem(i, trustItem(t));
        }
        inventory.setItem(BACK_SLOT, navButton(Material.ARROW, "§7« 返回我的地块"));
        inventory.setItem(ADD_SLOT, navButton(Material.EMERALD, "§a➕ 添加信任玩家", "§7点击后在聊天栏输入玩家名"));
    }

    private ItemStack trustItem(Map<String, Object> t) {
        ItemStack item = new ItemStack(Material.PLAYER_HEAD);
        ItemMeta meta = item.getItemMeta();
        if (meta instanceof SkullMeta skull) {
            try {
                skull.setOwningPlayer(Bukkit.getOfflinePlayer(UUID.fromString((String) t.get("trustedUuid"))));
            } catch (Exception ignored) {}
        }
        if (meta != null) {
            meta.displayName(Component.text("§b" + t.get("trustedName")));
            List<Component> lore = new ArrayList<>();
            lore.add(flag("破坏方块", (Boolean) t.get("canBuild")));
            lore.add(flag("开容器", (Boolean) t.get("canContainer")));
            lore.add(flag("互动方块", (Boolean) t.get("canInteract")));
            lore.add(Component.empty());
            lore.add(Component.text("左键=切换破坏 右键=切换容器", NamedTextColor.GRAY));
            lore.add(Component.text("Shift+右键=切换互动 Shift+左键=移除", NamedTextColor.GRAY));
            meta.lore(lore);
            item.setItemMeta(meta);
        }
        return item;
    }

    private Component flag(String label, boolean on) {
        return Component.text((on ? "✔ " : "✘ ") + label, on ? NamedTextColor.GREEN : NamedTextColor.RED);
    }

    private ItemStack navButton(Material mat, String name, String... lore) {
        ItemStack item = new ItemStack(mat);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.displayName(Component.text(name));
            if (lore.length > 0) {
                List<Component> list = new ArrayList<>();
                for (String s : lore) list.add(Component.text(s, NamedTextColor.GRAY));
                meta.lore(list);
            }
            item.setItemMeta(meta);
        }
        return item;
    }

    @Override public @NotNull Inventory getInventory() { return inventory; }

    public static final class Listener implements org.bukkit.event.Listener {
        private final KsEco eco;
        private final RealEstateManager mgr;

        public Listener(KsEco eco, RealEstateManager mgr) {
            this.eco = eco;
            this.mgr = mgr;
        }

        @EventHandler
        public void onClick(InventoryClickEvent event) {
            if (!(event.getInventory().getHolder() instanceof PlotTrustMenu menu)) return;
            event.setCancelled(true);
            if (!(event.getWhoClicked() instanceof Player player)) return;
            int slot = event.getSlot();

            if (slot == BACK_SLOT) {
                player.closeInventory();
                new PlotListMenu(eco, mgr).open(player);
                return;
            }
            if (slot == ADD_SLOT) {
                player.closeInventory();
                pendingTrustInput.put(player.getUniqueId(), menu.plotId);
                player.sendMessage("§a请在聊天栏输入要信任的玩家名（默认给予全部权限），或输入 cancel 取消");
                return;
            }
            if (slot < 0 || slot >= menu.trust.size()) return;
            Map<String, Object> t = menu.trust.get(slot);
            UUID target = UUID.fromString((String) t.get("trustedUuid"));
            UUID owner = player.getUniqueId();

            if (event.isShiftClick() && event.isLeftClick()) {
                mgr.revokeTrust(menu.plotId, owner, target);
                player.sendMessage("§e已移除 " + t.get("trustedName") + " 的信任。");
            } else if (event.isShiftClick() && event.isRightClick()) {
                mgr.grantTrust(menu.plotId, owner, target, (String) t.get("trustedName"),
                        (Boolean) t.get("canBuild"), (Boolean) t.get("canContainer"), !(Boolean) t.get("canInteract"));
            } else if (event.isRightClick()) {
                mgr.grantTrust(menu.plotId, owner, target, (String) t.get("trustedName"),
                        (Boolean) t.get("canBuild"), !(Boolean) t.get("canContainer"), (Boolean) t.get("canInteract"));
            } else {
                mgr.grantTrust(menu.plotId, owner, target, (String) t.get("trustedName"),
                        !(Boolean) t.get("canBuild"), (Boolean) t.get("canContainer"), (Boolean) t.get("canInteract"));
            }
            menu.open(player);
        }
    }

    /** 聊天栏输入玩家名 -> grantTrust(默认三项权限全开) -> 重新打开信任菜单。 */
    public static final class ChatListener implements org.bukkit.event.Listener {
        private final KsEco eco;
        private final RealEstateManager mgr;

        public ChatListener(KsEco eco, RealEstateManager mgr) {
            this.eco = eco;
            this.mgr = mgr;
        }

        @EventHandler
        public void onChat(AsyncPlayerChatEvent event) {
            Player player = event.getPlayer();
            String plotId = pendingTrustInput.remove(player.getUniqueId());
            if (plotId == null) return;
            event.setCancelled(true);

            String name = event.getMessage().trim();
            if (name.equalsIgnoreCase("cancel")) {
                player.sendMessage("§c已取消。");
                return;
            }
            Player online = Bukkit.getPlayerExact(name);
            OfflinePlayer target = online;
            if (target == null) {
                OfflinePlayer off = Bukkit.getOfflinePlayer(name);
                if (off.hasPlayedBefore()) target = off;
            }
            if (target == null || target.getUniqueId() == null) {
                player.sendMessage("§c找不到玩家: " + name + "（必须是在线过的玩家）");
                return;
            }
            UUID targetUuid = target.getUniqueId();
            String targetName = target.getName() == null ? name : target.getName();
            Bukkit.getScheduler().runTask(eco, () -> {
                String err = mgr.grantTrust(plotId, player.getUniqueId(), targetUuid, targetName, true, true, true);
                if (err != null) {
                    player.sendMessage("§c" + err);
                } else {
                    player.sendMessage("§a已信任 " + targetName + "（破坏/容器/互动 全部授予）");
                }
                new PlotTrustMenu(eco, mgr, plotId).open(player);
            });
        }
    }
}
