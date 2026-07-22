package com.steal;

import io.papermc.paper.registry.RegistryAccess;
import io.papermc.paper.registry.RegistryKey;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.entity.EntityShootBowEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.projectiles.ProjectileSource;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * ItemSteal —— 通用的"夺取/归还兵刃"辅助插件。
 *
 * 谁(thief)从谁(owner)那里夺走了哪些物品,都记在 thief 名下(无损,保留附魔/NBT)。
 * 当 thief 死亡(怪物或玩家皆可)时,所有被它夺走的物品自动归还给原主。
 *
 * - Boss 用法: MythicMobs 用 command 机制调用 /itemsteal steal <caster.uuid> <target.uuid>
 * - 玩家用法: /itemsteal givebow <玩家> 发一把一次性"窃魂之弓",射中玩家即夺取;被击杀即归还。
 */
public class ItemSteal extends JavaPlugin implements Listener {

    /** thiefUUID -> 它夺走的物品(含原主) */
    private final Map<UUID, List<Stolen>> stolen = new HashMap<>();
    /** ownerUUID -> 待离线归还的物品 */
    private final Map<UUID, List<ItemStack>> pending = new HashMap<>();

    private List<String> suffixes = new ArrayList<>();
    private NamespacedKey bowKey;
    private NamespacedKey arrowKey;
    private File storeFile;

    private record Stolen(UUID owner, ItemStack item) {}

    @Override
    public void onEnable() {
        saveDefaultConfig();
        reloadSuffixes();
        bowKey = new NamespacedKey(this, "steal_bow");
        arrowKey = new NamespacedKey(this, "steal_arrow");
        storeFile = new File(getDataFolder(), "store.yml");
        load();
        getServer().getPluginManager().registerEvents(this, this);
        getLogger().info("ItemSteal 已启用。匹配后缀: " + suffixes);
    }

    @Override
    public void onDisable() {
        save();
    }

    // ============ 匹配 ============
    private boolean matches(ItemStack it) {
        if (it == null || it.getType() == Material.AIR) return false;
        String name = it.getType().name();
        for (String s : suffixes) {
            if (name.endsWith(s.toUpperCase(Locale.ROOT))) return true;
        }
        return false;
    }

    private void reloadSuffixes() {
        List<String> configured = getConfig().getStringList("suffixes");
        List<String> sanitized = configured.stream()
                .filter(java.util.Objects::nonNull)
                .map(String::trim)
                .filter(value -> !value.isEmpty())
                .map(value -> value.toUpperCase(Locale.ROOT))
                .distinct()
                .toList();
        suffixes = sanitized.isEmpty()
                ? new ArrayList<>(Arrays.asList("_SWORD", "_AXE", "SHIELD", "TRIDENT"))
                : new ArrayList<>(sanitized);
    }

    // ============ 夺取(无损) ============
    private int doSteal(UUID thiefId, Player victim) {
        PlayerInventory inv = victim.getInventory();
        List<Stolen> bag = stolen.computeIfAbsent(thiefId, k -> new ArrayList<>());
        int count = 0;

        ItemStack[] storage = inv.getStorageContents(); // 36 格主背包+快捷栏
        for (int i = 0; i < storage.length; i++) {
            if (matches(storage[i])) {
                bag.add(new Stolen(victim.getUniqueId(), storage[i].clone()));
                storage[i] = null;
                count++;
            }
        }
        inv.setStorageContents(storage);

        ItemStack off = inv.getItemInOffHand(); // 副手(盾通常在这)
        if (matches(off)) {
            bag.add(new Stolen(victim.getUniqueId(), off.clone()));
            inv.setItemInOffHand(null);
            count++;
        }

        if (count > 0) {
            victim.sendMessage("§c你的 " + count + " 件兵刃被夺走了!击杀夺取者即可取回。");
            victim.updateInventory();
        } else {
            // 没东西可拿就不留空记录
            if (bag.isEmpty()) stolen.remove(thiefId);
        }
        save();
        return count;
    }

    // ============ 归还 ============
    private void returnItems(UUID thiefId) {
        List<Stolen> bag = stolen.remove(thiefId);
        if (bag == null || bag.isEmpty()) return;
        for (Stolen s : bag) giveBack(s.owner(), s.item());
        save();
    }

    private void giveBack(UUID ownerId, ItemStack item) {
        Player p = Bukkit.getPlayer(ownerId);
        if (p != null && p.isOnline()) {
            Map<Integer, ItemStack> left = p.getInventory().addItem(item);
            for (ItemStack l : left.values()) p.getWorld().dropItemNaturally(p.getLocation(), l);
            p.sendMessage("§a你的兵刃已归还!");
        } else {
            pending.computeIfAbsent(ownerId, k -> new ArrayList<>()).add(item);
        }
    }

    private void flushPending(Player p) {
        List<ItemStack> list = pending.remove(p.getUniqueId());
        if (list == null || list.isEmpty()) return;
        for (ItemStack it : list) {
            Map<Integer, ItemStack> left = p.getInventory().addItem(it);
            for (ItemStack l : left.values()) p.getWorld().dropItemNaturally(p.getLocation(), l);
        }
        p.sendMessage("§a上次被夺走的兵刃已归还!");
        save();
    }

    // ============ 事件 ============

    // 一次性"窃魂之弓":射出后给弹射物打标记,并让弓自毁
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onShoot(EntityShootBowEvent e) {
        ItemStack bow = e.getBow();
        if (bow == null) return;
        ItemMeta meta = bow.getItemMeta();
        if (meta == null || !meta.getPersistentDataContainer().has(bowKey, PersistentDataType.BYTE)) return;

        e.getProjectile().getPersistentDataContainer().set(arrowKey, PersistentDataType.BYTE, (byte) 1);

        if (e.getEntity() instanceof Player p) {
            consumeStealBow(p, e.getHand());
            p.sendMessage("§7窃魂之弓已碎裂。");
        }
    }

    private void consumeStealBow(Player player, EquipmentSlot hand) {
        PlayerInventory inventory = player.getInventory();
        ItemStack current = hand == EquipmentSlot.OFF_HAND
                ? inventory.getItemInOffHand()
                : inventory.getItemInMainHand();
        ItemMeta meta = current.getItemMeta();
        if (meta == null || !meta.getPersistentDataContainer().has(bowKey, PersistentDataType.BYTE)) return;

        ItemStack replacement = null;
        if (current.getAmount() > 1) {
            replacement = current.clone();
            replacement.setAmount(current.getAmount() - 1);
        }
        if (hand == EquipmentSlot.OFF_HAND) inventory.setItemInOffHand(replacement);
        else inventory.setItemInMainHand(replacement);
    }

    // 被标记的弹射物命中玩家 -> 夺取
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onHitPrepare(EntityDamageByEntityEvent e) {
        if (!(e.getDamager() instanceof Projectile proj)) return;
        if (!proj.getPersistentDataContainer().has(arrowKey, PersistentDataType.BYTE)) return;
        if (!(e.getEntity() instanceof Player)) return;
        e.setDamage(0);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onHitCommit(EntityDamageByEntityEvent e) {
        if (!(e.getDamager() instanceof Projectile proj)) return;
        if (!proj.getPersistentDataContainer().has(arrowKey, PersistentDataType.BYTE)) return;
        if (!(e.getEntity() instanceof Player victim)) return;

        ProjectileSource src = proj.getShooter();
        if (!(src instanceof Entity thiefEnt)) { proj.remove(); return; }

        int n = doSteal(thiefEnt.getUniqueId(), victim);
        proj.remove();

        if (src instanceof Player tp) {
            tp.sendMessage("§a你夺走了 " + victim.getName() + " 的 " + n + " 件兵刃!被对方击杀即归还。");
        }
    }

    // thief 死亡(PlayerDeathEvent 也是 EntityDeathEvent 的子类) -> 归还
    @EventHandler
    public void onDeath(EntityDeathEvent e) {
        returnItems(e.getEntity().getUniqueId());
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        flushPending(e.getPlayer());
    }

    // ============ 命令 ============
    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        if (args.length == 0) {
            sender.sendMessage("用法: /itemsteal steal|givebow|return|reload");
            return true;
        }
        switch (args[0].toLowerCase(Locale.ROOT)) {
            case "steal": {
                if (!sender.hasPermission("itemsteal.admin")) {
                    sender.sendMessage("No permission");
                    return true;
                }
                // 由 MythicMobs 后台调用: /itemsteal steal <thiefUUID> <victimUUID>
                if (args.length < 3) { sender.sendMessage("用法: /itemsteal steal <thiefUUID> <victimUUID>"); return true; }
                UUID thief, vic;
                try { thief = UUID.fromString(args[1]); vic = UUID.fromString(args[2]); }
                catch (IllegalArgumentException ex) { sender.sendMessage("UUID 无效"); return true; }
                Player victim = Bukkit.getPlayer(vic);
                if (victim != null) doSteal(thief, victim);
                return true;
            }
            case "givebow": {
                if (!sender.hasPermission("itemsteal.admin")) { sender.sendMessage("无权限"); return true; }
                if (args.length < 2) { sender.sendMessage("用法: /itemsteal givebow <玩家>"); return true; }
                Player p = Bukkit.getPlayer(args[1]);
                if (p == null) { sender.sendMessage("玩家不在线"); return true; }
                p.getInventory().addItem(makeBow());
                p.getInventory().addItem(new ItemStack(Material.ARROW, 1));
                sender.sendMessage("§a已发放窃魂之弓给 " + p.getName());
                return true;
            }
            case "return": {
                if (!sender.hasPermission("itemsteal.admin")) { sender.sendMessage("无权限"); return true; }
                if (args.length < 2) { sender.sendMessage("用法: /itemsteal return <thiefUUID>"); return true; }
                try { returnItems(UUID.fromString(args[1])); sender.sendMessage("§a已强制归还。"); }
                catch (IllegalArgumentException ex) { sender.sendMessage("UUID 无效"); }
                return true;
            }
            case "reload": {
                if (!sender.hasPermission("itemsteal.admin")) { sender.sendMessage("无权限"); return true; }
                reloadConfig();
                reloadSuffixes();
                sender.sendMessage("§a已重载,后缀: " + suffixes);
                return true;
            }
            default:
                sender.sendMessage("未知子命令");
                return true;
        }
    }

    private ItemStack makeBow() {
        ItemStack bow = new ItemStack(Material.BOW);
        ItemMeta m = bow.getItemMeta();
        m.displayName(Component.text("窃魂之弓", NamedTextColor.GOLD, TextDecoration.BOLD));
        m.lore(Arrays.asList(
                Component.text("射中玩家可夺走其剑/斧/盾", NamedTextColor.GRAY),
                Component.text("一次性 · 射出后碎裂", NamedTextColor.GRAY),
                Component.text("被你击杀的玩家将取回兵刃", NamedTextColor.GRAY)));
        m.addEnchant(RegistryAccess.registryAccess().getRegistry(RegistryKey.ENCHANTMENT)
                .get(NamespacedKey.minecraft("infinity")), 1, true);
        m.getPersistentDataContainer().set(bowKey, PersistentDataType.BYTE, (byte) 1);
        bow.setItemMeta(m);
        return bow;
    }

    // ============ 持久化(防重启/崩溃丢装备) ============
    private void save() {
        YamlConfiguration y = new YamlConfiguration();
        for (Map.Entry<UUID, List<Stolen>> en : stolen.entrySet()) {
            int i = 0;
            for (Stolen s : en.getValue()) {
                String base = "stolen." + en.getKey() + "." + i;
                y.set(base + ".owner", s.owner().toString());
                y.set(base + ".item", s.item());
                i++;
            }
        }
        for (Map.Entry<UUID, List<ItemStack>> en : pending.entrySet()) {
            int i = 0;
            for (ItemStack it : en.getValue()) {
                y.set("pending." + en.getKey() + "." + i, it);
                i++;
            }
        }
        try {
            if (!getDataFolder().exists()) getDataFolder().mkdirs();
            y.save(storeFile);
        } catch (Exception ex) {
            getLogger().warning("保存 store.yml 失败: " + ex.getMessage());
        }
    }

    private void load() {
        if (!storeFile.exists()) return;
        YamlConfiguration y = YamlConfiguration.loadConfiguration(storeFile);
        ConfigurationSection stolenSec = y.getConfigurationSection("stolen");
        if (stolenSec != null) {
            for (String thief : stolenSec.getKeys(false)) {
                List<Stolen> bag = new ArrayList<>();
                ConfigurationSection sec = stolenSec.getConfigurationSection(thief);
                if (sec == null) continue;
                for (String idx : sec.getKeys(false)) {
                    UUID owner = UUID.fromString(sec.getString(idx + ".owner"));
                    ItemStack it = sec.getItemStack(idx + ".item");
                    if (it != null) bag.add(new Stolen(owner, it));
                }
                if (!bag.isEmpty()) stolen.put(UUID.fromString(thief), bag);
            }
        }
        ConfigurationSection pendSec = y.getConfigurationSection("pending");
        if (pendSec != null) {
            for (String owner : pendSec.getKeys(false)) {
                List<ItemStack> list = new ArrayList<>();
                ConfigurationSection sec = pendSec.getConfigurationSection(owner);
                if (sec == null) continue;
                for (String idx : sec.getKeys(false)) {
                    ItemStack it = sec.getItemStack(idx);
                    if (it != null) list.add(it);
                }
                if (!list.isEmpty()) pending.put(UUID.fromString(owner), list);
            }
        }
    }
}
