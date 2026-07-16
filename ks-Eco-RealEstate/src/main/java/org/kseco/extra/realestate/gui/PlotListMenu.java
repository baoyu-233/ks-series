package org.kseco.extra.realestate.gui;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.jetbrains.annotations.NotNull;
import org.kseco.KsEco;
import org.kseco.extra.realestate.RealEstateManager;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * /land 打开的"我的地块"列表 GUI。点一块地 -> PLAYER 地块进信任管理，ENTERPRISE 地块只提示去企业系统管理成员。
 */
public final class PlotListMenu implements InventoryHolder {

    private final KsEco eco;
    private final RealEstateManager mgr;
    private Inventory inventory;
    private final List<Map<String, Object>> plots = new ArrayList<>();

    public PlotListMenu(KsEco eco, RealEstateManager mgr) {
        this.eco = eco;
        this.mgr = mgr;
    }

    public void open(Player player) {
        plots.clear();
        plots.addAll(mgr.listAccessiblePlots(player.getUniqueId()));
        build();
        player.openInventory(inventory);
    }

    private void build() {
        inventory = Bukkit.createInventory(this, 54, Component.text("§8我的地块"));
        for (int i = 0; i < plots.size() && i < 45; i++) {
            Map<String, Object> p = plots.get(i);
            boolean isEnterprise = RealEstateManager.OWNER_ENTERPRISE.equals(p.get("ownerType"));
            ItemStack item = new ItemStack(isEnterprise ? Material.GOLD_BLOCK : Material.GRASS_BLOCK);
            ItemMeta meta = item.getItemMeta();
            if (meta != null) {
                meta.displayName(Component.text("§e" + p.get("id")));
                List<Component> lore = new ArrayList<>();
                lore.add(Component.text("世界: " + p.get("world"), NamedTextColor.GRAY));
                lore.add(Component.text("范围: [" + p.get("x1") + "," + p.get("z1") + "]-[" + p.get("x2") + "," + p.get("z2") + "]", NamedTextColor.GRAY));
                lore.add(Component.text(isEnterprise ? "类型: 企业地产" : "类型: 个人地产", NamedTextColor.GRAY));
                lore.add(Component.empty());
                lore.add(isEnterprise
                        ? Component.text("点击查看说明（企业地块按成员名单放行）", NamedTextColor.YELLOW)
                        : Component.text("点击管理信任名单", NamedTextColor.GREEN));
                meta.lore(lore);
                item.setItemMeta(meta);
            }
            inventory.setItem(i, item);
        }
        if (plots.isEmpty()) {
            ItemStack hint = new ItemStack(Material.BARRIER);
            ItemMeta m = hint.getItemMeta();
            if (m != null) { m.displayName(Component.text("§7你还没有地块")); hint.setItemMeta(m); }
            inventory.setItem(22, hint);
        }
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
            if (!(event.getInventory().getHolder() instanceof PlotListMenu menu)) return;
            event.setCancelled(true);
            if (!(event.getWhoClicked() instanceof Player player)) return;
            int slot = event.getSlot();
            if (slot < 0 || slot >= menu.plots.size()) return;
            Map<String, Object> plot = menu.plots.get(slot);
            boolean isEnterprise = RealEstateManager.OWNER_ENTERPRISE.equals(plot.get("ownerType"));
            if (isEnterprise) {
                player.sendMessage("§e企业地块按企业成员名单自动放行，去 /enterprise 管理成员名单。");
                return;
            }
            player.closeInventory();
            new PlotTrustMenu(eco, mgr, (String) plot.get("id")).open(player);
        }
    }
}
