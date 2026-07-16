package org.kseries.rpggui;

import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.PluginCommand;
import org.bukkit.entity.Player;
import org.bukkit.plugin.RegisteredServiceProvider;
import org.bukkit.plugin.java.JavaPlugin;
import org.kseries.rpg.api.RpgContentApi;
import org.kseries.rpg.api.RpgProgressionApi;

import java.io.File;
import java.util.Optional;
import java.util.UUID;

public final class KsRpgGui extends JavaPlugin {
    private MenuLayout layout;

    @Override
    public void onEnable() {
        saveResource("menu.yml", false);
        try {
            layout = MenuLayout.load(new File(getDataFolder(), "menu.yml"));
        } catch (Exception ex) {
            throw new IllegalStateException("Unable to load menu.yml", ex);
        }
        PluginCommand command = getCommand("rpgmenu");
        if (command == null) throw new IllegalStateException("rpgmenu command missing from plugin.yml");
        command.setExecutor(this::onCommand);
        getServer().getPluginManager().registerEvents(new RpgMenuListener(), this);
    }

    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length > 0 && args[0].equalsIgnoreCase("reload")) {
            if (!sender.hasPermission("ksrpggui.admin")) {
                sender.sendMessage("§c你没有重载 RPG 面板配置的权限。");
                return true;
            }
            UUID requester = sender instanceof Player player ? player.getUniqueId() : null;
            reloadMenuAsync(requester, requester != null);
            sender.sendMessage("§e正在重载 RPG 面板配置。");
            return true;
        }
        if (!(sender instanceof Player player)) {
            sender.sendMessage("§c只有玩家可以打开 RPG 面板。");
            return true;
        }
        if (!player.hasPermission("ksrpggui.use")) {
            player.sendMessage("§c你没有打开 RPG 面板的权限。");
            return true;
        }
        openMain(player);
        return true;
    }

    void openMain(Player player) {
        api().ifPresentOrElse(api -> RpgMenu.openMain(this, player, api, contentApi().orElse(null)),
                () -> player.sendMessage("§cks-RPG 进度服务暂不可用。"));
    }

    void reloadMenuAsync(UUID requesterId, boolean reopenMenu) {
        File file = new File(getDataFolder(), "menu.yml");
        getServer().getScheduler().runTaskAsynchronously(this, () -> {
            final MenuLayout loaded;
            try {
                loaded = MenuLayout.load(file);
            } catch (Exception ex) {
                getServer().getScheduler().runTask(this, () -> notifyReload(requesterId,
                        "§cRPG 面板配置重载失败，请检查 menu.yml。", false));
                return;
            }
            getServer().getScheduler().runTask(this, () -> {
                layout = loaded;
                notifyReload(requesterId, "§aRPG 面板配置已重载。", reopenMenu);
            });
        });
    }

    private void notifyReload(UUID requesterId, String message, boolean reopenMenu) {
        if (requesterId == null) {
            getServer().getConsoleSender().sendMessage(message);
            return;
        }
        Player player = getServer().getPlayer(requesterId);
        if (player == null) return;
        player.sendMessage(message);
        if (reopenMenu && message.startsWith("§a")) openMain(player);
    }

    MenuLayout layout() {
        return layout;
    }

    Optional<RpgProgressionApi> api() {
        RegisteredServiceProvider<RpgProgressionApi> provider = getServer().getServicesManager()
                .getRegistration(RpgProgressionApi.class);
        return provider == null ? Optional.empty() : Optional.ofNullable(provider.getProvider());
    }

    Optional<RpgContentApi> contentApi() {
        RegisteredServiceProvider<RpgContentApi> provider = getServer().getServicesManager()
                .getRegistration(RpgContentApi.class);
        return provider == null ? Optional.empty() : Optional.ofNullable(provider.getProvider());
    }
}
