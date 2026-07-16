package org.itemedit;

import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.List;
import java.util.UUID;

public final class EditSession {

    private final ItemEditor plugin;
    private final UUID uuid;
    private final int slot; // 主手所在的物品栏槽位

    /** 标记正在等待聊天输入——期间关闭 GUI 不应清理会话。 */
    private boolean pendingChatInput = false;

    public EditSession(ItemEditor plugin, UUID uuid, int slot) {
        this.plugin = plugin;
        this.uuid = uuid;
        this.slot = slot;
    }

    public Player player() {
        return Bukkit.getPlayer(uuid);
    }

    /** 当前正在编辑的物品（物品栏槽位里的那一份）。 */
    public ItemStack item() {
        Player p = player();
        return p == null ? null : p.getInventory().getItem(slot);
    }

    /**
     * 写回库存。不在此处调用 applyGeneratedLore——
     * FE 的 EnchantmentDisplayListener 和 PacketEventsHook 会各自在
     * 事件/数据包层面处理附魔 Lore 的生成，我们只需写入物品即可。
     * 主动调用 applyGeneratedLore 反而会把 FE 生成的槽位行等
     * 永久烘焙进物品 NBT，即使该物品此前并没有这些行。
     */
    public void setItem(ItemStack stack) {
        Player p = player();
        if (p == null) return;
        p.getInventory().setItem(slot, stack);
    }

    public boolean isPendingChatInput() {
        return pendingChatInput;
    }

    private String currentName() {
        ItemStack it = item();
        if (it == null) return "";
        ItemMeta m = it.getItemMeta();
        if (m == null || !m.hasDisplayName()) return "";
        return TextUtil.plain(m.displayName());
    }

    private String currentLoreLine(int index) {
        List<Component> lore = ItemEdits.lore(item());
        if (index < 0 || index >= lore.size()) return "";
        return TextUtil.plain(lore.get(index));
    }

    // ---------------- 用聊天输入（完全避开纸/铁砧 API） ----------------

    public void promptName() {
        Player p = player();
        if (p == null) return;
        String oldName = currentName();
        pendingChatInput = true;
        ChatInput.prompt(p, "修改物品名称", oldName, text -> {
            pendingChatInput = false;
            ItemStack it = item();
            if (it == null) return;
            String trimmed = text == null ? "" : text.trim();
            if (trimmed.isEmpty()) {
                ItemEdits.clearName(it);
                p.sendMessage(TextUtil.parse("&a已清除名称。"));
            } else if (!trimmed.equals(oldName)) {
                ItemEdits.setName(it, trimmed);
                p.sendMessage(TextUtil.parse("&a名称已更新。"));
            }
            setItem(it);
            new MainMenu(plugin, this).open();
        }, () -> {
            pendingChatInput = false;
            new MainMenu(plugin, this).open();
        });
    }

    public void promptLoreAdd() {
        Player p = player();
        if (p == null) return;
        pendingChatInput = true;
        ChatInput.prompt(p, "新增一行 Lore", "", text -> {
            pendingChatInput = false;
            ItemStack it = item();
            if (it == null) return;
            if (!text.isBlank()) {
                ItemEdits.addLoreLine(it, text);
                setItem(it);
                p.sendMessage(TextUtil.parse("&a已新增一行 Lore。"));
            }
            new LoreMenu(plugin, this, 0).open();
        }, () -> {
            pendingChatInput = false;
            new LoreMenu(plugin, this, 0).open();
        });
    }

    public void promptLoreEdit(int index) {
        Player p = player();
        if (p == null) return;
        pendingChatInput = true;
        ChatInput.prompt(p, "编辑第 " + (index + 1) + " 行", currentLoreLine(index), text -> {
            pendingChatInput = false;
            ItemStack it = item();
            if (it == null) return;
            if (!text.isBlank()) {
                ItemEdits.editLoreLine(it, index, text);
                setItem(it);
                p.sendMessage(TextUtil.parse("&a第 " + (index + 1) + " 行已更新。"));
            }
            new LoreMenu(plugin, this, 0).open();
        }, () -> {
            pendingChatInput = false;
            new LoreMenu(plugin, this, 0).open();
        });
    }

    public void promptModel(boolean keepBody) {
        Player p = player();
        if (p == null) return;
        pendingChatInput = true;
        ChatInput.prompt(p, keepBody ? "输入 IA 物品 ID（保留本体）" : "输入 IA 物品 ID（替换）",
                "namespace:id", text -> {
            pendingChatInput = false;
            applyModelFromId(p, text.trim(), keepBody);
        }, () -> {
            pendingChatInput = false;
            new ModelMenu(plugin, this, 0).open();
        });
    }

    private void applyModelFromId(Player p, String id, boolean keepBody) {
        if (id.isBlank() || id.equals("namespace:id")) {
            new ModelMenu(plugin, this, 0).open();
            return;
        }
        if (!plugin.itemsAdderEnabled()) {
            p.sendMessage(TextUtil.parse("&c服务器未安装 ItemsAdder。"));
            new MainMenu(plugin, this).open();
            return;
        }
        ItemStack ref = ItemsAdderHook.reference(id);
        if (ref == null) {
            p.sendMessage(TextUtil.parse("&c找不到 ItemsAdder 物品: &f" + id));
            new ModelMenu(plugin, this, 0).open();
            return;
        }
        ItemStack item = item();
        if (item == null) return;
        if (keepBody) {
            boolean ok = ItemEdits.applyModelKeepBody(item, ref);
            setItem(item);
            p.sendMessage(ok
                    ? TextUtil.parse("&a已套用模型(保留本体): &f" + id)
                    : TextUtil.parse("&e该 IA 物品没有可复制外观，试试「替换为IA物品」。"));
        } else {
            ItemStack out = ItemEdits.applyModelReplace(ref, item);
            setItem(out);
            p.sendMessage(TextUtil.parse("&a已替换为 IA 物品: &f" + id));
        }
        new ModelMenu(plugin, this, 0).open();
    }

    // ---------------- 模板加载 / 导出 ----------------

    /**
     * 管理员 GUI：从模板码加载模板，应用到当前编辑的物品上。
     */
    public void promptLoadTemplate() {
        Player p = player();
        if (p == null) return;
        pendingChatInput = true;
        ChatInput.prompt(p, "加载模板", "输入模板码（8位字母数字）", text -> {
            pendingChatInput = false;
            ItemStack it = item();
            if (it == null) return;
            String code = text.trim();
            TemplateManager.Template template = plugin.webServer().templateManager().load(code);
            if (template == null || template.item == null) {
                p.sendMessage(TextUtil.parse("&c模板不存在: &f" + code));
                new MainMenu(plugin, this).open();
                return;
            }

            ItemStack newItem = ItemSerializer.fromItemData(template.item);
            ItemSerializer.applyExtendedData(newItem, template.item);
            setItem(newItem);
            p.sendMessage(TextUtil.parse("&a✅ 模板已加载: &e" + code
                    + " &7(作者: " + (template.createdBy != null ? template.createdBy.name : "未知") + ")"));
            new MainMenu(plugin, this).open();
        }, () -> {
            pendingChatInput = false;
            new MainMenu(plugin, this).open();
        });
    }

    /**
     * 管理员 GUI：将当前编辑的物品导出为管理员专属模板码。
     */
    public void promptExportAdminTemplate() {
        Player p = player();
        if (p == null) return;
        ItemStack it = item();
        if (it == null || it.getType().isAir()) {
            p.sendMessage(TextUtil.parse("&c没有可导出的物品。"));
            new MainMenu(plugin, this).open();
            return;
        }

        ItemData data = ItemSerializer.toItemData(it, p);
        TemplateManager.Template template = plugin.webServer().templateManager()
                .save(data, p.getUniqueId(), p.getName(), true);

        p.sendMessage(TextUtil.parse("\n&6▎ &e模板已导出（管理员专属）\n"
                + "&7  模板码: &e&l" + template.code + "\n"
                + "&7  加载: &f/design load " + template.code + "\n"
                + "&c  [管理员专属 — 玩家无法加载]\n"));
        new MainMenu(plugin, this).open();
    }

    /**
     * 管理员 GUI：将当前物品导出为玩家模板码（普通玩家可加载）。
     */
    public void promptExportPlayerTemplate() {
        Player p = player();
        if (p == null) return;
        ItemStack it = item();
        if (it == null || it.getType().isAir()) {
            p.sendMessage(TextUtil.parse("&c没有可导出的物品。"));
            new MainMenu(plugin, this).open();
            return;
        }

        ItemData data = ItemSerializer.toItemData(it, p);
        // ★ 玩家模板：截断附魔等级到自然上限，移除管理员专属字段
        data.capEnchantmentLevels();
        data.feEnchantments = null;
        data.attributeModifiers = null;
        data.iaModel = null;
        TemplateManager.Template template = plugin.webServer().templateManager()
                .save(data, p.getUniqueId(), p.getName(), false);

        p.sendMessage(TextUtil.parse("\n&6▎ &e模板已导出（玩家模板）\n"
                + "&7  模板码: &e&l" + template.code + "\n"
                + "&7  加载: &f/design load " + template.code + "\n"
                + "&a  [玩家模板 — 所有玩家可加载]\n"));
        new MainMenu(plugin, this).open();
    }
}
