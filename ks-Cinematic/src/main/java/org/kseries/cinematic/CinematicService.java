package org.kseries.cinematic;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerGameModeChangeEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.RegisteredServiceProvider;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitTask;
import org.kseries.instanceworld.api.InstanceGridSpec;
import org.kseries.instanceworld.api.InstancePreparation;
import org.kseries.instanceworld.api.InstancePrepareRequest;
import org.kseries.instanceworld.api.InstanceWorldApi;
import org.kseries.instanceworld.api.PreparedInstance;
import org.kseries.instanceworld.api.ReleaseCause;

import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

final class CinematicService implements Listener {
    private final KsCinematic plugin;
    private final NamespacedKey recoveryKey;
    private volatile CinematicCatalog catalog = CinematicCatalog.empty();
    private final Map<UUID, Session> sessions = new ConcurrentHashMap<>();
    private final Map<UUID, PendingPreparation> pendingPreparations = new ConcurrentHashMap<>();
    private final Map<UUID, String> editing = new ConcurrentHashMap<>();

    CinematicService(KsCinematic plugin) {
        this.plugin = plugin;
        this.recoveryKey = new NamespacedKey(plugin, "cinematic_recovery");
    }

    void reload() {
        try {
            Files.createDirectories(root());
            CinematicCatalog next = CinematicCatalog.load(root());
            catalog = next;
            plugin.getLogger().info("Loaded " + next.list().size() + " cinematic packages.");
        } catch (Exception error) {
            plugin.getLogger().severe("Cinematic reload failed; keeping previous content: " + error.getMessage());
        }
    }

    Path root() {
        return plugin.getDataFolder().toPath().resolve("stories");
    }

    List<CinematicCatalog.Story> list() {
        return catalog.list();
    }

    Optional<CinematicCatalog.Story> story(String id) {
        return catalog.get(id);
    }

    Player onlinePlayer(String name) {
        return Bukkit.getPlayerExact(name);
    }

    @EventHandler(ignoreCancelled = true)
    void trigger(PlayerInteractEvent event) {
        if (event.getHand() != EquipmentSlot.HAND
                || (event.getAction() != Action.RIGHT_CLICK_AIR && event.getAction() != Action.RIGHT_CLICK_BLOCK)
                || !event.getPlayer().hasPermission("kscinematic.use")) {
            return;
        }
        for (CinematicCatalog.Story story : catalog.list()) {
            if (matches(event.getItem(), story.trigger())) {
                event.setCancelled(true);
                start(event.getPlayer(), story, false);
                return;
            }
        }
    }

    boolean start(Player player, CinematicCatalog.Story story, boolean adminPreview) {
        UUID playerId = player.getUniqueId();
        if (sessions.containsKey(playerId) || pendingPreparations.containsKey(playerId)) return false;
        if (!adminPreview && !story.replayable() && flag(player, story.id())) {
            player.sendMessage("§7你已经看过这段记录。");
            return false;
        }
        InstanceWorldApi api = api();
        if (api == null) {
            player.sendMessage("§c演出实例服务不可用。");
            return false;
        }
        if (sessions.size() + pendingPreparations.size()
                >= plugin.getConfig().getInt("defaults.max-concurrent", 2)) {
            player.sendMessage("§c当前演出繁忙，请稍后再试。");
            return false;
        }

        InstanceGridSpec grid = new InstanceGridSpec(story.scene().world(), story.scene().spacing(), story.scene().max());
        InstancePrepareRequest request = new InstancePrepareRequest(
                "ks-cinematic", story.id(), story.scene().schematic(), grid, story.scene().pasteY(),
                story.scene().radius(), Duration.ofSeconds(story.scene().timeout())
        );
        InstancePreparation preparation = api.prepare(request);
        PendingPreparation pending = new PendingPreparation(playerId, story, adminPreview, api, preparation);
        pendingPreparations.put(playerId, pending);
        preparation.ready().whenComplete((ready, error) -> finishPreparation(pending, ready, error));
        return true;
    }

    private void finishPreparation(PendingPreparation pending, PreparedInstance ready, Throwable error) {
        if (!pendingPreparations.remove(pending.player(), pending)) return;
        UUID playerId = pending.player();
        CinematicCatalog.Story story = pending.story();
        boolean adminPreview = pending.adminPreview();
        InstanceWorldApi api = pending.api();
        InstancePreparation preparation = pending.preparation();
        Player player = Bukkit.getPlayer(playerId);
        if (error != null) {
            if (player != null && player.isOnline()) player.sendMessage("§c演出场景准备失败。");
            return;
        }
        if (ready == null || player == null || !player.isOnline()) {
            api.release(preparation.instanceId(), ReleaseCause.PREPARATION_FAILED);
            if (player != null && player.isOnline()) player.sendMessage("§c演出场景准备失败。");
            return;
        }
        if (!adminPreview && !matches(player.getInventory().getItemInMainHand(), story.trigger())) {
            api.release(preparation.instanceId(), ReleaseCause.PREPARATION_FAILED);
            player.sendMessage("§7演出凭证已不在主手，已取消进入。");
            return;
        }
        if (!adminPreview && story.trigger().consume()) {
            consumeMainHand(player);
        }
        begin(player, story, api, ready);
    }

    private void begin(Player player, CinematicCatalog.Story story, InstanceWorldApi api, PreparedInstance ready) {
        Location original = player.getLocation().clone();
        GameMode mode = player.getGameMode();
        boolean flight = player.getAllowFlight();
        boolean flying = player.isFlying();
        saveRecovery(player, original, mode, flight, flying);

        Location spawn = ready.spawn().toLocation(ready.world());
        player.teleport(spawn);
        ArmorStand camera = ready.world().spawn(spawn, ArmorStand.class, stand -> {
            stand.setInvisible(true);
            stand.setMarker(true);
            stand.setGravity(false);
            stand.setInvulnerable(true);
            stand.setSilent(true);
        });
        player.setGameMode(GameMode.SPECTATOR);
        player.setSpectatorTarget(camera);

        Session session = new Session(player.getUniqueId(), story, api, ready, original, mode, flight, flying, camera);
        sessions.put(player.getUniqueId(), session);
        session.task = Bukkit.getScheduler().runTaskTimer(plugin, () -> tick(session), 1L, 1L);
    }

    private void tick(Session session) {
        Player player = Bukkit.getPlayer(session.player);
        if (player == null || !player.isOnline()) {
            end(session, false, ReleaseCause.PLAYER_LEFT);
            return;
        }
        int tick = session.tick++;
        for (CinematicCatalog.Action action : session.story.actions()) {
            if (action.at() == tick) perform(session, action);
        }
        if (session.finished) end(session, true, ReleaseCause.COMPLETED);
    }

    private void perform(Session session, CinematicCatalog.Action action) {
        Player player = Bukkit.getPlayer(session.player);
        if (player == null) return;
        Map<String, Object> data = action.data();

        switch (action.type()) {
            case "TITLE" -> player.sendTitle(text(data, "title"), text(data, "subtitle"),
                    integer(data, "fade-in", 10), integer(data, "stay", 40), integer(data, "fade-out", 10));
            case "SOUND" -> playSound(player, data);
            case "PARTICLE" -> spawnParticle(session, data);
            case "CAMERA" -> moveCamera(session, string(data, "point"), integer(data, "duration", 20),
                    string(data, "easing", "SMOOTH"));
            case "BLOCK" -> replaceBlock(session, data);
            case "COMMAND" -> dispatchConfiguredCommand(session, player, data);
            case "COMPLETE" -> session.finished = true;
            default -> plugin.getLogger().warning("Unknown cinematic action " + action.type());
        }
    }

    private void playSound(Player player, Map<String, Object> data) {
        try {
            player.playSound(player.getLocation(), Sound.valueOf(string(data, "sound")),
                    (float) number(data, "volume", 1), (float) number(data, "pitch", 1));
        } catch (IllegalArgumentException ignored) {
            plugin.getLogger().warning("Invalid cinematic sound: " + string(data, "sound"));
        }
    }

    private void spawnParticle(Session session, Map<String, Object> data) {
        try {
            session.ready.world().spawnParticle(Particle.valueOf(string(data, "particle")),
                    point(session, string(data, "point", "spawn")), integer(data, "count", 10),
                    number(data, "offset-x", 0.3), number(data, "offset-y", 0.3),
                    number(data, "offset-z", 0.3), number(data, "extra", 0));
        } catch (IllegalArgumentException ignored) {
            plugin.getLogger().warning("Invalid cinematic particle: " + string(data, "particle"));
        }
    }

    private void replaceBlock(Session session, Map<String, Object> data) {
        Material material = Material.matchMaterial(string(data, "material"));
        if (material == null || !material.isBlock()) {
            plugin.getLogger().warning("Invalid cinematic block material: " + string(data, "material"));
            return;
        }
        point(session, string(data, "point", "spawn")).getBlock().setType(material, false);
    }

    private void dispatchConfiguredCommand(Session session, Player player, Map<String, Object> data) {
        String command = string(data, "command");
        if (command.isBlank()) return;
        Location spawn = session.ready.spawn().toLocation(session.ready.world());
        command = command.replace("%player%", player.getName())
                .replace("%world%", session.ready.world().getName())
                .replace("%spawn_x%", Double.toString(spawn.getX()))
                .replace("%spawn_y%", Double.toString(spawn.getY()))
                .replace("%spawn_z%", Double.toString(spawn.getZ()));
        for (String pointName : session.story.points().keySet()) {
            Location point = point(session, pointName);
            String prefix = "%point_" + pointName + "_";
            command = command.replace(prefix + "x%", Double.toString(point.getX()))
                    .replace(prefix + "y%", Double.toString(point.getY()))
                    .replace(prefix + "z%", Double.toString(point.getZ()));
        }
        Bukkit.dispatchCommand(Bukkit.getConsoleSender(), command.startsWith("/") ? command.substring(1) : command);
    }

    private void moveCamera(Session session, String pointName, int duration, String easing) {
        Location end = point(session, pointName);
        Location start = session.camera.getLocation();
        int total = Math.max(1, duration);
        new BukkitRunnable() {
            int age;

            @Override
            public void run() {
                if (session.finished || session.closed || session.camera.isDead()) {
                    cancel();
                    return;
                }
                double progress = Math.min(1D, ++age / (double) total);
                if (easing.equalsIgnoreCase("SMOOTH")) progress = progress * progress * (3 - 2 * progress);
                Location next = start.clone().add(end.toVector().subtract(start.toVector()).multiply(progress));
                next.setYaw((float) (start.getYaw() + (end.getYaw() - start.getYaw()) * progress));
                next.setPitch((float) (start.getPitch() + (end.getPitch() - start.getPitch()) * progress));
                session.camera.teleport(next);
                if (progress >= 1D) cancel();
            }
        }.runTaskTimer(plugin, 0L, 1L);
    }

    private Location point(Session session, String name) {
        CinematicCatalog.Point point = session.story.points().get(name);
        Location base = session.ready.spawn().toLocation(session.ready.world());
        if (point == null) return base;
        Location result = base.add(point.x(), point.y(), point.z());
        result.setYaw(point.yaw());
        result.setPitch(point.pitch());
        return result;
    }

    private void end(Session session, boolean complete, ReleaseCause cause) {
        if (session.closed) return;
        session.closed = true;
        if (session.task != null) session.task.cancel();
        if (session.camera != null) session.camera.remove();

        Player player = Bukkit.getPlayer(session.player);
        if (player != null && player.isOnline()) {
            player.setSpectatorTarget(null);
            player.setGameMode(session.mode);
            player.setAllowFlight(session.flight);
            player.setFlying(session.flying);
            player.teleport(session.original);
            player.getPersistentDataContainer().remove(recoveryKey);
            if (complete) {
                setFlag(player, session.story.id());
                player.addPotionEffect(new org.bukkit.potion.PotionEffect(
                        org.bukkit.potion.PotionEffectType.BLINDNESS, 40, 0, false, false, false));
                player.sendMessage("§a已记录：" + session.story.display());
            }
        }
        sessions.remove(session.player, session);
        session.api.release(session.ready.instanceId(), cause);
    }

    @EventHandler
    void quit(PlayerQuitEvent event) {
        UUID playerId = event.getPlayer().getUniqueId();
        PendingPreparation pending = pendingPreparations.remove(playerId);
        if (pending != null) pending.api().release(pending.preparation().instanceId(), ReleaseCause.PLAYER_LEFT);
        Session session = sessions.get(playerId);
        if (session != null) end(session, false, ReleaseCause.PLAYER_LEFT);
        editing.remove(playerId);
    }

    @EventHandler
    void join(PlayerJoinEvent event) {
        if (!sessions.containsKey(event.getPlayer().getUniqueId())) {
            Bukkit.getScheduler().runTask(plugin, () -> restoreRecovery(event.getPlayer()));
        }
    }

    @EventHandler(ignoreCancelled = true)
    void gameMode(PlayerGameModeChangeEvent event) {
        Session session = sessions.get(event.getPlayer().getUniqueId());
        if (session != null && !session.closed && event.getNewGameMode() != GameMode.SPECTATOR) {
            event.setCancelled(true);
        }
    }

    void shutdown() {
        for (PendingPreparation pending : List.copyOf(pendingPreparations.values())) {
            if (pendingPreparations.remove(pending.player(), pending)) {
                pending.api().release(pending.preparation().instanceId(), ReleaseCause.PLUGIN_DISABLE);
            }
        }
        for (Session session : List.copyOf(sessions.values())) {
            end(session, false, ReleaseCause.PLUGIN_DISABLE);
        }
        editing.clear();
    }

    void select(Player player, String id) {
        editing.put(player.getUniqueId(), id);
    }

    String editing(Player player) {
        return editing.get(player.getUniqueId());
    }

    void giveTrigger(Player player, CinematicCatalog.Story story, org.bukkit.command.CommandSender sender) {
        CinematicCatalog.Trigger trigger = story.trigger();
        if (!trigger.kind().equals("PDC")) {
            sender.sendMessage("§e该剧情使用外部物品触发，请按其内容配置发放；仅 PDC 触发器可由本插件生成。");
            return;
        }
        ItemStack item = new ItemStack(trigger.material());
        ItemMeta meta = item.getItemMeta();
        meta.setDisplayName(color(trigger.tokenName()));
        meta.setLore(trigger.tokenLore().stream().map(CinematicService::color).toList());
        meta.getPersistentDataContainer().set(triggerKey(trigger.key()), PersistentDataType.STRING, trigger.value());
        item.setItemMeta(meta);
        Map<Integer, ItemStack> remaining = player.getInventory().addItem(item);
        remaining.values().forEach(left -> player.getWorld().dropItemNaturally(player.getLocation(), left));
        sender.sendMessage("§a已给予 " + player.getName() + "：" + story.display());
    }

    private boolean matches(ItemStack item, CinematicCatalog.Trigger trigger) {
        if (item == null || item.getType().isAir()) return false;
        return switch (trigger.kind()) {
            case "VANILLA" -> item.getType() == trigger.material();
            case "PDC" -> {
                ItemMeta meta = item.getItemMeta();
                yield meta != null && trigger.value().equals(meta.getPersistentDataContainer()
                        .get(triggerKey(trigger.key()), PersistentDataType.STRING));
            }
            case "MMOITEM" -> matchesMmoItem(item, trigger);
            default -> false;
        };
    }

    private boolean matchesMmoItem(ItemStack item, CinematicCatalog.Trigger trigger) {
        try {
            Plugin mmoItems = Bukkit.getPluginManager().getPlugin("MMOItems");
            if (mmoItems == null || !mmoItems.isEnabled()) return false;
            Class<?> type = Class.forName("net.Indyuce.mmoitems.MMOItems", true, mmoItems.getClass().getClassLoader());
            Object runtime = type.getField("plugin").get(null);
            Method id = type.getMethod("getID", ItemStack.class);
            Method itemType = type.getMethod("getTypeName", ItemStack.class);
            return trigger.id().equalsIgnoreCase((String) id.invoke(runtime, item))
                    && trigger.type().equalsIgnoreCase((String) itemType.invoke(runtime, item));
        } catch (Throwable ignored) {
            return false;
        }
    }

    private void consumeMainHand(Player player) {
        ItemStack hand = player.getInventory().getItemInMainHand();
        if (hand.getAmount() <= 1) {
            player.getInventory().setItemInMainHand(null);
        } else {
            hand.setAmount(hand.getAmount() - 1);
        }
    }

    private InstanceWorldApi api() {
        RegisteredServiceProvider<InstanceWorldApi> registration = Bukkit.getServicesManager().getRegistration(InstanceWorldApi.class);
        return registration == null ? null : registration.getProvider();
    }

    private NamespacedKey triggerKey(String key) {
        return new NamespacedKey(plugin, "trigger." + key);
    }

    private boolean flag(Player player, String id) {
        return player.getPersistentDataContainer().has(new NamespacedKey(plugin, "story." + id), PersistentDataType.BYTE);
    }

    private void setFlag(Player player, String id) {
        player.getPersistentDataContainer().set(new NamespacedKey(plugin, "story." + id), PersistentDataType.BYTE, (byte) 1);
    }

    private static String string(Map<String, Object> data, String key) {
        return String.valueOf(data.getOrDefault(key, ""));
    }

    private static String string(Map<String, Object> data, String key, String fallback) {
        return String.valueOf(data.getOrDefault(key, fallback));
    }

    private static double number(Map<String, Object> data, String key, double fallback) {
        Object value = data.get(key);
        return value instanceof Number numeric ? numeric.doubleValue() : fallback;
    }

    private static int integer(Map<String, Object> data, String key, int fallback) {
        return (int) Math.round(number(data, key, fallback));
    }

    private static String text(Map<String, Object> data, String key) {
        return color(string(data, key));
    }

    private static String color(String value) {
        return ChatColor.translateAlternateColorCodes('&', value);
    }

    private void saveRecovery(Player player, Location location, GameMode mode, boolean flight, boolean flying) {
        player.getPersistentDataContainer().set(recoveryKey, PersistentDataType.STRING,
                location.getWorld().getName() + ";" + location.getX() + ";" + location.getY() + ";" + location.getZ()
                        + ";" + location.getYaw() + ";" + location.getPitch() + ";" + mode.name()
                        + ";" + flight + ";" + flying);
    }

    private void restoreRecovery(Player player) {
        String raw = player.getPersistentDataContainer().get(recoveryKey, PersistentDataType.STRING);
        if (raw == null) return;
        try {
            String[] values = raw.split(";", 9);
            World world = Bukkit.getWorld(values[0]);
            if (world != null) {
                player.setGameMode(GameMode.valueOf(values[6]));
                player.setAllowFlight(Boolean.parseBoolean(values[7]));
                player.setFlying(Boolean.parseBoolean(values[8]));
                player.teleport(new Location(world, Double.parseDouble(values[1]), Double.parseDouble(values[2]),
                        Double.parseDouble(values[3]), Float.parseFloat(values[4]), Float.parseFloat(values[5])));
            }
            player.getPersistentDataContainer().remove(recoveryKey);
        } catch (Exception ignored) {
            player.setGameMode(GameMode.SURVIVAL);
            player.getPersistentDataContainer().remove(recoveryKey);
        }
    }

    private static final class Session {
        private final UUID player;
        private final CinematicCatalog.Story story;
        private final InstanceWorldApi api;
        private final PreparedInstance ready;
        private final Location original;
        private final GameMode mode;
        private final boolean flight;
        private final boolean flying;
        private final ArmorStand camera;
        private int tick;
        private boolean finished;
        private boolean closed;
        private BukkitTask task;

        private Session(UUID player, CinematicCatalog.Story story, InstanceWorldApi api, PreparedInstance ready,
                        Location original, GameMode mode, boolean flight, boolean flying, ArmorStand camera) {
            this.player = player;
            this.story = story;
            this.api = api;
            this.ready = ready;
            this.original = original;
            this.mode = mode;
            this.flight = flight;
            this.flying = flying;
            this.camera = camera;
        }
    }

    private record PendingPreparation(UUID player, CinematicCatalog.Story story, boolean adminPreview,
                                      InstanceWorldApi api, InstancePreparation preparation) { }
}
