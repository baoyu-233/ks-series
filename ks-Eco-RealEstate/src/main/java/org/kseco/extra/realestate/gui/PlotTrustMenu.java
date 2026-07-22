package org.kseco.extra.realestate.gui;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.AsyncPlayerChatEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.SkullMeta;
import org.jetbrains.annotations.NotNull;
import org.kseco.KsEco;
import org.kseco.extra.realestate.RealEstateManager;
import org.kseco.extra.realestate.RealEstateManager.TrustEntry;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.RejectedExecutionException;
import java.util.regex.Pattern;

/**
 * 单块地的信任名单管理 GUI（仅 PLAYER 地块）。
 * 左键=切换破坏权限，右键=切换容器权限，Shift+右键=切换互动权限，Shift+左键=移除信任。
 */
public final class PlotTrustMenu implements InventoryHolder {

    private static final int ADD_SLOT = 49;
    private static final int BACK_SLOT = 45;
    private static final long INPUT_TIMEOUT_MILLIS = 120_000L;
    private static final Pattern PLAYER_NAME = Pattern.compile("[A-Za-z0-9_]{3,16}");

    private final KsEco eco;
    private final RealEstateManager mgr;
    private final String plotId;
    private final List<TrustEntry> trust = new ArrayList<>();
    private Inventory inventory;
    private long loadGeneration;
    private boolean loaded;
    private boolean mutationInFlight;

    private record PendingTrustInput(String plotId, long expiresAt) {}

    // 等待聊天栏输入"要信任的玩家名"的玩家 -> 目标地块与过期时间
    private static final Map<UUID, PendingTrustInput> pendingTrustInput = new ConcurrentHashMap<>();

    public PlotTrustMenu(KsEco eco, RealEstateManager mgr, String plotId) {
        this.eco = eco;
        this.mgr = mgr;
        this.plotId = plotId;
    }

    public void open(Player player) {
        inventory = Bukkit.createInventory(this, 54, Component.text("§8地块信任 - " + plotId));
        renderLoading("正在读取信任名单...");
        player.openInventory(inventory);
        submitLoad(player.getUniqueId());
    }

    private void submitLoad(UUID playerId) {
        loaded = false;
        long generation = ++loadGeneration;
        try {
            eco.asyncWorkPool().executeDatabase(() -> {
                List<TrustEntry> entries = List.of();
                String error = null;
                try {
                    entries = mgr.loadOwnedTrustEntries(plotId, playerId);
                } catch (Exception exception) {
                    error = "读取信任名单失败";
                    eco.getLogger().warning("[房地产] 异步读取地块信任失败: " + exception.getMessage());
                }
                List<TrustEntry> result = entries;
                String resultError = error;
                eco.scheduler().runPlayer(playerId, () -> applyLoad(playerId, generation, result, resultError));
            });
        } catch (RejectedExecutionException rejected) {
            renderError("数据库队列繁忙，请稍后重试");
        }
    }

    private void applyLoad(UUID playerId, long generation, List<TrustEntry> entries, String error) {
        if (generation != loadGeneration) return;
        Player player = Bukkit.getPlayer(playerId);
        if (player == null || !isViewing(player)) return;
        if (error != null) {
            renderError(error);
            return;
        }
        trust.clear();
        trust.addAll(entries);
        loaded = true;
        renderTrust();
    }

    private void renderLoading(String message) {
        inventory.clear();
        inventory.setItem(22, navButton(Material.CLOCK, "§e" + message));
        inventory.setItem(BACK_SLOT, navButton(Material.ARROW, "§7« 返回我的地块"));
    }

    private void renderError(String message) {
        loaded = false;
        inventory.clear();
        inventory.setItem(22, navButton(Material.BARRIER, "§c" + message));
        inventory.setItem(BACK_SLOT, navButton(Material.ARROW, "§7« 返回我的地块"));
    }

    private void renderTrust() {
        inventory.clear();
        for (int i = 0; i < trust.size() && i < 45; i++) {
            inventory.setItem(i, trustItem(trust.get(i)));
        }
        if (trust.isEmpty()) inventory.setItem(22, navButton(Material.PAPER, "§7尚未信任其他玩家"));
        inventory.setItem(BACK_SLOT, navButton(Material.ARROW, "§7« 返回我的地块"));
        inventory.setItem(ADD_SLOT, navButton(Material.EMERALD, "§a➕ 添加信任玩家", "点击后在聊天栏输入玩家名"));
    }

    private ItemStack trustItem(TrustEntry entry) {
        ItemStack item = new ItemStack(Material.PLAYER_HEAD);
        ItemMeta meta = item.getItemMeta();
        if (meta instanceof SkullMeta skull) {
            skull.setOwningPlayer(Bukkit.getOfflinePlayer(entry.trustedUuid()));
        }
        if (meta != null) {
            String displayName = entry.trustedName() == null || entry.trustedName().isBlank()
                    ? entry.trustedUuid().toString()
                    : entry.trustedName();
            meta.displayName(Component.text("§b" + displayName));
            List<Component> lore = new ArrayList<>();
            lore.add(flag("破坏方块", entry.canBuild()));
            lore.add(flag("开容器", entry.canContainer()));
            lore.add(flag("互动方块", entry.canInteract()));
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

    private ItemStack navButton(Material material, String name, String... lore) {
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.displayName(Component.text(name));
            if (lore.length > 0) {
                List<Component> lines = new ArrayList<>();
                for (String line : lore) lines.add(Component.text(line, NamedTextColor.GRAY));
                meta.lore(lines);
            }
            item.setItemMeta(meta);
        }
        return item;
    }

    private void submitMutation(Player player, TrustEntry entry, boolean revoke,
                                boolean build, boolean container, boolean interact) {
        if (mutationInFlight) {
            player.sendMessage("§e上一个信任操作仍在处理中。");
            return;
        }
        mutationInFlight = true;
        loaded = false;
        ++loadGeneration;
        renderLoading("正在保存信任设置...");

        UUID playerId = player.getUniqueId();
        try {
            eco.asyncWorkPool().executeDatabase(() -> {
                String error = revoke
                        ? mgr.revokeTrust(plotId, playerId, entry.trustedUuid())
                        : mgr.grantTrust(plotId, playerId, entry.trustedUuid(), entry.trustedName(),
                                build, container, interact);
                eco.scheduler().runPlayer(playerId, () -> finishMutation(playerId, entry, revoke, error));
            });
        } catch (RejectedExecutionException rejected) {
            mutationInFlight = false;
            loaded = true;
            renderTrust();
            player.sendMessage("§c数据库队列繁忙，请稍后重试。");
        }
    }

    private void finishMutation(UUID playerId, TrustEntry entry, boolean revoke, String error) {
        mutationInFlight = false;
        Player player = Bukkit.getPlayer(playerId);
        if (player == null) return;
        if (error != null) player.sendMessage("§c" + error);
        else if (revoke) player.sendMessage("§e已移除 " + entry.trustedName() + " 的信任。");
        else player.sendMessage("§a信任权限已更新。");
        if (isViewing(player)) {
            renderLoading("正在刷新信任名单...");
            submitLoad(playerId);
        }
    }

    private boolean isViewing(Player player) {
        return player.getOpenInventory().getTopInventory().getHolder() == this;
    }

    @Override
    public @NotNull Inventory getInventory() {
        return inventory;
    }

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
            if (!(event.getWhoClicked() instanceof Player player) || event.getClickedInventory() != menu.inventory) return;
            int slot = event.getRawSlot();

            if (slot == BACK_SLOT) {
                player.closeInventory();
                new PlotListMenu(eco, mgr).open(player);
                return;
            }
            if (!menu.loaded || menu.mutationInFlight) return;
            if (slot == ADD_SLOT) {
                player.closeInventory();
                pendingTrustInput.put(player.getUniqueId(),
                        new PendingTrustInput(menu.plotId, System.currentTimeMillis() + INPUT_TIMEOUT_MILLIS));
                player.sendMessage("§a请在聊天栏输入要信任的玩家名（默认给予全部权限），或输入 cancel 取消");
                return;
            }
            if (slot < 0 || slot >= menu.trust.size()) return;
            if (!event.isLeftClick() && !event.isRightClick()) return;

            TrustEntry entry = menu.trust.get(slot);
            boolean revoke = event.isShiftClick() && event.isLeftClick();
            boolean build = revoke || !event.isShiftClick() && event.isLeftClick()
                    ? !entry.canBuild() : entry.canBuild();
            boolean container = !revoke && !event.isShiftClick() && event.isRightClick()
                    ? !entry.canContainer() : entry.canContainer();
            boolean interact = !revoke && event.isShiftClick() && event.isRightClick()
                    ? !entry.canInteract() : entry.canInteract();
            menu.submitMutation(player, entry, revoke, build, container, interact);
        }

        @EventHandler
        public void onDrag(InventoryDragEvent event) {
            if (event.getInventory().getHolder() instanceof PlotTrustMenu) event.setCancelled(true);
        }
    }

    /** 聊天栏输入玩家名 -> 异步 grantTrust(默认三项权限全开) -> 重新打开信任菜单。 */
    public static final class ChatListener implements org.bukkit.event.Listener {
        private final KsEco eco;
        private final RealEstateManager mgr;

        public ChatListener(KsEco eco, RealEstateManager mgr) {
            this.eco = eco;
            this.mgr = mgr;
        }

        @EventHandler
        public void onChat(AsyncPlayerChatEvent event) {
            UUID playerId = event.getPlayer().getUniqueId();
            PendingTrustInput pending = pendingTrustInput.remove(playerId);
            if (pending == null || pending.expiresAt() < System.currentTimeMillis()) return;
            event.setCancelled(true);

            String name = event.getMessage().trim();
            eco.scheduler().runPlayer(playerId, () -> resolveAndGrant(playerId, pending.plotId(), name));
        }

        private void resolveAndGrant(UUID playerId, String plotId, String name) {
            Player player = Bukkit.getPlayer(playerId);
            if (player == null) return;
            if (name.equalsIgnoreCase("cancel")) {
                player.sendMessage("§c已取消。");
                return;
            }
            if (!PLAYER_NAME.matcher(name).matches()) {
                pendingTrustInput.put(playerId,
                        new PendingTrustInput(plotId, System.currentTimeMillis() + INPUT_TIMEOUT_MILLIS));
                player.sendMessage("§c玩家名只能包含 3-16 位字母、数字或下划线，请重新输入或输入 cancel。");
                return;
            }

            Player online = Bukkit.getPlayerExact(name);
            OfflinePlayer target = online;
            if (target == null) {
                OfflinePlayer offline = Bukkit.getOfflinePlayer(name);
                if (offline.hasPlayedBefore()) target = offline;
            }
            if (target == null) {
                pendingTrustInput.put(playerId,
                        new PendingTrustInput(plotId, System.currentTimeMillis() + INPUT_TIMEOUT_MILLIS));
                player.sendMessage("§c找不到玩家: " + name + "（必须是在线过的玩家），请重新输入或输入 cancel。");
                return;
            }

            UUID targetUuid = target.getUniqueId();
            String targetName = target.getName() == null ? name : target.getName();
            player.sendMessage("§e正在保存信任设置...");
            try {
                eco.asyncWorkPool().executeDatabase(() -> {
                    String error = mgr.grantTrust(plotId, playerId, targetUuid, targetName, true, true, true);
                    eco.scheduler().runPlayer(playerId, () -> finishGrant(playerId, plotId, targetName, error));
                });
            } catch (RejectedExecutionException rejected) {
                player.sendMessage("§c数据库队列繁忙，请稍后重试。");
            }
        }

        private void finishGrant(UUID playerId, String plotId, String targetName, String error) {
            Player player = Bukkit.getPlayer(playerId);
            if (player == null) return;
            if (error != null) {
                player.sendMessage("§c" + error);
                return;
            }
            player.sendMessage("§a已信任 " + targetName + "（破坏/容器/互动 全部授予）");
            new PlotTrustMenu(eco, mgr, plotId).open(player);
        }

        @EventHandler
        public void onQuit(PlayerQuitEvent event) {
            pendingTrustInput.remove(event.getPlayer().getUniqueId());
        }
    }
}
