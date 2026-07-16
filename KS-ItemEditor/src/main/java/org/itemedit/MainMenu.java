package org.itemedit;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Material;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

public final class MainMenu extends Menu {

    private static final int PREVIEW = 4;
    private static final int LOAD_TEMPLATE = 2;
    private static final int EXPORT_ADMIN = 5;
    private static final int EXPORT_PLAYER = 6;
    private static final int NAME = 10;
    private static final int LORE = 12;
    private static final int ENCHANT = 14;
    private static final int MODEL = 16;
    private static final int UNBREAKABLE = 20;
    private static final int GLOWING = 24;
    private static final int CLOSE = 22;

    public MainMenu(ItemEditor plugin, EditSession session) {
        super(plugin, session);
    }

    @Override
    protected void build() {
        inventory = create(27, Component.text("物品编辑器", NamedTextColor.DARK_AQUA));

        ItemStack item = session.item();

        inventory.setItem(PREVIEW, item.clone());

        // —— 模板加载 ——
        inventory.setItem(LOAD_TEMPLATE, button(Material.BOOKSHELF,
                Component.text("📋 加载模板", NamedTextColor.AQUA),
                Component.text("输入模板码从模板库加载配置", NamedTextColor.GRAY),
                Component.text("管理员可加载全部模板", NamedTextColor.DARK_GRAY)));

        // —— 导出管理员模板 ——
        inventory.setItem(EXPORT_ADMIN, button(Material.WRITABLE_BOOK,
                Component.text("📤 导出管理模板", NamedTextColor.GOLD),
                Component.text("仅管理员可加载", NamedTextColor.RED),
                Component.text("包含 IA 模型 + FE 附魔等完整数据", NamedTextColor.GRAY)));

        // —— 导出玩家模板 ——
        inventory.setItem(EXPORT_PLAYER, button(Material.PAPER,
                Component.text("📤 导出玩家模板", NamedTextColor.YELLOW),
                Component.text("普通玩家可加载", NamedTextColor.GREEN),
                Component.text("不含管理员专属内容", NamedTextColor.GRAY)));

        inventory.setItem(NAME, button(Material.NAME_TAG,
                Component.text("修改名称", NamedTextColor.YELLOW),
                Component.text("点击在聊天框输入新名称", NamedTextColor.GRAY),
                Component.text("支持 & 颜色码 与 MiniMessage", NamedTextColor.DARK_GRAY)));

        inventory.setItem(LORE, button(Material.WRITABLE_BOOK,
                Component.text("修改描述 (Lore)", NamedTextColor.YELLOW),
                Component.text("逐行增删改、调整顺序", NamedTextColor.GRAY)));

        inventory.setItem(ENCHANT, button(Material.ENCHANTED_BOOK,
                Component.text("修改附魔", NamedTextColor.YELLOW),
                Component.text("可超出原版等级上限", NamedTextColor.GRAY)));

        if (plugin.itemsAdderEnabled()) {
            inventory.setItem(MODEL, button(Material.ITEM_FRAME,
                    Component.text("ItemsAdder 套模型", NamedTextColor.LIGHT_PURPLE),
                    Component.text("浏览或输入 ID 套用自定义模型", NamedTextColor.GRAY)));
        } else {
            inventory.setItem(MODEL, button(Material.BARRIER,
                    Component.text("ItemsAdder 套模型", NamedTextColor.DARK_GRAY),
                    Component.text("未检测到 ItemsAdder", NamedTextColor.RED)));
        }

        // —— 不可破坏开关 ——
        boolean ub = ItemEdits.isUnbreakable(item);
        ItemStack ubButton = button(ub ? Material.BEDROCK : Material.STONE,
                ub ? Component.text("不可破坏: ☑ 开启", NamedTextColor.GREEN)
                   : Component.text("不可破坏: ☐ 关闭", NamedTextColor.GRAY),
                Component.text("点击切换", NamedTextColor.DARK_GRAY),
                Component.text("开启后物品不会消耗耐久", NamedTextColor.GRAY));
        if (ub) {
            ItemMeta ubm = ubButton.getItemMeta();
            ubm.setEnchantmentGlintOverride(true);
            ubButton.setItemMeta(ubm);
        }
        inventory.setItem(UNBREAKABLE, ubButton);

        // —— 发光开关 ——
        boolean glow = ItemEdits.isGlowing(item);
        ItemStack glowButton = button(glow ? Material.GLOWSTONE_DUST : Material.GUNPOWDER,
                glow ? Component.text("发光效果: ☑ 开启", NamedTextColor.GREEN)
                     : Component.text("发光效果: ☐ 关闭", NamedTextColor.GRAY),
                Component.text("点击切换", NamedTextColor.DARK_GRAY),
                Component.text("开启后物品始终显示附魔光效", NamedTextColor.GRAY));
        if (glow) {
            ItemMeta gm = glowButton.getItemMeta();
            gm.setEnchantmentGlintOverride(true);
            glowButton.setItemMeta(gm);
        }
        inventory.setItem(GLOWING, glowButton);

        inventory.setItem(CLOSE, button(Material.BARRIER,
                Component.text("关闭", NamedTextColor.RED)));

        fillEmpty();
    }

    @Override
    public void handle(InventoryClickEvent event) {
        ItemStack item = session.item();
        switch (event.getSlot()) {
            case LOAD_TEMPLATE -> session.promptLoadTemplate();
            case EXPORT_ADMIN -> session.promptExportAdminTemplate();
            case EXPORT_PLAYER -> session.promptExportPlayerTemplate();
            case NAME -> session.promptName();
            case LORE -> new LoreMenu(plugin, session, 0).open();
            case ENCHANT -> new EnchantMenu(plugin, session, 0).open();
            case MODEL -> {
                if (plugin.itemsAdderEnabled()) {
                    new ModelMenu(plugin, session, 0).open();
                }
            }
            case UNBREAKABLE -> {
                boolean now = !ItemEdits.isUnbreakable(item);
                ItemEdits.setUnbreakable(item, now);
                session.setItem(item);
                open();
            }
            case GLOWING -> {
                boolean now = !ItemEdits.isGlowing(item);
                ItemEdits.setGlowing(item, now);
                session.setItem(item);
                open();
            }
            case CLOSE -> session.player().closeInventory();
            default -> { /* preview / filler: ignore */ }
        }
    }
}
