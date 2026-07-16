package org.kseries.maintenance;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerLoginEvent;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

public final class KsMaintenance extends JavaPlugin implements Listener, TabExecutor {
    private static final String ADMIN_PERMISSION = "ksmaintenance.admin";
    private static final String BYPASS_PERMISSION = "ksmaintenance.bypass";

    private boolean enabled;
    private boolean kickExistingOnEnable;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        loadSettings();
        Bukkit.getPluginManager().registerEvents(this, this);

        var command = getCommand("ksmaintenance");
        if (command != null) {
            command.setExecutor(this);
            command.setTabCompleter(this);
        }

        getLogger().info("ks-Maintenance enabled. maintenance=" + enabled);
    }

    @Override
    public void reloadConfig() {
        super.reloadConfig();
        loadSettings();
    }

    private void loadSettings() {
        enabled = getConfig().getBoolean("maintenance.enabled", false);
        kickExistingOnEnable = getConfig().getBoolean("maintenance.kick-existing-on-enable", true);
    }

    @EventHandler
    public void onPlayerLogin(PlayerLoginEvent event) {
        if (!enabled) {
            return;
        }

        Player player = event.getPlayer();
        if (canBypass(player)) {
            return;
        }

        event.disallow(PlayerLoginEvent.Result.KICK_OTHER, color(message("kick")));
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!hasAdmin(sender)) {
            send(sender, "no-permission");
            return true;
        }

        if (args.length == 0) {
            send(sender, enabled ? "status-enabled" : "status-disabled");
            sendRaw(sender, message("usage"));
            return true;
        }

        switch (args[0].toLowerCase(Locale.ROOT)) {
            case "on" -> {
                setMaintenance(true);
                send(sender, "enabled");
                if (kickExistingOnEnable) {
                    int kicked = kickOnlinePlayers();
                    sendRaw(sender, message("kicked-online").replace("%count%", Integer.toString(kicked)));
                }
                return true;
            }
            case "off" -> {
                setMaintenance(false);
                send(sender, "disabled");
                return true;
            }
            case "status" -> {
                send(sender, enabled ? "status-enabled" : "status-disabled");
                return true;
            }
            case "reload" -> {
                reloadConfig();
                send(sender, "reloaded");
                return true;
            }
            case "kick" -> {
                int kicked = kickOnlinePlayers();
                sendRaw(sender, message("kicked-online").replace("%count%", Integer.toString(kicked)));
                return true;
            }
            default -> {
                sendRaw(sender, message("usage"));
                return true;
            }
        }
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (!hasAdmin(sender)) {
            return Collections.emptyList();
        }

        if (args.length != 1) {
            return Collections.emptyList();
        }

        String prefix = args[0].toLowerCase(Locale.ROOT);
        List<String> matches = new ArrayList<>();
        for (String option : List.of("on", "off", "status", "reload", "kick")) {
            if (option.startsWith(prefix)) {
                matches.add(option);
            }
        }
        return matches;
    }

    private void setMaintenance(boolean value) {
        enabled = value;
        getConfig().set("maintenance.enabled", value);
        saveConfig();
    }

    private int kickOnlinePlayers() {
        int kicked = 0;
        String kickMessage = color(message("kick"));
        for (Player player : new ArrayList<>(Bukkit.getOnlinePlayers())) {
            if (canBypass(player)) {
                continue;
            }
            player.kickPlayer(kickMessage);
            kicked++;
        }
        return kicked;
    }

    private boolean canBypass(Player player) {
        return player.isOp() || player.hasPermission(BYPASS_PERMISSION);
    }

    private boolean hasAdmin(CommandSender sender) {
        return !(sender instanceof Player player) || player.isOp() || player.hasPermission(ADMIN_PERMISSION);
    }

    private void send(CommandSender sender, String key) {
        sendRaw(sender, message(key));
    }

    private void sendRaw(CommandSender sender, String message) {
        sender.sendMessage(color(message("prefix") + message));
    }

    private String message(String key) {
        return getConfig().getString("messages." + key, "");
    }

    private String color(String text) {
        return ChatColor.translateAlternateColorCodes('&', text == null ? "" : text);
    }
}
