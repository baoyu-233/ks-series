package org.kstitle;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.kstitle.model.TitleAttribute;
import org.kstitle.model.TitleDef;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * 指令结构照抄 PlayerTitle-5.0.5-free.jar 反编译得到的 {@code CommandChildTypeEnum}
 * （父类目 title/player/buff + 动作 add/del/list/desc/require/set/stop/edit），
 * 方便原 PlayerTitle 管理员直接套用习惯用法，只是根命令从 {@code /plt} 换成 {@code /title}：
 *
 * <pre>
 * /title                              — 打开称号 GUI
 * /title web                          — 获取 Web 管理令牌
 * /title title add &lt;显示名...&gt;         — 新建称号（默认 ADMIN_GRANT）
 * /title title del &lt;id&gt;                — 删除称号
 * /title title list                    — 列出全部称号
 * /title title desc &lt;id&gt; &lt;描述...&gt;     — 设置描述
 * /title title require &lt;id&gt; &lt;类型&gt; &lt;值...&gt; — 设为条件解锁（对应 PlayerTitle 的 require）
 * /title title edit &lt;id&gt; &lt;字段&gt; &lt;值...&gt;  — 编辑任意字段（ks-Title 自带扩展，PlayerTitle无对应项）
 * /title player add &lt;玩家&gt; &lt;id&gt;         — 发放称号
 * /title player del &lt;玩家&gt; &lt;id&gt;         — 收回称号
 * /title player set &lt;玩家&gt; &lt;id&gt;         — 强制为在线玩家佩戴
 * /title player stop &lt;玩家&gt;             — 强制摘下在线玩家当前称号
 * /title player list &lt;玩家&gt;             — 查看玩家持有/佩戴情况
 * /title buff add &lt;id&gt; &lt;类型&gt; &lt;key&gt; &lt;amount&gt; — 添加属性加成
 * /title buff del &lt;attrId&gt;             — 删除属性加成
 * /title buff edit &lt;attrId&gt; &lt;amount&gt;   — 修改属性加成数值
 * /title buff list &lt;id&gt;                — 列出称号的属性加成
 * /title frame add|clear|remove ...     — 动画帧管理（ks-Title 自带扩展，PlayerTitle无对应项）
 * </pre>
 *
 * PlayerTitle 里的 card/coin/particle/import/export/position 等在 ks-Title 里没有对应概念
 * （分别是各自的抽卡系统、自建货币、粒子特效、旧版数据导入导出——ks-Title 走 Vault 经济、无粒子系统）。
 */
public final class TitleCommand implements CommandExecutor, TabCompleter {

    private static final int MAX_CARD_AMOUNT = 2_304;

    private final KsTitle plugin;

    public TitleCommand(KsTitle plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        if (args.length == 0) {
            if (!(sender instanceof Player player)) {
                handleHelp(sender);
                return true;
            }
            TitleMenu.open(plugin, player, 0);
            return true;
        }

        String sub = args[0].toLowerCase(Locale.ROOT);
        if ("web".equals(sub)) { handleWeb(sender); return true; }
        if ("help".equals(sub) || "?".equals(sub)) { handleHelp(sender); return true; }

        if (!sender.hasPermission("kstitle.admin")) { sender.sendMessage("§c无权限，用 /title help 查看可用指令"); return true; }
        try {
            switch (sub) {
                case "title" -> handleTitleCategory(sender, args);
                case "player" -> handlePlayerCategory(sender, args);
                case "card" -> handleCardCategory(sender, args);
                case "buff" -> handleBuffCategory(sender, args);
                case "frame" -> handleFrameCategory(sender, args);
                default -> sender.sendMessage("§c未知子命令，用 /title help 查看可用指令");
            }
        } catch (NumberFormatException e) {
            sender.sendMessage("§c参数格式错误: " + e.getMessage());
        } catch (IllegalArgumentException e) {
            sender.sendMessage("§c参数错误: " + e.getMessage());
        }
        return true;
    }

    // ==================== help ====================

    private void handleHelp(CommandSender sender) {
        sender.sendMessage("§6§l===== ks-Title 指令帮助 =====");
        sender.sendMessage("§e/title §7— 打开称号GUI（浏览/佩戴/购买，仅玩家）");
        sender.sendMessage("§e/title web §7— 获取Web管理令牌（需 kstitle.admin）");
        if (!sender.hasPermission("kstitle.admin")) {
            sender.sendMessage("§7(管理类指令需要 kstitle.admin 权限，未显示)");
            return;
        }
        sender.sendMessage("§6[称号定义] §7(照抄 PlayerTitle 习惯: add/del/list/desc/require)");
        sender.sendMessage("§e/title title add <显示名...> §7— 新建称号(默认仅管理员发放)");
        sender.sendMessage("§e/title title copyplt <PLT原ID|all> [新显示名...] §7— 从 PlayerTitle 复制称号模板");
        sender.sendMessage("§e/title title del <id> §7— 删除称号");
        sender.sendMessage("§e/title title list §7— 列出全部称号");
        sender.sendMessage("§e/title title desc <id> <描述...> §7— 设置描述");
        sender.sendMessage("§e/title title require <id> <PERMISSION等类型> <值...> §7— 设为条件解锁");
        sender.sendMessage("§e/title title edit <id> <字段> <值...> §7— 编辑字段(name/desc/category/rarity/price/unlock/visible/enabled)");
        sender.sendMessage("§6[玩家持有/佩戴]");
        sender.sendMessage("§e/title player add <玩家> <id> §7— 发放");
        sender.sendMessage("§e/title player addtemp <玩家> <id> <时长> §7— 发放临时称号");
        sender.sendMessage("§e/title player del <玩家> <id> §7— 收回");
        sender.sendMessage("§e/title player set <玩家> <id> §7— 强制佩戴(需在线)");
        sender.sendMessage("§e/title player stop <玩家> §7— 强制摘下(需在线)");
        sender.sendMessage("§e/title player list <玩家> §7— 查看持有/佩戴情况");
        sender.sendMessage("§6[称号卡]");
        sender.sendMessage("§e/title card give <玩家> <id> <时长> [数量] §7— 发放可右键使用的临时称号卡");
        sender.sendMessage("§6[属性加成]");
        sender.sendMessage("§e/title buff add <id> <POTION|ATTRIBUTE|MYTHICLIB_STAT> <key> <amount> §7— 添加");
        sender.sendMessage("§e/title buff del <attrId> §7— 删除");
        sender.sendMessage("§e/title buff edit <attrId> <amount> §7— 修改数值");
        sender.sendMessage("§e/title buff list <id> §7— 列出称号的全部属性加成");
        sender.sendMessage("§6[动画帧] §7(ks-Title 自带扩展，PlayerTitle无对应项)");
        sender.sendMessage("§e/title frame add <id> <间隔ms> <帧文本...> §7— 追加一帧");
        sender.sendMessage("§e/title frame clear <id> §7— 清空全部帧");
        sender.sendMessage("§e/title frame remove <frameId> §7— 删除单帧");
        sender.sendMessage("§7IA图片故障风格称号生成器 / 绑定图片动画 / 迁移状态查看 请用 §e/title web §7打开管理面板");
    }

    private void handleWeb(CommandSender sender) {
        if (!(sender instanceof Player player)) { sender.sendMessage("§c仅玩家可获取 token"); return; }
        if (!player.hasPermission("kstitle.admin")) { player.sendMessage("§c无权限"); return; }
        try {
            var core = Bukkit.getPluginManager().getPlugin("ks-core");
            if (core == null) { player.sendMessage("§cks-core 未安装，无法生成 Web 链接"); return; }
            var bridge = core.getClass().getMethod("bridge").invoke(core);
            var session = bridge.getClass().getMethod("createToken", UUID.class, String.class, boolean.class)
                .invoke(bridge, player.getUniqueId(), player.getName(), true);
            String token = (String) session.getClass().getField("token").get(session);

            var ksConfig = core.getClass().getMethod("ksConfig").invoke(core);
            String host = (String) ksConfig.getClass().getMethod("getPublicAddress").invoke(ksConfig);
            int port = (int) ksConfig.getClass().getMethod("getPort").invoke(ksConfig);
            String webUrl = "http://" + host + ":" + port + "/ks-Title/admin?token=" + token;

            player.sendMessage(Component.text("[ks-Title] ", TextColor.color(0xFFAA00))
                .append(Component.text("称号管理 Web 令牌:", TextColor.color(0x55FF55))));
            player.sendMessage(Component.text(webUrl, TextColor.color(0x55FFFF), TextDecoration.UNDERLINED)
                .clickEvent(ClickEvent.clickEvent(ClickEvent.Action.OPEN_URL, webUrl)));
        } catch (Exception e) {
            player.sendMessage("§cToken 生成失败: " + e.getMessage());
        }
    }

    // ==================== title 类目 ====================

    private void handleTitleCategory(CommandSender sender, String[] args) {
        if (args.length < 2) { sender.sendMessage("§e用法: /title title <add|copyplt|del|list|desc|require|edit> ..."); return; }
        String action = args[1].toLowerCase(Locale.ROOT);
        switch (action) {
            case "add" -> titleAdd(sender, args);
            case "copyplt", "pltcopy" -> titleCopyPlt(sender, args);
            case "del" -> titleDel(sender, args);
            case "list" -> titleList(sender);
            case "desc" -> titleDesc(sender, args);
            case "require" -> titleRequire(sender, args);
            case "edit" -> titleEdit(sender, args);
            default -> sender.sendMessage("§c未知操作: " + action);
        }
    }

    // /title title add <显示名...>
    private void titleAdd(CommandSender sender, String[] args) {
        if (args.length < 3) { sender.sendMessage("§c用法: /title title add <显示名...>"); return; }
        String displayName = wrapPlainCommandTitle(String.join(" ", java.util.Arrays.copyOfRange(args, 2, args.length)));
        int id = plugin.titleManager().createTitle(displayName, "", "general", "COMMON", 0, "ADMIN_GRANT", "", "");
        sender.sendMessage(id > 0 ? "§a创建成功，ID=" + id + " §7(默认仅管理员发放，价格/条件可用 title edit / title require 调整)" : "§c创建失败");
    }

    static String wrapPlainCommandTitle(String displayName) {
        String value = displayName == null ? "" : displayName.trim();
        if (value.isEmpty()) return value;
        if (hasBracketWrapper(value)) return normalizeCommandBracketWrapper(ensureResetEdges(value));
        return "&r[&r" + value + "&r]&r";
    }

    /** Normalizes only the legacy wrapper emitted by /title title add and edit. */
    static String normalizeCommandBracketWrapper(String value) {
        if (value == null) return "";
        String out = value.trim();
        if (!out.startsWith("&r[") || !out.endsWith("]&r")) return out;
        String inner = out.substring(3, out.length() - 3);
        while (startsWithReset(inner)) inner = inner.substring(2);
        while (endsWithReset(inner)) inner = inner.substring(0, inner.length() - 2);
        return "&r[&r" + inner + "&r]&r";
    }

    private static boolean hasBracketWrapper(String value) {
        String plain = stripLegacyCodes(value).trim();
        return (plain.startsWith("[") && plain.endsWith("]"))
            || (plain.startsWith("【") && plain.endsWith("】"))
            || (plain.startsWith("「") && plain.endsWith("」"))
            || (plain.startsWith("《") && plain.endsWith("》"));
    }

    private static String stripLegacyCodes(String value) {
        return value
            .replaceAll("(?i)&#[0-9a-f]{6}", "")
            .replaceAll("(?i)&x(?:&[0-9a-f]){6}", "")
            .replaceAll("(?i)[&§][0-9a-fk-orx]", "");
    }

    private static String ensureResetEdges(String value) {
        String out = value.trim();
        if (!startsWithReset(out)) out = "&r" + out;
        if (!endsWithReset(out)) out = out + "&r";
        return out;
    }

    private static boolean startsWithReset(String value) {
        return value.length() >= 2
            && (value.charAt(0) == '&' || value.charAt(0) == '§')
            && Character.toLowerCase(value.charAt(1)) == 'r';
    }

    private static boolean endsWithReset(String value) {
        int len = value.length();
        return len >= 2
            && (value.charAt(len - 2) == '&' || value.charAt(len - 2) == '§')
            && Character.toLowerCase(value.charAt(len - 1)) == 'r';
    }

    private void titleCopyPlt(CommandSender sender, String[] args) {
        if (args.length < 3) {
            sender.sendMessage("§c用法: /title title copyplt <PLT原ID|all> [新显示名...]");
            return;
        }
        PlayerTitleTemplateCopier copier = new PlayerTitleTemplateCopier(plugin);
        if ("all".equalsIgnoreCase(args[2])) {
            PlayerTitleTemplateCopier.CopyAllResult result = copier.copyAllMissing();
            if (!result.success()) {
                sender.sendMessage("§c批量复制失败: " + result.message());
                return;
            }
            sender.sendMessage("§aPLT 全量模板复制完成: 新增 " + result.copied()
                + " 个，跳过 " + result.skipped() + " 个，失败 " + result.failed()
                + " 个，属性加成复制 " + result.attributeCount() + " 条");
            return;
        }
        int sourceId = Integer.parseInt(args[2]);
        String overrideName = args.length > 3
            ? wrapPlainCommandTitle(String.join(" ", java.util.Arrays.copyOfRange(args, 3, args.length)))
            : "";
        PlayerTitleTemplateCopier.CopyResult result = copier.copyAsNew(sourceId, overrideName);
        if (!result.success()) {
            sender.sendMessage("§c复制失败: " + result.message());
            return;
        }
        sender.sendMessage("§a已从 PlayerTitle #" + sourceId + " 复制为新称号 ID=" + result.newTitleId()
            + " §7(属性加成复制 " + result.attributeCount() + " 条)");
        sender.sendMessage("§7下一步可用: /title title edit " + result.newTitleId() + " name <新显示名>");
    }

    private void titleDel(CommandSender sender, String[] args) {
        if (args.length < 3) { sender.sendMessage("§c用法: /title title del <id>"); return; }
        int id = Integer.parseInt(args[2]);
        sender.sendMessage(plugin.titleManager().deleteTitle(id) ? "§a已删除" : "§c删除失败");
    }

    private void titleList(CommandSender sender) {
        var defs = plugin.titleManager().listDefs();
        sender.sendMessage("§6[ks-Title] §7共 " + defs.size() + " 个称号定义:");
        for (TitleDef d : defs) {
            sender.sendMessage("§7#" + d.id() + " " + LegacyText.colorize(d.displayName()) + " §8[" + d.unlockType() + "]");
        }
    }

    // /title title desc <id> <描述...>
    private void titleDesc(CommandSender sender, String[] args) {
        if (args.length < 4) { sender.sendMessage("§c用法: /title title desc <id> <描述...>"); return; }
        int id = Integer.parseInt(args[2]);
        TitleDef d = plugin.titleManager().getDef(id);
        if (d == null) { sender.sendMessage("§c未找到称号 #" + id); return; }
        String desc = String.join(" ", java.util.Arrays.copyOfRange(args, 3, args.length));
        boolean ok = plugin.titleManager().updateTitle(id, d.displayName(), desc, d.category(), d.rarity(), d.price(),
            d.unlockType(), d.conditionType(), d.conditionValue(), d.visible(), d.enabled());
        sender.sendMessage(ok ? "§a已更新描述" : "§c更新失败");
    }

    // /title title require <id> <类型> <条件值...>  —— 对应 PlayerTitle 的 require：设为条件解锁
    private void titleRequire(CommandSender sender, String[] args) {
        if (args.length < 5) { sender.sendMessage("§c用法: /title title require <id> <类型> <条件值...>"); return; }
        int id = Integer.parseInt(args[2]);
        TitleDef d = plugin.titleManager().getDef(id);
        if (d == null) { sender.sendMessage("§c未找到称号 #" + id); return; }
        String conditionType = args[3].toUpperCase(Locale.ROOT);
        String conditionValue = String.join(" ", java.util.Arrays.copyOfRange(args, 4, args.length));
        boolean ok = plugin.titleManager().updateTitle(id, d.displayName(), d.description(), d.category(), d.rarity(),
            d.price(), "CONDITION", conditionType, conditionValue, d.visible(), d.enabled());
        sender.sendMessage(ok ? "§a已设为条件解锁: " + conditionType + "=" + conditionValue : "§c更新失败");
    }

    // /title title edit <id> <name|desc|category|rarity|price|unlock|visible|enabled> <值...>  —— ks-Title 自带扩展
    private void titleEdit(CommandSender sender, String[] args) {
        if (args.length < 5) {
            sender.sendMessage("§c用法: /title title edit <id> <name|desc|category|rarity|price|unlock|visible|enabled> <值...>");
            return;
        }
        int id = Integer.parseInt(args[2]);
        TitleDef d = plugin.titleManager().getDef(id);
        if (d == null) { sender.sendMessage("§c未找到称号 #" + id); return; }
        String field = args[3].toLowerCase(Locale.ROOT);
        String value = String.join(" ", java.util.Arrays.copyOfRange(args, 4, args.length));

        String displayName = d.displayName(), description = d.description(), category = d.category(), rarity = d.rarity();
        double price = d.price();
        String unlockType = d.unlockType();
        boolean visible = d.visible(), enabled = d.enabled();

        switch (field) {
            case "name" -> displayName = wrapPlainCommandTitle(value);
            case "desc", "description" -> description = value;
            case "category" -> category = value;
            case "rarity" -> rarity = value.toUpperCase(Locale.ROOT);
            case "price" -> price = Double.parseDouble(value);
            case "unlock", "unlocktype" -> unlockType = value.toUpperCase(Locale.ROOT);
            case "visible" -> visible = Boolean.parseBoolean(value);
            case "enabled" -> enabled = Boolean.parseBoolean(value);
            default -> { sender.sendMessage("§c未知字段: " + field); return; }
        }
        boolean ok = plugin.titleManager().updateTitle(id, displayName, description, category, rarity, price,
            unlockType, d.conditionType(), d.conditionValue(), visible, enabled);
        sender.sendMessage(ok ? "§a已更新称号 #" + id + " 的 " + field : "§c更新失败");
    }

    // ==================== player 类目 ====================

    private void handlePlayerCategory(CommandSender sender, String[] args) {
        if (args.length < 2) { sender.sendMessage("§e用法: /title player <add|addtemp|del|set|stop|list> ..."); return; }
        String action = args[1].toLowerCase(Locale.ROOT);
        switch (action) {
            case "add" -> playerAdd(sender, args);
            case "addtemp" -> playerAddTemp(sender, args);
            case "del" -> playerDel(sender, args);
            case "set" -> playerSet(sender, args);
            case "stop" -> playerStop(sender, args);
            case "list" -> playerList(sender, args);
            default -> sender.sendMessage("§c未知操作: " + action);
        }
    }

    // /title player add <玩家> <id>  —— 发放
    private void playerAdd(CommandSender sender, String[] args) {
        if (args.length < 4) { sender.sendMessage("§c用法: /title player add <玩家> <id>"); return; }
        OfflinePlayer target = Bukkit.getOfflinePlayer(args[2]);
        int id = Integer.parseInt(args[3]);
        boolean ok = plugin.titleManager().grantOwnership(target.getUniqueId(), id, "ADMIN");
        sender.sendMessage(ok ? "§a已发放称号 #" + id + " 给 " + args[2] : "§c发放失败(可能已持有)");
    }

    // /title player addtemp <玩家> <id> <时长>
    private void playerAddTemp(CommandSender sender, String[] args) {
        if (args.length < 5) { sender.sendMessage("§c用法: /title player addtemp <玩家> <id> <时长>"); return; }
        OfflinePlayer target = Bukkit.getOfflinePlayer(args[2]);
        int id = Integer.parseInt(args[3]);
        long duration = DurationParser.parseMillis(args[4]);
        var result = plugin.titleManager().grantTemporaryOwnership(target.getUniqueId(), id, duration, "ADMIN_TEMP");
        sender.sendMessage(result.success()
            ? "§a已发放临时称号 #" + id + " 给 " + args[2] + " §7到期: §f" + DurationParser.formatExpiry(result.expiresAt())
            : "§c临时发放失败: " + result.message());
    }

    // /title player del <玩家> <id>  —— 收回
    private void playerDel(CommandSender sender, String[] args) {
        if (args.length < 4) { sender.sendMessage("§c用法: /title player del <玩家> <id>"); return; }
        OfflinePlayer target = Bukkit.getOfflinePlayer(args[2]);
        int id = Integer.parseInt(args[3]);
        boolean ok = plugin.titleManager().revokeOwnership(target.getUniqueId(), id);
        sender.sendMessage(ok ? "§a已收回称号 #" + id : "§c收回失败(可能未持有)");
    }

    // /title player set <玩家> <id>  —— 强制为在线玩家佩戴
    private void playerSet(CommandSender sender, String[] args) {
        if (args.length < 4) { sender.sendMessage("§c用法: /title player set <玩家> <id>"); return; }
        Player target = Bukkit.getPlayer(args[2]);
        if (target == null) { sender.sendMessage("§c玩家不在线"); return; }
        int id = Integer.parseInt(args[3]);
        if (!plugin.titleManager().hasOwnership(target.getUniqueId(), id)) {
            plugin.titleManager().grantOwnership(target.getUniqueId(), id, "ADMIN");
        }
        boolean ok = plugin.titleManager().equip(target, id);
        sender.sendMessage(ok ? "§a已为 " + target.getName() + " 强制佩戴称号 #" + id : "§c操作失败");
    }

    // /title player stop <玩家>  —— 强制摘下
    private void playerStop(CommandSender sender, String[] args) {
        if (args.length < 3) { sender.sendMessage("§c用法: /title player stop <玩家>"); return; }
        Player target = Bukkit.getPlayer(args[2]);
        if (target == null) { sender.sendMessage("§c玩家不在线"); return; }
        boolean ok = plugin.titleManager().unequip(target);
        sender.sendMessage(ok ? "§a已摘下 " + target.getName() + " 的称号" : "§c该玩家当前未佩戴任何称号");
    }

    // /title player list <玩家>  —— 查看持有/佩戴
    private void playerList(CommandSender sender, String[] args) {
        if (args.length < 3) { sender.sendMessage("§c用法: /title player list <玩家>"); return; }
        OfflinePlayer target = Bukkit.getOfflinePlayer(args[2]);
        var owned = plugin.titleManager().listOwned(target.getUniqueId());
        Integer equipped = plugin.titleManager().getEquipped(target.getUniqueId());
        sender.sendMessage("§6[ks-Title] §7" + args[2] + " 持有:");
        if (owned.isEmpty()) {
            sender.sendMessage("§8- 无");
        } else {
            for (int id : owned) {
                long expiresAt = plugin.titleManager().getOwnershipExpiresAt(target.getUniqueId(), id);
                String expireText = expiresAt <= 0L ? "永久" : ("到期 " + DurationParser.formatExpiry(expiresAt));
                sender.sendMessage("§7- #" + id + " §8(" + expireText + ")");
            }
        }
        sender.sendMessage("§7佩戴中: §f" + (equipped == null ? "无" : ("#" + equipped)));
    }

    // ==================== card 类目 ====================

    private void handleCardCategory(CommandSender sender, String[] args) {
        if (args.length < 2) { sender.sendMessage("§e用法: /title card <give> ..."); return; }
        String action = args[1].toLowerCase(Locale.ROOT);
        switch (action) {
            case "give" -> cardGive(sender, args);
            default -> sender.sendMessage("§c未知操作: " + action);
        }
    }

    // /title card give <玩家> <id> <时长> [数量]
    private void cardGive(CommandSender sender, String[] args) {
        if (args.length < 5) { sender.sendMessage("§c用法: /title card give <玩家> <id> <时长> [数量]"); return; }
        Player target = Bukkit.getPlayer(args[2]);
        if (target == null) { sender.sendMessage("§c玩家不在线"); return; }
        int id = Integer.parseInt(args[3]);
        if (plugin.titleManager().getDef(id) == null) { sender.sendMessage("§c称号不存在: #" + id); return; }
        long duration = DurationParser.parseMillis(args[4]);
        int amount = args.length >= 6 ? Integer.parseInt(args[5]) : 1;
        if (amount < 1 || amount > MAX_CARD_AMOUNT) {
            throw new IllegalArgumentException("amount must be between 1 and " + MAX_CARD_AMOUNT);
        }

        int remaining = amount;
        while (remaining > 0) {
            int stackAmount = Math.min(64, remaining);
            var leftovers = target.getInventory().addItem(plugin.titleCardFactory().create(id, duration, stackAmount));
            for (var leftover : leftovers.values()) {
                target.getWorld().dropItemNaturally(target.getLocation(), leftover);
            }
            remaining -= stackAmount;
        }
        sender.sendMessage("§a已发放称号临时卡 #" + id + " x" + amount + " 给 " + target.getName()
            + " §7时长: §f" + DurationParser.formatDuration(duration));
    }

    // ==================== buff 类目 ====================

    private void handleBuffCategory(CommandSender sender, String[] args) {
        if (args.length < 2) { sender.sendMessage("§e用法: /title buff <add|del|edit|list> ..."); return; }
        String action = args[1].toLowerCase(Locale.ROOT);
        switch (action) {
            case "add" -> buffAdd(sender, args);
            case "del" -> buffDel(sender, args);
            case "edit" -> buffEdit(sender, args);
            case "list" -> buffList(sender, args);
            default -> sender.sendMessage("§c未知操作: " + action);
        }
    }

    // /title buff add <id> <POTION|ATTRIBUTE|MYTHICLIB_STAT> <key> <amount>
    private void buffAdd(CommandSender sender, String[] args) {
        if (args.length < 6) { sender.sendMessage("§c用法: /title buff add <id> <类型> <key> <amount>"); return; }
        int id = Integer.parseInt(args[2]);
        String buffType = args[3].toUpperCase(Locale.ROOT);
        String key = args[4];
        double amount = Double.parseDouble(args[5]);
        int attrId = plugin.titleManager().addAttribute(id, buffType, key, amount, "");
        sender.sendMessage(attrId > 0 ? "§a已添加属性 #" + attrId : "§c添加失败");
    }

    // /title buff del <attrId>
    private void buffDel(CommandSender sender, String[] args) {
        if (args.length < 3) { sender.sendMessage("§c用法: /title buff del <attrId>"); return; }
        boolean ok = plugin.titleManager().removeAttribute(Integer.parseInt(args[2]));
        sender.sendMessage(ok ? "§a已删除属性" : "§c未找到该属性");
    }

    // /title buff edit <attrId> <amount>
    private void buffEdit(CommandSender sender, String[] args) {
        if (args.length < 4) { sender.sendMessage("§c用法: /title buff edit <attrId> <amount>"); return; }
        int attrId = Integer.parseInt(args[2]);
        double amount = Double.parseDouble(args[3]);
        boolean ok = plugin.titleManager().updateAttributeAmount(attrId, amount);
        sender.sendMessage(ok ? "§a已更新属性数值" : "§c未找到该属性");
    }

    // /title buff list <id>
    private void buffList(CommandSender sender, String[] args) {
        if (args.length < 3) { sender.sendMessage("§c用法: /title buff list <id>"); return; }
        int id = Integer.parseInt(args[2]);
        var attrs = plugin.titleManager().listAttributes(id);
        if (attrs.isEmpty()) { sender.sendMessage("§7该称号无属性加成"); return; }
        for (TitleAttribute a : attrs) sender.sendMessage("§7#" + a.id() + " " + a.buffType() + " " + a.buffKey() + "=" + a.amount());
    }

    // ==================== frame 类目（ks-Title 自带扩展，PlayerTitle无对应项） ====================

    private void handleFrameCategory(CommandSender sender, String[] args) {
        if (args.length < 2) { sender.sendMessage("§e用法: /title frame <add|clear|remove> ..."); return; }
        String action = args[1].toLowerCase(Locale.ROOT);
        switch (action) {
            case "add" -> frameAdd(sender, args);
            case "clear" -> frameClear(sender, args);
            case "remove" -> frameRemove(sender, args);
            default -> sender.sendMessage("§c未知操作: " + action);
        }
    }

    // /title frame add <id> <间隔ms> <帧文本...>
    private void frameAdd(CommandSender sender, String[] args) {
        if (args.length < 5) { sender.sendMessage("§c用法: /title frame add <id> <间隔ms> <帧文本...>"); return; }
        int id = Integer.parseInt(args[2]);
        int interval = Integer.parseInt(args[3]);
        String text = String.join(" ", java.util.Arrays.copyOfRange(args, 4, args.length));
        int nextIndex = plugin.titleManager().listFrames(id).size();
        boolean ok = plugin.titleManager().addFrame(id, nextIndex, text, interval);
        sender.sendMessage(ok ? "§a已添加第 " + nextIndex + " 帧" : "§c添加失败");
    }

    private void frameClear(CommandSender sender, String[] args) {
        if (args.length < 3) { sender.sendMessage("§c用法: /title frame clear <id>"); return; }
        plugin.titleManager().clearFrames(Integer.parseInt(args[2]));
        sender.sendMessage("§a已清空动画帧");
    }

    private void frameRemove(CommandSender sender, String[] args) {
        if (args.length < 3) { sender.sendMessage("§c用法: /title frame remove <frameId>"); return; }
        boolean ok = plugin.titleManager().removeFrame(Integer.parseInt(args[2]));
        sender.sendMessage(ok ? "§a已删除该帧" : "§c未找到该帧");
    }

    // ==================== Tab 补全 ====================

    private static final List<String> TITLE_ACTIONS = List.of("add", "copyplt", "del", "list", "desc", "require", "edit");
    private static final List<String> PLAYER_ACTIONS = List.of("add", "addtemp", "del", "set", "stop", "list");
    private static final List<String> CARD_ACTIONS = List.of("give");
    private static final List<String> BUFF_ACTIONS = List.of("add", "del", "edit", "list");
    private static final List<String> FRAME_ACTIONS = List.of("add", "clear", "remove");
    private static final List<String> EDIT_FIELDS = List.of("name", "desc", "category", "rarity", "price", "unlock", "visible", "enabled");
    private static final List<String> BUFF_TYPES = List.of("POTION", "ATTRIBUTE", "MYTHICLIB_STAT");
    private static final List<String> DURATION_EXAMPLES = List.of("30m", "1h", "12h", "1d", "7d", "1w");

    @Override
    public List<String> onTabComplete(CommandSender sender, Command cmd, String alias, String[] args) {
        boolean admin = sender.hasPermission("kstitle.admin");
        if (args.length == 1) {
            List<String> top = new ArrayList<>(List.of("web", "help"));
            if (admin) top.addAll(List.of("title", "player", "card", "buff", "frame"));
            return filter(top, args[0]);
        }
        if (!admin) return List.of();

        String category = args[0].toLowerCase(Locale.ROOT);
        String action = args.length > 1 ? args[1].toLowerCase(Locale.ROOT) : "";

        if (args.length == 2) {
            return switch (category) {
                case "title" -> filter(TITLE_ACTIONS, args[1]);
                case "player" -> filter(PLAYER_ACTIONS, args[1]);
                case "card" -> filter(CARD_ACTIONS, args[1]);
                case "buff" -> filter(BUFF_ACTIONS, args[1]);
                case "frame" -> filter(FRAME_ACTIONS, args[1]);
                default -> List.of();
            };
        }

        if (args.length == 3) {
            if ("player".equals(category)) return filter(onlinePlayerNames(), args[2]);
            if ("card".equals(category) && "give".equals(action)) return filter(onlinePlayerNames(), args[2]);
            if ("title".equals(category) && List.of("copyplt", "pltcopy").contains(action)) return filter(new PlayerTitleTemplateCopier(plugin).listSourceTitleIds(), args[2]);
            if ("title".equals(category) && List.of("del", "desc", "require", "edit").contains(action)) return filter(titleIdStrings(), args[2]);
            if ("buff".equals(category) && List.of("add", "list").contains(action)) return filter(titleIdStrings(), args[2]);
            if ("frame".equals(category) && List.of("add", "clear").contains(action)) return filter(titleIdStrings(), args[2]);
        }

        if (args.length == 4) {
            if ("title".equals(category) && "edit".equals(action)) return filter(EDIT_FIELDS, args[3]);
            if ("title".equals(category) && "require".equals(action)) return filter(List.of("PERMISSION"), args[3]);
            if ("player".equals(category) && List.of("add", "addtemp", "del", "set").contains(action)) return filter(titleIdStrings(), args[3]);
            if ("card".equals(category) && "give".equals(action)) return filter(titleIdStrings(), args[3]);
            if ("buff".equals(category) && "add".equals(action)) return filter(BUFF_TYPES, args[3]);
        }

        if (args.length == 5) {
            if ("player".equals(category) && "addtemp".equals(action)) return filter(DURATION_EXAMPLES, args[4]);
            if ("card".equals(category) && "give".equals(action)) return filter(DURATION_EXAMPLES, args[4]);
        }

        if (args.length == 6) {
            if ("card".equals(category) && "give".equals(action)) return filter(List.of("1", "8", "16", "32", "64"), args[5]);
        }

        return List.of();
    }

    private List<String> onlinePlayerNames() {
        return Bukkit.getOnlinePlayers().stream().map(Player::getName).collect(Collectors.toList());
    }

    private List<String> titleIdStrings() {
        return plugin.titleManager().listDefs().stream().map(d -> String.valueOf(d.id())).collect(Collectors.toList());
    }

    private List<String> filter(List<String> options, String typed) {
        String lower = typed.toLowerCase(Locale.ROOT);
        return options.stream().filter(o -> o.toLowerCase(Locale.ROOT).startsWith(lower)).collect(Collectors.toList());
    }
}
