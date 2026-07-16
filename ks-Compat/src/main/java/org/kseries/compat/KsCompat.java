package org.kseries.compat;

import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;
import org.jetbrains.annotations.NotNull;
import org.kscore.KsCore;
import org.kscore.KsPluginBridge;
import org.kseries.compat.bot.BotManagerModule;
import org.kseries.compat.fotia.FotiaBridge;
import org.kseries.compat.fotia.FotiaMoneyDurabilityModule;
import org.kseries.compat.service.EconomyBridge;
import org.kseries.compat.vulcan.VulcanArmorStandCompatModule;
import org.kseries.compat.bot.ZombifiedPiglinAggroModule;
import org.kseries.compat.web.CompatWebHandler;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public final class KsCompat extends JavaPlugin {

    private KsCore ksCore;
    private KsPluginBridge bridge;
    private EconomyBridge economyBridge;
    private FotiaBridge fotiaBridge;
    private BotManagerModule botManagerModule;
    private FotiaMoneyDurabilityModule fotiaMoneyDurabilityModule;
    private VulcanArmorStandCompatModule vulcanArmorStandCompatModule;
    private ZombifiedPiglinAggroModule zombifiedPiglinAggroModule;
    private CompatWebHandler webHandler;
    private final List<CompatModule> modules = new ArrayList<>();
    private String route = "/ks-Compat";
    private String webPluginId = "ks-compat";

    @Override
    public void onEnable() {
        saveDefaultConfig();

        Plugin corePlugin = Bukkit.getPluginManager().getPlugin("ks-core");
        if (corePlugin instanceof KsCore core) {
            this.ksCore = core;
            this.bridge = core.bridge();
        } else {
            getLogger().severe("ks-core not found, disabling ks-Compat.");
            Bukkit.getPluginManager().disablePlugin(this);
            return;
        }

        this.economyBridge = new EconomyBridge(this);
        this.fotiaBridge = new FotiaBridge(this);
        this.botManagerModule = new BotManagerModule(this, economyBridge);
        this.fotiaMoneyDurabilityModule = new FotiaMoneyDurabilityModule(this, economyBridge, fotiaBridge, botManagerModule);
        this.vulcanArmorStandCompatModule = new VulcanArmorStandCompatModule(this);
        this.zombifiedPiglinAggroModule = new ZombifiedPiglinAggroModule(this, botManagerModule);

        modules.add(fotiaMoneyDurabilityModule);
        modules.add(botManagerModule);
        modules.add(vulcanArmorStandCompatModule);
        modules.add(zombifiedPiglinAggroModule);
        for (CompatModule module : modules) {
            module.enable();
        }

        if (getCommand("kscompat") != null) getCommand("kscompat").setExecutor(this);
        if (getCommand("ksbot") != null) {
            getCommand("ksbot").setExecutor(botManagerModule);
            getCommand("ksbot").setTabCompleter(botManagerModule);
        }
        if (getCommand("ksbotstorage") != null) {
            getCommand("ksbotstorage").setExecutor(botManagerModule);
            getCommand("ksbotstorage").setTabCompleter(botManagerModule);
        }

        registerWebRoute();
        getLogger().info("ks-Compat enabled with " + modules.size() + " module(s).");
    }

    @Override
    public void onDisable() {
        if (bridge != null) {
            bridge.unregisterRoute(webPluginId);
        }
        for (CompatModule module : modules) {
            module.disable();
        }
    }

    public void reloadAll() {
        reloadConfig();
        economyBridge.refresh();
        fotiaBridge.refresh();
        for (CompatModule module : modules) {
            module.reload();
        }
        registerWebRoute();
    }

    private void registerWebRoute() {
        if (bridge == null || !getConfig().getBoolean("web.enabled", true)) return;
        webPluginId = getConfig().getString("web.plugin-id", "ks-compat");
        route = getConfig().getString("web.route", "/ks-Compat");
        try {
            if (bridge.isPluginRouteEnabled(webPluginId)) {
                route = bridge.getPluginRoute(webPluginId);
            }
        } catch (Exception ignored) {
            // Keep the default route if ks-core has no explicit sub-plugin entry yet.
        }
        if (webHandler == null) {
            webHandler = new CompatWebHandler(this);
        }
        bridge.registerRoute(webPluginId, route, webHandler);
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command,
                             @NotNull String label, @NotNull String[] args) {
        if (!sender.hasPermission("kscompat.admin")) {
            sender.sendMessage("§c权限不足。");
            return true;
        }
        if (args.length == 0 || "status".equalsIgnoreCase(args[0])) {
            sendStatus(sender);
            return true;
        }
        switch (args[0].toLowerCase()) {
            case "reload" -> {
                reloadAll();
                sender.sendMessage("§a[ks-Compat] 已重载。");
            }
            case "web" -> openAdminWeb(sender);
            default -> {
                sender.sendMessage("§e/kscompat status §7- 查看状态");
                sender.sendMessage("§e/kscompat reload §7- 重载配置");
                sender.sendMessage("§e/kscompat web §7- 打开管理网页");
            }
        }
        return true;
    }

    private void sendStatus(CommandSender sender) {
        sender.sendMessage("§6[ks-Compat] §7route: §f" + route);
        sender.sendMessage("§7经济: §f" + economyBridge.providerName() + " §7可用=" + economyBridge.isAvailable());
        sender.sendMessage("§7Fotia: §f" + fotiaBridge.isAvailable());
        for (CompatModule module : modules) {
            sender.sendMessage("§e- " + module.displayName() + " §7" + module.status());
        }
    }

    private void openAdminWeb(CommandSender sender) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage("§c只有玩家可以生成网页链接。");
            return;
        }
        String link = bridge.createWebLink(player, true, route + "/admin");
        player.sendMessage("§a[ks-Compat] 管理网页: " + link);
        player.sendMessage(net.kyori.adventure.text.Component.text("§e点击打开 ks-Compat 管理页")
            .clickEvent(net.kyori.adventure.text.event.ClickEvent.openUrl(link)));
    }

    public KsPluginBridge bridge() { return bridge; }

    public EconomyBridge economyBridge() { return economyBridge; }

    public FotiaBridge fotiaBridge() { return fotiaBridge; }

    public BotManagerModule botManagerModule() { return botManagerModule; }

    public FotiaMoneyDurabilityModule fotiaMoneyDurabilityModule() { return fotiaMoneyDurabilityModule; }

    public String route() { return route; }

    public Map<String, Object> statusSnapshot() {
        java.util.Map<String, Object> out = new java.util.LinkedHashMap<>();
        out.put("route", route);
        out.put("economyProvider", economyBridge.providerName());
        out.put("economyAvailable", economyBridge.isAvailable());
        out.put("fotiaAvailable", fotiaBridge.isAvailable());
        java.util.List<Map<String, Object>> moduleStates = new java.util.ArrayList<>();
        for (CompatModule module : modules) moduleStates.add(module.status());
        out.put("modules", moduleStates);
        return out;
    }
}
