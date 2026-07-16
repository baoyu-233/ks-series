package org.kseco;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.sql.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.logging.Level;

/**
 * 盲盒系统管理器。
 *
 * 替代原 OfficialSellManager 的官方直售：玩家支付固定价格抽取卡池中的随机物品。
 *
 * 三种卡池类型：ITEM（一般物品）/ MATERIAL（材料）/ EQUIPMENT（装备）
 * 战利品稀有度：COMMON / UNCOMMON / RARE / EPIC / LEGENDARY
 * 保底：同池 N 抽未出 RARE 及以上必出（forceGuaranteedRare）
 */
public final class BlindBoxManager {

    public static final String TYPE_ITEM = "ITEM";
    public static final String TYPE_MATERIAL = "MATERIAL";
    public static final String TYPE_EQUIPMENT = "EQUIPMENT";

    public static final String RARITY_COMMON = "COMMON";
    public static final String RARITY_UNCOMMON = "UNCOMMON";
    public static final String RARITY_RARE = "RARE";
    public static final String RARITY_EPIC = "EPIC";
    public static final String RARITY_LEGENDARY = "LEGENDARY";
    public static final String ENTERPRISE_PERMISSION_BLINDBOX_DRAW = "BLINDBOX_DRAW";

    private static final String[] ALL_RARITY = {
            RARITY_COMMON, RARITY_UNCOMMON, RARITY_RARE, RARITY_EPIC, RARITY_LEGENDARY
    };

    private static final Map<String, Integer> RARITY_RANK = Map.of(
            RARITY_COMMON, 0,
            RARITY_UNCOMMON, 1,
            RARITY_RARE, 2,
            RARITY_EPIC, 3,
            RARITY_LEGENDARY, 4
    );

    private static final Set<String> RARE_OR_ABOVE = new HashSet<>(Arrays.asList(
            RARITY_RARE, RARITY_EPIC, RARITY_LEGENDARY));

    private final KsEco plugin;
    private final Set<String> activeAsyncPulls = ConcurrentHashMap.newKeySet();

    private record PityRule(String rarity, int maxPulls) {}
    private record PityState(int rareCount, String triggeredRarity, int triggeredMax) {
        boolean triggered() {
            return triggeredRarity != null && triggeredMax > 0;
        }
    }

    public BlindBoxManager(KsEco plugin) {
        this.plugin = plugin;
        createTables();
    }

    private static int rarityRank(String rarity) {
        if (rarity == null) return 0;
        return RARITY_RANK.getOrDefault(rarity.toUpperCase(Locale.ROOT), 0);
    }

    private static String normalizeRarity(String rarity) {
        if (rarity == null) return RARITY_COMMON;
        String upper = rarity.trim().toUpperCase(Locale.ROOT);
        for (String r : ALL_RARITY) {
            if (r.equals(upper)) return r;
        }
        return RARITY_COMMON;
    }

    public static String normalizePityRules(String raw, int fallbackRareMax) {
        LinkedHashMap<String, Integer> rules = parsePityRules(raw, fallbackRareMax);
        if (rules.isEmpty()) return "";
        List<String> parts = new ArrayList<>();
        for (var e : rules.entrySet()) {
            parts.add(e.getKey() + ":" + e.getValue());
        }
        return String.join(",", parts);
    }

    public static String pityRulesText(Object raw, Object fallbackRareMax) {
        int fallback = fallbackRareMax instanceof Number n ? n.intValue() : 0;
        String normalized = normalizePityRules(raw instanceof String s ? s : "", fallback);
        return normalized.isEmpty() ? "none" : normalized;
    }

    private static LinkedHashMap<String, Integer> parsePityRules(String raw, int fallbackRareMax) {
        TreeMap<Integer, Map.Entry<String, Integer>> sorted = new TreeMap<>();
        if (raw != null && !raw.isBlank()) {
            for (String part : raw.split("[,;\\n]")) {
                String p = part.trim();
                if (p.isEmpty()) continue;
                String[] pair = p.split("[:=]", 2);
                if (pair.length != 2) continue;
                String rarity = normalizeRarity(pair[0]);
                try {
                    int max = Integer.parseInt(pair[1].trim());
                    if (max > 0 && rarityRank(rarity) >= rarityRank(RARITY_RARE)) {
                        sorted.put(rarityRank(rarity), Map.entry(rarity, max));
                    }
                } catch (NumberFormatException ignored) {
                }
            }
        }
        if (sorted.isEmpty() && fallbackRareMax > 0) {
            sorted.put(rarityRank(RARITY_RARE), Map.entry(RARITY_RARE, fallbackRareMax));
        }
        LinkedHashMap<String, Integer> rules = new LinkedHashMap<>();
        for (var e : sorted.values()) rules.put(e.getKey(), e.getValue());
        return rules;
    }

    private void createTables() {
        // ensureAllTables() 也会建；此处双保险，幂等
        try (var conn = plugin.ksCore().dataStore().getConnection()) {
            if (conn == null) return;
            try (var s = conn.createStatement()) {
                s.execute("CREATE TABLE IF NOT EXISTS ks_bb_pools (id TEXT PRIMARY KEY, name TEXT NOT NULL, pool_type TEXT NOT NULL DEFAULT 'ITEM', price REAL NOT NULL DEFAULT 100, enabled INTEGER DEFAULT 1, pity_max INTEGER DEFAULT 50, description TEXT DEFAULT '', owner_type TEXT NOT NULL DEFAULT 'PUBLIC', allowed_categories TEXT DEFAULT '', allowed_industries TEXT DEFAULT '', created_at INTEGER NOT NULL)");
                s.execute("CREATE TABLE IF NOT EXISTS ks_bb_loot (id TEXT PRIMARY KEY, pool_id TEXT NOT NULL, item_material TEXT NOT NULL, item_data BLOB, display_name TEXT DEFAULT '', weight INTEGER NOT NULL DEFAULT 1, rarity TEXT NOT NULL DEFAULT 'COMMON', quantity INTEGER DEFAULT 1, created_at INTEGER NOT NULL)");
                s.execute("CREATE TABLE IF NOT EXISTS ks_bb_pity (uuid TEXT NOT NULL, pool_id TEXT NOT NULL, count_since_rare INTEGER DEFAULT 0, updated_at INTEGER NOT NULL, PRIMARY KEY(uuid, pool_id))");
                s.execute("CREATE TABLE IF NOT EXISTS ks_bb_pity_rarity (uuid TEXT NOT NULL, pool_id TEXT NOT NULL, rarity TEXT NOT NULL, count_since_hit INTEGER DEFAULT 0, updated_at INTEGER NOT NULL, PRIMARY KEY(uuid, pool_id, rarity))");
                s.execute("CREATE TABLE IF NOT EXISTS ks_bb_log (id INTEGER PRIMARY KEY AUTOINCREMENT, uuid TEXT NOT NULL, pool_id TEXT NOT NULL, item_material TEXT NOT NULL, rarity TEXT NOT NULL, pulled_at INTEGER NOT NULL)");
            }
            // 迁移：加 bundle 列（SQLite 不支持 IF NOT EXISTS for ALTER，忽略重复列错误）
            try (var s = conn.createStatement()) {
                s.execute("ALTER TABLE ks_bb_loot ADD COLUMN bundle_id TEXT DEFAULT NULL");
            } catch (SQLException ignored) {} // 列已存在时忽略
            try (var s = conn.createStatement()) {
                s.execute("ALTER TABLE ks_bb_loot ADD COLUMN bundle_slot INTEGER DEFAULT 0");
            } catch (SQLException ignored) {}
            // 地块福利联动：限定只有拥有对应类型地块（AGRICULTURAL/INDUSTRIAL，逗号分隔可多选）的玩家/企业才能抽此池
            try (var s = conn.createStatement()) {
                s.execute("ALTER TABLE ks_bb_pools ADD COLUMN required_land_zone_types TEXT DEFAULT ''");
            } catch (SQLException ignored) {}
            try (var s = conn.createStatement()) {
                s.execute("ALTER TABLE ks_bb_pools ADD COLUMN pity_rules TEXT DEFAULT ''");
            } catch (SQLException ignored) {}
            try (var s = conn.createStatement()) {
                s.execute("ALTER TABLE ks_bb_pools ADD COLUMN limited_only INTEGER NOT NULL DEFAULT 0");
            } catch (SQLException ignored) {}
            try (var s = conn.createStatement()) {
                s.execute("ALTER TABLE ks_bb_pools ADD COLUMN min_enterprise_level INTEGER NOT NULL DEFAULT 1");
            } catch (SQLException ignored) {}
        } catch (SQLException e) {
            plugin.getLogger().log(Level.WARNING, "盲盒表创建失败", e);
        }
    }

    // ================================================================
    // 行业 / 物品分类
    // ================================================================

    public static final String CATEGORY_WEAPON = "weapon";
    public static final String CATEGORY_ARMOR = "armor";
    public static final String CATEGORY_TOOL = "tool";
    public static final String CATEGORY_MATERIAL = "material";
    public static final String CATEGORY_FOOD = "food";
    public static final String CATEGORY_BLOCK = "block";
    public static final String CATEGORY_OTHER = "other";

    public static final String INDUSTRY_OTHER = "OTHER";
    public static final String INDUSTRY_INDUSTRY = "INDUSTRY";
    public static final String INDUSTRY_AGRICULTURE = "AGRICULTURE";
    public static final String INDUSTRY_REAL_ESTATE = "REAL_ESTATE";

    /** 物品材质自动归类（用于行业白名单校验） */
    public static String categorizeMaterial(Material mat) {
        if (mat == null) return CATEGORY_OTHER;
        String n = mat.name();
        // 武器
        if (n.endsWith("_SWORD") || n.endsWith("_BOW") || n.endsWith("_CROSSBOW") || n.endsWith("_TRIDENT")
                || n.equals("BOW") || n.equals("CROSSBOW") || n.equals("TRIDENT")
                || n.equals("ARROW") || n.equals("SPECTRAL_ARROW") || n.equals("TIPPED_ARROW")
                || n.equals("SHIELD") || n.equals("MACE")) {
            return CATEGORY_WEAPON;
        }
        // 盔甲
        if (n.endsWith("_HELMET") || n.endsWith("_CHESTPLATE") || n.endsWith("_LEGGINGS") || n.endsWith("_BOOTS")
                || n.equals("SHIELD")) {
            return CATEGORY_ARMOR;
        }
        // 工具
        if (n.endsWith("_PICKAXE") || n.endsWith("_AXE") || n.endsWith("_SHOVEL") || n.endsWith("_HOE")
                || n.equals("FISHING_ROD") || n.equals("SHEARS") || n.equals("FLINT_AND_STEEL")
                || n.equals("BRUSH") || n.endsWith("_AXE")) {
            return CATEGORY_TOOL;
        }
        // 食物
        if (n.endsWith("_APPLE") || n.endsWith("_BREAD") || n.endsWith("_COOKIE") || n.endsWith("_CAKE")
                || n.endsWith("_STEW") || n.endsWith("_SOUP") || n.endsWith("_PIE")
                || n.equals("GOLDEN_APPLE") || n.equals("ENCHANTED_GOLDEN_APPLE")
                || n.equals("PUMPKIN_PIE") || n.equals("MELON_SLICE") || n.equals("HONEY_BOTTLE")
                || n.equals("DRIED_KELP") || n.equals("COOKED_BEEF") || n.equals("COOKED_PORKCHOP")
                || n.equals("COOKED_CHICKEN") || n.equals("COOKED_MUTTON") || n.equals("COOKED_SALMON")
                || n.equals("COOKED_COD") || n.equals("BREAD") || n.equals("CARROT") || n.equals("POTATO")
                || n.equals("BEETROOT") || n.equals("SWEET_BERRIES") || n.equals("GLOW_BERRIES")) {
            return CATEGORY_FOOD;
        }
        // 材料
        if (n.endsWith("_INGOT") || n.endsWith("_NUGGET") || n.equals("DIAMOND") || n.equals("EMERALD")
                || n.equals("COAL") || n.equals("CHARCOAL") || n.equals("REDSTONE") || n.equals("LAPIS_LAZULI")
                || n.equals("QUARTZ") || n.equals("AMETHYST_SHARD") || n.equals("GLOWSTONE_DUST")
                || n.equals("NETHER_STAR") || n.equals("NETHERITE_SCRAP") || n.equals("NETHERITE_INGOT")
                || n.equals("FLINT") || n.equals("LEATHER") || n.equals("FEATHER") || n.equals("STRING")
                || n.equals("GUNPOWDER") || n.equals("SLIME_BALL") || n.equals("BONE") || n.equals("ENDER_PEARL")
                || n.equals("BLAZE_ROD") || n.equals("BLAZE_POWDER") || n.equals("GHAST_TEAR")
                || n.equals("PHANTOM_MEMBRANE") || n.equals("ECHO_SHARD") || n.equals("DISC_FRAGMENT_5")
                || n.equals("HEART_OF_THE_SEA") || n.equals("NAUTILUS_SHELL") || n.equals("TRIDENT")) {
            return CATEGORY_MATERIAL;
        }
        // 方块
        if (mat.isBlock()) return CATEGORY_BLOCK;
        return CATEGORY_OTHER;
    }

    /** 获取企业行业（OTHER 是默认） */
    public String getIndustry(String enterpriseId) {
        if (enterpriseId == null) return INDUSTRY_OTHER;
        try (var conn = plugin.ksCore().dataStore().getConnection()) {
            if (conn == null) return INDUSTRY_OTHER;
            try (var ps = conn.prepareStatement(
                    "SELECT industry FROM ks_ent_enterprises WHERE id=?")) {
                ps.setString(1, enterpriseId);
                try (var rs = ps.executeQuery()) {
                    if (rs.next()) {
                        String ind = rs.getString(1);
                        return ind != null && !ind.isEmpty() ? ind : INDUSTRY_OTHER;
                    }
                }
            }
        } catch (SQLException ignored) {}
        return INDUSTRY_OTHER;
    }

    /** 企业行业是否被池禁止 */
    public static boolean isIndustryBlocked(String industry, String allowedIndustries) {
        if (industry == null || industry.isEmpty() || allowedIndustries == null || allowedIndustries.isEmpty())
            return false;
        for (String s : allowedIndustries.split(",")) {
            if (s.trim().equalsIgnoreCase(industry)) return false;
        }
        return true;
    }

    /** 战利品分类是否在池白名单中（空白名单 = 不限） */
    public static boolean isCategoryAllowed(String category, String allowedCategories) {
        if (allowedCategories == null || allowedCategories.isEmpty()) return true;
        for (String s : allowedCategories.split(",")) {
            if (s.trim().equalsIgnoreCase(category)) return true;
        }
        return false;
    }

    /** 反射调用 ks-eco-realestate 的 LandPerkManager（地块福利，与本方法的"行业"白名单是不同维度）。 */
    private Object callLandPerk(String method, Class<?>[] argTypes, Object... args) {
        if (plugin.extraModuleLoader() == null) return null;
        Object module = plugin.extraModuleLoader().getModule("ks-eco-realestate");
        if (module == null) return null;
        try {
            Object manager = module.getClass().getMethod("landPerkManager").invoke(module);
            if (manager == null) return null;
            return manager.getClass().getMethod(method, argTypes).invoke(manager, args);
        } catch (Exception e) {
            plugin.getLogger().warning("[盲盒] 反射调用地块福利模块失败(" + method + "): " + e.getMessage());
            return null;
        }
    }

    /**
     * 地块福利门控：池的 required_land_zone_types 非空时，要求玩家本人（enterpriseId==null）或企业
     * 在服务器任意位置拥有 CSV 里至少一种类型的地块，否则返回中文错误信息；无限制或已满足返回 null。
     */
    private String landGateError(Map<String, Object> pool, UUID actorUuid, String enterpriseId) {
        Object raw = pool.get("requiredLandZoneTypes");
        String csv = raw instanceof String s ? s.trim() : "";
        if (csv.isEmpty()) return null;
        for (String zoneType : csv.split(",")) {
            zoneType = zoneType.trim();
            if (zoneType.isEmpty()) continue;
            boolean owns = enterpriseId != null
                    ? Boolean.TRUE.equals(callLandPerk("enterpriseOwnsZoneType", new Class<?>[]{String.class, String.class}, enterpriseId, zoneType))
                    : actorUuid != null && Boolean.TRUE.equals(callLandPerk("ownsAnyZoneType", new Class<?>[]{UUID.class, String.class}, actorUuid, zoneType));
            if (owns) return null;
        }
        return "该盲盒池仅限拥有对应地块（" + csv + "）的玩家/企业抽取";
    }

    // ================================================================
    // 公共数据访问
    // ================================================================

    /** 列出所有卡池（含战利品计数与该池累计抽数） */
    public List<Map<String, Object>> listPools() {
        List<Map<String, Object>> out = new ArrayList<>();
        try (var conn = plugin.ksCore().dataStore().getConnection()) {
            if (conn == null) return out;
            try (var rs = conn.createStatement().executeQuery(
                    "SELECT p.*, (SELECT COUNT(*) FROM ks_bb_loot l WHERE l.pool_id=p.id) AS loot_count, " +
                    "(SELECT COUNT(*) FROM ks_bb_log g WHERE g.pool_id=p.id) AS pull_count " +
                    "FROM ks_bb_pools p ORDER BY p.created_at DESC")) {
                while (rs.next()) {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("id", rs.getString("id"));
                    m.put("name", rs.getString("name"));
                    m.put("poolType", rs.getString("pool_type"));
                    m.put("price", rs.getDouble("price"));
                    m.put("enabled", rs.getInt("enabled") == 1);
                    try { m.put("limitedOnly", rs.getInt("limited_only") == 1); } catch (SQLException ignored) { m.put("limitedOnly", false); }
                    m.put("pityMax", rs.getInt("pity_max"));
                    try { m.put("pityRules", rs.getString("pity_rules")); } catch (SQLException ignored) { m.put("pityRules", ""); }
                    m.put("pityRulesText", pityRulesText(m.get("pityRules"), m.get("pityMax")));
                    m.put("description", rs.getString("description"));
                    m.put("ownerType", rs.getString("owner_type"));
                    m.put("allowedCategories", rs.getString("allowed_categories"));
                    m.put("allowedIndustries", rs.getString("allowed_industries"));
                    try { m.put("requiredLandZoneTypes", rs.getString("required_land_zone_types")); } catch (SQLException ignored) { m.put("requiredLandZoneTypes", ""); }
                    try { m.put("minEnterpriseLevel", Math.max(1, rs.getInt("min_enterprise_level"))); } catch (SQLException ignored) { m.put("minEnterpriseLevel", 1); }
                    m.put("createdAt", rs.getLong("created_at"));
                    m.put("lootCount", rs.getInt("loot_count"));
                    m.put("pullCount", rs.getInt("pull_count"));
                    out.add(m);
                }
            }
        } catch (SQLException e) {
            plugin.getLogger().warning("列出盲盒池失败: " + e.getMessage());
        }
        return out;
    }

    /** 列出某池所有战利品（含 lore、bundle 字段，按 bundle_slot + 权重降序排列） */
    public List<Map<String, Object>> listLoot(String poolId) {
        List<Map<String, Object>> out = new ArrayList<>();
        try (var conn = plugin.ksCore().dataStore().getConnection()) {
            if (conn == null) return out;
            try (var ps = conn.prepareStatement(
                    "SELECT id, pool_id, item_material, item_data, display_name, weight, rarity, quantity, " +
                    "COALESCE(bundle_id, '') AS bundle_id, COALESCE(bundle_slot, 0) AS bundle_slot, created_at " +
                    "FROM ks_bb_loot WHERE pool_id=? ORDER BY COALESCE(bundle_slot,0) ASC, weight DESC, created_at ASC")) {
                ps.setString(1, poolId);
                try (var rs = ps.executeQuery()) {
                    while (rs.next()) {
                        Map<String, Object> m = new LinkedHashMap<>();
                        m.put("id", rs.getString("id"));
                        m.put("poolId", rs.getString("pool_id"));
                        m.put("itemMaterial", rs.getString("item_material"));
                        m.put("displayName", rs.getString("display_name"));
                        m.put("weight", rs.getInt("weight"));
                        m.put("rarity", rs.getString("rarity"));
                        m.put("quantity", rs.getInt("quantity"));
                        String bundleId = rs.getString("bundle_id");
                        m.put("bundleId", bundleId != null && !bundleId.isEmpty() ? bundleId : null);
                        m.put("bundleSlot", rs.getInt("bundle_slot"));
                        m.put("createdAt", rs.getLong("created_at"));
                        // 提取 lore（从 BLOB 反序列化）
                        List<String> lore = new ArrayList<>();
                        byte[] blob = rs.getBytes("item_data");
                        if (blob != null && blob.length > 0) {
                            try {
                                ItemStack is = ItemStack.deserializeBytes(blob);
                                if (is.hasItemMeta() && is.getItemMeta() != null && is.getItemMeta().hasLore()) {
                                    List<String> rawLore = is.getItemMeta().getLore();
                                    if (rawLore != null) lore.addAll(rawLore);
                                }
                            } catch (Exception ignored) {}
                        }
                        m.put("lore", lore);
                        out.add(m);
                    }
                }
            }
        } catch (SQLException e) {
            plugin.getLogger().warning("列出盲盒战利品失败: " + e.getMessage());
        }
        return out;
    }

    /** 单条卡池 */
    public Map<String, Object> getPool(String poolId) {
        try (var conn = plugin.ksCore().dataStore().getConnection()) {
            if (conn == null) return null;
            try (var ps = conn.prepareStatement(
                    "SELECT id, name, pool_type, price, enabled, limited_only, pity_max, pity_rules, description, " +
                    "owner_type, allowed_categories, allowed_industries, required_land_zone_types, min_enterprise_level, created_at " +
                    "FROM ks_bb_pools WHERE id=?")) {
                ps.setString(1, poolId);
                try (var rs = ps.executeQuery()) {
                    if (rs.next()) {
                        Map<String, Object> m = new LinkedHashMap<>();
                        m.put("id", rs.getString("id"));
                        m.put("name", rs.getString("name"));
                        m.put("poolType", rs.getString("pool_type"));
                        m.put("price", rs.getDouble("price"));
                        m.put("enabled", rs.getInt("enabled") == 1);
                        try { m.put("limitedOnly", rs.getInt("limited_only") == 1); } catch (SQLException ignored) { m.put("limitedOnly", false); }
                        m.put("pityMax", rs.getInt("pity_max"));
                        try { m.put("pityRules", rs.getString("pity_rules")); } catch (SQLException ignored) { m.put("pityRules", ""); }
                        m.put("pityRulesText", pityRulesText(m.get("pityRules"), m.get("pityMax")));
                        m.put("description", rs.getString("description"));
                        m.put("ownerType", rs.getString("owner_type"));
                        m.put("allowedCategories", rs.getString("allowed_categories"));
                        m.put("allowedIndustries", rs.getString("allowed_industries"));
                        String reqLand;
                        try { reqLand = rs.getString("required_land_zone_types"); } catch (SQLException ignored) { reqLand = ""; }
                        m.put("requiredLandZoneTypes", reqLand);
                        try { m.put("minEnterpriseLevel", Math.max(1, rs.getInt("min_enterprise_level"))); } catch (SQLException ignored) { m.put("minEnterpriseLevel", 1); }
                        m.put("createdAt", rs.getLong("created_at"));
                        return m;
                    }
                }
            }
        } catch (SQLException e) {
            plugin.getLogger().warning("查询盲盒池失败: " + e.getMessage());
        }
        return null;
    }

    /** 玩家在某池的保底计数 */
    public int getPityCount(UUID uuid, String poolId) {
        if (uuid == null) return 0;
        return getPityCountRaw(uuid.toString(), poolId, RARITY_RARE);
    }

    public Map<String, Integer> getPityCounts(UUID uuid, String poolId) {
        Map<String, Object> pool = getPool(poolId);
        LinkedHashMap<String, Integer> rules = parsePityRules(
                pool != null ? (String) pool.getOrDefault("pityRules", "") : "",
                pool != null && pool.get("pityMax") instanceof Number n ? n.intValue() : 0);
        Map<String, Integer> out = new LinkedHashMap<>();
        if (uuid == null) return out;
        for (String rarity : rules.keySet()) {
            out.put(rarity, getPityCountRaw(uuid.toString(), poolId, rarity));
        }
        return out;
    }

    private int getPityCountRaw(String uuidKey, String poolId, String rarity) {
        if (uuidKey == null || poolId == null || rarity == null) return 0;
        String normalized = normalizeRarity(rarity);
        try (var conn = plugin.ksCore().dataStore().getConnection()) {
            if (conn == null) return 0;
            try (var ps = conn.prepareStatement(
                    "SELECT count_since_hit FROM ks_bb_pity_rarity WHERE uuid=? AND pool_id=? AND rarity=?")) {
                ps.setString(1, uuidKey);
                ps.setString(2, poolId);
                ps.setString(3, normalized);
                try (var rs = ps.executeQuery()) {
                    if (rs.next()) return rs.getInt(1);
                }
            }
        } catch (SQLException ignored) {}
        if (RARITY_RARE.equals(normalized)) return getLegacyRarePityCount(uuidKey, poolId);
        return 0;
    }

    private int getLegacyRarePityCount(String uuidKey, String poolId) {
        try (var conn = plugin.ksCore().dataStore().getConnection()) {
            if (conn == null) return 0;
            try (var ps = conn.prepareStatement(
                    "SELECT count_since_rare FROM ks_bb_pity WHERE uuid=? AND pool_id=?")) {
                ps.setString(1, uuidKey);
                ps.setString(2, poolId);
                try (var rs = ps.executeQuery()) {
                    if (rs.next()) return rs.getInt(1);
                }
            }
        } catch (SQLException ignored) {}
        return 0;
    }

    private LinkedHashMap<String, Integer> pityRulesForPool(Map<String, Object> pool) {
        return parsePityRules(
                pool != null ? (String) pool.getOrDefault("pityRules", "") : "",
                pool != null && pool.get("pityMax") instanceof Number n ? n.intValue() : 0);
    }

    private Map<String, Integer> loadPityCounts(String uuidKey, String poolId, LinkedHashMap<String, Integer> rules) {
        Map<String, Integer> counts = new LinkedHashMap<>();
        counts.put(RARITY_RARE, getPityCountRaw(uuidKey, poolId, RARITY_RARE));
        for (String rarity : rules.keySet()) {
            counts.put(rarity, getPityCountRaw(uuidKey, poolId, rarity));
        }
        return counts;
    }

    private PityState pityStateFromCounts(Map<String, Integer> counts, LinkedHashMap<String, Integer> rules) {
        int rareCount = counts.getOrDefault(RARITY_RARE, 0);
        String triggeredRarity = null;
        int triggeredMax = 0;
        for (var rule : rules.entrySet()) {
            int count = counts.getOrDefault(rule.getKey(), 0);
            int max = rule.getValue();
            if (max > 0 && count + 1 >= max) {
                if (triggeredRarity == null || rarityRank(rule.getKey()) > rarityRank(triggeredRarity)) {
                    triggeredRarity = rule.getKey();
                    triggeredMax = max;
                }
            }
        }
        return new PityState(rareCount, triggeredRarity, triggeredMax);
    }

    private void advancePityCounts(Map<String, Integer> counts, LinkedHashMap<String, Integer> rules, String rarity) {
        int resultRank = rarityRank(rarity);
        counts.put(RARITY_RARE, resultRank >= rarityRank(RARITY_RARE) ? 0 : counts.getOrDefault(RARITY_RARE, 0) + 1);
        for (String ruleRarity : rules.keySet()) {
            int next = resultRank >= rarityRank(ruleRarity) ? 0 : counts.getOrDefault(ruleRarity, 0) + 1;
            counts.put(ruleRarity, next);
        }
    }

    private int nextRarePity(int pityBefore, String rarity) {
        return rarityRank(rarity) >= rarityRank(RARITY_RARE) ? 0 : pityBefore + 1;
    }

    private PityState pityState(String uuidKey, String poolId, Map<String, Object> pool) {
        LinkedHashMap<String, Integer> rules = pityRulesForPool(pool);
        Map<String, Integer> counts = loadPityCounts(uuidKey, poolId, rules);
        return pityStateFromCounts(counts, rules);
    }

    /** 玩家近 N 条抽取记录 */
    public List<Map<String, Object>> recentPulls(UUID uuid, int limit) {
        List<Map<String, Object>> out = new ArrayList<>();
        try (var conn = plugin.ksCore().dataStore().getConnection()) {
            if (conn == null) return out;
            try (var ps = conn.prepareStatement(
                    "SELECT g.id, g.pool_id, p.name AS pool_name, g.item_material, g.rarity, g.pulled_at " +
                    "FROM ks_bb_log g LEFT JOIN ks_bb_pools p ON p.id=g.pool_id " +
                    "WHERE g.uuid=? ORDER BY g.pulled_at DESC LIMIT ?")) {
                ps.setString(1, uuid.toString());
                ps.setInt(2, limit);
                try (var rs = ps.executeQuery()) {
                    while (rs.next()) {
                        Map<String, Object> m = new LinkedHashMap<>();
                        m.put("id", rs.getLong("id"));
                        m.put("poolId", rs.getString("pool_id"));
                        m.put("poolName", rs.getString("pool_name"));
                        m.put("itemMaterial", rs.getString("item_material"));
                        m.put("rarity", rs.getString("rarity"));
                        m.put("pulledAt", rs.getLong("pulled_at"));
                        out.add(m);
                    }
                }
            }
        } catch (SQLException e) {
            plugin.getLogger().warning("查询抽取记录失败: " + e.getMessage());
        }
        return out;
    }

    // ================================================================
    // 管理 CRUD
    // ================================================================

    /** 创建/更新卡池（id 存在则覆盖） */
    public boolean upsertPool(String id, String name, String poolType, double price,
                              boolean enabled, int pityMax, String description,
                              String ownerType, String allowedCategories, String allowedIndustries,
                              String requiredLandZoneTypes, String pityRules, int minEnterpriseLevel) {
        if (!Double.isFinite(price) || price < 0 || price > 1_000_000_000_000d) return false;
        if (minEnterpriseLevel < 1 || minEnterpriseLevel > plugin.enterpriseLevelManager().getMaxLevel()) return false;
        long now = System.currentTimeMillis() / 1000;
        String normalizedPityRules = normalizePityRules(pityRules, pityMax);
        try (var conn = plugin.ksCore().dataStore().getConnection()) {
            if (conn == null) return false;
            try (var ps = conn.prepareStatement(
                    "INSERT INTO ks_bb_pools (id, name, pool_type, price, enabled, pity_max, pity_rules, description, " +
                    "owner_type, allowed_categories, allowed_industries, required_land_zone_types, min_enterprise_level, created_at) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?) " +
                    "ON CONFLICT(id) DO UPDATE SET name=excluded.name, pool_type=excluded.pool_type, " +
                    "price=excluded.price, enabled=excluded.enabled, pity_max=excluded.pity_max, pity_rules=excluded.pity_rules, " +
                    "description=excluded.description, owner_type=excluded.owner_type, " +
                    "allowed_categories=excluded.allowed_categories, allowed_industries=excluded.allowed_industries, " +
                    "required_land_zone_types=excluded.required_land_zone_types, min_enterprise_level=excluded.min_enterprise_level")) {
                ps.setString(1, id);
                ps.setString(2, name);
                ps.setString(3, poolType);
                ps.setDouble(4, price);
                ps.setInt(5, enabled ? 1 : 0);
                ps.setInt(6, pityMax);
                ps.setString(7, normalizedPityRules);
                ps.setString(8, description != null ? description : "");
                ps.setString(9, ownerType != null ? ownerType : "PUBLIC");
                ps.setString(10, allowedCategories != null ? allowedCategories : "");
                ps.setString(11, allowedIndustries != null ? allowedIndustries : "");
                ps.setString(12, requiredLandZoneTypes != null ? requiredLandZoneTypes : "");
                ps.setInt(13, minEnterpriseLevel);
                ps.setLong(14, now);
                ps.executeUpdate();
                return true;
            }
        } catch (SQLException e) {
            plugin.getLogger().warning("保存盲盒池失败: " + e.getMessage());
        }
        return false;
    }

    public boolean upsertPool(String id, String name, String poolType, double price,
                              boolean enabled, int pityMax, String description,
                              String ownerType, String allowedCategories, String allowedIndustries,
                              String requiredLandZoneTypes, String pityRules) {
        return upsertPool(id, name, poolType, price, enabled, pityMax, description,
                ownerType, allowedCategories, allowedIndustries, requiredLandZoneTypes, pityRules, 1);
    }

    /** Marks a pool as available exclusively through the limited-sale blind box entry. */
    public boolean setLimitedOnly(String poolId, boolean limitedOnly) {
        try (var conn = plugin.ksCore().dataStore().getConnection()) {
            if (conn == null) return false;
            try (var ps = conn.prepareStatement("UPDATE ks_bb_pools SET limited_only=? WHERE id=?")) {
                ps.setInt(1, limitedOnly ? 1 : 0);
                ps.setString(2, poolId);
                return ps.executeUpdate() > 0;
            }
        } catch (SQLException e) {
            plugin.getLogger().warning("保存盲盒限时入口设置失败: " + e.getMessage());
            return false;
        }
    }

    private static boolean isLimitedOnly(Map<String, Object> pool) {
        return pool != null && Boolean.TRUE.equals(pool.get("limitedOnly"));
    }

    private static String limitedOnlyError(Map<String, Object> pool) {
        return isLimitedOnly(pool) ? "该盲盒仅可通过限时盲盒入口抽取" : null;
    }

    /** upsertPool 兼容旧调用（无 allowedIndustries/requiredLandZoneTypes 字段时） */
    public boolean upsertPool(String id, String name, String poolType, double price,
                              boolean enabled, int pityMax, String description,
                              String ownerType, String allowedCategories, String allowedIndustries,
                              String requiredLandZoneTypes) {
        return upsertPool(id, name, poolType, price, enabled, pityMax, description,
                ownerType, allowedCategories, allowedIndustries, requiredLandZoneTypes, "");
    }

    public boolean upsertPool(String id, String name, String poolType, double price,
                              boolean enabled, int pityMax, String description,
                              String ownerType, String allowedCategories, String allowedIndustries) {
        return upsertPool(id, name, poolType, price, enabled, pityMax, description, ownerType, allowedCategories, allowedIndustries, "");
    }

    /** upsertPool 兼容旧调用（无 ownerType/allowed 字段时） */
    public boolean upsertPool(String id, String name, String poolType, double price,
                              boolean enabled, int pityMax, String description) {
        return upsertPool(id, name, poolType, price, enabled, pityMax, description, "PUBLIC", "", "", "");
    }

    public boolean deletePool(String id) {
        try (var conn = plugin.ksCore().dataStore().getConnection()) {
            if (conn == null) return false;
            try (var ps = conn.prepareStatement("DELETE FROM ks_bb_loot WHERE pool_id=?")) {
                ps.setString(1, id);
                ps.executeUpdate();
            }
            try (var ps = conn.prepareStatement("DELETE FROM ks_bb_pools WHERE id=?")) {
                ps.setString(1, id);
                return ps.executeUpdate() > 0;
            }
        } catch (SQLException e) {
            plugin.getLogger().warning("删除盲盒池失败: " + e.getMessage());
        }
        return false;
    }

    /** 新增战利品（id 为空自动生成） */
    public String addLoot(String poolId, String itemMaterial, String displayName,
                          int weight, String rarity, int quantity) {
        String id = UUID.randomUUID().toString();
        long now = System.currentTimeMillis() / 1000;
        try (var conn = plugin.ksCore().dataStore().getConnection()) {
            if (conn == null) return null;
            try (var ps = conn.prepareStatement(
                    "INSERT INTO ks_bb_loot (id, pool_id, item_material, item_data, display_name, weight, rarity, quantity, created_at) " +
                    "VALUES (?,?,?,?,?,?,?,?,?)")) {
                ps.setString(1, id);
                ps.setString(2, poolId);
                ps.setString(3, itemMaterial);
                ps.setBytes(4, null); // 后续编辑可支持 BLOB 覆盖；目前以 material+displayName 重建
                ps.setString(5, displayName != null ? displayName : "");
                ps.setInt(6, weight);
                ps.setString(7, rarity);
                ps.setInt(8, quantity);
                ps.setLong(9, now);
                ps.executeUpdate();
                return id;
            }
        } catch (SQLException e) {
            plugin.getLogger().warning("添加战利品失败: " + e.getMessage());
        }
        return null;
    }

    public boolean deleteLoot(String lootId) {
        try (var conn = plugin.ksCore().dataStore().getConnection()) {
            if (conn == null) return false;
            try (var ps = conn.prepareStatement("DELETE FROM ks_bb_loot WHERE id=?")) {
                ps.setString(1, lootId);
                return ps.executeUpdate() > 0;
            }
        } catch (SQLException e) {
            plugin.getLogger().warning("删除战利品失败: " + e.getMessage());
        }
        return false;
    }

    /**
     * 以完整 NBT ItemStack 新增战利品（管理员 GUI 用）。
     * item_data 存 serializeAsBytes() blob，material/displayName 同步提取方便查询。
     * bundleId 不为 null 时作为 bundle 主条目（bundle_slot=0）；为 null 时为独立条目。
     */
    public String addLootWithData(String poolId, ItemStack item, String rarity, int weight, int quantity) {
        return addLootWithData(poolId, item, rarity, weight, quantity, null);
    }

    public String addLootWithData(String poolId, ItemStack item, String rarity, int weight, int quantity, String bundleId) {
        String id = UUID.randomUUID().toString();
        // 若 bundleId 为 null 且外部要求新建 bundle，在此生成
        // （GUI 会先调用本方法拿到 id 后，把 id 当 bundleId 用于后续 addLootToBundle）
        long now = System.currentTimeMillis() / 1000;
        String materialName = item.getType().name();
        String displayName = "";
        if (item.hasItemMeta() && item.getItemMeta() != null && item.getItemMeta().hasDisplayName()) {
            displayName = item.getItemMeta().getDisplayName();
        }
        byte[] blob;
        try {
            blob = item.serializeAsBytes();
        } catch (Exception e) {
            plugin.getLogger().warning("盲盒 serializeAsBytes 失败，回退存 null: " + e.getMessage());
            blob = null;
        }
        try (var conn = plugin.ksCore().dataStore().getConnection()) {
            if (conn == null) return null;
            try (var ps = conn.prepareStatement(
                    "INSERT INTO ks_bb_loot (id,pool_id,item_material,item_data,display_name,weight,rarity,quantity,bundle_id,bundle_slot,created_at) " +
                    "VALUES (?,?,?,?,?,?,?,?,?,0,?)")) {
                ps.setString(1, id);
                ps.setString(2, poolId);
                ps.setString(3, materialName);
                ps.setBytes(4, blob);
                ps.setString(5, displayName);
                ps.setInt(6, weight);
                ps.setString(7, rarity);
                ps.setInt(8, quantity);
                ps.setString(9, bundleId); // null = 独立条目; 非 null = bundle 主条目
                ps.setLong(10, now);
                ps.executeUpdate();
                return id;
            }
        } catch (SQLException e) {
            plugin.getLogger().warning("addLootWithData 失败: " + e.getMessage());
        }
        return null;
    }

    /**
     * 向已有 bundle 中追加一个附加物品（bundle_slot > 0）。
     * @param poolId     所属卡池
     * @param bundleId   bundle 分组 id（由主条目 addLootWithData 返回时一并传入）
     * @param item       附加物品 ItemStack
     * @param bundleSlot bundle 内的顺序（1, 2, ...）
     * @param quantity   给予数量
     * @return 新 loot id，失败 null
     */
    public String addLootToBundle(String poolId, String bundleId, ItemStack item, int bundleSlot, int quantity) {
        // 附加物品继承主条目的 rarity（读取 primary entry 的 rarity）
        String rarity = RARITY_COMMON;
        try (var conn = plugin.ksCore().dataStore().getConnection()) {
            if (conn == null) return null;
            try (var ps = conn.prepareStatement(
                    "SELECT rarity FROM ks_bb_loot WHERE bundle_id=? AND COALESCE(bundle_slot,0)=0 LIMIT 1")) {
                ps.setString(1, bundleId);
                try (var rs = ps.executeQuery()) {
                    if (rs.next()) rarity = rs.getString(1);
                }
            }
        } catch (SQLException ignored) {}

        String id = UUID.randomUUID().toString();
        long now = System.currentTimeMillis() / 1000;
        String materialName = item.getType().name();
        String displayName = (item.hasItemMeta() && item.getItemMeta() != null && item.getItemMeta().hasDisplayName())
                ? item.getItemMeta().getDisplayName() : "";
        byte[] blob;
        try { blob = item.serializeAsBytes(); }
        catch (Exception e) { blob = null; }

        try (var conn = plugin.ksCore().dataStore().getConnection()) {
            if (conn == null) return null;
            try (var ps = conn.prepareStatement(
                    "INSERT INTO ks_bb_loot (id,pool_id,item_material,item_data,display_name,weight,rarity,quantity,bundle_id,bundle_slot,created_at) " +
                    "VALUES (?,?,?,?,?,0,?,?,?,?,?)")) {
                ps.setString(1, id);
                ps.setString(2, poolId);
                ps.setString(3, materialName);
                ps.setBytes(4, blob);
                ps.setString(5, displayName);
                ps.setString(6, rarity);
                ps.setInt(7, quantity);
                ps.setString(8, bundleId);
                ps.setInt(9, bundleSlot);
                ps.setLong(10, now);
                ps.executeUpdate();
                return id;
            }
        } catch (SQLException e) {
            plugin.getLogger().warning("addLootToBundle 失败: " + e.getMessage());
        }
        return null;
    }

    /**
     * 修改战利品的稀有度/权重/数量（不改物品本体 BLOB）。
     * 若是 bundle 主条目，同时更新同 bundle 所有附加条目的 rarity。
     */
    public boolean updateLoot(String lootId, String rarity, int weight, int quantity) {
        try (var conn = plugin.ksCore().dataStore().getConnection()) {
            if (conn == null) return false;
            conn.setAutoCommit(false);
            // 查当前 bundle_id / bundle_slot
            String bundleId = null;
            int bundleSlot = 0;
            try (var ps = conn.prepareStatement(
                    "SELECT COALESCE(bundle_id,'') AS bundle_id, COALESCE(bundle_slot,0) AS bundle_slot FROM ks_bb_loot WHERE id=?")) {
                ps.setString(1, lootId);
                try (var rs = ps.executeQuery()) {
                    if (!rs.next()) { conn.rollback(); return false; }
                    bundleId = rs.getString("bundle_id");
                    bundleSlot = rs.getInt("bundle_slot");
                }
            }
            // 更新主条目
            try (var ps = conn.prepareStatement(
                    "UPDATE ks_bb_loot SET rarity=?, weight=?, quantity=? WHERE id=?")) {
                ps.setString(1, rarity);
                ps.setInt(2, weight);
                ps.setInt(3, quantity);
                ps.setString(4, lootId);
                ps.executeUpdate();
            }
            // 若是 bundle 主条目，同步更新附加条目的 rarity（附加条目 weight=0，quantity/rarity 跟随主条目 rarity）
            if (bundleId != null && !bundleId.isEmpty() && bundleSlot == 0) {
                try (var ps = conn.prepareStatement(
                        "UPDATE ks_bb_loot SET rarity=? WHERE bundle_id=? AND COALESCE(bundle_slot,0)>0")) {
                    ps.setString(1, rarity);
                    ps.setString(2, bundleId);
                    ps.executeUpdate();
                }
            }
            conn.commit();
            return true;
        } catch (SQLException e) {
            plugin.getLogger().warning("updateLoot 失败: " + e.getMessage());
        }
        return false;
    }

    /** 替换战利品的物品本体 BLOB（保持 rarity/weight/quantity 不变） */
    public boolean replaceLootItem(String lootId, ItemStack newItem) {
        byte[] blob;
        try { blob = newItem.serializeAsBytes(); }
        catch (Exception e) { blob = null; }
        String materialName = newItem.getType().name();
        String displayName = (newItem.hasItemMeta() && newItem.getItemMeta() != null && newItem.getItemMeta().hasDisplayName())
                ? newItem.getItemMeta().getDisplayName() : "";
        try (var conn = plugin.ksCore().dataStore().getConnection()) {
            if (conn == null) return false;
            try (var ps = conn.prepareStatement(
                    "UPDATE ks_bb_loot SET item_data=?, item_material=?, display_name=? WHERE id=?")) {
                ps.setBytes(1, blob);
                ps.setString(2, materialName);
                ps.setString(3, displayName);
                ps.setString(4, lootId);
                return ps.executeUpdate() > 0;
            }
        } catch (SQLException e) {
            plugin.getLogger().warning("replaceLootItem 失败: " + e.getMessage());
        }
        return false;
    }

    /**
     * 反序列化某条战利品的 ItemStack（优先读 BLOB，失败 fallback 到 Material）。
     * 返回 null 表示 lootId 不存在。
     */
    public ItemStack getLootItemStack(String lootId) {
        try (var conn = plugin.ksCore().dataStore().getConnection()) {
            if (conn == null) return null;
            try (var ps = conn.prepareStatement(
                    "SELECT item_data, item_material, display_name, quantity FROM ks_bb_loot WHERE id=?")) {
                ps.setString(1, lootId);
                try (var rs = ps.executeQuery()) {
                    if (!rs.next()) return null;
                    byte[] blob = rs.getBytes("item_data");
                    int qty = Math.max(1, rs.getInt("quantity"));
                    if (blob != null && blob.length > 0) {
                        try {
                            return ItemStack.deserializeBytes(blob);
                        } catch (Exception ignored) {}
                    }
                    String matName = rs.getString("item_material");
                    try {
                        Material mat = Material.valueOf(matName);
                        ItemStack fallback = new ItemStack(mat, qty);
                        String dn = rs.getString("display_name");
                        if (dn != null && !dn.isEmpty()) {
                            var meta = fallback.getItemMeta();
                            if (meta != null) {
                                meta.setDisplayName(ChatColor.translateAlternateColorCodes('&', dn));
                                fallback.setItemMeta(meta);
                            }
                        }
                        return fallback;
                    } catch (IllegalArgumentException ignored) {}
                }
            }
        } catch (SQLException e) {
            plugin.getLogger().warning("getLootItemStack 失败: " + e.getMessage());
        }
        return null;
    }

    // ================================================================
    // 内部：单次查询加载所有战利品（含预构建 ItemStack，避免逐条 getLootItemStack）
    // ================================================================

    /** 仅含数据库字节和纯数据，可安全跨到工作线程。 */
    private record LootSnapshot(Map<String, Object> meta, byte[] itemData) {
        private LootSnapshot {
            meta = Collections.unmodifiableMap(new LinkedHashMap<>(meta));
            itemData = itemData == null ? null : itemData.clone();
        }
    }

    /** 仅在服务器线程构造和使用。 */
    private record LootFull(Map<String, Object> meta, ItemStack item) {}

    private record PreparedDraw(LootSnapshot chosen, List<LootSnapshot> rewards, PityState pity) {}

    private record PullBatchPreparation(
            Map<String, Object> pool,
            List<PreparedDraw> draws,
            LinkedHashMap<String, Integer> pityRules,
            Map<String, Integer> finalPityCounts
    ) {}

    private record PullLog(String material, String rarity) {}

    /**
     * 一次 SQL 查询加载指定池的所有战利品，并反序列化 BLOB → ItemStack。
     * 比 listLoot() + N×getLootItemStack() 少 N 次数据库往返。
     */
    private List<LootSnapshot> loadLootSnapshots(String poolId) {
        List<LootSnapshot> out = new ArrayList<>();
        try (var conn = plugin.ksCore().dataStore().getConnection()) {
            if (conn == null) return out;
            try (var ps = conn.prepareStatement(
                    "SELECT id, pool_id, item_material, item_data, display_name, weight, rarity, quantity, " +
                    "COALESCE(bundle_id,'') AS bundle_id, COALESCE(bundle_slot,0) AS bundle_slot " +
                    "FROM ks_bb_loot WHERE pool_id=? ORDER BY COALESCE(bundle_slot,0) ASC, weight DESC")) {
                ps.setString(1, poolId);
                try (var rs = ps.executeQuery()) {
                    while (rs.next()) {
                        Map<String, Object> m = new LinkedHashMap<>();
                        m.put("id", rs.getString("id"));
                        m.put("itemMaterial", rs.getString("item_material"));
                        m.put("displayName", rs.getString("display_name"));
                        m.put("weight", rs.getInt("weight"));
                        m.put("rarity", rs.getString("rarity"));
                        m.put("quantity", rs.getInt("quantity"));
                        String bid = rs.getString("bundle_id");
                        m.put("bundleId", bid.isEmpty() ? null : bid);
                        m.put("bundleSlot", rs.getInt("bundle_slot"));

                        byte[] blob = rs.getBytes("item_data");
                        out.add(new LootSnapshot(m, blob));
                    }
                }
            }
        } catch (SQLException e) {
            plugin.getLogger().warning("loadLootSnapshots 失败: " + e.getMessage());
        }
        return out;
    }

    /** 服务器线程解码数据库快照，任何 ItemStack/PDC 访问都留在这里。 */
    private List<LootFull> decodeLootSnapshots(List<LootSnapshot> snapshots) {
        List<LootFull> out = new ArrayList<>(snapshots.size());
        for (LootSnapshot snapshot : snapshots) {
            ItemStack item = null;
            byte[] blob = snapshot.itemData();
            if (blob != null && blob.length > 0) {
                try { item = ItemStack.deserializeBytes(blob); }
                catch (RuntimeException ignored) {}
            }
            if (item == null) {
                try {
                    int qty = Math.max(1, ((Number) snapshot.meta().getOrDefault("quantity", 1)).intValue());
                    Material mat = Material.valueOf((String) snapshot.meta().get("itemMaterial"));
                    item = new ItemStack(mat, Math.min(qty, mat.getMaxStackSize()));
                    String displayName = (String) snapshot.meta().get("displayName");
                    if (displayName != null && !displayName.isEmpty()) {
                        var meta = item.getItemMeta();
                        if (meta != null) {
                            meta.setDisplayName(ChatColor.translateAlternateColorCodes('&', displayName));
                            item.setItemMeta(meta);
                        }
                    }
                } catch (RuntimeException ignored) {}
            }
            out.add(new LootFull(snapshot.meta(), item));
        }
        return out;
    }

    private List<LootFull> loadLootFull(String poolId) {
        return decodeLootSnapshots(loadLootSnapshots(poolId));
    }

    private LootSnapshot chooseLootSnapshot(List<LootSnapshot> loots, String minimumRarity) {
        List<LootSnapshot> candidates = new ArrayList<>();
        int minRank = minimumRarity == null ? -1 : rarityRank(minimumRarity);
        for (LootSnapshot loot : loots) {
            int slot = loot.meta().get("bundleSlot") instanceof Number n ? n.intValue() : 0;
            int weight = loot.meta().get("weight") instanceof Number n ? n.intValue() : 0;
            String rarity = (String) loot.meta().get("rarity");
            if (slot > 0 || weight <= 0 || (minRank >= 0 && rarityRank(rarity) < minRank)) continue;
            candidates.add(loot);
        }
        if (candidates.isEmpty()) {
            for (LootSnapshot loot : loots) {
                if ((loot.meta().get("bundleSlot") instanceof Number n ? n.intValue() : 0) == 0) candidates.add(loot);
            }
            if (candidates.isEmpty()) candidates.addAll(loots);
        }
        int total = candidates.stream().mapToInt(l -> Math.max(0, ((Number) l.meta().get("weight")).intValue())).sum();
        int random = new Random().nextInt(Math.max(1, total));
        int accumulated = 0;
        for (LootSnapshot loot : candidates) {
            accumulated += Math.max(0, ((Number) loot.meta().get("weight")).intValue());
            if (random < accumulated) return loot;
        }
        return candidates.get(candidates.size() - 1);
    }

    private List<LootSnapshot> resolveBundleSnapshots(LootSnapshot chosen, List<LootSnapshot> allLoots) {
        String bundleId = chosen.meta().get("bundleId") instanceof String s && !s.isEmpty() ? s : null;
        if (bundleId == null) return List.of(chosen);
        List<LootSnapshot> items = new ArrayList<>();
        for (LootSnapshot loot : allLoots) {
            if (bundleId.equals(loot.meta().get("bundleId"))) items.add(loot);
        }
        return items.isEmpty() ? List.of(chosen) : List.copyOf(items);
    }

    /** 从 LootFull 列表中权重随机（pityHit=true 时只从 RARE+ 选） */
    private LootFull chooseLootFull(List<LootFull> loots, boolean pityHit) {
        return chooseLootFull(loots, pityHit ? RARITY_RARE : null);
    }

    private LootFull chooseLootFull(List<LootFull> loots, String minimumRarity) {
        List<LootFull> pool = new ArrayList<>();
        int minRank = minimumRarity == null ? -1 : rarityRank(minimumRarity);
        for (var l : loots) {
            int slot = l.meta().get("bundleSlot") instanceof Number n ? n.intValue() : 0;
            if (slot > 0) continue;
            String rarity = (String) l.meta().get("rarity");
            int w = l.meta().get("weight") instanceof Number n ? n.intValue() : 0;
            if (w <= 0) continue;
            if (minRank >= 0 && rarityRank(rarity) < minRank) continue;
            pool.add(l);
        }
        if (pool.isEmpty()) {
            for (var l : loots) {
                if ((l.meta().get("bundleSlot") instanceof Number n ? n.intValue() : 0) == 0) pool.add(l);
            }
            if (pool.isEmpty()) pool = loots;
        }
        int total = pool.stream().mapToInt(l -> ((Number) l.meta().get("weight")).intValue()).sum();
        int r = new Random().nextInt(Math.max(1, total));
        int acc = 0;
        for (var l : pool) {
            acc += ((Number) l.meta().get("weight")).intValue();
            if (r < acc) return l;
        }
        return pool.get(pool.size() - 1);
    }

    /** 将选中条目展开为 bundle 全部条目 */
    private List<LootFull> resolveBundleFull(LootFull chosen, List<LootFull> allLoots) {
        String bundleId = chosen.meta().get("bundleId") instanceof String s && !s.isEmpty() ? s : null;
        if (bundleId == null) return List.of(chosen);
        List<LootFull> items = new ArrayList<>();
        for (var l : allLoots) {
            String bid = l.meta().get("bundleId") instanceof String s ? s : null;
            if (bundleId.equals(bid)) items.add(l);
        }
        if (items.isEmpty()) items.add(chosen);
        return items;
    }

    /** 写 log + 保底（可在 async 线程调用） */
    private void writeLogAndPity(String uuidKey, String poolId, String material, String rarity, int pityBefore) {
        Map<String, Object> pool = getPool(poolId);
        LinkedHashMap<String, Integer> rules = parsePityRules(
                pool != null ? (String) pool.getOrDefault("pityRules", "") : "",
                pool != null && pool.get("pityMax") instanceof Number n ? n.intValue() : 0);
        Map<String, Integer> beforeCounts = loadPityCounts(uuidKey, poolId, rules);
        int resultRank = rarityRank(rarity);
        int nextPity = resultRank >= rarityRank(RARITY_RARE) ? 0 : pityBefore + 1;
        long now = System.currentTimeMillis() / 1000;
        try (var conn = plugin.ksCore().dataStore().getConnection()) {
            if (conn == null) return;
            conn.setAutoCommit(false);
            try (var ps = conn.prepareStatement(
                    "INSERT INTO ks_bb_log (uuid, pool_id, item_material, rarity, pulled_at) VALUES (?,?,?,?,?)")) {
                ps.setString(1, uuidKey); ps.setString(2, poolId);
                ps.setString(3, material); ps.setString(4, rarity); ps.setLong(5, now);
                ps.executeUpdate();
            }
            try (var ps = conn.prepareStatement(
                    "INSERT INTO ks_bb_pity (uuid, pool_id, count_since_rare, updated_at) VALUES (?,?,?,?) " +
                    "ON CONFLICT(uuid, pool_id) DO UPDATE SET count_since_rare=excluded.count_since_rare, updated_at=excluded.updated_at")) {
                ps.setString(1, uuidKey); ps.setString(2, poolId);
                ps.setInt(3, nextPity); ps.setLong(4, now);
                ps.executeUpdate();
            }
            for (String ruleRarity : rules.keySet()) {
                int before = beforeCounts.getOrDefault(ruleRarity, 0);
                int next = resultRank >= rarityRank(ruleRarity) ? 0 : before + 1;
                try (var ps = conn.prepareStatement(
                        "INSERT INTO ks_bb_pity_rarity (uuid, pool_id, rarity, count_since_hit, updated_at) VALUES (?,?,?,?,?) " +
                        "ON CONFLICT(uuid, pool_id, rarity) DO UPDATE SET count_since_hit=excluded.count_since_hit, updated_at=excluded.updated_at")) {
                    ps.setString(1, uuidKey);
                    ps.setString(2, poolId);
                    ps.setString(3, ruleRarity);
                    ps.setInt(4, next);
                    ps.setLong(5, now);
                    ps.executeUpdate();
                }
            }
            conn.commit();
        } catch (SQLException e) {
            plugin.getLogger().warning("盲盒 log/pity 写入失败: " + e.getMessage());
        }
    }

    // ================================================================
    // 抽取主流程
    // ================================================================

    public static class PullResult {
        public final boolean success;
        public final String error;
        public final String poolId;
        public final String poolName;
        public final String itemMaterial;
        public final String itemDisplayName;
        public final String rarity;
        public final int quantity;
        public final int pityCount;        // 抽前保底计数
        public final int pityAfter;        // 抽后
        public final boolean inventoryFull;
        public final boolean storedToBox;  // 暂存箱
        public final int pityTriggered;    // 本次是否触发保底

        private PullResult(boolean success, String error, String poolId, String poolName,
                           String itemMaterial, String itemDisplayName, String rarity, int quantity,
                           int pityCount, int pityAfter, boolean inventoryFull, boolean storedToBox,
                           int pityTriggered) {
            this.success = success;
            this.error = error;
            this.poolId = poolId;
            this.poolName = poolName;
            this.itemMaterial = itemMaterial;
            this.itemDisplayName = itemDisplayName;
            this.rarity = rarity;
            this.quantity = quantity;
            this.pityCount = pityCount;
            this.pityAfter = pityAfter;
            this.inventoryFull = inventoryFull;
            this.storedToBox = storedToBox;
            this.pityTriggered = pityTriggered;
        }

        public static PullResult error(String msg) {
            return new PullResult(false, msg, null, null, null, null, null, 0, 0, 0, false, false, 0);
        }

        public static PullResult ok(String poolId, String poolName, String material, String display,
                                    String rarity, int qty, int pityBefore, int pityAfter,
                                    boolean invFull, boolean stored, int pityTriggered) {
            return new PullResult(true, null, poolId, poolName, material, display, rarity, qty,
                    pityBefore, pityAfter, invFull, stored, pityTriggered);
        }

        public Map<String, Object> toMap() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("success", success);
            if (error != null) m.put("error", error);
            if (poolId != null) m.put("poolId", poolId);
            if (poolName != null) m.put("poolName", poolName);
            if (itemMaterial != null) m.put("itemMaterial", itemMaterial);
            if (itemDisplayName != null) m.put("itemDisplayName", itemDisplayName);
            if (rarity != null) m.put("rarity", rarity);
            m.put("quantity", quantity);
            m.put("pityCount", pityCount);
            m.put("pityAfter", pityAfter);
            m.put("inventoryFull", inventoryFull);
            m.put("storedToBox", storedToBox);
            m.put("pityTriggered", pityTriggered);
            return m;
        }
    }

    /** 玩家单抽 */
    public PullResult pull(UUID playerUuid, String poolId) {
        if (playerUuid == null || poolId == null) return PullResult.error("参数无效");
        Map<String, Object> pool = getPool(poolId);
        if (pool == null) return PullResult.error("盲盒池不存在");
        if (!(boolean) pool.get("enabled")) return PullResult.error("该盲盒池已停用");
        String limitedOnlyError = limitedOnlyError(pool);
        if (limitedOnlyError != null) return PullResult.error(limitedOnlyError);
        String landGateErr = landGateError(pool, playerUuid, null);
        if (landGateErr != null) return PullResult.error(landGateErr);

        double price = ((Number) pool.get("price")).doubleValue();

        // 1) 扣款（BuiltinEconomy 或 Vault）
        Player online = Bukkit.getPlayer(playerUuid);
        if (online == null) return PullResult.error("玩家不在线");

        if (!chargePlayer(online, price)) return PullResult.error("余额不足");

        // 2) 加载战利品（一次查询，含预反序列化 ItemStack）
        List<LootFull> loots = loadLootFull(poolId);
        if (loots.isEmpty()) {
            refundPlayer(online, price);
            return PullResult.error("该盲盒池暂无可抽物品");
        }

        // 3) 保底判定
        PityState pity = pityState(playerUuid.toString(), poolId, pool);
        int pityBefore = pity.rareCount();
        LootFull chosen = chooseLootFull(loots, pity.triggeredRarity());
        List<LootFull> toGive = resolveBundleFull(chosen, loots);

        // 4) 主条目信息
        String materialName = (String) chosen.meta().get("itemMaterial");
        String displayName = (String) chosen.meta().get("displayName");
        String rarity = (String) chosen.meta().get("rarity");
        int qty = Math.max(1, ((Number) chosen.meta().get("quantity")).intValue());

        // 5) 发放（使用预构建 ItemStack，不再逐条查 DB）
        boolean[] state = giveItems(online, playerUuid, toGive, "blindbox:" + poolId);
        boolean invFull = state[0], stored = state[1];
        ItemStack displayItem = toGive.isEmpty() || toGive.get(0).item() == null ? null : toGive.get(0).item().clone();
        if (displayItem == null) {
            refundPlayer(online, price);
            return PullResult.error("战利品材质无效: " + materialName);
        }

        // 6) 写 log + 保底
        writeLogAndPity(playerUuid.toString(), poolId, materialName, rarity, pityBefore);

        // 7) 反馈
        String showName = (displayItem.hasItemMeta() && displayItem.getItemMeta() != null && displayItem.getItemMeta().hasDisplayName())
                ? displayItem.getItemMeta().getDisplayName() : materialName;
        String bundleSuffix = toGive.size() > 1 ? " §7(+§e" + (toGive.size() - 1) + "§7个附加物品)" : "";
        online.sendMessage("§6[盲盒] §e你从 §b" + pool.get("name") + "§e 抽到: " +
                showName + bundleSuffix + " §7(稀有度:" + rarityColor(rarity) + rarity + "§7)");
        if (stored) online.sendMessage("§c背包已满，物品已暂存到暂存箱，请使用 /kseco storage 提取。");

        int pityAfter = nextRarePity(pityBefore, rarity);
        return PullResult.ok(poolId, (String) pool.get("name"), materialName, displayName, rarity, qty,
                pityBefore, pityAfter, invFull, stored, pity.triggered() ? pity.triggeredMax() : 0);
    }

    /** 单抽但不扣盲盒池价格；用于限时盲盒等外部已经完成计费的入口。 */
    public PullResult pullNoCharge(UUID playerUuid, String poolId, String sourceTag) {
        if (playerUuid == null || poolId == null) return PullResult.error("参数无效");
        Map<String, Object> pool = getPool(poolId);
        if (pool == null) return PullResult.error("盲盒池不存在");
        if (!(boolean) pool.get("enabled")) return PullResult.error("该盲盒池已停用");
        String landGateErr = landGateError(pool, playerUuid, null);
        if (landGateErr != null) return PullResult.error(landGateErr);
        List<LootFull> loots = loadLootFull(poolId);
        if (loots.isEmpty()) return PullResult.error("该盲盒池暂无可抽物品");
        String tag = sourceTag == null || sourceTag.isBlank() ? "blindbox:" + poolId : sourceTag;
        return doSinglePull(playerUuid, poolId, pool, loots, tag);
    }

    // ================================================================
    // 异步抽取（GUI 使用，DB 操作全部在 async 线程，主线程只做扣款/发物品）
    // ================================================================

    /**
     * 异步单抽。回调在 Bukkit 主线程执行。
     * 流程：worker 读 DB/抽签 → main 解码、扣款和发物品 → worker 原子写 log+pity → main 回调。
     */
    public void pullAsync(UUID playerUuid, String poolId, Consumer<PullResult> callback) {
        pullBatchAsync(playerUuid, poolId, 1, "blindbox:" + poolId, true, true, results ->
                callback.accept(results.isEmpty() ? PullResult.error("抽取失败") : results.get(0)));
    }

    /**
     * 异步十连抽。回调在主线程，参数为 10 个 PullResult 的 List。
     */
    public void pullTenAsync(UUID playerUuid, String poolId, Consumer<List<PullResult>> callback) {
        pullBatchAsync(playerUuid, poolId, 10, "blindbox:" + poolId, true, true, callback);
    }

    /** 外部入口已完成计费时使用；回调固定在服务器线程。 */
    public void pullNoChargeBatchAsync(UUID playerUuid, String poolId, int quantity, String sourceTag,
                                       Consumer<List<PullResult>> callback) {
        int count = Math.max(1, Math.min(64, quantity));
        String tag = sourceTag == null || sourceTag.isBlank() ? "blindbox:" + poolId : sourceTag;
        pullBatchAsync(playerUuid, poolId, count, tag, false, false, callback);
    }

    private void pullBatchAsync(UUID playerUuid, String poolId, int quantity, String sourceTag,
                                boolean chargePoolPrice, boolean enforceLimitedOnly,
                                Consumer<List<PullResult>> callback) {
        if (!Bukkit.isPrimaryThread()) {
            Bukkit.getScheduler().runTask(plugin, () -> pullBatchAsync(playerUuid, poolId, quantity, sourceTag,
                    chargePoolPrice, enforceLimitedOnly, callback));
            return;
        }
        Player online = playerUuid == null ? null : Bukkit.getPlayer(playerUuid);
        if (online == null || !online.isOnline()) {
            callback.accept(List.of(PullResult.error("玩家不在线")));
            return;
        }
        if (poolId == null || poolId.isBlank()) {
            callback.accept(List.of(PullResult.error("盲盒池不存在")));
            return;
        }
        String operationKey = playerUuid + ":" + poolId;
        if (!activeAsyncPulls.add(operationKey)) {
            callback.accept(List.of(PullResult.error("该盲盒正在结算，请稍候")));
            return;
        }

        plugin.asyncWorkPool().execute(() -> {
            try {
                Map<String, Object> pool = getPool(poolId);
                String error = validateAsyncPool(pool, playerUuid, enforceLimitedOnly);
                if (error != null) {
                    finishPullBatch(operationKey, callback, List.of(PullResult.error(error)));
                    return;
                }
                List<LootSnapshot> loots = loadLootSnapshots(poolId);
                if (loots.isEmpty()) {
                    finishPullBatch(operationKey, callback, List.of(PullResult.error("该盲盒池暂无可抽物品")));
                    return;
                }
                LinkedHashMap<String, Integer> rules = pityRulesForPool(pool);
                Map<String, Integer> pityCounts = loadPityCounts(playerUuid.toString(), poolId, rules);
                List<PreparedDraw> draws = new ArrayList<>(quantity);
                for (int i = 0; i < quantity; i++) {
                    PityState pity = pityStateFromCounts(pityCounts, rules);
                    LootSnapshot chosen = chooseLootSnapshot(loots, pity.triggeredRarity());
                    draws.add(new PreparedDraw(chosen, resolveBundleSnapshots(chosen, loots), pity));
                    advancePityCounts(pityCounts, rules, (String) chosen.meta().get("rarity"));
                }
                PullBatchPreparation prepared = new PullBatchPreparation(
                        Collections.unmodifiableMap(new LinkedHashMap<>(pool)), List.copyOf(draws),
                        new LinkedHashMap<>(rules), Map.copyOf(pityCounts));
                Bukkit.getScheduler().runTask(plugin, () -> {
                    Player currentPlayer = Bukkit.getPlayer(playerUuid);
                    if (currentPlayer == null || !currentPlayer.isOnline()) {
                        activeAsyncPulls.remove(operationKey);
                        callback.accept(List.of(PullResult.error("玩家已离线")));
                        return;
                    }
                    settlePreparedPullBatch(operationKey, currentPlayer, playerUuid, poolId, sourceTag,
                            chargePoolPrice, prepared, callback);
                });
            } catch (RuntimeException e) {
                plugin.getLogger().warning("准备盲盒异步抽取失败: " + e.getMessage());
                finishPullBatch(operationKey, callback, List.of(PullResult.error("准备抽取失败")));
            }
        });
    }

    private String validateAsyncPool(Map<String, Object> pool, UUID playerUuid, boolean enforceLimitedOnly) {
        if (pool == null) return "盲盒池不存在";
        if (!Boolean.TRUE.equals(pool.get("enabled"))) return "该盲盒池已停用";
        if (enforceLimitedOnly) {
            String error = limitedOnlyError(pool);
            if (error != null) return error;
        }
        return landGateError(pool, playerUuid, null);
    }

    private void settlePreparedPullBatch(String operationKey, Player online, UUID playerUuid, String poolId,
                                         String sourceTag, boolean chargePoolPrice, PullBatchPreparation prepared,
                                         Consumer<List<PullResult>> callback) {
        if (!online.isOnline()) {
            activeAsyncPulls.remove(operationKey);
            callback.accept(List.of(PullResult.error("玩家已离线")));
            return;
        }
        double unitPrice = ((Number) prepared.pool().get("price")).doubleValue();
        double totalPrice = unitPrice * prepared.draws().size();
        if (chargePoolPrice && (!Double.isFinite(totalPrice) || totalPrice <= 0 || !chargePlayer(online, totalPrice))) {
            activeAsyncPulls.remove(operationKey);
            callback.accept(List.of(PullResult.error("余额不足（需要 " + totalPrice + "）")));
            return;
        }

        List<List<LootFull>> decodedRewards = new ArrayList<>(prepared.draws().size());
        for (PreparedDraw draw : prepared.draws()) {
            List<LootFull> decoded = decodeLootSnapshots(draw.rewards());
            if (decoded.isEmpty() || decoded.stream().anyMatch(entry -> entry.item() == null)) {
                if (chargePoolPrice) refundPlayer(online, totalPrice);
                activeAsyncPulls.remove(operationKey);
                callback.accept(List.of(PullResult.error("战利品物品数据无效")));
                return;
            }
            decodedRewards.add(decoded);
        }

        List<PullResult> results = new ArrayList<>(prepared.draws().size());
        List<PullLog> logs = new ArrayList<>(prepared.draws().size());
        for (int i = 0; i < prepared.draws().size(); i++) {
            PreparedDraw draw = prepared.draws().get(i);
            boolean[] state = giveItems(online, playerUuid, decodedRewards.get(i), sourceTag);
            String rarity = (String) draw.chosen().meta().get("rarity");
            String material = (String) draw.chosen().meta().get("itemMaterial");
            String display = (String) draw.chosen().meta().get("displayName");
            int amount = Math.max(1, ((Number) draw.chosen().meta().get("quantity")).intValue());
            PityState pity = draw.pity();
            results.add(PullResult.ok(poolId, (String) prepared.pool().get("name"), material, display, rarity,
                    amount, pity.rareCount(), nextRarePity(pity.rareCount(), rarity), state[0], state[1],
                    pity.triggered() ? pity.triggeredMax() : 0));
            logs.add(new PullLog(material, rarity));
        }

        plugin.asyncWorkPool().execute(() -> {
            persistPullBatch(playerUuid.toString(), poolId, logs, prepared.pityRules(), prepared.finalPityCounts());
            finishPullBatch(operationKey, callback, List.copyOf(results));
        });
    }

    private boolean persistPullBatch(String uuidKey, String poolId, List<PullLog> logs,
                                     LinkedHashMap<String, Integer> rules, Map<String, Integer> finalCounts) {
        long now = System.currentTimeMillis() / 1000;
        try (var conn = plugin.ksCore().dataStore().getConnection()) {
            if (conn == null) return false;
            conn.setAutoCommit(false);
            try {
                try (var ps = conn.prepareStatement(
                        "INSERT INTO ks_bb_log (uuid, pool_id, item_material, rarity, pulled_at) VALUES (?,?,?,?,?)")) {
                    for (PullLog log : logs) {
                        ps.setString(1, uuidKey);
                        ps.setString(2, poolId);
                        ps.setString(3, log.material());
                        ps.setString(4, log.rarity());
                        ps.setLong(5, now);
                        ps.addBatch();
                    }
                    ps.executeBatch();
                }
                try (var ps = conn.prepareStatement(
                        "INSERT INTO ks_bb_pity (uuid, pool_id, count_since_rare, updated_at) VALUES (?,?,?,?) " +
                                "ON CONFLICT(uuid, pool_id) DO UPDATE SET count_since_rare=excluded.count_since_rare, updated_at=excluded.updated_at")) {
                    ps.setString(1, uuidKey);
                    ps.setString(2, poolId);
                    ps.setInt(3, finalCounts.getOrDefault(RARITY_RARE, 0));
                    ps.setLong(4, now);
                    ps.executeUpdate();
                }
                try (var ps = conn.prepareStatement(
                        "INSERT INTO ks_bb_pity_rarity (uuid, pool_id, rarity, count_since_hit, updated_at) VALUES (?,?,?,?,?) " +
                                "ON CONFLICT(uuid, pool_id, rarity) DO UPDATE SET count_since_hit=excluded.count_since_hit, updated_at=excluded.updated_at")) {
                    for (String rarity : rules.keySet()) {
                        ps.setString(1, uuidKey);
                        ps.setString(2, poolId);
                        ps.setString(3, rarity);
                        ps.setInt(4, finalCounts.getOrDefault(rarity, 0));
                        ps.setLong(5, now);
                        ps.addBatch();
                    }
                    ps.executeBatch();
                }
                conn.commit();
                return true;
            } catch (SQLException e) {
                conn.rollback();
                throw e;
            }
        } catch (SQLException e) {
            plugin.getLogger().warning("盲盒批量日志/保底写入失败: " + e.getMessage());
            return false;
        }
    }

    private void finishPullBatch(String operationKey, Consumer<List<PullResult>> callback, List<PullResult> results) {
        Bukkit.getScheduler().runTask(plugin, () -> {
            activeAsyncPulls.remove(operationKey);
            callback.accept(results);
        });
    }

    /** 给予 toGive 中所有物品，背包满则暂存。返回 [invFull, stored] */
    private boolean[] giveItems(Player online, UUID playerUuid, List<LootFull> toGive, String storageTag) {
        boolean invFull = false, stored = false;
        for (LootFull entry : toGive) {
            if (entry.item() == null) continue;
            int entryQty = Math.max(1, entry.meta().get("quantity") instanceof Number n ? n.intValue() : 1);
            int maxStack = Math.max(1, entry.item().getMaxStackSize());
            int remaining = entryQty;
            while (remaining > 0) {
                ItemStack item = entry.item().clone();
                int stackAmount = Math.min(remaining, maxStack);
                item.setAmount(stackAmount);
                remaining -= stackAmount;
                if (online != null && online.isOnline()) {
                    invFull |= online.getInventory().firstEmpty() == -1;
                    HashMap<Integer, ItemStack> overflow = online.getInventory().addItem(item);
                    if (!overflow.isEmpty()) {
                        for (ItemStack left : overflow.values()) {
                            plugin.storageManager().storeItem(playerUuid, left, storageTag);
                        }
                        stored = true;
                    }
                } else {
                    plugin.storageManager().storeItem(playerUuid, item, storageTag);
                    stored = true;
                }
            }
        }
        return new boolean[]{invFull, stored};
    }

    private int getPityCountSync(UUID uuid, String poolId) { return getPityCount(uuid, poolId); }

    private void syncCb(Consumer<PullResult> cb, PullResult r) {
        Bukkit.getScheduler().runTask(plugin, () -> cb.accept(r));
    }

    private void syncListCb(Consumer<List<PullResult>> cb, PullResult r) {
        Bukkit.getScheduler().runTask(plugin, () -> cb.accept(List.of(r)));
    }

    /**
     * 权重选择；pityHit=true 时只从 RARE 及以上选。
     * 只对 bundle_slot=0（主条目）参与抽签，附加条目不参与。
     */
    private Map<String, Object> chooseLoot(List<Map<String, Object>> loots, boolean pityHit) {
        List<Map<String, Object>> pool = new ArrayList<>();
        for (var l : loots) {
            int slot = l.get("bundleSlot") instanceof Number n ? n.intValue() : 0;
            if (slot > 0) continue; // 附加条目不参与抽签
            String rarity = (String) l.get("rarity");
            int w = l.get("weight") instanceof Number n ? n.intValue() : 0;
            if (w <= 0) continue;
            if (pityHit && !RARE_OR_ABOVE.contains(rarity)) continue;
            pool.add(l);
        }
        if (pool.isEmpty()) {
            // 兜底：只取主条目
            for (var l : loots) {
                int slot = l.get("bundleSlot") instanceof Number n ? n.intValue() : 0;
                if (slot == 0) pool.add(l);
            }
            if (pool.isEmpty()) pool = loots;
        }

        int total = 0;
        for (var l : pool) total += ((Number) l.get("weight")).intValue();
        int r = new Random().nextInt(Math.max(1, total));
        int acc = 0;
        for (var l : pool) {
            acc += ((Number) l.get("weight")).intValue();
            if (r < acc) return l;
        }
        return pool.get(pool.size() - 1);
    }

    /**
     * 根据选中条目和已加载的 loots 列表，返回需要发放的所有条目（bundle 时返回同组全部）。
     */
    private List<Map<String, Object>> resolveBundleItems(Map<String, Object> chosen, List<Map<String, Object>> allLoots) {
        String bundleId = chosen.get("bundleId") instanceof String s && !s.isEmpty() ? s : null;
        if (bundleId == null) return List.of(chosen);
        List<Map<String, Object>> items = new ArrayList<>();
        for (var l : allLoots) {
            String bid = l.get("bundleId") instanceof String s ? s : null;
            if (bundleId.equals(bid)) items.add(l);
        }
        if (items.isEmpty()) items.add(chosen);
        return items;
    }

    private boolean chargePlayer(Player p, double amount) {
        if (amount <= 0) return true;
        if (plugin.vaultHook() != null && plugin.vaultHook().isAvailable()) {
            if (!plugin.vaultHook().has(p, amount)) return false;
            return plugin.vaultHook().withdraw(p, amount);
        }
        return plugin.builtinEconomy().withdraw(p.getUniqueId(), p.getName(), amount);
    }

    private void refundPlayer(Player p, double amount) {
        if (amount <= 0) return;
        if (plugin.vaultHook() != null && plugin.vaultHook().isAvailable()) {
            plugin.vaultHook().deposit(p, amount);
        } else {
            plugin.builtinEconomy().deposit(p.getUniqueId(), p.getName(), amount);
        }
    }

    private String rarityColor(String rarity) {
        if (rarity == null) return "§f";
        return switch (rarity) {
            case RARITY_COMMON -> "§f";
            case RARITY_UNCOMMON -> "§a";
            case RARITY_RARE -> "§b";
            case RARITY_EPIC -> "§d";
            case RARITY_LEGENDARY -> "§6";
            default -> "§f";
        };
    }

    // ================================================================
    // 企业盲盒（公户扣款 + 行业白名单 + 企业等级）
    // ================================================================

    /** 企业公户扣款。公户、企业资产与实际开户行资产必须同步。返回扣后余额，失败 -1。 */
    public double chargeCorporate(String enterpriseId, double amount) {
        if (amount <= 0) return 0;
        try (var conn = plugin.ksCore().dataStore().getConnection()) {
            if (conn == null) return -1;
            conn.setAutoCommit(false);
            String bankId;
            try (var ps = conn.prepareStatement(
                    "SELECT balance,bank_id FROM ks_ent_corporate_accounts WHERE enterprise_id=?")) {
                ps.setString(1, enterpriseId);
                try (var rs = ps.executeQuery()) {
                    if (!rs.next() || rs.getDouble(1) < amount) {
                        conn.rollback();
                        return -1;
                    }
                    bankId = rs.getString("bank_id");
                }
            }
            long now = System.currentTimeMillis() / 1000;
            try (var ps = conn.prepareStatement(
                    "UPDATE ks_ent_corporate_accounts SET balance=balance-?, updated_at=? WHERE enterprise_id=?")) {
                ps.setDouble(1, amount);
                ps.setLong(2, now);
                ps.setString(3, enterpriseId);
                ps.executeUpdate();
            }
            try (var ps = conn.prepareStatement("UPDATE ks_ent_enterprises SET current_assets=MAX(current_assets-?,0) WHERE id=?")) {
                ps.setDouble(1, amount); ps.setString(2, enterpriseId); ps.executeUpdate();
            }
            try (var ps = conn.prepareStatement("UPDATE ks_bank_banks SET total_assets=total_assets-? WHERE id=? AND total_assets>=?")) {
                ps.setDouble(1, amount); ps.setString(2, bankId); ps.setDouble(3, amount);
                if (ps.executeUpdate() != 1) { conn.rollback(); return -1; }
            }
            try (var ps = conn.prepareStatement(
                    "SELECT balance FROM ks_ent_corporate_accounts WHERE enterprise_id=?")) {
                ps.setString(1, enterpriseId);
                try (var rs = ps.executeQuery()) {
                    rs.next();
                    double newBal = rs.getDouble(1);
                    conn.commit();
                    return newBal;
                }
            }
        } catch (SQLException e) {
            plugin.getLogger().warning("企业公户扣款失败: " + e.getMessage());
            return -1;
        }
    }

    public double getCorporateBalance(String enterpriseId) {
        try (var conn = plugin.ksCore().dataStore().getConnection()) {
            if (conn == null) return 0;
            try (var ps = conn.prepareStatement(
                    "SELECT balance FROM ks_ent_corporate_accounts WHERE enterprise_id=?")) {
                ps.setString(1, enterpriseId);
                try (var rs = ps.executeQuery()) {
                    if (rs.next()) return rs.getDouble(1);
                }
            }
        } catch (SQLException ignored) {}
        return 0;
    }

    /**
     * 企业盲盒单抽。
     * @param enterpriseId 企业 ID
     * @param actorUuid    抽签的玩家（员工）
     * @param poolId       池 ID
     * @return PullResult
     */
    public PullResult pullForEnterprise(String enterpriseId, UUID actorUuid, String poolId) {
        if (enterpriseId == null || poolId == null) return PullResult.error("参数无效");
        if (!canEnterpriseBlindBoxDraw(enterpriseId, actorUuid)) {
            return PullResult.error("你没有该企业的盲盒抽取权限");
        }
        Map<String, Object> pool = getPool(poolId);
        if (pool == null) return PullResult.error("盲盒池不存在");
        if (!(boolean) pool.get("enabled")) return PullResult.error("该盲盒池已停用");
        String limitedOnlyError = limitedOnlyError(pool);
        if (limitedOnlyError != null) return PullResult.error(limitedOnlyError);

        String ownerType = (String) pool.getOrDefault("ownerType", "PUBLIC");
        if ("PLAYER".equalsIgnoreCase(ownerType)) {
            return PullResult.error("该池仅限玩家");
        }

        String industry = getIndustry(enterpriseId);
        String allowedInd = (String) pool.getOrDefault("allowedIndustries", "");
        if (isIndustryBlocked(industry, allowedInd)) {
            return PullResult.error("本企业行业（" + industry + "）不在该池许可范围内");
        }
        String landGateErr = landGateError(pool, null, enterpriseId);
        if (landGateErr != null) return PullResult.error(landGateErr);
        int requiredLevel = pool.get("minEnterpriseLevel") instanceof Number n ? Math.max(1, n.intValue()) : 1;
        int enterpriseLevel = plugin.enterpriseLevelManager().getLevel(enterpriseId);
        if (enterpriseLevel < requiredLevel) {
            return PullResult.error("企业等级不足：需要 " + requiredLevel + " 级，当前 " + enterpriseLevel + " 级");
        }

        double price = ((Number) pool.get("price")).doubleValue();

        // 1) 公户扣款
        if (chargeCorporate(enterpriseId, price) < 0) {
            return PullResult.error("企业公户余额不足");
        }

        // 2) 加载战利品（一次查询，含预反序列化 ItemStack）
        String allowedCat = (String) pool.getOrDefault("allowedCategories", "");
        List<LootFull> allLoots = loadLootFull(poolId);
        if (allLoots.isEmpty()) {
            refundCorporate(enterpriseId, price);
            return PullResult.error("该盲盒池暂无可抽物品");
        }
        List<LootFull> filtered = new ArrayList<>();
        for (var lf : allLoots) {
            String mat = (String) lf.meta().get("itemMaterial");
            try {
                if (isCategoryAllowed(categorizeMaterial(Material.valueOf(mat)), allowedCat)) filtered.add(lf);
            } catch (IllegalArgumentException ignored) {}
        }
        if (filtered.isEmpty()) {
            refundCorporate(enterpriseId, price);
            return PullResult.error("该池无符合行业要求的战利品");
        }

        // 3) 保底 + 抽签
        String actorKey = "ent:" + enterpriseId;
        PityState pity = pityState(actorKey, poolId, pool);
        int pityBefore = pity.rareCount();
        LootFull chosen = chooseLootFull(filtered, pity.triggeredRarity());
        List<LootFull> toGiveEnt = resolveBundleFull(chosen, allLoots);

        // 4) 主条目信息
        String materialName = (String) chosen.meta().get("itemMaterial");
        String displayName = (String) chosen.meta().get("displayName");
        String rarity = (String) chosen.meta().get("rarity");
        int qty = Math.max(1, chosen.meta().get("quantity") instanceof Number n ? n.intValue() : 1);

        // 5) 发放（使用预构建 ItemStack，不再逐条查 DB）
        Player online = actorUuid != null ? Bukkit.getPlayer(actorUuid) : null;
        boolean[] state = giveItems(online, actorUuid, toGiveEnt, "entblindbox:" + poolId);
        boolean invFull = state[0], stored = state[1];

        if (online != null) {
            ItemStack firstItem = toGiveEnt.isEmpty() ? null : toGiveEnt.get(0).item();
            String showName = (firstItem != null && firstItem.hasItemMeta() && firstItem.getItemMeta() != null
                    && firstItem.getItemMeta().hasDisplayName())
                    ? firstItem.getItemMeta().getDisplayName() : materialName;
            String bundleSuffix = toGiveEnt.size() > 1 ? " §7(+§e" + (toGiveEnt.size() - 1) + "§7个)" : "";
            online.sendMessage("§6[企业盲盒] §e你代表企业从 §b" + pool.get("name") +
                    "§e 抽到: " + showName + bundleSuffix + " §7(稀有度:" + rarityColor(rarity) + rarity + "§7)");
            if (stored) online.sendMessage("§c背包已满，物品已暂存到暂存箱。");
        }

        // 6) log + 保底
        writeLogAndPity(actorKey, poolId, materialName, rarity, pityBefore);
        writeEnterpriseAudit("ENTERPRISE_BLINDBOX_PULL", enterpriseId, actorUuid,
            "pool=" + poolId + " item=" + materialName + " rarity=" + rarity + " price=" + price);

        int pityAfter = nextRarePity(pityBefore, rarity);
        return PullResult.ok(poolId, (String) pool.get("name"), materialName, displayName, rarity, qty,
                pityBefore, pityAfter, invFull, stored, pity.triggered() ? pity.triggeredMax() : 0);
    }

    /** 保底计数（支持企业 key 形如 "ent:xxx"） */
    private int getPityCountRaw(String key, String poolId) {
        try (var conn = plugin.ksCore().dataStore().getConnection()) {
            if (conn == null) return 0;
            try (var ps = conn.prepareStatement(
                    "SELECT count_since_rare FROM ks_bb_pity WHERE uuid=? AND pool_id=?")) {
                ps.setString(1, key);
                ps.setString(2, poolId);
                try (var rs = ps.executeQuery()) {
                    if (rs.next()) return rs.getInt(1);
                }
            }
        } catch (SQLException ignored) {}
        return 0;
    }

    private void refundCorporate(String enterpriseId, double amount) {
        if (amount <= 0) return;
        try (var conn = plugin.ksCore().dataStore().getConnection()) {
            if (conn == null) return;
            conn.setAutoCommit(false);
            String bankId;
            try (var ps = conn.prepareStatement("SELECT bank_id FROM ks_ent_corporate_accounts WHERE enterprise_id=?")) {
                ps.setString(1, enterpriseId);
                try (var rs = ps.executeQuery()) { if (!rs.next()) { conn.rollback(); return; } bankId = rs.getString(1); }
            }
            try (var ps = conn.prepareStatement(
                    "UPDATE ks_ent_corporate_accounts SET balance=balance+?, updated_at=? WHERE enterprise_id=?")) {
                ps.setDouble(1, amount);
                ps.setLong(2, System.currentTimeMillis() / 1000);
                ps.setString(3, enterpriseId);
                ps.executeUpdate();
            }
            try (var ps = conn.prepareStatement("UPDATE ks_ent_enterprises SET current_assets=current_assets+? WHERE id=?")) {
                ps.setDouble(1, amount); ps.setString(2, enterpriseId); ps.executeUpdate();
            }
            try (var ps = conn.prepareStatement("UPDATE ks_bank_banks SET total_assets=total_assets+? WHERE id=?")) {
                ps.setDouble(1, amount); ps.setString(2, bankId); ps.executeUpdate();
            }
            conn.commit();
        } catch (SQLException e) {
            plugin.getLogger().warning("企业公户退款失败: " + e.getMessage());
        }
    }

    /**
     * 玩家 10 连抽。
     * 保底计数器每抽后递增/重置；10 抽中只要出现 RARE+，整体 pityAfter=0。
     * 扣款一次性扣 10 * price；任一抽失败（池空了）会逐抽校验。
     */
    public List<PullResult> pullTen(UUID playerUuid, String poolId) {
        List<PullResult> out = new ArrayList<>();
        if (playerUuid == null) return out;
        Player online = Bukkit.getPlayer(playerUuid);
        if (online == null) { out.add(PullResult.error("玩家不在线")); return out; }
        Map<String, Object> pool = getPool(poolId);
        if (pool == null) { out.add(PullResult.error("盲盒池不存在")); return out; }
        if (!(boolean) pool.get("enabled")) { out.add(PullResult.error("该盲盒池已停用")); return out; }
        String limitedOnlyError = limitedOnlyError(pool);
        if (limitedOnlyError != null) { out.add(PullResult.error(limitedOnlyError)); return out; }
        String landGateErr = landGateError(pool, playerUuid, null);
        if (landGateErr != null) { out.add(PullResult.error(landGateErr)); return out; }
        double unitPrice = ((Number) pool.get("price")).doubleValue();
        if (!chargePlayer(online, unitPrice * 10)) {
            out.add(PullResult.error("余额不足（10 抽需 " + unitPrice * 10 + "）")); return out;
        }
        // 加载一次战利品，避免 10×getLootItemStack 查 DB
        List<LootFull> loots = loadLootFull(poolId);
        if (loots.isEmpty()) {
            refundPlayer(online, unitPrice * 10);
            out.add(PullResult.error("该盲盒池暂无可抽物品")); return out;
        }
        for (int i = 0; i < 10; i++) {
            PullResult r = doSinglePull(playerUuid, poolId, pool, loots);
            if (!r.success) {
                refundPlayer(online, unitPrice * (10 - i - 1));
                out.add(r); return out;
            }
            out.add(r);
        }
        return out;
    }

    /** 内部：单抽（不扣款；不向玩家反馈）— 使用预加载的战利品列表 */
    private PullResult doSinglePull(UUID playerUuid, String poolId, Map<String, Object> pool, List<LootFull> loots) {
        return doSinglePull(playerUuid, poolId, pool, loots, "blindbox:" + poolId);
    }

    /** 内部：单抽（不扣款；不向玩家反馈）— 使用预加载的战利品列表 */
    private PullResult doSinglePull(UUID playerUuid, String poolId, Map<String, Object> pool, List<LootFull> loots, String storageTag) {
        if (loots.isEmpty()) return PullResult.error("该盲盒池暂无可抽物品");
        PityState pity = pityState(playerUuid.toString(), poolId, pool);
        int pityBefore = pity.rareCount();
        LootFull chosen = chooseLootFull(loots, pity.triggeredRarity());
        List<LootFull> toGive = resolveBundleFull(chosen, loots);

        String materialName = (String) chosen.meta().get("itemMaterial");
        String displayName = (String) chosen.meta().get("displayName");
        String rarity = (String) chosen.meta().get("rarity");
        int qty = Math.max(1, ((Number) chosen.meta().get("quantity")).intValue());

        Player online = Bukkit.getPlayer(playerUuid);
        boolean[] state = giveItems(online, playerUuid, toGive, storageTag);
        boolean invFull = state[0], stored = state[1];

        writeLogAndPity(playerUuid.toString(), poolId, materialName, rarity, pityBefore);
        int pityAfter = nextRarePity(pityBefore, rarity);
        return PullResult.ok(poolId, (String) pool.get("name"), materialName, displayName, rarity, qty,
                pityBefore, pityAfter, invFull, stored, pity.triggered() ? pity.triggeredMax() : 0);
    }

    /** Resolves enterprise blind-box access from the enterprise's own role template and individual grants. */
    public boolean canEnterpriseBlindBoxDraw(String enterpriseId, UUID actorUuid) {
        if (enterpriseId == null || enterpriseId.isBlank() || actorUuid == null) return false;
        var provider = plugin.enterpriseAccessProvider();
        if (provider != null) return provider.hasPermission(enterpriseId, actorUuid, EnterprisePermissionService.BLINDBOX_DRAW);
        try (var conn = plugin.ksCore().dataStore().getConnection()) {
            if (conn == null) return false;
            return new EnterprisePermissionService().hasPermission(conn, enterpriseId, actorUuid,
                    EnterprisePermissionService.BLINDBOX_DRAW);
        } catch (SQLException e) {
            plugin.getLogger().warning("Enterprise blind box permission check failed: " + e.getMessage());
            return false;
        }
    }

    private void writeEnterpriseAudit(String action, String enterpriseId, UUID actorUuid, String details) {
        if (enterpriseId == null || actorUuid == null) return;
        String playerName = "";
        Player online = Bukkit.getPlayer(actorUuid);
        if (online != null) playerName = online.getName();
        try (var conn = plugin.ksCore().dataStore().getConnection();
             var ps = conn == null ? null : conn.prepareStatement(
                 "INSERT INTO ks_audit_log (action,player_uuid,player_name,target_type,target_id,details,created_at) VALUES (?,?,?,?,?,?,?)")) {
            if (ps == null) return;
            ps.setString(1, action);
            ps.setString(2, actorUuid.toString());
            ps.setString(3, playerName);
            ps.setString(4, "enterprise");
            ps.setString(5, enterpriseId);
            ps.setString(6, details == null ? "" : details);
            ps.setLong(7, System.currentTimeMillis() / 1000);
            ps.executeUpdate();
        } catch (SQLException e) {
            plugin.getLogger().warning("Enterprise blind box audit write failed: " + e.getMessage());
        }
    }
}
