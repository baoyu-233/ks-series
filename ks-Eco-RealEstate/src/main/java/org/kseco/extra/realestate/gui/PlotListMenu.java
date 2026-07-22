package org.kseco.extra.realestate.gui;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.jetbrains.annotations.NotNull;
import org.kseco.KsEco;
import org.kseco.extra.realestate.RealEstateManager;
import org.kseco.extra.realestate.RealEstateManager.PlotSummary;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.RejectedExecutionException;

/**
 * /land 打开的"我的地块"列表 GUI。数据库读取在串行数据库队列完成，物品与 GUI 只在服务器线程构建。
 */
public final class PlotListMenu implements InventoryHolder {

    private final KsEco eco;
    private final RealEstateManager mgr;
    private final List<PlotSummary> plots = new ArrayList<>();
    private Inventory inventory;
    private long loadGeneration;

    public PlotListMenu(KsEco eco, RealEstateManager mgr) {
        this.eco = eco;
        this.mgr = mgr;
    }

    public void open(Player player) {
        inventory = Bukkit.createInventory(this, 54, Component.text("§8我的地块"));
        renderLoading();
        player.openInventory(inventory);

        UUID playerId = player.getUniqueId();
        long generation = ++loadGeneration;
        try {
            eco.asyncWorkPool().executeDatabase(() -> {
                List<PlotSummary> loaded = List.of();
                String error = null;
                try {
                    loaded = mgr.loadAccessiblePlotSummaries(playerId);
                } catch (Exception exception) {
                    error = "读取地块失败";
                    eco.getLogger().warning("[房地产] 异步读取我的地块失败: " + exception.getMessage());
                }
                List<PlotSummary> result = loaded;
                String resultError = error;
                eco.scheduler().runPlayer(playerId, () -> applyLoad(playerId, generation, result, resultError));
            });
        } catch (RejectedExecutionException rejected) {
            renderError("数据库队列繁忙，请稍后重试");
        }
    }

    private void applyLoad(UUID playerId, long generation, List<PlotSummary> loaded, String error) {
        if (generation != loadGeneration) return;
        Player player = Bukkit.getPlayer(playerId);
        if (player == null || !isViewing(player)) return;
        if (error != null) {
            renderError(error);
            return;
        }
        plots.clear();
        plots.addAll(loaded);
        renderPlots();
    }

    private void renderLoading() {
        inventory.clear();
        inventory.setItem(22, statusItem(Material.CLOCK, "§e正在读取地块..."));
    }

    private void renderError(String message) {
        inventory.clear();
        inventory.setItem(22, statusItem(Material.BARRIER, "§c" + message));
    }

    private void renderPlots() {
        inventory.clear();
        for (int i = 0; i < plots.size() && i < 45; i++) {
            PlotSummary plot = plots.get(i);
            boolean enterprise = RealEstateManager.OWNER_ENTERPRISE.equals(plot.ownerType());
            ItemStack item = new ItemStack(enterprise ? Material.GOLD_BLOCK : Material.GRASS_BLOCK);
            ItemMeta meta = item.getItemMeta();
            if (meta != null) {
                meta.displayName(Component.text("§e" + plot.id()));
                List<Component> lore = new ArrayList<>();
                lore.add(Component.text("世界: " + plot.world(), NamedTextColor.GRAY));
                lore.add(Component.text("范围: [" + plot.x1() + "," + plot.z1() + "]-[" +
                        plot.x2() + "," + plot.z2() + "]", NamedTextColor.GRAY));
                lore.add(Component.text(enterprise ? "类型: 企业地产" : "类型: 个人地产", NamedTextColor.GRAY));
                lore.add(Component.empty());
                lore.add(enterprise
                        ? Component.text("点击查看说明（企业地块按成员名单放行）", NamedTextColor.YELLOW)
                        : Component.text("点击管理信任名单", NamedTextColor.GREEN));
                meta.lore(lore);
                item.setItemMeta(meta);
            }
            inventory.setItem(i, item);
        }
        if (plots.isEmpty()) inventory.setItem(22, statusItem(Material.BARRIER, "§7你还没有地块"));
    }

    private ItemStack statusItem(Material material, String name) {
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.displayName(Component.text(name));
            item.setItemMeta(meta);
        }
        return item;
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
            if (!(event.getInventory().getHolder() instanceof PlotListMenu menu)) return;
            event.setCancelled(true);
            if (!(event.getWhoClicked() instanceof Player player) || event.getClickedInventory() != menu.inventory) return;
            int slot = event.getRawSlot();
            if (slot < 0 || slot >= menu.plots.size()) return;
            PlotSummary plot = menu.plots.get(slot);
            if (RealEstateManager.OWNER_ENTERPRISE.equals(plot.ownerType())) {
                player.sendMessage("§e企业地块按企业成员名单自动放行，去 /enterprise 管理成员名单。");
                return;
            }
            player.closeInventory();
            new PlotTrustMenu(eco, mgr, plot.id()).open(player);
        }

        @EventHandler
        public void onDrag(InventoryDragEvent event) {
            if (event.getInventory().getHolder() instanceof PlotListMenu) event.setCancelled(true);
        }
    }
}
