package org.itemedit;

import io.papermc.paper.registry.RegistryAccess;
import io.papermc.paper.registry.RegistryKey;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Material;
import org.bukkit.Registry;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 玩家武器精炼 GUI（27 槽）。
 *
 * 布局：
 * Row 0: [券][名称][管理Lore][预览][ ][ ][ ][ ][消耗]
 * Row 1: [E1][E2][E3][E4][E5][E6][E7][E8][E9]
 * Row 2: [ ][不可破坏][发光][ ][确认][ ][ ][ ][取消]
 *
 * 点击「管理 Lore」打开 RefineLoreMenu 子菜单（逐行编辑/删除/移动/新增）。
 * 附魔添加时自动移除互斥附魔（如锋利与亡灵杀手不能共存）。
 */
public final class RefineMenu implements InventoryHolder {

    private static final int MAX_ENCHANT_SLOTS = 9;

    // Slot constants
    private static final int SLOT_VOUCHER    = 0;
    private static final int SLOT_NAME       = 1;
    private static final int SLOT_LORE       = 2;
    private static final int SLOT_PREVIEW    = 4;
    private static final int SLOT_LOAD_TPL   = 6;
    private static final int SLOT_EXPORT_TPL = 7;
    private static final int SLOT_COST       = 8;
    private static final int ENCHANT_START   = 9;
    private static final int SLOT_UNBREAKABLE = 19;
    private static final int SLOT_GLOWING    = 20;
    private static final int SLOT_CONFIRM    = 22;
    private static final int SLOT_CANCEL     = 26;

    private final ItemEditor plugin;
    private final RefineSession session;
    private final ConfigurationSection config;
    private Inventory inventory;
    private final List<Enchantment> applicableEnchants;

    public RefineMenu(ItemEditor plugin, RefineSession session, ConfigurationSection config) {
        this.plugin = plugin;
        this.session = session;
        this.config = config;
        this.applicableEnchants = computeApplicableEnchants();
    }

    /** 列出物品可用的原版附魔（按类型过滤 + 去重排序）。 */
    private List<Enchantment> computeApplicableEnchants() {
        Set<String> seen = new HashSet<>();
        List<Enchantment> list = new ArrayList<>();
        ItemStack item = session.snapshot();

        try {
            Registry<Enchantment> reg = RegistryAccess.registryAccess().getRegistry(RegistryKey.ENCHANTMENT);
            for (Enchantment e : reg) {
                if (seen.add(e.getKey().toString()) && e.canEnchantItem(item)) {
                    list.add(e);
                }
            }
        } catch (Exception ignored) {
            for (Enchantment e : Registry.ENCHANTMENT) {
                if (seen.add(e.getKey().toString()) && e.canEnchantItem(item)) {
                    list.add(e);
                }
            }
        }

        list.sort(Comparator.comparing(e -> e.getKey().toString()));
        return list;
    }

    public void open() {
        Player p = session.player();
        if (p == null) return;

        inventory = plugin.getServer().createInventory(this, 27,
                Component.text("武器精炼", NamedTextColor.DARK_AQUA));

        build();
        p.openInventory(inventory);
    }

    private void build() {
        ItemStack item = session.snapshot();
        int needed = config.getInt("voucher.amount", 1);

        // -- Voucher info --
        boolean hasVoucher = VoucherUtil.hasVoucher(session.player(), config);
        inventory.setItem(SLOT_VOUCHER, button(hasVoucher ? Material.EMERALD : Material.REDSTONE,
                hasVoucher ? Component.text("兑换券: 已就绪", NamedTextColor.GREEN)
                           : Component.text("兑换券: 不足!", NamedTextColor.RED),
                Component.text("需要: " + needed + " 个", NamedTextColor.GRAY),
                Component.text(VoucherUtil.voucherDescription(config), NamedTextColor.DARK_GRAY)));

        // -- Name edit --
        inventory.setItem(SLOT_NAME, button(Material.NAME_TAG,
                Component.text("修改名称", NamedTextColor.YELLOW),
                Component.text("点击在聊天框输入新名称", NamedTextColor.GRAY),
                Component.text("支持 & 颜色码与 MiniMessage", NamedTextColor.DARK_GRAY),
                Component.text("留空确认 = 清除名称", NamedTextColor.DARK_GRAY)));

        // -- Lore management --
        ItemMeta im = item.getItemMeta();
        boolean hasLore = im != null && im.hasLore();
        int loreCount = hasLore ? im.lore().size() : 0;
        inventory.setItem(SLOT_LORE, button(Material.WRITABLE_BOOK,
                Component.text("管理 Lore", NamedTextColor.YELLOW),
                hasLore
                    ? Component.text("当前共 " + loreCount + " 行 — 点击进入编辑", NamedTextColor.GRAY)
                    : Component.text("当前无描述 — 点击添加", NamedTextColor.DARK_GRAY),
                Component.text("可逐行编辑、删除、移动、新增", NamedTextColor.GRAY)));

        // -- Preview --
        inventory.setItem(SLOT_PREVIEW, item.clone());

        // -- Template: Load --
        inventory.setItem(SLOT_LOAD_TPL, button(Material.BOOKSHELF,
                Component.text("📋 加载模板", NamedTextColor.AQUA),
                Component.text("输入模板码加载已保存的配置", NamedTextColor.GRAY),
                Component.text("玩家模板可用，管理员模板需权限", NamedTextColor.DARK_GRAY)));

        // -- Template: Export --
        inventory.setItem(SLOT_EXPORT_TPL, button(Material.WRITABLE_BOOK,
                Component.text("📤 导出模板", NamedTextColor.GOLD),
                Component.text("将当前配置导出为模板码", NamedTextColor.GRAY),
                Component.text("可复制分享给其他玩家", NamedTextColor.DARK_GRAY)));

        // -- Cost --
        inventory.setItem(SLOT_COST, button(Material.GOLD_NUGGET,
                Component.text("消耗: " + needed + " 张兑换券", NamedTextColor.GOLD),
                Component.text("确认后扣除", NamedTextColor.GRAY)));

        // -- Enchantment buttons --
        int maxLevel = config.getInt("max-enchant-level", 10);
        for (int i = 0; i < Math.min(applicableEnchants.size(), MAX_ENCHANT_SLOTS); i++) {
            Enchantment ench = applicableEnchants.get(i);
            int level = item.getEnchantmentLevel(ench);
            boolean has = level > 0;

            String name = ench.getKey().getKey().replace('_', ' ');
            if (!name.isEmpty()) name = name.substring(0, 1).toUpperCase() + name.substring(1);

            List<Component> btnLore = new ArrayList<>();
            btnLore.add(Component.text("当前等级: ", NamedTextColor.GRAY)
                    .append(Component.text(level, has ? NamedTextColor.AQUA : NamedTextColor.DARK_GRAY)));
            btnLore.add(Component.text("最大等级: " + Math.min(ench.getMaxLevel(), maxLevel), NamedTextColor.DARK_GRAY));
            btnLore.add(Component.text("左键 +1  右键 -1", NamedTextColor.YELLOW));
            btnLore.add(Component.text(ench.getKey().toString(), NamedTextColor.DARK_GRAY));

            inventory.setItem(ENCHANT_START + i, button(
                    has ? Material.ENCHANTED_BOOK : Material.BOOK,
                    Component.text(name, has ? NamedTextColor.GREEN : NamedTextColor.WHITE),
                    btnLore));
        }

        // -- Unbreakable toggle --
        boolean ub = ItemEdits.isUnbreakable(item);
        ItemStack ubButton = button(ub ? Material.BEDROCK : Material.STONE,
                ub ? Component.text("不可破坏: ☑", NamedTextColor.GREEN)
                   : Component.text("不可破坏: ☐", NamedTextColor.GRAY),
                Component.text("点击切换", NamedTextColor.DARK_GRAY));
        if (ub) {
            ItemMeta ubm = ubButton.getItemMeta();
            ubm.setEnchantmentGlintOverride(true);
            ubButton.setItemMeta(ubm);
        }
        inventory.setItem(SLOT_UNBREAKABLE, ubButton);

        // -- Glowing toggle --
        boolean glow = ItemEdits.isGlowing(item);
        ItemStack glowButton = button(glow ? Material.GLOWSTONE_DUST : Material.GUNPOWDER,
                glow ? Component.text("发光: ☑", NamedTextColor.GREEN)
                     : Component.text("发光: ☐", NamedTextColor.GRAY),
                Component.text("点击切换", NamedTextColor.DARK_GRAY));
        if (glow) {
            ItemMeta gm = glowButton.getItemMeta();
            gm.setEnchantmentGlintOverride(true);
            glowButton.setItemMeta(gm);
        }
        inventory.setItem(SLOT_GLOWING, glowButton);

        // -- Confirm --
        inventory.setItem(SLOT_CONFIRM, button(Material.LIME_DYE,
                Component.text("确认精炼", NamedTextColor.GREEN),
                Component.text("消耗兑换券并应用所有修改", NamedTextColor.GRAY)));

        // -- Cancel --
        inventory.setItem(SLOT_CANCEL, button(Material.BARRIER,
                Component.text("取消", NamedTextColor.RED),
                Component.text("放弃修改并归还物品", NamedTextColor.GRAY)));

        fillEmpty();
    }

    public void handle(InventoryClickEvent event) {
        int slot = event.getSlot();

        // 附魔按钮（含原版冲突检测）
        if (slot >= ENCHANT_START && slot < ENCHANT_START + MAX_ENCHANT_SLOTS) {
            int idx = slot - ENCHANT_START;
            if (idx >= applicableEnchants.size()) return;
            Enchantment ench = applicableEnchants.get(idx);
            ItemStack item = session.snapshot();
            int level = item.getEnchantmentLevel(ench);
            int maxLevel = Math.min(ench.getMaxLevel(), config.getInt("max-enchant-level", 10));

            ClickType c = event.getClick();
            if (c.isLeftClick()) level = Math.min(level + 1, maxLevel);
            else if (c.isRightClick()) level = Math.max(level - 1, 0);

            // ★ 原版附魔冲突处理：添加新附魔时移除互斥附魔
            if (level > 0) {
                ItemMeta snapshotMeta = item.getItemMeta();
                if (snapshotMeta != null) {
                for (Enchantment existing : new ArrayList<>(snapshotMeta.getEnchants().keySet())) {
                    if (!existing.equals(ench) && (ench.conflictsWith(existing) || existing.conflictsWith(ench))) {
                        ItemEdits.setEnchant(item, existing, 0);
                        // 提示玩家
                        Player p = session.player();
                        if (p != null) {
                            String removedName = existing.getKey().getKey().replace('_', ' ');
                            String addedName = ench.getKey().getKey().replace('_', ' ');
                            p.sendMessage(TextUtil.parse("&e" + removedName + " 与 " + addedName + " 冲突，已被自动移除。"));
                        }
                    }
                }
                }
            }

            ItemEdits.setEnchant(item, ench, level);
            refresh();
            return;
        }

        switch (slot) {
            case SLOT_NAME -> promptName();
            case SLOT_LORE ->
                new RefineLoreMenu(plugin, session, this::open).open();
            case SLOT_LOAD_TPL -> promptLoadTemplate();
            case SLOT_EXPORT_TPL -> promptExportTemplate();
            case SLOT_UNBREAKABLE -> {
                ItemStack item = session.snapshot();
                ItemEdits.setUnbreakable(item, !ItemEdits.isUnbreakable(item));
                refresh();
            }
            case SLOT_GLOWING -> {
                ItemStack item = session.snapshot();
                ItemEdits.setGlowing(item, !ItemEdits.isGlowing(item));
                refresh();
            }
            case SLOT_CONFIRM -> handleConfirm();
            case SLOT_CANCEL -> doCancel();
            default -> { /* ignore filler / info / preview */ }
        }
    }

    // ---- 聊天输入：名称 / Lore ----

    private String currentName() {
        ItemMeta meta = session.snapshot().getItemMeta();
        if (meta == null || !meta.hasDisplayName()) return "";
        return TextUtil.plain(meta.displayName());
    }

    private void promptName() {
        Player p = session.player();
        if (p == null) return;
        String oldName = currentName();
        session.setPendingChatInput(true);
        ChatInput.prompt(p, "修改武器名称", oldName, text -> {
            session.setPendingChatInput(false);
            String trimmed = text == null ? "" : text.trim();
            if (trimmed.isEmpty()) {
                ItemEdits.clearName(session.snapshot());
            } else if (!trimmed.equals(oldName)) {
                ItemEdits.setName(session.snapshot(), trimmed);
            }
            open();
        }, () -> {
            session.setPendingChatInput(false);
            open();
        });
    }

    // ---- 模板加载 / 导出 ----

    private void promptLoadTemplate() {
        Player p = session.player();
        if (p == null) return;
        session.setPendingChatInput(true);
        ChatInput.prompt(p, "加载模板", "输入模板码（8位字母数字）", text -> {
            session.setPendingChatInput(false);
            String code = text.trim();
            p.closeInventory();

            TemplateManager.Template template = plugin.webServer().templateManager().load(code);
            if (template == null || template.item == null) {
                p.sendMessage(TextUtil.parse("&c模板不存在: &f" + code));
                open();
                return;
            }

            // ★ 玩家不可加载管理员专属模板
            if (template.adminTemplate && !p.hasPermission("itemedit.admin")) {
                p.sendMessage(TextUtil.parse("&c该模板为管理员专属模板，玩家无法加载！"));
                open();
                return;
            }

            // 在快照上应用模板数据
            boolean isAdmin = p.hasPermission("itemedit.admin");
            ItemData itemData = isAdmin ? template.item : sanitizePlayerTemplate(template.item);
            ItemStack newItem = ItemSerializer.fromItemData(itemData);
            ItemSerializer.applyExtendedData(newItem, itemData);
            session.setSnapshot(newItem);

            p.sendMessage(TextUtil.parse("&a✅ 模板已加载: &e" + code
                    + " &7(作者: " + (template.createdBy != null ? template.createdBy.name : "未知") + ")"));
            open();
        }, () -> {
            session.setPendingChatInput(false);
            open();
        });
    }

    private ItemData sanitizePlayerTemplate(ItemData source) {
        ItemData safe = new ItemData();
        if (source == null) return safe;

        safe.material = source.material;
        safe.name = source.name;
        safe.lore = source.lore != null ? new ArrayList<>(source.lore) : new ArrayList<>();
        safe.enchantments = source.enchantments != null
                ? new LinkedHashMap<>(source.enchantments)
                : new LinkedHashMap<>();
        safe.unbreakable = source.unbreakable;
        safe.glowing = source.glowing;

        safe.capEnchantmentLevels();
        capToRefineMax(safe);

        safe.feEnchantments = new LinkedHashMap<>();
        safe.attributeModifiers = new ArrayList<>();
        safe.iaModel = null;
        return safe;
    }

    private void capToRefineMax(ItemData data) {
        if (data.enchantments == null || data.enchantments.isEmpty()) return;
        int maxLevel = Math.max(1, config.getInt("max-enchant-level", 10));
        Map<String, Integer> capped = new LinkedHashMap<>();
        for (Map.Entry<String, Integer> entry : data.enchantments.entrySet()) {
            int level = Math.min(entry.getValue(), maxLevel);
            if (level > 0) capped.put(entry.getKey(), level);
        }
        data.enchantments = capped;
    }

    private void promptExportTemplate() {
        Player p = session.player();
        if (p == null) return;
        ItemStack it = session.snapshot();
        if (it == null || it.getType().isAir()) {
            p.sendMessage(TextUtil.parse("&c没有可导出的物品。"));
            return;
        }

        ItemData data = ItemSerializer.toItemData(it, p);
        TemplateManager.Template template = plugin.webServer().templateManager()
                .save(data, p.getUniqueId(), p.getName(), false); // 玩家模板

        p.closeInventory();
        p.sendMessage(TextUtil.parse("\n&6▎ &e模板已导出！\n"
                + "&7  模板码: &e&l" + template.code + "\n"
                + "&7  在 &f/refine &7里点击加载模板，输入 &e" + template.code + " &7即可加载。\n"
                + "&7  也可以分享给其他玩家使用。\n"));
        open();
    }

    // ---- 确认 / 取消 ----

    private void doCancel() {
        session.cancel();
        Player p = session.player();
        if (p != null) {
            p.sendMessage(TextUtil.parse("&7已取消精炼，物品已归还。"));
            p.closeInventory();
        }
    }

    private void handleConfirm() {
        Player p = session.player();
        if (p == null) return;

        if (!VoucherUtil.hasVoucher(p, config)) {
            p.sendMessage(TextUtil.parse("&c你没有足够的兑换券！需要: &f"
                    + VoucherUtil.voucherDescription(config)));
            session.cancel();
            p.closeInventory();
            return;
        }

        VoucherUtil.consumeVoucher(p, config);
        session.confirm();

        p.sendMessage(TextUtil.parse("&a精炼完成！兑换券已消耗。"));
        p.closeInventory();
    }

    // ---- 辅助 ----

    private void refresh() {
        for (int i = 0; i < 27; i++) inventory.setItem(i, null);
        build();
    }

    private void fillEmpty() {
        ItemStack f = button(Material.GRAY_STAINED_GLASS_PANE, Component.text(" "));
        for (int i = 0; i < 27; i++) {
            if (inventory.getItem(i) == null) inventory.setItem(i, f);
        }
    }

    private ItemStack button(Material material, Component name, Component... lore) {
        return button(material, name, List.of(lore));
    }

    private ItemStack button(Material material, Component name, List<Component> lore) {
        ItemStack it = new ItemStack(material);
        ItemMeta meta = it.getItemMeta();
        if (meta != null) {
            meta.displayName(TextUtil.clean(name));
            List<Component> cleaned = new ArrayList<>();
            for (Component c : lore) cleaned.add(TextUtil.clean(c));
            meta.lore(cleaned);
            it.setItemMeta(meta);
        }
        return it;
    }

    @Override
    public @NotNull Inventory getInventory() {
        return inventory;
    }
}
