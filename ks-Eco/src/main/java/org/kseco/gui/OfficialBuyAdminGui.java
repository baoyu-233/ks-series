package org.kseco.gui;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.player.AsyncPlayerChatEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.jetbrains.annotations.NotNull;
import org.kseco.KsEco;
import org.kseco.MaterialNames;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** 游戏内官方收购内容与价格管理。官方收购按材质匹配，不保存手持物品 NBT。 */
public final class OfficialBuyAdminGui implements InventoryHolder {
    private static final int SIZE = 54;
    private static final int PAGE_SIZE = 45;
    private static final ConcurrentHashMap<UUID, PendingPrice> PENDING = new ConcurrentHashMap<>();

    private final KsEco plugin;
    private Inventory inventory;
    private List<String> materials = List.of();
    private int page;

    public OfficialBuyAdminGui(KsEco plugin) {
        this.plugin = plugin;
    }

    public void open(Player player) {
        if (!player.hasPermission("kseco.admin")) {
            player.sendMessage("§c权限不足。");
            return;
        }
        page = 0;
        rebuild(player);
    }

    private void rebuild(Player player) {
        materials = plugin.priceEngine().getAllPrices().keySet().stream()
                .filter(material -> plugin.priceEngine().getOfficialBuyPrice(material) > 0)
                .sorted(Comparator.comparing(MaterialNames::get))
                .toList();
        int maxPage = Math.max(0, (materials.size() - 1) / PAGE_SIZE);
        page = Math.max(0, Math.min(page, maxPage));
        inventory = Bukkit.createInventory(this, SIZE,
                Component.text("§8官方收购设置 第" + (page + 1) + "页"));
        int start = page * PAGE_SIZE;
        for (int slot = 0; slot < PAGE_SIZE && start + slot < materials.size(); slot++) {
            String material = materials.get(start + slot);
            Material type;
            try { type = Material.valueOf(material); }
            catch (IllegalArgumentException e) { type = Material.BARRIER; }
            inventory.setItem(slot, priceIcon(type, material));
        }
        if (materials.isEmpty()) inventory.setItem(22, button(Material.PAPER, "§7当前没有官方收购物品"));
        if (page > 0) inventory.setItem(45, button(Material.ARROW, "§a上一页"));
        inventory.setItem(46, button(Material.HOPPER, "§a设置主手物品",
                "§7按材质新增或修改官方收购价",
                "§7点击后在聊天栏输入价格",
                "§70 表示停止收购"));
        inventory.setItem(49, button(Material.OAK_DOOR, "§c返回市场"));
        if ((page + 1) * PAGE_SIZE < materials.size()) inventory.setItem(53, button(Material.ARROW, "§a下一页"));
        fillEmpty();
        player.openInventory(inventory);
    }

    private ItemStack priceIcon(Material type, String material) {
        ItemStack item = new ItemStack(type);
        ItemMeta meta = item.getItemMeta();
        if (meta == null) return item;
        meta.displayName(Component.text("§a" + MaterialNames.get(material)));
        List<Component> lore = new ArrayList<>();
        lore.add(Component.text("材质: " + material, NamedTextColor.GRAY));
        lore.add(Component.text("当前收购价: §e" + plugin.vaultHook().format(
                plugin.priceEngine().getOfficialBuyPrice(material)), NamedTextColor.GRAY));
        lore.add(Component.empty());
        lore.add(Component.text("§a左键修改价格", NamedTextColor.GREEN));
        lore.add(Component.text("§c右键停止收购", NamedTextColor.RED));
        meta.lore(lore);
        item.setItemMeta(meta);
        return item;
    }

    private ItemStack button(Material material, String name, String... lore) {
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.displayName(Component.text(name));
            List<Component> lines = new ArrayList<>();
            for (String line : lore) lines.add(Component.text(line, NamedTextColor.GRAY));
            if (!lines.isEmpty()) meta.lore(lines);
            item.setItemMeta(meta);
        }
        return item;
    }

    private void fillEmpty() {
        ItemStack pane = button(Material.BLACK_STAINED_GLASS_PANE, " ");
        for (int slot = 0; slot < SIZE; slot++) {
            if (inventory.getItem(slot) == null) inventory.setItem(slot, pane.clone());
        }
    }

    private boolean canEdit(Player player) {
        if (!player.hasPermission("kseco.admin")) {
            player.sendMessage("§c管理员权限已失效。");
            return false;
        }
        String governanceError = plugin.politicGovernanceError();
        if (governanceError != null) {
            player.sendMessage("§c" + governanceError);
            return false;
        }
        return true;
    }

    private void askPrice(Player player, Material material) {
        if (!canEdit(player)) return;
        PENDING.put(player.getUniqueId(), new PendingPrice(material, this));
        player.closeInventory();
        player.sendMessage("§a请输入 §f" + MaterialNames.get(material.name())
                + " §a的官方收购单价，输入 §f0 §a停止收购，或输入 cancel 取消。");
    }

    @Override
    public @NotNull Inventory getInventory() {
        return inventory;
    }

    private record PendingPrice(Material material, OfficialBuyAdminGui gui) { }

    public static final class GuiListener implements Listener {
        private final KsEco plugin;

        public GuiListener(KsEco plugin) {
            this.plugin = plugin;
        }

        @EventHandler
        public void onClick(InventoryClickEvent event) {
            if (!(event.getView().getTopInventory().getHolder() instanceof OfficialBuyAdminGui gui)) return;
            event.setCancelled(true);
            if (!(event.getWhoClicked() instanceof Player player)) return;
            int slot = event.getRawSlot();
            if (slot < 0 || slot >= gui.getInventory().getSize()) return;
            if (slot < PAGE_SIZE) {
                int index = gui.page * PAGE_SIZE + slot;
                if (index >= gui.materials.size()) return;
                Material material;
                try { material = Material.valueOf(gui.materials.get(index)); }
                catch (IllegalArgumentException e) { return; }
                if (event.isRightClick()) {
                    if (!gui.canEdit(player)) return;
                    boolean ok = plugin.priceEngine().setOfficialBuyPrice(material.name(), 0);
                    player.sendMessage(ok ? "§a已停止收购 " + MaterialNames.get(material.name()) : "§c停收失败。");
                    gui.rebuild(player);
                } else if (event.isLeftClick()) {
                    gui.askPrice(player, material);
                }
                return;
            }
            switch (slot) {
                case 45 -> { if (gui.page > 0) { gui.page--; gui.rebuild(player); } }
                case 46 -> {
                    ItemStack hand = player.getInventory().getItemInMainHand();
                    if (hand == null || hand.getType().isAir()) player.sendMessage("§c请先在主手持有要设置的物品。");
                    else gui.askPrice(player, hand.getType());
                }
                case 49 -> new MarketMenu(plugin).open(player);
                case 53 -> {
                    if ((gui.page + 1) * PAGE_SIZE < gui.materials.size()) {
                        gui.page++;
                        gui.rebuild(player);
                    }
                }
            }
        }
    }

    public static final class ChatListener implements Listener {
        private final KsEco plugin;

        public ChatListener(KsEco plugin) {
            this.plugin = plugin;
        }

        @EventHandler
        public void onChat(AsyncPlayerChatEvent event) {
            Player player = event.getPlayer();
            PendingPrice pending = PENDING.remove(player.getUniqueId());
            if (pending == null) return;
            event.setCancelled(true);
            String input = event.getMessage().trim();
            Bukkit.getScheduler().runTask(plugin, () -> {
                if (input.equalsIgnoreCase("cancel")) {
                    player.sendMessage("§7已取消官方收购设置。");
                    pending.gui().rebuild(player);
                    return;
                }
                if (!pending.gui().canEdit(player)) return;
                try {
                    double price = Double.parseDouble(input);
                    if (!plugin.priceEngine().setOfficialBuyPrice(pending.material().name(), price)) {
                        throw new IllegalArgumentException();
                    }
                    player.sendMessage(price == 0 ? "§a已停止收购 " + MaterialNames.get(pending.material().name())
                            : "§a官方收购价已设为 " + plugin.vaultHook().format(price));
                    pending.gui().rebuild(player);
                } catch (RuntimeException e) {
                    PENDING.put(player.getUniqueId(), pending);
                    player.sendMessage("§c请输入 0 或不超过一万亿的非负数字，或输入 cancel 取消。");
                }
            });
        }

        @EventHandler
        public void onQuit(PlayerQuitEvent event) {
            PENDING.remove(event.getPlayer().getUniqueId());
        }
    }
}
