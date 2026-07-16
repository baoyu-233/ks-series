package org.kseco.extra.realestatedungeon;

import org.bukkit.configuration.file.YamlConfiguration;
import org.kseco.KsEco;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 副本系统配置管理。
 *
 * 职责：
 * - 启动时从 classpath:dungeon.yml 拷贝默认配置到 plugins/ks-Eco/dungeon.yml
 * - 加载 plugins/ks-Eco/dungeon.yml 到内存（按 [即时]/[副本] 分类）
 * - 提供热更新 API（白名单 + 类型校验 + 写回 + 失败回滚）
 */
public final class DungeonConfigManager {

    public enum ReloadMode { IMMEDIATE, ON_NEXT_INSTANCE }

    public static class ConfigSnapshot {
        // ticket
        public double ticketDefaultPrice = 500.0;
        // revive
        public double reviveBaseCost = 200.0;
        public double reviveExponent = 1.8;
        public int maxRevivesPerPlayer = 10;
        // grid
        public String gridWorldName = "ks-dungeon-world";
        public int gridSpacing = 5000;
        public int maxGrids = 64;
        public int cleanTimeoutSeconds = 120;
        public int instanceTimeoutMinutes = 60;
        // map（FAWE 贴 schematic）
        public int mapBaseY = 64;            // 贴图基准高度（clipboard 原点对齐到此 Y）
        public int mapArenaRadius = 256;     // 清场/清画布的水平半径（以网格中心为原点）
        public boolean mapSpawnMobs = true;  // 是否扫描 [mm] 告示牌刷怪
        // 注：房产相关配置（开发费/契税/容积率）已迁至 ks-Eco-RealEstate（RealEstateManager 常量）
    }

    private final KsEco eco;
    private final File configFile;
    private ConfigSnapshot snapshot = new ConfigSnapshot();
    private YamlConfiguration yaml;

    // 热更新白名单（key → mode + 类型）
    private static final Map<String, KeySpec> KEY_SPECS = new LinkedHashMap<>();
    static {
        KEY_SPECS.put("ticket.default_price", new KeySpec(Double.class, ReloadMode.IMMEDIATE));
        KEY_SPECS.put("revive.base_cost", new KeySpec(Double.class, ReloadMode.IMMEDIATE));
        KEY_SPECS.put("revive.exponent", new KeySpec(Double.class, ReloadMode.IMMEDIATE));
        KEY_SPECS.put("revive.max_revives_per_player", new KeySpec(Integer.class, ReloadMode.IMMEDIATE));
        KEY_SPECS.put("grid.world_name", new KeySpec(String.class, ReloadMode.ON_NEXT_INSTANCE));
        KEY_SPECS.put("grid.spacing", new KeySpec(Integer.class, ReloadMode.ON_NEXT_INSTANCE));
        KEY_SPECS.put("grid.max_grids", new KeySpec(Integer.class, ReloadMode.ON_NEXT_INSTANCE));
        KEY_SPECS.put("grid.clean_timeout_seconds", new KeySpec(Integer.class, ReloadMode.IMMEDIATE));
        KEY_SPECS.put("grid.instance_timeout_minutes", new KeySpec(Integer.class, ReloadMode.IMMEDIATE));
        KEY_SPECS.put("map.base_y", new KeySpec(Integer.class, ReloadMode.ON_NEXT_INSTANCE));
        KEY_SPECS.put("map.arena_radius", new KeySpec(Integer.class, ReloadMode.ON_NEXT_INSTANCE));
        KEY_SPECS.put("map.spawn_mobs", new KeySpec(Boolean.class, ReloadMode.IMMEDIATE));
    }

    public DungeonConfigManager(KsEco eco) {
        this.eco = eco;
        this.configFile = new File(eco.getDataFolder(), "dungeon.yml");
    }

    public void init() {
        try {
            if (!eco.getDataFolder().exists()) eco.getDataFolder().mkdirs();
            if (!configFile.exists()) {
                try (InputStream in = getClass().getResourceAsStream("/dungeon.yml")) {
                    if (in != null) {
                        Files.copy(in, configFile.toPath(), StandardCopyOption.REPLACE_EXISTING);
                    } else {
                        configFile.createNewFile();
                    }
                }
            }
            reload();
            eco.getLogger().info("[副本系统] 配置已加载: " + configFile.getAbsolutePath());
        } catch (IOException e) {
            eco.getLogger().warning("[副本系统] 配置初始化失败: " + e.getMessage());
        }
    }

    public void reload() {
        yaml = YamlConfiguration.loadConfiguration(configFile);
        // 用 classpath 默认值补缺失 key
        try (InputStream in = getClass().getResourceAsStream("/dungeon.yml")) {
            if (in != null) {
                YamlConfiguration defaults = YamlConfiguration.loadConfiguration(
                        new InputStreamReader(in, StandardCharsets.UTF_8));
                yaml.setDefaults(defaults);
            }
        } catch (IOException ignored) {}
        loadInto(snapshot, yaml);
    }

    private void loadInto(ConfigSnapshot s, YamlConfiguration y) {
        s.ticketDefaultPrice = y.getDouble("ticket.default_price", 500.0);
        s.reviveBaseCost = y.getDouble("revive.base_cost", 200.0);
        s.reviveExponent = y.getDouble("revive.exponent", 1.8);
        s.maxRevivesPerPlayer = y.getInt("revive.max_revives_per_player", 10);
        s.gridWorldName = y.getString("grid.world_name", "ks-dungeon-world");
        s.gridSpacing = y.getInt("grid.spacing", 5000);
        s.maxGrids = y.getInt("grid.max_grids", 64);
        s.cleanTimeoutSeconds = y.getInt("grid.clean_timeout_seconds", 120);
        s.instanceTimeoutMinutes = y.getInt("grid.instance_timeout_minutes", 60);
        s.mapBaseY = y.getInt("map.base_y", 64);
        s.mapArenaRadius = y.getInt("map.arena_radius", 256);
        s.mapSpawnMobs = y.getBoolean("map.spawn_mobs", true);
    }

    public ConfigSnapshot snapshot() { return snapshot; }

    /**
     * 读全部配置（用于 GET /api/realestate-dungeon/config）
     */
    public Map<String, Object> dumpAll() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ticket.default_price", snapshot.ticketDefaultPrice);
        out.put("revive.base_cost", snapshot.reviveBaseCost);
        out.put("revive.exponent", snapshot.reviveExponent);
        out.put("revive.max_revives_per_player", snapshot.maxRevivesPerPlayer);
        out.put("grid.world_name", snapshot.gridWorldName);
        out.put("grid.spacing", snapshot.gridSpacing);
        out.put("grid.max_grids", snapshot.maxGrids);
        out.put("grid.clean_timeout_seconds", snapshot.cleanTimeoutSeconds);
        out.put("grid.instance_timeout_minutes", snapshot.instanceTimeoutMinutes);
        out.put("map.base_y", snapshot.mapBaseY);
        out.put("map.arena_radius", snapshot.mapArenaRadius);
        out.put("map.spawn_mobs", snapshot.mapSpawnMobs);
        return out;
    }

    /**
     * 热更新单条配置。返回 null 成功；非 null 错误信息。
     */
    public String updateKey(String key, String rawValue) {
        KeySpec spec = KEY_SPECS.get(key);
        if (spec == null) return "不支持的配置 key: " + key;
        // 类型校验
        Object parsed;
        try {
            if (spec.type == Double.class) parsed = Double.parseDouble(rawValue);
            else if (spec.type == Integer.class) parsed = Integer.parseInt(rawValue);
            else if (spec.type == Boolean.class) parsed = Boolean.parseBoolean(rawValue);
            else parsed = rawValue;
        } catch (NumberFormatException e) {
            return "值类型不匹配: 期望 " + spec.type.getSimpleName();
        }
        // 备份当前文件
        ConfigSnapshot backup = deepCopy(snapshot);
        try {
            yaml.set(key, parsed);
            yaml.save(configFile);
            // 重新加载到 snapshot
            loadInto(snapshot, yaml);
            return null;
        } catch (Exception e) {
            // 回滚
            snapshot = backup;
            reload();
            return "写回失败: " + e.getMessage();
        }
    }

    public ReloadMode modeOf(String key) {
        KeySpec s = KEY_SPECS.get(key);
        return s == null ? ReloadMode.IMMEDIATE : s.mode;
    }

    public static List<String> whitelistKeys() {
        return List.copyOf(KEY_SPECS.keySet());
    }

    private static ConfigSnapshot deepCopy(ConfigSnapshot src) {
        ConfigSnapshot c = new ConfigSnapshot();
        c.ticketDefaultPrice = src.ticketDefaultPrice;
        c.reviveBaseCost = src.reviveBaseCost;
        c.reviveExponent = src.reviveExponent;
        c.maxRevivesPerPlayer = src.maxRevivesPerPlayer;
        c.gridWorldName = src.gridWorldName;
        c.gridSpacing = src.gridSpacing;
        c.maxGrids = src.maxGrids;
        c.cleanTimeoutSeconds = src.cleanTimeoutSeconds;
        c.instanceTimeoutMinutes = src.instanceTimeoutMinutes;
        c.mapBaseY = src.mapBaseY;
        c.mapArenaRadius = src.mapArenaRadius;
        c.mapSpawnMobs = src.mapSpawnMobs;
        return c;
    }

    private static final class KeySpec {
        final Class<?> type;
        final ReloadMode mode;
        KeySpec(Class<?> t, ReloadMode m) { this.type = t; this.mode = m; }
    }
}
