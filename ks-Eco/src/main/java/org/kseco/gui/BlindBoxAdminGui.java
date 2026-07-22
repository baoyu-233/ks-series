package org.kseco.gui;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.player.AsyncPlayerChatEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.jetbrains.annotations.NotNull;
import org.kseco.BlindBoxManager;
import org.kseco.KsEco;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 盲盒管理员 GUI (/blindboxadmin)。
 *
 * 三个视图：
 *   view=0  卡池列表（浏览 / 选择 / 删除 / 新建）
 *   view=1  战利品列表（展示真实 NBT 物品图标，可删除）
 *   view=2  添加战利品（Q 丢入手持物品，设置稀有度/权重/数量，确认保存）
 *
 * 与 ExchangeGui admin 模式相同理念：管理员手持物品 Q 键丢入指定槽位，保存完整 NBT BLOB。
 */
public final class BlindBoxAdminGui implements InventoryHolder {

    private final KsEco plugin;
    private Inventory inventory;

    // view 状态
    int view = 0;
    int page = 0;
    String selectedPoolId = null;

    // view=2 临时状态
    ItemStack pendingItem = null;
    String pendingRarity = "COMMON";
    int pendingWeight = 5;
    int pendingQty = 1;

    // view=2 bundle 状态（"保存并继续添加到同一组"）
    String pendingBundleId = null;   // 当前 bundle 的主条目 lootId（用作 bundleId 外键）
    int pendingBundleSlot = 0;       // 下一个 bundle_slot 编号

    // view=4 编辑战利品状态
    String selectedLootId = null;
    ItemStack pendingEditItem = null;   // 替换物品（Q 键丢入；null = 不替换）
    String pendingEditRarity = "COMMON";
    int pendingEditWeight = 5;
    int pendingEditQty = 1;

    // view=3 编辑卡池状态
    double editPrice = 100;
    int editPityMax = 50;
    int editMinEnterpriseLevel = 1;
    String editPityRules = "";
    String editName = "";           // 非空时表示已通过聊天输入修改

    // 加载的数据
    final List<Map<String, Object>> pools = new ArrayList<>();
    final List<Map<String, Object>> loots = new ArrayList<>();
    private boolean loading;
    private long loadGeneration;

    private static final int ROWS = 6;
    private static final int PAGE_SIZE = 36;

    // 稀有度循环顺序
    private static final String[] RARITIES = {"COMMON", "UNCOMMON", "RARE", "EPIC", "LEGENDARY"};

    // ---- 静态 pending（chat + drop 模式） ----

    record PendingCreatePool(int step, String name, double price, String poolType) {}
    static final Map<UUID, PendingCreatePool> pendingCreate = new ConcurrentHashMap<>();

    // Drop pending: 玩家等待丢入物品进添加视图（view=2 或 view=4）
    static final Map<UUID, BlindBoxAdminGui> pendingDrop = new ConcurrentHashMap<>();

    // Chat pending for pool name edit (view=3)
    static final Map<UUID, BlindBoxAdminGui> pendingPoolNameEdit = new ConcurrentHashMap<>();
    static final Map<UUID, BlindBoxAdminGui> pendingPoolRulesEdit = new ConcurrentHashMap<>();

    // ---- 构造 ----

    public BlindBoxAdminGui(KsEco plugin) {
        this.plugin = plugin;
    }

    public void open(Player player) {
        if (!player.hasPermission("kseco.admin")) {
            player.sendMessage("§c权限不足。需要 kseco.admin。");
            return;
        }
        this.view = 0;
        this.page = 0;
        this.selectedPoolId = null;
        refreshPools(player);
    }

    // ---- 数据加载 ----

    private void refreshPools(Player player) {
        refreshPools(player, null);
    }

    private void refreshPools(Player player, Runnable afterLoad) {
        long generation = ++loadGeneration;
        loading = true;
        pools.clear();
        build();
        player.openInventory(inventory);
        plugin.blindBoxManager().loadPoolsAsync(rows -> {
            plugin.scheduler().runEntity(player, () -> {
                if (generation != loadGeneration || !isStillOpen(player)) return;
                pools.clear();
                pools.addAll(rows);
                loading = false;
                if (afterLoad != null) afterLoad.run();
                else rebuildAndOpen(player);
            }, () -> { });
        }, error -> plugin.scheduler().runEntity(player,
                () -> finishLoadError(player, generation, error), () -> { }));
    }

    private void refreshLoots(Player player) {
        long generation = ++loadGeneration;
        loading = true;
        loots.clear();
        build();
        player.openInventory(inventory);
        if (selectedPoolId == null) {
            loading = false;
            return;
        }
        plugin.blindBoxManager().loadLootViewsAsync(selectedPoolId, true, rows -> {
            plugin.scheduler().runEntity(player, () -> {
                if (generation != loadGeneration || !isStillOpen(player)) return;
                loots.clear();
                loots.addAll(rows);
                loading = false;
                rebuildAndOpen(player);
            }, () -> { });
        }, error -> plugin.scheduler().runEntity(player,
                () -> finishLoadError(player, generation, error), () -> { }));
    }

    private void finishLoadError(Player player, long generation, String error) {
        if (generation != loadGeneration || !isStillOpen(player)) return;
        loading = false;
        player.sendMessage("§c" + error);
        rebuildAndOpen(player);
    }

    private boolean isStillOpen(Player player) {
        return player.isOnline() && player.getOpenInventory().getTopInventory().getHolder() == this;
    }

    /** 重建并打开后恢复 Q 捕获状态；openInventory 会同步触发旧窗口的 close 清理。 */
    private void rebuildAndOpen(Player player) {
        build();
        player.openInventory(inventory);
        if (view == 2 || view == 4) pendingDrop.put(player.getUniqueId(), this);
        else pendingDrop.remove(player.getUniqueId());
    }

    // ---- Build ----

    void build() {
        switch (view) {
            case 0 -> buildPoolList();
            case 1 -> buildLootList();
            case 2 -> buildAddLoot();
            case 3 -> buildEditPool();
            case 4 -> buildEditLoot();
        }
        fillEmpty();
    }

    private void buildPoolList() {
        inventory = Bukkit.createInventory(this, ROWS * 9,
                Component.text("§8盲盒管理 — 卡池列表 第" + (page + 1) + "页"));

        // Tab 指示
        inventory.setItem(0, tab(Material.SHULKER_BOX, "§d卡池列表", true));
        inventory.setItem(1, tab(Material.DIAMOND, "§b战利品", false, "§7先选择卡池再查看战利品"));

        int start = page * PAGE_SIZE;
        for (int i = 0; i < PAGE_SIZE && (start + i) < pools.size(); i++) {
            inventory.setItem(9 + i, buildPoolIcon(pools.get(start + i)));
        }
        if (loading) inventory.setItem(22, hint("§e正在加载卡池..."));
        else if (pools.isEmpty()) inventory.setItem(22, hint("§7暂无卡池，点击下方按钮新建"));

        // 底栏
        inventory.setItem(46, navButton(Material.OAK_SIGN, "§a➕ 新建卡池",
                "§7输入名称 → 价格 → 类型"));
        if (page > 0) inventory.setItem(45, navButton(Material.ARROW, "§a◀ 上一页"));
        inventory.setItem(49, navButton(Material.OAK_DOOR, "§c✕ 关闭", "§7关闭面板"));
        if ((page + 1) * PAGE_SIZE < pools.size())
            inventory.setItem(53, navButton(Material.ARROW, "§a▶ 下一页"));
    }

    private void buildLootList() {
        String poolName = selectedPoolId != null ?
                pools.stream().filter(p -> selectedPoolId.equals(p.get("id")))
                        .map(p -> String.valueOf(p.get("name"))).findFirst().orElse(selectedPoolId)
                : "?";
        inventory = Bukkit.createInventory(this, ROWS * 9,
                Component.text("§8盲盒战利品 — " + poolName + " 第" + (page + 1) + "页"));

        inventory.setItem(0, tab(Material.SHULKER_BOX, "§d卡池列表", false));
        inventory.setItem(1, tab(Material.DIAMOND, "§b战利品列表", true));

        // Pool info — 可点击进入 view=3 编辑卡池
        for (var p : pools) {
            if (selectedPoolId != null && selectedPoolId.equals(p.get("id"))) {
                ItemStack icon = buildPoolInfoIcon(p);
                ItemMeta im = icon.getItemMeta();
                if (im != null) {
                    List<Component> loreList = im.hasLore() && im.lore() != null ? new ArrayList<>(im.lore()) : new ArrayList<>();
                    loreList.add(Component.empty());
                    loreList.add(Component.text("§a左键 §7编辑卡池（名字/价格/保底）", NamedTextColor.GRAY));
                    im.lore(loreList);
                    icon.setItemMeta(im);
                }
                inventory.setItem(4, icon);
                break;
            }
        }

        int start = page * PAGE_SIZE;
        for (int i = 0; i < PAGE_SIZE && (start + i) < loots.size(); i++) {
            inventory.setItem(9 + i, buildLootIcon(loots.get(start + i)));
        }
        if (loading) inventory.setItem(22, hint("§e正在加载战利品..."));
        else if (loots.isEmpty()) inventory.setItem(22, hint("§7暂无战利品，点击下方按钮添加"));

        inventory.setItem(46, navButton(Material.EMERALD, "§a🎁 添加战利品",
                "§7手持物品后点击，或 Q 键丢入",
                "§7支持 NBT/附魔等特殊属性"));
        if (page > 0) inventory.setItem(45, navButton(Material.ARROW, "§a◀ 上一页"));
        inventory.setItem(49, navButton(Material.OAK_DOOR, "§c✕ 返回卡池列表"));
        if ((page + 1) * PAGE_SIZE < loots.size())
            inventory.setItem(53, navButton(Material.ARROW, "§a▶ 下一页"));
    }

    private void buildAddLoot() {
        inventory = Bukkit.createInventory(this, ROWS * 9,
                Component.text("§8盲盒 — 添加战利品"));

        inventory.setItem(0, tab(Material.SHULKER_BOX, "§d卡池列表", false));
        inventory.setItem(1, tab(Material.DIAMOND, "§b战利品列表", false));

        // 物品放置区（slot 13）
        if (pendingItem != null && !pendingItem.getType().isAir()) {
            inventory.setItem(13, pendingItem.clone());
        } else {
            inventory.setItem(13, navButton(Material.ORANGE_STAINED_GLASS_PANE,
                    "§e点击放入物品 / Q 键丢入",
                    "§7支持附魔、自定义名称、NBT 等",
                    "§7也可以手持物品直接左键点此槽位"));
        }

        // 稀有度（slot 15）
        Material rarityMat = switch (pendingRarity) {
            case "LEGENDARY" -> Material.NETHER_STAR;
            case "EPIC" -> Material.AMETHYST_SHARD;
            case "RARE" -> Material.LAPIS_LAZULI;
            case "UNCOMMON" -> Material.EMERALD;
            default -> Material.COAL;
        };
        String rarityColor = rarityColor(pendingRarity);
        inventory.setItem(15, navButton(rarityMat, rarityColor + "稀有度: " + pendingRarity,
                "§7左键切换稀有度",
                "§aCOMMON §7→ §aUNCOMMON §7→ §9RARE §7→ §5EPIC §7→ §6LEGENDARY"));

        // 权重（slot 16）
        inventory.setItem(16, navButton(Material.REDSTONE, "§c权重: " + pendingWeight,
                "§7左键 +1 右键 -1",
                "§7Shift+左键 +10 Shift+右键 -10",
                "§7权重越高出现概率越大"));

        // 数量（slot 17）
        inventory.setItem(17, navButton(Material.GOLD_NUGGET, "§6每次给予数量: " + pendingQty,
                "§7左键 +1 右键 -1",
                "§7Shift+左键 +5 Shift+右键 -5"));

        // bundle 状态提示（slot 31）
        if (pendingBundleId != null) {
            inventory.setItem(31, navButton(Material.LIME_DYE,
                    "§a当前奖品组模式 (已有 " + pendingBundleSlot + " 个附加物品)",
                    "§7下一个物品将追加到同一奖品组",
                    "§7点击「确认添加（独立奖品）」可退出奖品组模式"));
        }

        // 确认添加（独立奖品）slot 40
        if (pendingItem != null && !pendingItem.getType().isAir()) {
            inventory.setItem(40, navButton(Material.LIME_STAINED_GLASS_PANE,
                    "§a§l✅ 确认添加（独立奖品）",
                    "§7物品: §f" + (pendingItem.hasItemMeta() && pendingItem.getItemMeta().hasDisplayName()
                            ? pendingItem.getItemMeta().getDisplayName() : pendingItem.getType().name()),
                    "§7稀有度: " + rarityColor + pendingRarity,
                    "§7权重: §f" + pendingWeight + "  数量: §f" + pendingQty,
                    "§8退出奖品组模式"));
            // 保存并继续添加到同一奖品组 slot 39
            String bundleLabel = pendingBundleId == null
                    ? "§e§l➕ 保存并开始新奖品组"
                    : "§e§l➕ 继续添加到同一奖品组";
            inventory.setItem(39, navButton(Material.YELLOW_STAINED_GLASS_PANE,
                    bundleLabel,
                    "§7同一奖品组内的物品抽到时同时给予",
                    "§7权重/稀有度/保底仅由第一个物品决定"));
        } else {
            inventory.setItem(40, navButton(Material.RED_STAINED_GLASS_PANE,
                    "§c请先放入物品"));
            if (pendingBundleId != null) {
                inventory.setItem(39, navButton(Material.ORANGE_STAINED_GLASS_PANE,
                        "§e继续添加到同一奖品组（请先放入物品）"));
            }
        }

        inventory.setItem(49, navButton(Material.OAK_DOOR, "§c✕ 返回战利品列表"));
    }

    // ---- view=3 编辑卡池 ----
    private void buildEditPool() {
        Map<String, Object> pool = pools.stream()
                .filter(p -> selectedPoolId != null && selectedPoolId.equals(p.get("id")))
                .findFirst().orElse(null);
        String poolName = pool != null ? String.valueOf(pool.get("name")) : "?";
        inventory = Bukkit.createInventory(this, ROWS * 9,
                Component.text("§8编辑卡池 — " + poolName));

        inventory.setItem(0, tab(Material.SHULKER_BOX, "§d卡池列表", false));
        inventory.setItem(1, tab(Material.DIAMOND, "§b战利品", false));

        // 当前信息预览（slot 4）
        String currentName = editName.isEmpty() ? poolName : editName;
        String currentRules = editPityRules == null || editPityRules.isBlank()
                ? BlindBoxManager.normalizePityRules("", editPityMax)
                : BlindBoxManager.normalizePityRules(editPityRules, editPityMax);
        inventory.setItem(4, navButton(Material.SHULKER_BOX,
                "§d" + currentName,
                "§7ID: §f" + selectedPoolId,
                "§7价格: §e" + editPrice,
                "§7兼容保底: §f" + editPityMax + " 抽",
                "§7分级保底: §f" + currentRules));

        // 价格调整（slot 11）
        inventory.setItem(11, navButton(Material.GOLD_INGOT,
                "§6价格: §e" + editPrice,
                "§7左键 +100  右键 -100",
                "§7Shift+左键 +1000  Shift+右键 -1000",
                "§7当前: §e" + editPrice));

        // 名字（slot 13）
        inventory.setItem(13, navButton(Material.NAME_TAG,
                "§f名字: §b" + currentName,
                "§7点击输入新名字（聊天栏）",
                "§8当前: §f" + currentName));

        // 保底调整（slot 15）
        inventory.setItem(15, navButton(Material.SHIELD,
                "§b保底(pityMax): §f" + editPityMax,
                "§7左键 +5  右键 -5",
                "§7Shift+左键 +25  Shift+右键 -25",
                "§7当前: §f" + editPityMax + " 抽"));

        inventory.setItem(16, navButton(Material.WRITABLE_BOOK,
                "§d分级保底规则: §f" + currentRules,
                "§7点击后在聊天栏输入规则",
                "§7格式: RARE:50,EPIC:120,LEGENDARY:300",
                "§70 / none 表示清空，清空后使用 pityMax 兜底"));

        inventory.setItem(17, navButton(Material.EXPERIENCE_BOTTLE,
                "§a最低企业等级: §f" + editMinEnterpriseLevel,
                "§7左键 +1  右键 -1",
                "§7仅企业使用卡池时生效"));

        // 确认保存（slot 40）
        inventory.setItem(40, navButton(Material.LIME_STAINED_GLASS_PANE,
                "§a§l✅ 确认保存",
                "§7名字: §f" + currentName,
                "§7价格: §e" + editPrice,
                "§7兼容保底: §f" + editPityMax + " 抽",
                "§7分级保底: §f" + currentRules,
                "§7最低企业等级: §f" + editMinEnterpriseLevel));

        inventory.setItem(49, navButton(Material.OAK_DOOR, "§c✕ 返回战利品列表"));
    }

    // ---- view=4 编辑战利品 ----
    private void buildEditLoot() {
        Map<String, Object> loot = loots.stream()
                .filter(l -> selectedLootId != null && selectedLootId.equals(l.get("id")))
                .findFirst().orElse(null);
        inventory = Bukkit.createInventory(this, ROWS * 9,
                Component.text("§8编辑战利品"));

        inventory.setItem(0, tab(Material.SHULKER_BOX, "§d卡池列表", false));
        inventory.setItem(1, tab(Material.DIAMOND, "§b战利品列表", false));

        // 物品展示/替换（slot 13）
        if (pendingEditItem != null && !pendingEditItem.getType().isAir()) {
            ItemStack preview = pendingEditItem.clone();
            ItemMeta pm = preview.getItemMeta();
            if (pm != null) {
                List<Component> pl = pm.hasLore() && pm.lore() != null ? new ArrayList<>(pm.lore()) : new ArrayList<>();
                pl.add(Component.text("§7（新替换物品，未保存）", NamedTextColor.DARK_GRAY));
                pm.lore(pl);
                preview.setItemMeta(pm);
            }
            inventory.setItem(13, preview);
        } else if (selectedLootId != null) {
            // 显示当前物品
            ItemStack cur = loots.stream()
                    .filter(row -> selectedLootId.equals(row.get("id")))
                    .map(row -> row.get("previewItem"))
                    .filter(ItemStack.class::isInstance)
                    .map(ItemStack.class::cast)
                    .findFirst().orElse(null);
            if (cur != null) {
                ItemStack preview = cur.clone();
                ItemMeta pm = preview.getItemMeta();
                if (pm == null) pm = preview.getItemMeta();
                if (pm != null) {
                    List<Component> pl = pm.hasLore() && pm.lore() != null ? new ArrayList<>(pm.lore()) : new ArrayList<>();
                    pl.add(Component.empty());
                    pl.add(Component.text("§7点击此槽位 / Q键丢入 以替换物品", NamedTextColor.DARK_GRAY));
                    pm.lore(pl);
                    preview.setItemMeta(pm);
                }
                inventory.setItem(13, preview);
            } else {
                inventory.setItem(13, navButton(Material.ORANGE_STAINED_GLASS_PANE,
                        "§e点击放入物品 / Q 键丢入以替换"));
            }
        }

        // 稀有度（slot 15）
        Material rarityMat2 = switch (pendingEditRarity) {
            case "LEGENDARY" -> Material.NETHER_STAR;
            case "EPIC" -> Material.AMETHYST_SHARD;
            case "RARE" -> Material.LAPIS_LAZULI;
            case "UNCOMMON" -> Material.EMERALD;
            default -> Material.COAL;
        };
        inventory.setItem(15, navButton(rarityMat2, rarityColor(pendingEditRarity) + "稀有度: " + pendingEditRarity,
                "§7左键切换稀有度"));

        // 权重（slot 16）
        inventory.setItem(16, navButton(Material.REDSTONE, "§c权重: " + pendingEditWeight,
                "§7左键 +1  右键 -1",
                "§7Shift+左键 +10  Shift+右键 -10"));

        // 数量（slot 17）
        inventory.setItem(17, navButton(Material.GOLD_NUGGET, "§6数量: " + pendingEditQty,
                "§7左键 +1  右键 -1",
                "§7Shift+左键 +5  Shift+右键 -5"));

        // 战利品信息（来自数据）
        if (loot != null) {
            String bundleId = loot.get("bundleId") instanceof String s ? s : null;
            int bundleSlot = loot.get("bundleSlot") instanceof Number n ? n.intValue() : 0;
            if (bundleId != null) {
                inventory.setItem(4, navButton(Material.LIME_DYE,
                        "§a奖品组物品 (slot " + bundleSlot + ")",
                        "§7Bundle ID: §8" + bundleId.substring(0, Math.min(8, bundleId.length()))));
            }
        }

        // 确认保存（slot 40）
        inventory.setItem(40, navButton(Material.LIME_STAINED_GLASS_PANE,
                "§a§l✅ 确认保存",
                "§7稀有度: " + rarityColor(pendingEditRarity) + pendingEditRarity,
                "§7权重: §f" + pendingEditWeight + "  数量: §f" + pendingEditQty,
                (pendingEditItem != null ? "§e物品将被替换" : "§7物品不替换")));

        inventory.setItem(49, navButton(Material.OAK_DOOR, "§c✕ 返回战利品列表"));
    }

    // ---- Icon builders ----

    private ItemStack buildPoolIcon(Map<String, Object> p) {
        String poolType = String.valueOf(p.getOrDefault("poolType", "ITEM"));
        boolean enabled = p.get("enabled") instanceof Boolean b ? b
                : p.get("enabled") instanceof Number n && n.intValue() != 0;
        Material icon = switch (poolType) {
            case "EQUIPMENT" -> Material.DIAMOND_CHESTPLATE;
            case "MATERIAL" -> Material.IRON_INGOT;
            default -> Material.SHULKER_BOX;
        };
        ItemStack stack = new ItemStack(icon);
        ItemMeta meta = stack.getItemMeta();
        if (meta == null) return stack;

        String name = String.valueOf(p.getOrDefault("name", p.get("id")));
        double price = p.get("price") instanceof Number n ? n.doubleValue() : 0;
        int pityMax = p.get("pityMax") instanceof Number n ? n.intValue() : 50;
        int lootCount = p.get("lootCount") instanceof Number n ? n.intValue() : 0;
        int pullCount = p.get("pullCount") instanceof Number n ? n.intValue() : 0;

        meta.displayName(Component.text((enabled ? "§d" : "§7") + "🎁 " + name
                + (enabled ? "" : " §8[停用]")));
        List<Component> lore = new ArrayList<>();
        lore.add(Component.text("ID: §f" + p.get("id"), NamedTextColor.DARK_GRAY));
        lore.add(Component.text("类型: §f" + poolType + "  价格: §e" + plugin.vaultHook().format(price), NamedTextColor.GRAY));
        lore.add(Component.text("保底: §f" + p.getOrDefault("pityRulesText", pityMax + "抽")
                + "  战利品: §f" + lootCount + "条  累计抽取: §f" + pullCount, NamedTextColor.GRAY));
        lore.add(Component.text("最低企业等级: §f" + p.getOrDefault("minEnterpriseLevel", 1), NamedTextColor.GRAY));
        lore.add(Component.empty());
        lore.add(Component.text("§a§l左键 §7查看/管理战利品", NamedTextColor.YELLOW));
        lore.add(Component.text("§e§l右键 §7" + (enabled ? "停用" : "启用") + "此池", NamedTextColor.YELLOW));
        lore.add(Component.text("§c§l中键 §7删除此池（包含所有战利品）", NamedTextColor.RED));
        meta.lore(lore);
        stack.setItemMeta(meta);
        return stack;
    }

    private ItemStack buildPoolInfoIcon(Map<String, Object> p) {
        ItemStack stack = new ItemStack(Material.SHULKER_BOX);
        ItemMeta meta = stack.getItemMeta();
        if (meta == null) return stack;
        meta.displayName(Component.text("§d📦 " + p.get("name")));
        List<Component> lore = new ArrayList<>();
        lore.add(Component.text("ID: " + p.get("id"), NamedTextColor.DARK_GRAY));
        lore.add(Component.text("类型: " + p.get("poolType") + "  价格: " + plugin.vaultHook().format(((Number) p.get("price")).doubleValue()), NamedTextColor.GRAY));
        lore.add(Component.text("保底: " + p.getOrDefault("pityRulesText", p.get("pityMax") + " 抽"), NamedTextColor.GRAY));
        meta.lore(lore);
        stack.setItemMeta(meta);
        return stack;
    }

    private ItemStack buildLootIcon(Map<String, Object> l) {
        // 真实物品已随异步 DTO 在主线程完成解码，不再逐条同步查询数据库。
        String lootId = String.valueOf(l.get("id"));
        ItemStack base = l.get("previewItem") instanceof ItemStack item ? item : null;

        if (base == null || base.getType().isAir()) {
            base = new ItemStack(Material.BARRIER);
        }

        ItemStack stack = base.clone();
        ItemMeta meta = stack.getItemMeta();
        if (meta == null) meta = stack.getItemMeta();
        if (meta == null) return stack;

        String rarity = String.valueOf(l.getOrDefault("rarity", "COMMON"));
        int weight = l.get("weight") instanceof Number n ? n.intValue() : 1;
        int qty = l.get("quantity") instanceof Number n ? n.intValue() : 1;
        String rarityColor = rarityColor(rarity);

        // 保留原 displayName，追加信息到 lore 末尾
        List<Component> lore = new ArrayList<>(meta.hasLore() && meta.lore() != null ? meta.lore() : List.of());
        lore.add(Component.empty());
        lore.add(Component.text("─── 盲盒管理信息 ───", NamedTextColor.DARK_GRAY));
        lore.add(Component.text("稀有度: " + rarityColor + rarity + "  权重: §f" + weight + "  数量: §f" + qty, NamedTextColor.GRAY));
        // bundle 信息
        String bundleId = l.get("bundleId") instanceof String s ? s : null;
        int bundleSlot = l.get("bundleSlot") instanceof Number n ? n.intValue() : 0;
        if (bundleId != null) {
            String bundleLabel = bundleSlot == 0 ? "§a[奖品组主条目]" : "§7[奖品组附加 slot=" + bundleSlot + "]";
            lore.add(Component.text(bundleLabel + " §8" + bundleId.substring(0, Math.min(8, bundleId.length())), NamedTextColor.DARK_GRAY));
        }
        lore.add(Component.text("ID: " + lootId, NamedTextColor.DARK_GRAY));
        lore.add(Component.empty());
        lore.add(Component.text("§a§l左键 §7编辑此战利品（稀有度/权重/数量/替换物品）", NamedTextColor.GREEN));
        lore.add(Component.text("§c§lShift+左键 §7删除此战利品", NamedTextColor.RED));
        meta.lore(lore);
        stack.setItemMeta(meta);
        return stack;
    }

    // ---- Action helpers ----

    void selectPool(String poolId, Player player) {
        this.selectedPoolId = poolId;
        this.view = 1;
        this.page = 0;
        refreshLoots(player);
    }

    void togglePool(String poolId, boolean enable, Player player) {
        // upsertPool 找到当前池数据再 toggle enabled
        for (var p : pools) {
            if (!poolId.equals(p.get("id"))) continue;
            plugin.blindBoxManager().upsertPool(
                    poolId,
                    String.valueOf(p.get("name")),
                    String.valueOf(p.getOrDefault("poolType", "ITEM")),
                    p.get("price") instanceof Number n ? n.doubleValue() : 100,
                    enable,
                    p.get("pityMax") instanceof Number n ? n.intValue() : 50,
                    String.valueOf(p.getOrDefault("description", "")),
                    String.valueOf(p.getOrDefault("ownerType", "PUBLIC")),
                    String.valueOf(p.getOrDefault("allowedCategories", "")),
                    String.valueOf(p.getOrDefault("allowedIndustries", "")),
                    String.valueOf(p.getOrDefault("requiredLandZoneTypes", "")),
                    String.valueOf(p.getOrDefault("pityRules", "")),
                    p.get("minEnterpriseLevel") instanceof Number n ? n.intValue() : 1
            );
            player.sendMessage("§a已" + (enable ? "启用" : "停用") + "卡池 " + p.get("name"));
            break;
        }
        refreshPools(player);
    }

    void deletePool(String poolId, Player player) {
        String name = pools.stream().filter(p -> poolId.equals(p.get("id")))
                .map(p -> String.valueOf(p.get("name"))).findFirst().orElse(poolId);
        boolean ok = plugin.blindBoxManager().deletePool(poolId);
        player.sendMessage(ok ? "§a已删除卡池 " + name : "§c删除失败");
        refreshPools(player);
    }

    void deleteLoot(String lootId, Player player) {
        boolean ok = plugin.blindBoxManager().deleteLoot(lootId);
        player.sendMessage(ok ? "§a已删除战利品" : "§c删除失败");
        refreshLoots(player);
    }

    /** 进入 view=3 编辑卡池 */
    void openEditPool(Player player) {
        Map<String, Object> pool = pools.stream()
                .filter(p -> selectedPoolId != null && selectedPoolId.equals(p.get("id")))
                .findFirst().orElse(null);
        if (pool == null) { player.sendMessage("§c请先选择卡池"); return; }
        this.editPrice = pool.get("price") instanceof Number n ? n.doubleValue() : 100;
        this.editPityMax = pool.get("pityMax") instanceof Number n ? n.intValue() : 50;
        this.editMinEnterpriseLevel = pool.get("minEnterpriseLevel") instanceof Number n ? n.intValue() : 1;
        this.editPityRules = String.valueOf(pool.getOrDefault("pityRules", ""));
        this.editName = String.valueOf(pool.get("name"));
        this.view = 3;
        build();
        player.openInventory(inventory);
        player.sendMessage("§a进入卡池编辑模式。点击「名字」按钮可通过聊天修改名字。");
    }

    /** view=3 保存卡池 */
    void saveEditPool(Player player) {
        Map<String, Object> pool = pools.stream()
                .filter(p -> selectedPoolId != null && selectedPoolId.equals(p.get("id")))
                .findFirst().orElse(null);
        if (pool == null) { player.sendMessage("§c卡池不存在"); return; }
        String name = editName.isEmpty() ? String.valueOf(pool.get("name")) : editName;
        boolean ok = plugin.blindBoxManager().upsertPool(
                selectedPoolId, name,
                String.valueOf(pool.getOrDefault("poolType", "ITEM")),
                editPrice,
                pool.get("enabled") instanceof Boolean b ? b : pool.get("enabled") instanceof Number n && n.intValue() != 0,
                editPityMax,
                String.valueOf(pool.getOrDefault("description", "")),
                String.valueOf(pool.getOrDefault("ownerType", "PUBLIC")),
                String.valueOf(pool.getOrDefault("allowedCategories", "")),
                String.valueOf(pool.getOrDefault("allowedIndustries", "")),
                String.valueOf(pool.getOrDefault("requiredLandZoneTypes", "")),
                editPityRules,
                editMinEnterpriseLevel
        );
        if (ok) {
            player.sendMessage("§a卡池已保存！名字: §f" + name + " §a价格: §e" + editPrice
                    + " §a保底: §f" + BlindBoxManager.normalizePityRules(editPityRules, editPityMax));
            player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_PLING, 0.5f, 2.0f);
        } else {
            player.sendMessage("§c保存失败");
        }
        editName = "";
        editPityRules = "";
        view = 1;
        page = 0;
        refreshPools(player, () -> refreshLoots(player));
    }

    /** 进入 view=4 编辑战利品 */
    void openEditLoot(String lootId, Player player) {
        Map<String, Object> loot = loots.stream().filter(l -> lootId.equals(l.get("id"))).findFirst().orElse(null);
        if (loot == null) { player.sendMessage("§c战利品不存在"); return; }
        this.selectedLootId = lootId;
        this.pendingEditItem = null;
        this.pendingEditRarity = String.valueOf(loot.getOrDefault("rarity", "COMMON"));
        this.pendingEditWeight = loot.get("weight") instanceof Number n ? n.intValue() : 1;
        this.pendingEditQty = loot.get("quantity") instanceof Number n ? n.intValue() : 1;
        this.view = 4;
        rebuildAndOpen(player);
    }

    /** view=4 确认保存战利品 */
    void confirmEditLoot(Player player) {
        if (selectedLootId == null) { player.sendMessage("§c未选中战利品"); return; }
        boolean ok = plugin.blindBoxManager().updateLoot(selectedLootId, pendingEditRarity, pendingEditWeight, pendingEditQty);
        if (ok) {
            player.sendMessage("§a战利品已更新！稀有度: " + pendingEditRarity + "  权重: " + pendingEditWeight);
        } else {
            player.sendMessage("§c更新失败");
        }
        if (pendingEditItem != null && !pendingEditItem.getType().isAir()) {
            boolean rep = plugin.blindBoxManager().replaceLootItem(selectedLootId, pendingEditItem);
            player.sendMessage(rep ? "§a物品已替换" : "§c物品替换失败");
        }
        player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_PLING, 0.5f, 2.0f);
        pendingEditItem = null;
        pendingDrop.remove(player.getUniqueId());
        selectedLootId = null;
        view = 1;
        page = 0;
        refreshLoots(player);
    }

    void confirmAddLoot(Player player) {
        confirmAddLoot(player, false);
    }

    /** @param bundleContinue true=保存并继续添加到同一奖品组 */
    void confirmAddLoot(Player player, boolean bundleContinue) {
        if (pendingItem == null || pendingItem.getType().isAir()) {
            player.sendMessage("§c请先放入物品（点击橙色槽位或 Q 键丢入）");
            return;
        }
        String id;
        if (pendingBundleId == null) {
            // 首次：作为独立条目（独立模式）或 bundle 主条目（bundle 模式）
            String bundleIdToUse = bundleContinue ? UUID.randomUUID().toString() : null;
            id = plugin.blindBoxManager().addLootWithData(
                    selectedPoolId, pendingItem, pendingRarity, pendingWeight, pendingQty, bundleIdToUse);
            if (id != null && bundleContinue) {
                pendingBundleId = bundleIdToUse; // bundle 主条目用 bundleIdToUse 作为 bundle_id
                pendingBundleSlot = 1;
                player.sendMessage("§a奖品组已创建！继续添加附加物品到同一组。");
            }
        } else {
            // 已有 bundle：添加附加物品
            id = plugin.blindBoxManager().addLootToBundle(
                    selectedPoolId, pendingBundleId, pendingItem, pendingBundleSlot, pendingQty);
            if (id != null) {
                pendingBundleSlot++;
                player.sendMessage("§a已追加第 " + (pendingBundleSlot) + " 个附加物品到奖品组！");
            }
        }

        if (id != null) {
            player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_PLING, 0.5f, 2.0f);
            pendingItem = null;
            // 重置除 bundle 状态外的字段
            pendingRarity = "COMMON";
            pendingWeight = 5;
            pendingQty = 1;
            if (!bundleContinue && pendingBundleId == null) {
                // 独立奖品模式：直接返回战利品列表
                view = 1;
                refreshLoots(player);
            } else {
                // bundle 模式：留在 view=2 继续添加
                if (!bundleContinue) {
                    // 用户点了「独立奖品」按钮，退出 bundle 模式
                    pendingBundleId = null;
                    pendingBundleSlot = 0;
                    view = 1;
                    refreshLoots(player);
                } else {
                    rebuildAndOpen(player);
                }
            }
        } else {
            player.sendMessage("§c添加失败，请检查卡池是否存在");
        }
    }

    private String rarityColor(String rarity) {
        return switch (rarity) {
            case "LEGENDARY" -> "§6";
            case "EPIC" -> "§5";
            case "RARE" -> "§9";
            case "UNCOMMON" -> "§a";
            default -> "§7";
        };
    }

    // ---- Nav helpers ----

    private ItemStack tab(Material mat, String name, boolean active, String... lore) {
        ItemStack item = new ItemStack(mat);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.displayName(Component.text(active ? "§l" + name + " §8◀" : name));
            List<Component> loreList = new ArrayList<>();
            loreList.add(Component.text(active ? "§7（当前视图）" : "§7点击切换", NamedTextColor.GRAY));
            for (String s : lore) loreList.add(Component.text(s, NamedTextColor.GRAY));
            meta.lore(loreList);
            item.setItemMeta(meta);
        }
        return item;
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

    private ItemStack hint(String msg) {
        ItemStack item = new ItemStack(Material.PAPER);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) { meta.displayName(Component.text(msg)); item.setItemMeta(meta); }
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

    // ================================================================
    // Listener
    // ================================================================

    public static class Listener implements org.bukkit.event.Listener {

        private final KsEco plugin;

        public Listener(KsEco plugin) { this.plugin = plugin; }

        @EventHandler
        public void onClick(InventoryClickEvent event) {
            if (!(event.getView().getTopInventory().getHolder() instanceof BlindBoxAdminGui gui)) return;
            event.setCancelled(true);
            if (!(event.getWhoClicked() instanceof Player player)) return;
            if (!player.hasPermission("kseco.admin")) {
                player.closeInventory();
                player.sendMessage("§c权限已失效。");
                return;
            }
            if (gui.loading) return;

            int slot = event.getRawSlot();
            if (slot < 0 || slot >= gui.getInventory().getSize()) return;

            // ---- Tab 切换 ----
            if (slot == 0) {
                if (gui.view != 0) { gui.view = 0; gui.page = 0; gui.build(); player.openInventory(gui.getInventory()); }
                return;
            }
            if (slot == 1) {
                if (gui.view != 1 && gui.selectedPoolId != null) {
                    gui.view = 1;
                    gui.page = 0;
                    gui.refreshLoots(player);
                } else if (gui.selectedPoolId == null) {
                    player.sendMessage("§c请先在卡池列表选择一个卡池");
                }
                return;
            }

            // ---- view=0 卡池列表 ----
            if (gui.view == 0) {
                if (slot >= 9 && slot < 9 + PAGE_SIZE) {
                    int index = gui.page * PAGE_SIZE + (slot - 9);
                    if (index < gui.pools.size()) {
                        Map<String, Object> pool = gui.pools.get(index);
                        String poolId = String.valueOf(pool.get("id"));
                        boolean enabled = pool.get("enabled") instanceof Boolean b ? b
                                : pool.get("enabled") instanceof Number n && n.intValue() != 0;
                        if (event.getClick() == ClickType.MIDDLE) {
                            gui.deletePool(poolId, player);
                        } else if (event.isRightClick()) {
                            gui.togglePool(poolId, !enabled, player);
                        } else {
                            gui.selectPool(poolId, player);
                        }
                    }
                    return;
                }
                if (slot == 46) {
                    // 新建卡池：开始 chat 输入
                    player.closeInventory();
                    pendingCreate.put(player.getUniqueId(), new PendingCreatePool(1, null, 0, "ITEM"));
                    player.sendMessage("§a请输入新卡池名称，或输入 cancel 取消：");
                    return;
                }
                switch (slot) {
                    case 45 -> { if (gui.page > 0) { gui.page--; gui.build(); player.openInventory(gui.getInventory()); } }
                    case 49 -> player.closeInventory();
                    case 53 -> {
                        if ((gui.page + 1) * PAGE_SIZE < gui.pools.size()) {
                            gui.page++; gui.build(); player.openInventory(gui.getInventory());
                        }
                    }
                }
                return;
            }

            // ---- view=1 战利品列表 ----
            if (gui.view == 1) {
                // Pool info (slot 4) → 编辑卡池 (view=3)
                if (slot == 4) {
                    gui.openEditPool(player);
                    return;
                }
                if (slot >= 9 && slot < 9 + PAGE_SIZE) {
                    int index = gui.page * PAGE_SIZE + (slot - 9);
                    if (index < gui.loots.size()) {
                        Map<String, Object> loot = gui.loots.get(index);
                        String lootId = String.valueOf(loot.get("id"));
                        if (event.isShiftClick() && event.isLeftClick()) {
                            gui.deleteLoot(lootId, player);
                        } else if (!event.isShiftClick()) {
                            // 普通左键/右键 → 进入编辑战利品 (view=4)
                            gui.openEditLoot(lootId, player);
                        }
                    }
                    return;
                }
                if (slot == 46) {
                    // 切到添加视图
                    gui.pendingItem = null;
                    gui.pendingBundleId = null;
                    gui.pendingBundleSlot = 0;
                    gui.view = 2;
                    gui.rebuildAndOpen(player);
                    return;
                }
                switch (slot) {
                    case 45 -> { if (gui.page > 0) { gui.page--; gui.build(); player.openInventory(gui.getInventory()); } }
                    case 49 -> { gui.view = 0; gui.page = 0; gui.build(); player.openInventory(gui.getInventory()); }
                    case 53 -> {
                        if ((gui.page + 1) * PAGE_SIZE < gui.loots.size()) {
                            gui.page++; gui.build(); player.openInventory(gui.getInventory());
                        }
                    }
                }
                return;
            }

            // ---- view=2 添加战利品 ----
            if (gui.view == 2) {
                if (slot == 13) {
                    // 手持物品点击 → 设置 pendingItem
                    ItemStack held = player.getInventory().getItemInMainHand();
                    if (held != null && !held.getType().isAir()) {
                        gui.pendingItem = held.clone();
                        gui.pendingItem.setAmount(1);
                        gui.rebuildAndOpen(player);
                        player.sendMessage("§a已设置物品: §f" + held.getType().name());
                    } else {
                        player.sendMessage("§c请手持要添加的物品，再点击此槽位");
                    }
                    return;
                }
                if (slot == 15) {
                    // 稀有度循环
                    int idx = 0;
                    for (int i = 0; i < RARITIES.length; i++) {
                        if (RARITIES[i].equals(gui.pendingRarity)) { idx = i; break; }
                    }
                    gui.pendingRarity = RARITIES[(idx + 1) % RARITIES.length];
                    gui.rebuildAndOpen(player);
                    return;
                }
                if (slot == 16) {
                    // 权重调整
                    int delta = event.isShiftClick() ? (event.isLeftClick() ? 10 : -10)
                            : event.isLeftClick() ? 1 : -1;
                    gui.pendingWeight = Math.max(1, gui.pendingWeight + delta);
                    gui.rebuildAndOpen(player);
                    return;
                }
                if (slot == 17) {
                    // 数量调整
                    int delta = event.isShiftClick() ? (event.isLeftClick() ? 5 : -5)
                            : event.isLeftClick() ? 1 : -1;
                    gui.pendingQty = Math.max(1, gui.pendingQty + delta);
                    gui.rebuildAndOpen(player);
                    return;
                }
                if (slot == 39) {
                    // 保存并继续添加到同一奖品组
                    gui.confirmAddLoot(player, true);
                    return;
                }
                if (slot == 40) {
                    // 独立奖品模式（退出 bundle 模式）
                    boolean wasBundleMode = gui.pendingBundleId != null;
                    if (wasBundleMode) {
                        // 在已有 bundle 模式下点「独立奖品」：视为结束 bundle，返回列表
                        gui.pendingBundleId = null;
                        gui.pendingBundleSlot = 0;
                        pendingDrop.remove(player.getUniqueId());
                        gui.view = 1;
                        gui.page = 0;
                        gui.refreshLoots(player);
                        player.sendMessage("§a已退出奖品组模式。");
                    } else {
                        gui.confirmAddLoot(player, false);
                    }
                    return;
                }
                if (slot == 49) {
                    pendingDrop.remove(player.getUniqueId());
                    gui.pendingBundleId = null;
                    gui.pendingBundleSlot = 0;
                    gui.view = 1;
                    gui.page = 0;
                    gui.refreshLoots(player);
                }
            }

            // ---- view=3 编辑卡池 ----
            if (gui.view == 3) {
                if (slot == 11) {
                    // 价格调整
                    double delta = event.isShiftClick()
                            ? (event.isLeftClick() ? 1000 : -1000)
                            : (event.isLeftClick() ? 100 : -100);
                    gui.editPrice = Math.max(1, gui.editPrice + delta);
                    gui.build(); player.openInventory(gui.getInventory());
                    return;
                }
                if (slot == 13) {
                    // 名字：触发聊天输入
                    player.closeInventory();
                    pendingPoolNameEdit.put(player.getUniqueId(), gui);
                    player.sendMessage("§a请输入新卡池名称（2~32字符），或输入 cancel 取消：");
                    return;
                }
                if (slot == 15) {
                    // 保底调整
                    int delta = event.isShiftClick()
                            ? (event.isLeftClick() ? 25 : -25)
                            : (event.isLeftClick() ? 5 : -5);
                    gui.editPityMax = Math.max(0, gui.editPityMax + delta);
                    gui.build(); player.openInventory(gui.getInventory());
                    return;
                }
                if (slot == 16) {
                    player.closeInventory();
                    pendingPoolRulesEdit.put(player.getUniqueId(), gui);
                    player.sendMessage("§a请输入分级保底规则，例如 §fRARE:50,EPIC:120,LEGENDARY:300§7；输入 0/none 清空，cancel 取消。");
                    return;
                }
                if (slot == 17) {
                    int delta = event.isLeftClick() ? 1 : -1;
                    gui.editMinEnterpriseLevel = Math.max(1, Math.min(
                            plugin.enterpriseLevelManager().getMaxLevel(), gui.editMinEnterpriseLevel + delta));
                    gui.build(); player.openInventory(gui.getInventory());
                    return;
                }
                if (slot == 40) {
                    gui.saveEditPool(player);
                    return;
                }
                if (slot == 49) {
                    gui.editName = "";
                    gui.editPityRules = "";
                    gui.view = 1; gui.page = 0; gui.build(); player.openInventory(gui.getInventory());
                }
                return;
            }

            // ---- view=4 编辑战利品 ----
            if (gui.view == 4) {
                if (slot == 13) {
                    // 点击槽位 = 手持物品设为替换物品
                    ItemStack held = player.getInventory().getItemInMainHand();
                    if (held != null && !held.getType().isAir()) {
                        gui.pendingEditItem = held.clone();
                        gui.pendingEditItem.setAmount(1);
                        gui.rebuildAndOpen(player);
                        player.sendMessage("§a已设置替换物品: §f" + held.getType().name());
                    } else {
                        player.sendMessage("§c请手持要替换的物品，再点击此槽位");
                    }
                    return;
                }
                if (slot == 15) {
                    int idx = 0;
                    for (int i = 0; i < RARITIES.length; i++) {
                        if (RARITIES[i].equals(gui.pendingEditRarity)) { idx = i; break; }
                    }
                    gui.pendingEditRarity = RARITIES[(idx + 1) % RARITIES.length];
                    gui.rebuildAndOpen(player);
                    return;
                }
                if (slot == 16) {
                    int delta = event.isShiftClick() ? (event.isLeftClick() ? 10 : -10)
                            : event.isLeftClick() ? 1 : -1;
                    gui.pendingEditWeight = Math.max(1, gui.pendingEditWeight + delta);
                    gui.rebuildAndOpen(player);
                    return;
                }
                if (slot == 17) {
                    int delta = event.isShiftClick() ? (event.isLeftClick() ? 5 : -5)
                            : event.isLeftClick() ? 1 : -1;
                    gui.pendingEditQty = Math.max(1, gui.pendingEditQty + delta);
                    gui.rebuildAndOpen(player);
                    return;
                }
                if (slot == 40) {
                    gui.confirmEditLoot(player);
                    return;
                }
                if (slot == 49) {
                    pendingDrop.remove(player.getUniqueId());
                    gui.pendingEditItem = null;
                    gui.selectedLootId = null;
                    gui.view = 1; gui.page = 0; gui.build(); player.openInventory(gui.getInventory());
                }
            }
        }

        @EventHandler
        public void onClose(InventoryCloseEvent event) {
            if (!(event.getInventory().getHolder() instanceof BlindBoxAdminGui)) return;
            if (event.getPlayer() instanceof Player player) {
                pendingDrop.remove(player.getUniqueId());
                pendingPoolNameEdit.remove(player.getUniqueId());
                pendingPoolRulesEdit.remove(player.getUniqueId());
            }
        }
    }

    // ================================================================
    // Drop Listener — Q 键丢入物品进 view=2 物品槽
    // ================================================================

    public static class DropListener implements org.bukkit.event.Listener {

        private final KsEco plugin;

        public DropListener(KsEco plugin) { this.plugin = plugin; }

        @EventHandler
        public void onDrop(PlayerDropItemEvent event) {
            Player player = event.getPlayer();
            BlindBoxAdminGui gui = pendingDrop.get(player.getUniqueId());
            if (gui == null) return;
            if (!player.hasPermission("kseco.admin")) {
                pendingDrop.remove(player.getUniqueId());
                return;
            }

            event.setCancelled(true);
            ItemStack dropped = event.getItemDrop().getItemStack();
            if (dropped == null || dropped.getType().isAir()) return;

            if (gui.view == 4) {
                // view=4：替换现有战利品的物品
                gui.pendingEditItem = dropped.clone();
                gui.pendingEditItem.setAmount(1);
                plugin.scheduler().runEntity(player, () -> gui.rebuildAndOpen(player), () -> { });
                player.sendMessage("§a已捕获替换物品: §f" + dropped.getType().name());
            } else {
                // view=2：添加新战利品
                gui.pendingItem = dropped.clone();
                gui.pendingItem.setAmount(1);
                plugin.scheduler().runEntity(player, () -> gui.rebuildAndOpen(player), () -> { });
                player.sendMessage("§a已捕获物品: §f" + dropped.getType().name());
            }
        }
    }

    // ================================================================
    // Chat Listener — 新建卡池向导
    // ================================================================

    public static class ChatListener implements org.bukkit.event.Listener {

        private final KsEco plugin;

        public ChatListener(KsEco plugin) { this.plugin = plugin; }

        @EventHandler
        public void onQuit(org.bukkit.event.player.PlayerQuitEvent event) {
            UUID playerId = event.getPlayer().getUniqueId();
            pendingCreate.remove(playerId);
            pendingDrop.remove(playerId);
            pendingPoolNameEdit.remove(playerId);
            pendingPoolRulesEdit.remove(playerId);
        }

        @EventHandler
        public void onChat(AsyncPlayerChatEvent event) {
            UUID playerId = event.getPlayer().getUniqueId();
            boolean hasPending = pendingPoolNameEdit.containsKey(playerId)
                    || pendingPoolRulesEdit.containsKey(playerId) || pendingCreate.containsKey(playerId);
            if (!hasPending) return;

            event.setCancelled(true);
            String msg = event.getMessage().trim();
            plugin.scheduler().runPlayer(playerId, () -> {
                Player player = Bukkit.getPlayer(playerId);
                if (player == null) {
                    pendingPoolNameEdit.remove(playerId);
                    pendingPoolRulesEdit.remove(playerId);
                    pendingCreate.remove(playerId);
                    return;
                }
                if (!player.hasPermission("kseco.admin")) {
                    pendingPoolNameEdit.remove(playerId);
                    pendingPoolRulesEdit.remove(playerId);
                    pendingCreate.remove(playerId);
                    player.sendMessage("§c权限已失效，输入已取消。");
                    return;
                }

                // view=3 池名字编辑
                BlindBoxAdminGui editGui = pendingPoolNameEdit.remove(playerId);
                if (editGui != null) {
                    if (msg.equalsIgnoreCase("cancel")) {
                        player.sendMessage("§c已取消名字修改。");
                    } else if (msg.length() < 2 || msg.length() > 32) {
                        player.sendMessage("§c名字需在2-32字符之间，已取消修改。");
                    } else {
                        editGui.editName = msg;
                        player.sendMessage("§a名字将改为: §f" + msg + " §7（请确认保存）");
                    }
                    editGui.build();
                    player.openInventory(editGui.getInventory());
                    return;
                }

                BlindBoxAdminGui rulesGui = pendingPoolRulesEdit.remove(playerId);
                if (rulesGui != null) {
                    if (msg.equalsIgnoreCase("cancel")) {
                        player.sendMessage("§c已取消保底规则修改。");
                    } else if (msg.equalsIgnoreCase("0") || msg.equalsIgnoreCase("none") || msg.equalsIgnoreCase("clear")) {
                        rulesGui.editPityRules = "";
                        player.sendMessage("§a分级保底规则已清空，将使用 pityMax 兜底（请确认保存）。");
                    } else {
                        String normalized = BlindBoxManager.normalizePityRules(msg, rulesGui.editPityMax);
                        if (normalized.isBlank()) {
                            player.sendMessage("§c规则无效，示例: RARE:50,EPIC:120,LEGENDARY:300");
                        } else {
                            rulesGui.editPityRules = normalized;
                            player.sendMessage("§a分级保底规则将改为: §f" + normalized + " §7（请确认保存）");
                        }
                    }
                    rulesGui.build();
                    player.openInventory(rulesGui.getInventory());
                    return;
                }

                PendingCreatePool pending = pendingCreate.get(playerId);
                if (pending == null) return;
                if (msg.equalsIgnoreCase("cancel")) {
                    pendingCreate.remove(playerId);
                    player.sendMessage("§c已取消新建卡池。");
                    BlindBoxAdminGui gui = new BlindBoxAdminGui(plugin);
                    gui.open(player);
                    return;
                }

                switch (pending.step) {
                    case 1 -> {
                        if (msg.length() < 2 || msg.length() > 32) {
                            player.sendMessage("§c名称需在2-32字符之间，重新输入：");
                            pendingCreate.put(playerId, new PendingCreatePool(1, null, 0, "ITEM"));
                            return;
                        }
                        pendingCreate.put(playerId, new PendingCreatePool(2, msg, 0, "ITEM"));
                        player.sendMessage("§a请输入价格（如 100）：");
                    }
                    case 2 -> {
                        try {
                            double price = Double.parseDouble(msg);
                            if (price <= 0) {
                                player.sendMessage("§c价格必须 > 0，重新输入：");
                                return;
                            }
                            pendingCreate.put(playerId, new PendingCreatePool(3, pending.name, price, "ITEM"));
                            player.sendMessage("§a请输入类型 §fITEM§7/§fMATERIAL§7/§fEQUIPMENT §7（直接回车默认 ITEM）：");
                        } catch (NumberFormatException e) {
                            player.sendMessage("§c无效价格，重新输入：");
                            pendingCreate.put(playerId, new PendingCreatePool(2, pending.name, 0, "ITEM"));
                        }
                    }
                    case 3 -> {
                        String poolType = msg.isEmpty() || msg.equals("-") ? "ITEM" : msg.toUpperCase();
                        if (!poolType.equals("ITEM") && !poolType.equals("MATERIAL") && !poolType.equals("EQUIPMENT")) {
                            poolType = "ITEM";
                        }
                        String poolId = "pool_" + UUID.randomUUID().toString().substring(0, 8);
                        boolean ok = plugin.blindBoxManager().upsertPool(
                                poolId, pending.name, poolType, pending.price,
                                true, 50, "", "PUBLIC", "", "");
                        pendingCreate.remove(playerId);
                        if (ok) {
                            player.sendMessage("§a卡池 「" + pending.name + "」 创建成功！ID: " + poolId);
                            player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_PLING, 0.5f, 2.0f);
                        } else {
                            player.sendMessage("§c创建失败，请稍后重试。");
                        }
                        BlindBoxAdminGui gui = new BlindBoxAdminGui(plugin);
                        gui.open(player);
                    }
                }
            });
        }
    }
}
