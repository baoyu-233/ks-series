package org.kseries.cinematic;

import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

final class CinematicCommand implements CommandExecutor, TabCompleter {
    private final CinematicService service;

    CinematicCommand(CinematicService service) {
        this.service = service;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 0 || args[0].equalsIgnoreCase("list")) {
            sender.sendMessage("§b[演出] §f" + String.join("、", service.list().stream().map(CinematicCatalog.Story::id).toList()));
            return true;
        }
        if (args[0].equalsIgnoreCase("reload")) {
            if (!sender.hasPermission("kscinematic.admin")) return denied(sender);
            service.reload();
            sender.sendMessage("§a演出内容已热重载。");
            return true;
        }
        if (!sender.hasPermission("kscinematic.admin")) return denied(sender);

        if (args[0].equalsIgnoreCase("give") && args.length > 1) {
            Player target = args.length > 2 ? service.onlinePlayer(args[2]) : sender instanceof Player player ? player : null;
            if (target == null) {
                sender.sendMessage("§c目标玩家不在线。");
                return true;
            }
            service.story(args[1]).ifPresentOrElse(
                    story -> service.giveTrigger(target, story, sender),
                    () -> sender.sendMessage("§c未知剧情。")
            );
            return true;
        }

        if (!(sender instanceof Player player)) {
            sender.sendMessage("§c该子命令只能由玩家使用。");
            return true;
        }
        if (args[0].equalsIgnoreCase("start") && args.length > 1) {
            service.story(args[1]).ifPresentOrElse(
                    story -> service.start(player, story, true),
                    () -> player.sendMessage("§c未知剧情。")
            );
            return true;
        }
        if (args[0].equalsIgnoreCase("edit") && args.length > 1) {
            if (service.story(args[1]).isEmpty()) {
                player.sendMessage("§c未知剧情。");
            } else {
                service.select(player, args[1]);
                player.sendMessage("§a已选择剧情：" + args[1]);
            }
            return true;
        }

        CinematicCatalog.Story story = service.editing(player) == null
                ? null : service.story(service.editing(player)).orElse(null);
        if (story == null) {
            player.sendMessage("§c先使用 /cinematic edit <剧情ID>。");
            return true;
        }
        if (args[0].equalsIgnoreCase("point") && args.length > 1) {
            savePoint(player, story, args[1]);
            return true;
        }
        if (args[0].equalsIgnoreCase("keyframe") && args.length > 2) {
            saveKeyframe(player, story, args);
            return true;
        }

        player.sendMessage("§7/cinematic list | reload | give <剧情ID> [玩家] | start <剧情ID> | edit <剧情ID>");
        player.sendMessage("§7/cinematic point <名称> | keyframe <开始tick> <点名> [时长] [LINEAR|SMOOTH]");
        return true;
    }

    private boolean denied(CommandSender sender) {
        sender.sendMessage("§c没有权限。");
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 1) return List.of("list", "reload", "give", "start", "edit", "point", "keyframe");
        if (args.length == 2 && List.of("give", "start", "edit").contains(args[0].toLowerCase(Locale.ROOT))) {
            return service.list().stream().map(CinematicCatalog.Story::id).toList();
        }
        return List.of();
    }

    private void savePoint(Player player, CinematicCatalog.Story story, String name) {
        if (!name.matches("[a-z0-9_.-]+")) {
            player.sendMessage("§c场景点名称只能使用小写字母、数字、点、下划线和连字符。");
            return;
        }
        try {
            YamlConfiguration yaml = YamlConfiguration.loadConfiguration(story.file().toFile());
            if (!yaml.contains("points.__editor_origin")) {
                yaml.set("points.__editor_origin.x", player.getLocation().getX());
                yaml.set("points.__editor_origin.y", player.getLocation().getY());
                yaml.set("points.__editor_origin.z", player.getLocation().getZ());
            }
            double x = player.getLocation().getX() - yaml.getDouble("points.__editor_origin.x");
            double y = player.getLocation().getY() - yaml.getDouble("points.__editor_origin.y");
            double z = player.getLocation().getZ() - yaml.getDouble("points.__editor_origin.z");
            yaml.set("points." + name + ".x", x);
            yaml.set("points." + name + ".y", y);
            yaml.set("points." + name + ".z", z);
            yaml.set("points." + name + ".yaw", player.getLocation().getYaw());
            yaml.set("points." + name + ".pitch", player.getLocation().getPitch());
            yaml.save(story.file().toFile());
            service.reload();
            player.sendMessage("§a已记录场景点：" + name + "。 ");
        } catch (Exception error) {
            player.sendMessage("§c写入场景点失败：" + error.getMessage());
        }
    }

    private void saveKeyframe(Player player, CinematicCatalog.Story story, String[] args) {
        try {
            int at = Integer.parseInt(args[1]);
            int duration = args.length > 3 ? Integer.parseInt(args[3]) : 40;
            String easing = args.length > 4 ? args[4].toUpperCase(Locale.ROOT) : "SMOOTH";
            if (at < 0 || duration < 1 || (!easing.equals("SMOOTH") && !easing.equals("LINEAR"))) {
                throw new IllegalArgumentException("关键帧参数无效");
            }
            YamlConfiguration yaml = YamlConfiguration.loadConfiguration(story.file().toFile());
            if (!yaml.contains("points." + args[2])) {
                throw new IllegalArgumentException("场景点不存在，先用 point 记录它");
            }
            List<Map<?, ?>> old = yaml.getMapList("timeline");
            List<Map<String, Object>> next = new ArrayList<>();
            for (Map<?, ?> value : old) {
                Map<String, Object> copy = new LinkedHashMap<>();
                value.forEach((key, item) -> copy.put(String.valueOf(key), item));
                next.add(copy);
            }
            Map<String, Object> action = new LinkedHashMap<>();
            action.put("at", at);
            action.put("type", "CAMERA");
            action.put("point", args[2]);
            action.put("duration", duration);
            action.put("easing", easing);
            next.add(action);
            yaml.set("timeline", next);
            yaml.save(story.file().toFile());
            service.reload();
            player.sendMessage("§a已写入镜头关键帧。");
        } catch (Exception error) {
            player.sendMessage("§c写入关键帧失败：" + error.getMessage());
        }
    }
}
