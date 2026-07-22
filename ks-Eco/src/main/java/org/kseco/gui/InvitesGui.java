package org.kseco.gui;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
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
import org.kseco.database.PortableSqlMutation;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.*;

/**
 * 合资邀请 GUI — 待处理的银行/企业联合邀请，接受/拒绝。
 */
public final class InvitesGui implements InventoryHolder {

    private final KsEco plugin;
    private Inventory inventory;
    private final List<Map<String, Object>> invites = new ArrayList<>();
    private int page = 0;
    private static final int ROWS = 6;
    private static final int PAGE_SIZE = 45;

    public InvitesGui(KsEco plugin) {
        this.plugin = plugin;
    }

    public void open(Player player) {
        this.page = 0;
        invites.clear();
        loadInvites(player.getUniqueId().toString());
        build();
        player.openInventory(inventory);
    }

    private void loadInvites(String uuid) {
        String safeUuid = uuid.replace("'", "''");
        try (Connection conn = plugin.ksCore().dataStore().getConnection();
             Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery(
                     "SELECT * FROM ks_ent_invites WHERE invitee_uuid='" + safeUuid
                             + "' AND status='PENDING' ORDER BY created_at DESC")) {
            while (rs.next()) {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("id", rs.getString("id"));
                row.put("enterprise_id", rs.getString("enterprise_id"));
                row.put("bank_id", rs.getString("bank_id"));
                row.put("inviter_uuid", rs.getString("inviter_uuid"));
                row.put("created_at", rs.getLong("created_at"));
                invites.add(row);
            }
        } catch (Exception e) {
            plugin.getLogger().warning("InvitesGui 加载失败: " + e.getMessage());
        }
    }

    private void build() {
        inventory = Bukkit.createInventory(this, ROWS * 9,
                Component.text("§8合资邀请 — 第" + (page + 1) + "页"));

        int start = page * PAGE_SIZE;
        for (int i = 0; i < PAGE_SIZE && (start + i) < invites.size(); i++) {
            inventory.setItem(i, buildInviteItem(invites.get(start + i)));
        }

        if (invites.isEmpty()) {
            inventory.setItem(22, emptyHint());
        }

        if (page > 0)
            inventory.setItem(45, navButton(Material.ARROW, "§a◀ 上一页"));
        inventory.setItem(49, navButton(Material.OAK_DOOR, "§c✕ 返回主菜单", "§7回到经济面板"));
        if ((page + 1) * PAGE_SIZE < invites.size())
            inventory.setItem(53, navButton(Material.ARROW, "§a▶ 下一页"));

        fillEmpty();
    }

    private ItemStack buildInviteItem(Map<String, Object> inv) {
        String id = inv.get("id") != null ? String.valueOf(inv.get("id")) : "";
        String entId = inv.get("enterprise_id") != null ? String.valueOf(inv.get("enterprise_id")) : null;
        String bankId = inv.get("bank_id") != null ? String.valueOf(inv.get("bank_id")) : null;
        String inviterUuid = inv.get("inviter_uuid") != null ? String.valueOf(inv.get("inviter_uuid")) : "";
        long createdAt = inv.get("created_at") instanceof Number n ? n.longValue() : 0;

        boolean isEnterprise = entId != null && !entId.isEmpty();
        Material icon = isEnterprise ? Material.IRON_BLOCK : Material.GOLD_BLOCK;
        String typeLabel = isEnterprise ? "企业" : "银行";
        String targetId = isEnterprise ? entId : bankId;
        if (targetId == null || targetId.isBlank()) targetId = "未知";

        // Resolve target name
        String targetName = targetId;
        String table = isEnterprise ? "ks_ent_enterprises" : "ks_bank_banks";
        try (Connection conn = plugin.ksCore().dataStore().getConnection();
             Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery(
                     "SELECT name FROM " + table + " WHERE id='" + targetId.replace("'", "''") + "'")) {
            if (rs.next()) targetName = rs.getString("name");
        } catch (Exception ignored) {}

        // Resolve inviter name
        String inviterName = inviterUuid.isBlank() ? "未知玩家" : inviterUuid;
        try {
            OfflinePlayer op = Bukkit.getOfflinePlayer(UUID.fromString(inviterUuid));
            if (op.getName() != null) inviterName = op.getName();
        } catch (Exception ignored) {}

        ItemStack item = new ItemStack(icon);
        ItemMeta meta = item.getItemMeta();
        if (meta == null) return item;

        meta.displayName(Component.text("§e🤝 " + typeLabel + "邀请: " + targetName));
        List<Component> lore = new ArrayList<>();
        lore.add(Component.text("类型: " + typeLabel + " 合资", NamedTextColor.GRAY));
        lore.add(Component.text("邀请人: " + inviterName, NamedTextColor.GRAY));
        lore.add(Component.text("目标: " + targetName + " (" + targetId + ")", NamedTextColor.GRAY));
        lore.add(Component.text("时间: " + new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm")
                .format(new java.util.Date(createdAt * 1000)), NamedTextColor.DARK_GRAY));
        lore.add(Component.empty());
        lore.add(Component.text("§a§l左键接受 §7| §c§l右键拒绝", NamedTextColor.YELLOW));
        meta.lore(lore);
        item.setItemMeta(meta);
        return item;
    }

    private ItemStack emptyHint() {
        ItemStack item = new ItemStack(Material.PAPER);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.displayName(Component.text("§7暂无待处理的合资邀请"));
            meta.lore(List.of(Component.text("§7被邀请加入银行或企业时会出现在这里", NamedTextColor.GRAY)));
            item.setItemMeta(meta);
        }
        return item;
    }

    /** 响应邀请（接受或拒绝） */
    private void respond(Player player, String inviteId, boolean accept) {
        String uuid = player.getUniqueId().toString();
        try (Connection conn = plugin.ksCore().dataStore().getConnection()) {
            if (conn == null) throw new java.sql.SQLException("database unavailable");
            conn.setAutoCommit(false);
            // 先查邀请信息
            String entId;
            String bankId;
            try (PreparedStatement query = conn.prepareStatement(
                    "SELECT enterprise_id,bank_id FROM ks_ent_invites "
                            + "WHERE id=? AND invitee_uuid=? AND status='PENDING'")) {
                query.setString(1, inviteId);
                query.setString(2, uuid);
                try (ResultSet rs = query.executeQuery()) {
                    if (!rs.next()) {
                        conn.rollback();
                        player.sendMessage("§c邀请已过期或不存在。");
                        return;
                    }
                    entId = rs.getString("enterprise_id");
                    bankId = rs.getString("bank_id");
                }
            }
            long now = System.currentTimeMillis() / 1000;

            String nextStatus = accept ? "ACCEPTED" : "DECLINED";
            try (PreparedStatement update = conn.prepareStatement(
                    "UPDATE ks_ent_invites SET status=?,responded_at=? "
                            + "WHERE id=? AND invitee_uuid=? AND status='PENDING'")) {
                update.setString(1, nextStatus);
                update.setLong(2, now);
                update.setString(3, inviteId);
                update.setString(4, uuid);
                if (update.executeUpdate() != 1) {
                    conn.rollback();
                    player.sendMessage("§c邀请已被处理。");
                    return;
                }
            }
            if (accept) {
                // 添加为成员
                if (entId != null && !entId.isEmpty()) {
                    PortableSqlMutation.insertIfAbsent(conn,
                            "SELECT 1 FROM ks_ent_members WHERE enterprise_id=? AND player_uuid=?", exists -> {
                                exists.setString(1, entId);
                                exists.setString(2, uuid);
                            }, "INSERT INTO ks_ent_members "
                                    + "(enterprise_id,player_uuid,player_name,role,joined_at) VALUES (?,?,?,?,?)", insert -> {
                                insert.setString(1, entId);
                                insert.setString(2, uuid);
                                insert.setString(3, player.getName());
                                insert.setString(4, "CO_OWNER");
                                insert.setLong(5, now);
                            });
                } else if (bankId != null && !bankId.isEmpty()) {
                    PortableSqlMutation.insertIfAbsent(conn,
                            "SELECT 1 FROM ks_bank_members WHERE bank_id=? AND player_uuid=?", exists -> {
                                exists.setString(1, bankId);
                                exists.setString(2, uuid);
                            }, "INSERT INTO ks_bank_members "
                                    + "(bank_id,player_uuid,player_name,role,joined_at) VALUES (?,?,?,?,?)", insert -> {
                                insert.setString(1, bankId);
                                insert.setString(2, uuid);
                                insert.setString(3, player.getName());
                                insert.setString(4, "CO_OWNER");
                                insert.setLong(5, now);
                            });
                }
                conn.commit();
                player.sendMessage("§a已接受邀请！你现在是共同所有者了。");
            } else {
                conn.commit();
                player.sendMessage("§7已拒绝邀请。");
            }

            // 重新加载
            invites.clear();
            loadInvites(player.getUniqueId().toString());
            build();
            player.openInventory(getInventory());
        } catch (Exception e) {
            plugin.getLogger().warning("InvitesGui respond 失败: " + e.getMessage());
            player.sendMessage("§c操作失败: " + e.getMessage());
        }
    }

    private ItemStack navButton(Material mat, String name, String... lore) {
        ItemStack item = new ItemStack(mat);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.displayName(Component.text(name));
            if (lore.length > 0) {
                List<Component> loreList = new ArrayList<>();
                for (String s : lore) loreList.add(Component.text(s, NamedTextColor.GRAY));
                meta.lore(loreList);
            }
            item.setItemMeta(meta);
        }
        return item;
    }

    private void fillEmpty() {
        ItemStack glass = new ItemStack(Material.BLACK_STAINED_GLASS_PANE);
        ItemMeta gm = glass.getItemMeta();
        if (gm != null) { gm.displayName(Component.text(" ")); glass.setItemMeta(gm); }
        for (int i = 0; i < ROWS * 9; i++) {
            if (inventory.getItem(i) == null) inventory.setItem(i, glass.clone());
        }
    }

    @Override public @NotNull Inventory getInventory() { return inventory; }

    // ---- Listener ----

    public static class Listener implements org.bukkit.event.Listener {

        private final KsEco plugin;

        public Listener(KsEco plugin) { this.plugin = plugin; }

        @EventHandler
        public void onClick(InventoryClickEvent event) {
            if (!(event.getView().getTopInventory().getHolder() instanceof InvitesGui gui)) return;
            event.setCancelled(true);
            if (!(event.getWhoClicked() instanceof Player player)) return;

            int slot = event.getRawSlot();
            if (slot < 0 || slot >= gui.getInventory().getSize()) return;
            // Content slot
            if (slot >= 0 && slot < PAGE_SIZE) {
                int index = gui.page * PAGE_SIZE + slot;
                if (index < gui.invites.size()) {
                    Map<String, Object> inv = gui.invites.get(index);
                    String id = String.valueOf(inv.get("id"));
                    if (event.isLeftClick()) {
                        gui.respond(player, id, true);
                    } else if (event.isRightClick()) {
                        gui.respond(player, id, false);
                    }
                }
                return;
            }

            switch (slot) {
                case 45 -> { if (gui.page > 0) { gui.page--; gui.build(); player.openInventory(gui.getInventory()); } }
                case 49 -> { player.closeInventory(); new EcoGuiMainMenu(plugin).open(player); }
                case 53 -> {
                    if ((gui.page + 1) * PAGE_SIZE < gui.invites.size()) {
                        gui.page++; gui.build(); player.openInventory(gui.getInventory());
                    }
                }
            }
        }
    }
}
