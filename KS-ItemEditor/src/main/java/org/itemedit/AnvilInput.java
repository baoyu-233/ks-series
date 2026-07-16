package org.itemedit;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * 用铁砧的"重命名"输入框来收集一行文本，完全不经过聊天，
 * 因此不受 RPChat / ChatColor2 等接管聊天的插件影响。
 *
 * ★ 文本捕获策略：不依赖 Paper 的 AnvilView.getRenameText()（在部分服务端上返回空），
 * 而是读取铁砧自然计算出的结果物品的显示名——原版铁砧会把玩家输入的文字作为
 * 结果物品的 displayName。
 */
public final class AnvilInput implements InventoryHolder {

    private final Player player;
    private final Component title;
    private final String initial;
    private final Consumer<String> onConfirm;
    private final Runnable onCancel;

    private Inventory inventory;
    private boolean handled = false;
    private String currentText = "";

    public AnvilInput(Player player, String title, String initial,
                      Consumer<String> onConfirm, Runnable onCancel) {
        this.player = player;
        this.title = Component.text(title, NamedTextColor.DARK_AQUA);
        this.initial = initial == null ? "" : initial;
        this.onConfirm = onConfirm;
        this.onCancel = onCancel;
    }

    public void open() {
        inventory = Bukkit.createInventory(this, InventoryType.ANVIL, title);
        // 放入一张纸，其显示名作为初始值；铁砧的自然结果物品的显示名 = 玩家输入的文字
        ItemStack left = new ItemStack(Material.PAPER);
        ItemMeta meta = left.getItemMeta();
        if (meta != null) {
            meta.displayName(Component.text(initial.isEmpty() ? " " : initial)
                    .decoration(TextDecoration.ITALIC, false));
            left.setItemMeta(meta);
        }
        inventory.setItem(0, left);
        currentText = initial;
        player.openInventory(inventory);
    }

    /**
     * 从铁砧自然结果物品的 displayName 提取玩家输入的文本。
     * 这是替代 AnvilView.getRenameText() 的可靠方案。
     */
    public static String textFromResultItem(ItemStack resultItem) {
        if (resultItem == null || !resultItem.hasItemMeta()) return null;
        ItemMeta meta = resultItem.getItemMeta();
        if (meta == null || !meta.hasDisplayName()) return null;
        return TextUtil.plain(meta.displayName());
    }

    /**
     * 根据提取到的文本构建确认按钮——保留原铁砧结果物品的名字，
     * 追加操作提示 lore。
     */
    public ItemStack buildResult(String text) {
        ItemStack result = new ItemStack(Material.NAME_TAG);
        ItemMeta meta = result.getItemMeta();
        if (meta != null) {
            String shown = (text == null || text.isEmpty()) ? "（空）" : text;
            meta.displayName(Component.text(shown, NamedTextColor.GREEN)
                    .decoration(TextDecoration.ITALIC, false));
            List<Component> lore = new ArrayList<>();
            lore.add(Component.text("点击确认", NamedTextColor.GRAY)
                    .decoration(TextDecoration.ITALIC, false));
            lore.add(Component.text("留空确认 = 取消/清除", NamedTextColor.DARK_GRAY)
                    .decoration(TextDecoration.ITALIC, false));
            meta.lore(lore);
            result.setItemMeta(meta);
        }
        return result;
    }

    public String getInitial() {
        return initial;
    }

    public void confirm(String text) {
        if (handled) return;
        handled = true;
        String effective = text == null ? initial : text;
        if (onConfirm != null) onConfirm.accept(effective);
    }

    public void cancel() {
        if (handled) return;
        handled = true;
        if (onCancel != null) onCancel.run();
    }

    /** 更新缓存的当前输入文本。 */
    public void updateCurrentText(String text) {
        this.currentText = text == null ? "" : text;
    }

    public String getCurrentText() {
        return currentText;
    }

    public boolean isHandled() {
        return handled;
    }

    public Player player() {
        return player;
    }

    @Override
    public @NotNull Inventory getInventory() {
        return inventory;
    }
}
