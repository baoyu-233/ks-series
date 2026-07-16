package org.kseries.rpggui;

import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.SkullMeta;
import org.jetbrains.annotations.NotNull;
import org.kseries.rpg.api.RpgContentApi;
import org.kseries.rpg.api.RpgProgressionApi;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;

final class RpgMenu implements InventoryHolder {
    private enum View { MAIN, PROOFS, GATES, ADMIN_ITEMS }

    private final KsRpgGui plugin;
    private final UUID ownerId;
    private final RpgProgressionApi api;
    private final RpgContentApi contentApi;
    private final View view;
    private final int page;
    private final List<RpgContentApi.ContentItem> contentItems;
    private Inventory inventory;

    private RpgMenu(KsRpgGui plugin, Player player, RpgProgressionApi api, RpgContentApi contentApi, View view, int page) {
        this.plugin = plugin;
        this.ownerId = player.getUniqueId();
        this.api = api;
        this.contentApi = contentApi;
        this.view = view;
        this.page = Math.max(0, page);
        this.contentItems = view == View.ADMIN_ITEMS && contentApi != null
                ? contentApi.items().stream().sorted(Comparator.comparing(RpgContentApi.ContentItem::category)
                .thenComparing(RpgContentApi.ContentItem::type).thenComparing(RpgContentApi.ContentItem::id)).toList()
                : List.of();
    }

    static void openMain(KsRpgGui plugin, Player player, RpgProgressionApi api, RpgContentApi contentApi) {
        new RpgMenu(plugin, player, api, contentApi, View.MAIN, 0).open(player);
    }

    private void open(Player player) {
        build(player);
        player.openInventory(inventory);
    }

    private void build(Player player) {
        MenuLayout layout = plugin.layout();
        inventory = Bukkit.createInventory(this, layout.size(), Component.text(layout.title()));
        fill(layout);
        if (view == View.MAIN) buildMain(player, layout);
        else buildList(player, layout);
    }

    private void buildMain(Player player, MenuLayout layout) {
        inventory.setItem(layout.slot("profile"), profile(player));
        int totalProofs = api.proofDefinitions().size();
        int ownedProofs = api.proofs(player).size();
        set(layout, "proofs", Material.NETHER_STAR, "&b战斗凭证", List.of("&7已获得：&f" + ownedProofs + "&7/&f" + totalProofs));
        set(layout, "materials", Material.HOPPER, "&e材料兑换", List.of());
        set(layout, "accessories", Material.TOTEM_OF_UNDYING, "&a饰品栏", List.of());

        int readyGates = (int) api.gateDefinitions().stream().filter(gate -> api.checkGate(player, gate.id()).satisfied()).count();
        set(layout, "gates", Material.END_PORTAL_FRAME, "&d突破门槛",
                List.of("&7已满足：&f" + readyGates + "&7/&f" + api.gateDefinitions().size()));
        set(layout, "refresh", Material.SUNFLOWER, "&e刷新", List.of());
        set(layout, "close", Material.BARRIER, "&c关闭", List.of());
        if (player.hasPermission("ksrpggui.admin")) {
            set(layout, "admin-items", Material.CHEST, "&6RPG 物品库", List.of("&7管理员调试领取入口。"));
            set(layout, "admin-reload", Material.REPEATER, "&6重载面板配置", List.of());
        }
    }

    private void buildList(Player player, MenuLayout layout) {
        List<Integer> contentSlots = contentSlots(layout);
        int values = listSize();
        int from = Math.min(page * contentSlots.size(), values);
        int to = Math.min(from + contentSlots.size(), values);
        for (int index = from; index < to; index++) inventory.setItem(contentSlots.get(index - from), listItem(player, layout, index));
        if (from == to) inventory.setItem(layout.size() / 2, icon(new MenuLayout.ItemSpec(Material.PAPER, "§7暂无可展示的条目", List.of())));
        set(layout, "back", Material.OAK_DOOR, "&c返回主面板", List.of());
        if (page > 0) set(layout, "previous", Material.ARROW, "&a上一页", List.of());
        if (to < values) set(layout, "next", Material.ARROW, "&a下一页", List.of());
        set(layout, "list-refresh", Material.SUNFLOWER, "&e刷新", List.of());
        set(layout, "list-close", Material.BARRIER, "&c关闭", List.of());
    }

    private int listSize() {
        return switch (view) {
            case PROOFS -> api.proofDefinitions().size();
            case GATES -> api.gateDefinitions().size();
            case ADMIN_ITEMS -> contentItems.size();
            case MAIN -> 0;
        };
    }

    private ItemStack listItem(Player player, MenuLayout layout, int index) {
        return switch (view) {
            case PROOFS -> proofItem(player, layout, api.proofDefinitions().stream()
                    .sorted(Comparator.comparing(RpgProgressionApi.ProofDefinition::display)).toList().get(index));
            case GATES -> gateItem(player, layout, api.gateDefinitions().stream()
                    .sorted(Comparator.comparing(RpgProgressionApi.GateDefinition::display)).toList().get(index));
            case ADMIN_ITEMS -> contentItem(contentItems.get(index));
            case MAIN -> new ItemStack(Material.AIR);
        };
    }

    private ItemStack proofItem(Player player, MenuLayout layout, RpgProgressionApi.ProofDefinition proof) {
        boolean owned = api.hasProof(player, proof.id());
        String itemId = owned ? "proof-owned" : "proof-missing";
        return icon(layout.item(itemId, owned ? Material.LIME_DYE : Material.GRAY_DYE,
                owned ? "&a" + proof.display() : "&7" + proof.display())
                .replace(Map.of("{display}", proof.display(), "{id}", proof.id())));
    }

    private ItemStack gateItem(Player player, MenuLayout layout, RpgProgressionApi.GateDefinition gate) {
        RpgProgressionApi.GateCheck check = api.checkGate(player, gate.id());
        boolean ready = check.satisfied();
        String itemId = ready ? "gate-ready" : "gate-locked";
        List<String> details = new ArrayList<>();
        for (String proofId : gate.requiredProofs()) {
            String display = api.proof(proofId).map(RpgProgressionApi.ProofDefinition::display).orElse(proofId);
            details.add((api.hasProof(player, proofId) ? "§a✓ " : "§c✗ ") + display);
        }
        MenuLayout.ItemSpec base = layout.item(itemId, ready ? Material.LIME_DYE : Material.RED_DYE,
                (ready ? "&a" : "&c") + gate.display()).replace(Map.of("{display}", gate.display(), "{id}", gate.id()));
        return icon(new MenuLayout.ItemSpec(base.material(), base.name(), join(base.lore(), details)));
    }

    private ItemStack contentItem(RpgContentApi.ContentItem definition) {
        ItemStack item = contentApi.preview(definition.key()).orElseGet(() -> new ItemStack(Material.BARRIER));
        ItemMeta meta = item.getItemMeta();
        if (meta == null) return item;
        List<Component> lore = new ArrayList<>();
        if (meta.lore() != null) lore.addAll(meta.lore());
        lore.add(Component.text("§8分类：" + definition.category()));
        lore.add(Component.text("§8类型：" + definition.type()));
        lore.add(Component.text("§e点击领取 1 个"));
        meta.lore(lore);
        item.setItemMeta(meta);
        return item;
    }

    private void set(MenuLayout layout, String itemId, Material fallback, String fallbackName, List<String> appendedLore) {
        int slot = layout.slot(itemId);
        if (slot < 0 || slot >= inventory.getSize()) return;
        MenuLayout.ItemSpec base = layout.item(itemId, fallback, fallbackName);
        inventory.setItem(slot, icon(new MenuLayout.ItemSpec(base.material(), base.name(), join(base.lore(), appendedLore))));
    }

    private ItemStack profile(Player player) {
        ItemStack head = new ItemStack(Material.PLAYER_HEAD);
        ItemMeta meta = head.getItemMeta();
        if (meta instanceof SkullMeta skull) {
            skull.setOwningPlayer(player);
            skull.displayName(Component.text("§b§l" + player.getName()));
            skull.lore(List.of(Component.text("§7生存进度与战斗资格")));
            head.setItemMeta(skull);
        }
        return head;
    }

    private void fill(MenuLayout layout) {
        ItemStack fill = pane(layout.fillMaterial());
        for (int slot = 0; slot < inventory.getSize(); slot++) inventory.setItem(slot, fill.clone());
        ItemStack frame = pane(layout.frameMaterial());
        for (int column = 0; column < 9; column++) {
            inventory.setItem(column, frame.clone());
            inventory.setItem(inventory.getSize() - 9 + column, frame.clone());
        }
    }

    private ItemStack pane(Material material) {
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.displayName(Component.text(" "));
            item.setItemMeta(meta);
        }
        return item;
    }

    private ItemStack icon(MenuLayout.ItemSpec spec) {
        ItemStack item = new ItemStack(spec.material());
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.displayName(Component.text(spec.name()));
            meta.lore(spec.lore().stream().map(Component::text).toList());
            item.setItemMeta(meta);
        }
        return item;
    }

    private List<Integer> contentSlots(MenuLayout layout) {
        List<Integer> slots = new ArrayList<>();
        for (int slot = 9; slot < layout.size() - 9; slot++) slots.add(slot);
        return slots;
    }

    private List<String> join(List<String> first, List<String> second) {
        List<String> result = new ArrayList<>(first);
        result.addAll(second);
        return result.stream().map(MenuLayout::color).toList();
    }

    void click(Player player, int slot) {
        if (!player.getUniqueId().equals(ownerId)) return;
        MenuLayout layout = plugin.layout();
        if (view == View.MAIN) {
            if (slot == layout.slot("proofs")) openView(player, View.PROOFS, 0);
            else if (slot == layout.slot("gates")) openView(player, View.GATES, 0);
            else if (slot == layout.slot("materials")) {
                player.closeInventory();
                player.performCommand("ksrpg 目录");
            } else if (slot == layout.slot("accessories")) {
                player.closeInventory();
                player.performCommand("rpggear");
            } else if (slot == layout.slot("refresh")) openMain(plugin, player, api, contentApi);
            else if (slot == layout.slot("close")) player.closeInventory();
            else if (slot == layout.slot("admin-items") && player.hasPermission("ksrpggui.admin") && contentApi != null) {
                openView(player, View.ADMIN_ITEMS, 0);
            } else if (slot == layout.slot("admin-reload") && player.hasPermission("ksrpggui.admin")) {
                plugin.reloadMenuAsync(player.getUniqueId(), true);
            }
            return;
        }
        if (view == View.ADMIN_ITEMS && player.hasPermission("ksrpggui.admin") && contentApi != null) {
            int position = contentSlots(layout).indexOf(slot);
            int index = page * contentSlots(layout).size() + position;
            if (position >= 0 && index < contentItems.size()) {
                switch (contentApi.grant(player, contentItems.get(index).key())) {
                    case GRANTED -> player.sendMessage("§a已领取物品。");
                    case DROPPED_AT_PLAYER -> player.sendMessage("§e背包已满，物品已掉落在脚边。");
                    case UNKNOWN_ITEM -> player.sendMessage("§c该物品已从当前配置移除，请刷新物品库。");
                    case ITEM_UNAVAILABLE -> player.sendMessage("§c物品暂不可生成，请检查 MMOItems 配置。");
                }
                return;
            }
        }
        if (slot == layout.slot("back")) openMain(plugin, player, api, contentApi);
        else if (slot == layout.slot("previous") && page > 0) openView(player, view, page - 1);
        else if (slot == layout.slot("next") && (page + 1) * contentSlots(layout).size() < listSize()) openView(player, view, page + 1);
        else if (slot == layout.slot("list-refresh")) openView(player, view, page);
        else if (slot == layout.slot("list-close")) player.closeInventory();
    }

    private void openView(Player player, View target, int targetPage) {
        new RpgMenu(plugin, player, api, contentApi, target, targetPage).open(player);
    }

    @Override
    public @NotNull Inventory getInventory() {
        return inventory;
    }
}
