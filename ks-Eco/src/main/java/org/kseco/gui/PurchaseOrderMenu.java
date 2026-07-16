package org.kseco.gui;

import io.papermc.paper.event.player.AsyncChatEvent;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.jetbrains.annotations.NotNull;
import org.kseco.KsEco;
import org.kseco.MaterialNames;
import org.kseco.PurchaseOrderManager;
import org.kseco.ShulkerBoxParser;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** 玩家求购大厅：列表、详情数量选择、成交与创建求购。 */
public final class PurchaseOrderMenu implements InventoryHolder {
    private static final int SIZE = 54;
    private static final int PAGE_SIZE = 45;
    private static final Map<UUID, PendingCreation> PENDING = new ConcurrentHashMap<>();

    private final KsEco plugin;
    private Inventory inventory;
    private List<PurchaseOrderManager.Order> orders = List.of();
    private int view = 0; // 0=list, 1=detail
    private int page = 0;
    private String selectedOrderId;
    private int selectedQuantity = 1;

    public PurchaseOrderMenu(KsEco plugin) {
        this.plugin = plugin;
    }

    public void open(Player player) {
        view = 0;
        page = 0;
        selectedOrderId = null;
        selectedQuantity = 1;
        inventory = Bukkit.createInventory(this, SIZE, Component.text("§8玩家求购 — 加载中"));
        inventory.setItem(22, button(Material.CLOCK, "§e正在读取求购单..."));
        fillEmpty();
        player.openInventory(inventory);

        plugin.asyncWorkPool().execute(() -> {
            List<PurchaseOrderManager.Order> loaded = plugin.purchaseOrderManager().activeOrders();
            Bukkit.getScheduler().runTask(plugin, () -> {
                if (!player.isOnline()
                        || player.getOpenInventory().getTopInventory().getHolder() != this) return;
                orders = loaded;
                build(player);
                player.openInventory(inventory);
            });
        });
    }

    private void build(Player player) {
        if (view == 1) buildDetail(player);
        else buildList(player);
        fillEmpty();
    }

    private void buildList(Player player) {
        inventory = Bukkit.createInventory(this, SIZE,
                Component.text("§8玩家求购 第" + (page + 1) + "页"));
        int start = page * PAGE_SIZE;
        for (int slot = 0; slot < PAGE_SIZE && start + slot < orders.size(); slot++) {
            inventory.setItem(slot, displayOrder(orders.get(start + slot), player));
        }
        if (orders.isEmpty()) {
            inventory.setItem(22, button(Material.PAPER, "§7暂无进行中的求购",
                    "§7手持目标物品后可在下方创建"));
        }

        if (page > 0) inventory.setItem(45, button(Material.ARROW, "§a上一页"));
        inventory.setItem(46, button(Material.PAPER, "§a新增材质求购",
                "§7只匹配物品材质，不限制名称、附魔或 NBT",
                "§7手持目标物品后点击"));
        inventory.setItem(47, button(Material.ENCHANTED_BOOK, "§d新增精确求购",
                "§7名称、附魔、NBT 与潜影盒内容必须完全一致",
                "§7手持目标物品后点击"));
        inventory.setItem(49, button(Material.OAK_DOOR, "§c返回市场"));
        if ((page + 1) * PAGE_SIZE < orders.size()) {
            inventory.setItem(53, button(Material.ARROW, "§a下一页"));
        }
    }

    private void buildDetail(Player player) {
        PurchaseOrderManager.Order order = selectedOrder();
        inventory = Bukkit.createInventory(this, SIZE, Component.text("§8求购详情 — 数量确认"));
        if (order == null) {
            inventory.setItem(22, button(Material.BARRIER, "§c求购单已结束"));
            inventory.setItem(49, button(Material.OAK_DOOR, "§c返回列表"));
            return;
        }

        ItemStack template = order.template().clone();
        template.setAmount(1);
        inventory.setItem(13, appendLore(template,
                "§7匹配方式: " + (order.exactNbt() ? "§d精确 NBT" : "§b仅材质"),
                "§7求购方: §f" + order.buyerName()));
        inventory.setItem(22, orderInfo(order));

        boolean owner = player.getUniqueId().equals(order.buyerUuid());
        ItemStack hand = player.getInventory().getItemInMainHand();
        int available = plugin.purchaseOrderManager().availableQuantity(order, hand);
        int cap = Math.max(1, available);
        selectedQuantity = Math.max(1, Math.min(selectedQuantity, cap));

        if (!owner) {
            inventory.setItem(28, button(Material.RED_STAINED_GLASS_PANE, "§c-64"));
            inventory.setItem(29, button(Material.ORANGE_STAINED_GLASS_PANE, "§c-16"));
            inventory.setItem(30, button(Material.YELLOW_STAINED_GLASS_PANE, "§e-1"));
            inventory.setItem(31, button(Material.PAPER, "§e出售数量: §f" + selectedQuantity,
                    "§7主手可匹配: §a" + available,
                    ShulkerBoxParser.isShulkerBox(hand)
                            ? "§7已检查潜影盒内的真实内容" : "§7从主手物品扣除"));
            inventory.setItem(32, button(Material.LIME_STAINED_GLASS_PANE, "§a+1"));
            inventory.setItem(33, button(Material.GREEN_STAINED_GLASS_PANE, "§a+16"));
            inventory.setItem(34, button(Material.EMERALD_BLOCK, "§a+64"));
            inventory.setItem(35, button(Material.CHEST, "§b全部: " + available));
            double payment = selectedQuantity * order.unitPrice();
            inventory.setItem(40, button(available > 0 ? Material.GOLD_INGOT : Material.GRAY_CONCRETE,
                    available > 0 ? "§a确认出售 " + selectedQuantity + " 个" : "§c主手没有匹配物品",
                    available > 0 ? "§7成交金额: §e" + plugin.vaultHook().format(payment)
                            : "§7可直接手持物品，或手持装有匹配物品的潜影盒",
                    available > 0 ? "§a点击立即成交" : "§8盒子及不匹配内容不会被扣除"));
        } else {
            inventory.setItem(31, button(Material.WRITABLE_BOOK, "§e这是你发布的求购单",
                    "§7有限求购剩余预存款会在撤销后退回"));
            inventory.setItem(40, button(Material.BARRIER, "§c撤销求购单",
                    "§7Shift+右键确认撤销"));
        }
        inventory.setItem(49, button(Material.OAK_DOOR, "§c返回求购列表"));
    }

    private ItemStack displayOrder(PurchaseOrderManager.Order order, Player viewer) {
        ItemStack item = order.template().clone();
        item.setAmount(1);
        ItemMeta meta = item.getItemMeta();
        if (meta == null) return item;
        meta.displayName(Component.text("§e求购 · " + MaterialNames.get(order.material().name())));
        List<Component> lore = meta.hasLore() ? new ArrayList<>(meta.lore()) : new ArrayList<>();
        if (!lore.isEmpty()) lore.add(Component.empty());
        lore.add(Component.text("求购方: " + order.buyerName(), NamedTextColor.GRAY));
        lore.add(Component.text("单价: " + plugin.vaultHook().format(order.unitPrice()), NamedTextColor.GOLD));
        lore.add(Component.text(order.remaining() < 0 ? "剩余: 不限量" : "剩余: " + order.remaining(), NamedTextColor.GREEN));
        lore.add(Component.text(order.exactNbt() ? "匹配: 精确 NBT" : "匹配: 仅材质", NamedTextColor.AQUA));
        lore.add(Component.empty());
        if (viewer.getUniqueId().equals(order.buyerUuid())) {
            lore.add(Component.text("左键查看 · Shift+右键撤销", NamedTextColor.YELLOW));
        } else {
            lore.add(Component.text("点击进入详情并选择出售数量", NamedTextColor.YELLOW));
            lore.add(Component.text("支持识别主手潜影盒内的匹配物品", NamedTextColor.DARK_GRAY));
        }
        meta.lore(lore);
        item.setItemMeta(meta);
        return item;
    }

    private ItemStack orderInfo(PurchaseOrderManager.Order order) {
        return button(Material.BOOK, "§6求购结算",
                "§7物品: §f" + MaterialNames.get(order.material().name()),
                "§7单价: §e" + plugin.vaultHook().format(order.unitPrice()),
                order.remaining() < 0 ? "§7数量: §a不限量（成交时实时扣款）"
                        : "§7剩余: §a" + order.remaining() + "（已预存款）",
                "§7匹配: " + (order.exactNbt() ? "§d精确 NBT" : "§b仅材质"));
    }

    private PurchaseOrderManager.Order selectedOrder() {
        if (selectedOrderId == null) return null;
        for (PurchaseOrderManager.Order order : orders) {
            if (selectedOrderId.equals(order.id())) return order;
        }
        return null;
    }

    private ItemStack appendLore(ItemStack item, String... lines) {
        ItemMeta meta = item.getItemMeta();
        if (meta == null) return item;
        List<Component> lore = meta.hasLore() ? new ArrayList<>(meta.lore()) : new ArrayList<>();
        lore.add(Component.empty());
        for (String line : lines) lore.add(Component.text(line, NamedTextColor.GRAY));
        meta.lore(lore);
        item.setItemMeta(meta);
        return item;
    }

    private ItemStack button(Material material, String name, String... lore) {
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

    private void fillEmpty() {
        ItemStack pane = button(Material.BLACK_STAINED_GLASS_PANE, " ");
        for (int slot = 0; slot < SIZE; slot++) {
            if (inventory.getItem(slot) == null) inventory.setItem(slot, pane.clone());
        }
    }

    private void rebuild(Player player) {
        build(player);
        player.openInventory(inventory);
    }

    @Override
    public @NotNull Inventory getInventory() {
        return inventory;
    }

    public static final class Listener implements org.bukkit.event.Listener {
        private final KsEco plugin;

        public Listener(KsEco plugin) {
            this.plugin = plugin;
        }

        @EventHandler
        public void onClick(InventoryClickEvent event) {
            if (!(event.getView().getTopInventory().getHolder() instanceof PurchaseOrderMenu menu)) return;
            event.setCancelled(true);
            if (!(event.getWhoClicked() instanceof Player player)) return;
            int slot = event.getRawSlot();
            if (slot < 0 || slot >= menu.getInventory().getSize()) return;

            if (menu.view == 0) {
                if (slot < PAGE_SIZE) {
                    int index = menu.page * PAGE_SIZE + slot;
                    if (index < menu.orders.size()) {
                        PurchaseOrderManager.Order order = menu.orders.get(index);
                        if (event.isShiftClick() && event.isRightClick()
                                && player.getUniqueId().equals(order.buyerUuid())) {
                            cancel(player, menu, order);
                        } else {
                            menu.selectedOrderId = order.id();
                            menu.selectedQuantity = 1;
                            menu.view = 1;
                            menu.rebuild(player);
                        }
                    }
                    return;
                }
                switch (slot) {
                    case 45 -> {
                        if (menu.page > 0) {
                            menu.page--;
                            menu.rebuild(player);
                        }
                    }
                    case 46 -> beginCreate(player, false);
                    case 47 -> beginCreate(player, true);
                    case 49 -> {
                        player.closeInventory();
                        new MarketMenu(plugin).open(player);
                    }
                    case 53 -> {
                        if ((menu.page + 1) * PAGE_SIZE < menu.orders.size()) {
                            menu.page++;
                            menu.rebuild(player);
                        }
                    }
                }
                return;
            }

            PurchaseOrderManager.Order order = menu.selectedOrder();
            if (order == null) {
                menu.open(player);
                return;
            }
            boolean owner = player.getUniqueId().equals(order.buyerUuid());
            if (owner) {
                if (slot == 40 && event.isShiftClick() && event.isRightClick()) cancel(player, menu, order);
            } else {
                int available = plugin.purchaseOrderManager().availableQuantity(order,
                        player.getInventory().getItemInMainHand());
                switch (slot) {
                    case 28 -> menu.selectedQuantity = Math.max(1, menu.selectedQuantity - 64);
                    case 29 -> menu.selectedQuantity = Math.max(1, menu.selectedQuantity - 16);
                    case 30 -> menu.selectedQuantity = Math.max(1, menu.selectedQuantity - 1);
                    case 32 -> menu.selectedQuantity = Math.min(Math.max(1, available), menu.selectedQuantity + 1);
                    case 33 -> menu.selectedQuantity = Math.min(Math.max(1, available), menu.selectedQuantity + 16);
                    case 34 -> menu.selectedQuantity = Math.min(Math.max(1, available), menu.selectedQuantity + 64);
                    case 35 -> menu.selectedQuantity = Math.max(1, available);
                    case 40 -> fulfill(player, menu, order);
                    default -> { }
                }
                if (slot >= 28 && slot <= 35 && slot != 31) menu.rebuild(player);
            }
            if (slot == 49) {
                menu.view = 0;
                menu.selectedOrderId = null;
                menu.selectedQuantity = 1;
                menu.rebuild(player);
            }
        }

        private void beginCreate(Player player, boolean exactNbt) {
            ItemStack hand = player.getInventory().getItemInMainHand();
            if (hand == null || hand.getType().isAir()) {
                player.sendMessage("§c请先在主手持有想要求购的物品。");
                return;
            }
            ItemStack template = hand.clone();
            template.setAmount(1);
            player.closeInventory();
            PENDING.put(player.getUniqueId(), new PendingCreation(template, exactNbt));
            player.sendMessage("§a请输入 §f单价 数量§a，例如 §f50 128§a；数量输入 §f0§a 或 §f不限§a 表示不限量，输入 cancel 取消。");
        }

        private void fulfill(Player player, PurchaseOrderMenu menu, PurchaseOrderManager.Order order) {
            PurchaseOrderManager.FulfillmentResult result = plugin.purchaseOrderManager().fulfill(
                    player, order, player.getInventory().getItemInMainHand(), menu.selectedQuantity);
            player.sendMessage((result.success() ? "§a" : "§c") + result.message());
            player.playSound(player.getLocation(), result.success()
                    ? Sound.ENTITY_EXPERIENCE_ORB_PICKUP : Sound.BLOCK_NOTE_BLOCK_BASS, 0.6f, 1.2f);
            if (result.success()) menu.open(player);
            else menu.rebuild(player);
        }

        private void cancel(Player player, PurchaseOrderMenu menu, PurchaseOrderManager.Order order) {
            if (plugin.purchaseOrderManager().cancel(player, order)) {
                player.sendMessage("§a求购单已撤销，剩余预存款已退回。");
                menu.open(player);
            } else {
                player.sendMessage("§c撤销失败，求购单可能已结束。");
            }
        }
    }

    public static final class ChatListener implements org.bukkit.event.Listener {
        private final KsEco plugin;

        public ChatListener(KsEco plugin) {
            this.plugin = plugin;
        }

        @EventHandler
        public void onChat(AsyncChatEvent event) {
            Player player = event.getPlayer();
            PendingCreation pending = PENDING.remove(player.getUniqueId());
            if (pending == null) return;
            event.setCancelled(true);
            String input = PlainTextComponentSerializer.plainText().serialize(event.message()).trim();
            Bukkit.getScheduler().runTask(plugin, () -> handleInput(player, pending, input));
        }

        @EventHandler
        public void onQuit(PlayerQuitEvent event) {
            PENDING.remove(event.getPlayer().getUniqueId());
        }

        private void handleInput(Player player, PendingCreation pending, String input) {
            if (input.equalsIgnoreCase("cancel")) {
                player.sendMessage("§7已取消创建求购单。");
                new PurchaseOrderMenu(plugin).open(player);
                return;
            }
            String[] parts = input.split("\\s+");
            try {
                if (parts.length < 2) throw new IllegalArgumentException();
                double price = Double.parseDouble(parts[0]);
                int quantity = parts[1].equalsIgnoreCase("不限") || parts[1].equalsIgnoreCase("unlimited")
                        ? -1 : Integer.parseInt(parts[1]);
                if (quantity == 0) quantity = -1;
                if (!Double.isFinite(price) || price <= 0 || quantity < -1) throw new IllegalArgumentException();
                boolean created = plugin.purchaseOrderManager().create(
                        player, pending.template(), pending.exactNbt(), price, quantity);
                player.sendMessage(created ? "§a求购单已创建。" : "§c创建失败，请检查余额、单价与数量。");
                new PurchaseOrderMenu(plugin).open(player);
            } catch (RuntimeException e) {
                PENDING.put(player.getUniqueId(), pending);
                player.sendMessage("§c格式无效。请输入“单价 数量”，例如 50 128；或输入 cancel 取消。");
            }
        }
    }

    private record PendingCreation(ItemStack template, boolean exactNbt) { }
}
