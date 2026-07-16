package org.kseco.gui;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.player.AsyncPlayerChatEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.jetbrains.annotations.NotNull;
import org.kseco.KsEco;
import org.kseco.TransferManager;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** Bank-adjacent player money transfer flow with an independent feature gate. */
public final class TransferGui implements InventoryHolder {
    private static final Map<UUID, Draft> DRAFTS = new ConcurrentHashMap<>();
    private static final Map<UUID, InputType> PENDING_INPUT = new ConcurrentHashMap<>();

    private final KsEco plugin;
    private Inventory inventory;

    public TransferGui(KsEco plugin) {
        this.plugin = plugin;
    }

    public void open(Player player) {
        if (!plugin.isFeatureOpen(player, "transfer")) {
            player.sendMessage("§c转账功能当前未开放。");
            return;
        }
        build(player);
        player.openInventory(inventory);
    }

    private void build(Player player) {
        Draft draft = DRAFTS.getOrDefault(player.getUniqueId(), Draft.empty());
        inventory = Bukkit.createInventory(this, 36, Component.text("§8银行 · 玩家转账"));
        inventory.setItem(4, button(Material.SUNFLOWER, "§6当前余额: §e" + plugin.vaultHook().format(
                plugin.vaultHook().getBalance(player)), "§7转账税费由付款人额外承担"));
        inventory.setItem(11, button(draft.targetUuid() == null ? Material.NAME_TAG : Material.PLAYER_HEAD,
                draft.targetUuid() == null ? "§e选择收款人" : "§a收款人: §f" + draft.targetName(),
                draft.targetUuid() == null ? "§7点击后在聊天栏输入玩家名" : "§7UUID: " + draft.targetUuid(),
                "§b▸ §7点击修改"));
        inventory.setItem(15, button(Material.GOLD_INGOT,
                draft.amount() == null ? "§e输入转账金额" : "§a转账金额: §f" + plugin.vaultHook().format(draft.amount()),
                draft.amount() == null ? "§7点击后在聊天栏输入金额" : "§b▸ §7点击修改"));

        if (draft.ready()) {
            TransferManager.TransferQuote quote = plugin.transferManager().quote(draft.amount());
            inventory.setItem(22, button(Material.LIME_CONCRETE, "§a§l确认转账",
                    "§7收款人: §f" + draft.targetName(),
                    "§7转账本金: §e" + plugin.vaultHook().format(quote.amount()),
                    "§7免税部分: §a" + plugin.vaultHook().format(quote.exemptAmount()),
                    "§7应税部分: §f" + plugin.vaultHook().format(quote.taxableAmount()),
                    "§7税率: §f" + String.format(java.util.Locale.ROOT, "%.2f%%", quote.taxRate() * 100),
                    "§7税费: §c" + plugin.vaultHook().format(quote.tax()),
                    "§7合计扣款: §6" + plugin.vaultHook().format(quote.totalDebit()),
                    "", "§a§l点击确认并立即执行"));
        } else {
            inventory.setItem(22, button(Material.GRAY_CONCRETE, "§7等待填写",
                    "§7请先选择收款人并输入金额"));
        }

        inventory.setItem(27, button(Material.ARROW, "§c返回", "§7返回经济主菜单"));
        inventory.setItem(31, button(Material.BUCKET, "§e清空重填", "§7清除当前收款人与金额"));
        inventory.setItem(35, button(Material.BARRIER, "§c关闭"));
        fillEmpty();
    }

    private ItemStack button(Material material, String name, String... lore) {
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.displayName(Component.text(name));
            if (lore.length > 0) {
                meta.lore(java.util.Arrays.stream(lore)
                        .map(line -> Component.text(line, NamedTextColor.GRAY)).toList());
            }
            item.setItemMeta(meta);
        }
        return item;
    }

    private void fillEmpty() {
        ItemStack pane = button(Material.BLACK_STAINED_GLASS_PANE, " ");
        for (int i = 0; i < inventory.getSize(); i++) {
            if (inventory.getItem(i) == null) inventory.setItem(i, pane.clone());
        }
    }

    @Override
    public @NotNull Inventory getInventory() {
        return inventory;
    }

    private static OfflinePlayer resolvePlayer(String name) {
        Player online = Bukkit.getPlayerExact(name);
        if (online != null) return online;
        for (OfflinePlayer player : Bukkit.getOfflinePlayers()) {
            if (player.getName() != null && player.getName().equalsIgnoreCase(name)) return player;
        }
        return null;
    }

    private enum InputType { TARGET, AMOUNT }

    private record Draft(UUID targetUuid, String targetName, Double amount) {
        static Draft empty() { return new Draft(null, null, null); }
        boolean ready() { return targetUuid != null && targetName != null && amount != null; }
        Draft withTarget(OfflinePlayer target) {
            return new Draft(target.getUniqueId(), target.getName(), amount);
        }
        Draft withAmount(double value) { return new Draft(targetUuid, targetName, value); }
    }

    public static final class Listener implements org.bukkit.event.Listener {
        private final KsEco plugin;

        public Listener(KsEco plugin) { this.plugin = plugin; }

        @EventHandler
        public void onClick(InventoryClickEvent event) {
            if (!(event.getInventory().getHolder() instanceof TransferGui gui)) return;
            event.setCancelled(true);
            if (!(event.getWhoClicked() instanceof Player player)) return;
            if (!plugin.isFeatureOpen(player, "transfer")) {
                player.closeInventory();
                player.sendMessage("§c转账功能当前未开放。");
                return;
            }
            int slot = event.getRawSlot();
            if (slot < 0 || slot >= event.getView().getTopInventory().getSize()) return;
            switch (slot) {
                case 11 -> ask(player, InputType.TARGET, "§a请在聊天栏输入收款玩家名，或输入 cancel 取消");
                case 15 -> ask(player, InputType.AMOUNT, "§a请在聊天栏输入转账金额，或输入 cancel 取消");
                case 22 -> confirm(player, gui);
                case 27 -> { player.closeInventory(); new EcoGuiMainMenu(plugin).open(player); }
                case 31 -> { DRAFTS.remove(player.getUniqueId()); gui.open(player); }
                case 35 -> player.closeInventory();
            }
        }

        @EventHandler
        public void onQuit(PlayerQuitEvent event) {
            UUID uuid = event.getPlayer().getUniqueId();
            DRAFTS.remove(uuid);
            PENDING_INPUT.remove(uuid);
        }

        private void ask(Player player, InputType type, String message) {
            player.closeInventory();
            PENDING_INPUT.put(player.getUniqueId(), type);
            player.sendMessage(message);
        }

        private void confirm(Player player, TransferGui gui) {
            Draft draft = DRAFTS.get(player.getUniqueId());
            if (draft == null || !draft.ready()) {
                player.sendMessage("§c请先填写收款人与金额。");
                return;
            }
            OfflinePlayer recipient = Bukkit.getOfflinePlayer(draft.targetUuid());
            TransferManager.TransferResult result = plugin.transferManager().transfer(player, recipient, draft.amount());
            if (!result.success()) {
                player.sendMessage("§c转账失败: " + result.error());
                gui.open(player);
                return;
            }
            DRAFTS.remove(player.getUniqueId());
            TransferManager.TransferQuote quote = result.quote();
            player.sendMessage("§a转账成功！§f" + plugin.vaultHook().format(quote.amount()) + " §a已转给 "
                    + draft.targetName() + "，税费 §c" + plugin.vaultHook().format(quote.tax()) + "§a。");
            player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_PLING, 0.6f, 1.8f);
            Player onlineRecipient = Bukkit.getPlayer(draft.targetUuid());
            if (onlineRecipient != null) {
                onlineRecipient.sendMessage("§a你收到来自 §f" + player.getName() + " §a的转账: §e"
                        + plugin.vaultHook().format(quote.amount()));
                onlineRecipient.playSound(onlineRecipient.getLocation(), Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 0.5f, 1.5f);
            }
            gui.open(player);
        }
    }

    public static final class ChatListener implements org.bukkit.event.Listener {
        private final KsEco plugin;

        public ChatListener(KsEco plugin) { this.plugin = plugin; }

        @EventHandler
        public void onChat(AsyncPlayerChatEvent event) {
            Player player = event.getPlayer();
            InputType type = PENDING_INPUT.remove(player.getUniqueId());
            if (type == null) return;
            event.setCancelled(true);
            String input = event.getMessage().trim();
            Bukkit.getScheduler().runTask(plugin, () -> handleInput(player, type, input));
        }

        private void handleInput(Player player, InputType type, String input) {
            if (!plugin.isFeatureOpen(player, "transfer")) {
                player.sendMessage("§c转账功能当前未开放。");
                return;
            }
            if (input.equalsIgnoreCase("cancel")) {
                new TransferGui(plugin).open(player);
                return;
            }
            Draft draft = DRAFTS.getOrDefault(player.getUniqueId(), Draft.empty());
            if (type == InputType.TARGET) {
                OfflinePlayer target = resolvePlayer(input);
                if (target == null) {
                    player.sendMessage("§c未找到玩家: " + input);
                    PENDING_INPUT.put(player.getUniqueId(), type);
                    player.sendMessage("§7请重新输入玩家名，或输入 cancel 取消");
                    return;
                }
                if (target.getUniqueId().equals(player.getUniqueId())) {
                    player.sendMessage("§c不能向自己转账。");
                    PENDING_INPUT.put(player.getUniqueId(), type);
                    return;
                }
                DRAFTS.put(player.getUniqueId(), draft.withTarget(target));
            } else {
                double amount;
                try { amount = Double.parseDouble(input); }
                catch (NumberFormatException e) { amount = -1; }
                if (!Double.isFinite(amount) || amount <= 0.0 || amount > 1_000_000_000_000d) {
                    player.sendMessage("§c请输入大于 0 的有效金额。");
                    PENDING_INPUT.put(player.getUniqueId(), type);
                    return;
                }
                DRAFTS.put(player.getUniqueId(), draft.withAmount(amount));
            }
            new TransferGui(plugin).open(player);
        }
    }
}
