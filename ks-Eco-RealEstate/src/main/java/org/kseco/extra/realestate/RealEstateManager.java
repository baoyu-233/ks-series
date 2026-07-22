package org.kseco.extra.realestate;

import org.kseco.KsEco;
import org.kseco.crossserver.assets.AssetSource;
import org.kseco.crossserver.assets.FederatedCapability;
import org.kseco.database.PortableSqlMutation;

import java.sql.*;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 房地产管理器。
 *
 * 数据模型：
 * - ks_re_zones: 区域（可售/国有），含 world + 大致边界
 * - ks_re_plots: 地块（从区域切分出来），含 owner（企业/玩家）、价格
 * - ks_re_houses: 在地块内圈定登记的房屋，含 owner、tax_rate（流转税率，继承自地块/区域）
 *
 * 税收模型：tax_rate（"契税率"）是房屋/地块所有权变更时（市场卖房成交）一次性收的流转税，
 * 不是按年/按周期收的持有维护费——持有不动产本身不产生任何费用。
 *
 * 与现有系统集成：
 * - 玩家/企业购买：扣款走 VaultHook / 企业公户（CORP-BANK）自动判断
 * - 与 ksE-blindbox 类似：玩家抽到的房地产行业（REAL_ESTATE）受企业行业白名单限制
 */
public final class RealEstateManager {

    public static final String ZONE_TYPE_INDUSTRIAL = "INDUSTRIAL";
    public static final String ZONE_TYPE_AGRICULTURAL = "AGRICULTURAL";
    public static final String ZONE_TYPE_RESIDENTIAL = "RESIDENTIAL";
    public static final String ZONE_TYPE_COMMERCIAL = "COMMERCIAL";

    public static final String ZONE_STATUS_STATE_OWNED = "STATE_OWNED";
    public static final String ZONE_STATUS_FOR_SALE = "FOR_SALE";
    public static final String ZONE_STATUS_SOLD = "SOLD";

    public static final String OWNER_PLAYER = "PLAYER";
    public static final String OWNER_ENTERPRISE = "ENTERPRISE";

    // 房产功能枚举（含副本内房产；原 ks-Eco-RealEstateDungeon.PropertyManager 迁入）
    public static final String FUNCTION_RESIDENTIAL = "RESIDENTIAL";
    public static final String FUNCTION_DUNGEON_PORTAL = "DUNGEON_PORTAL";
    public static final String FUNCTION_SAFEHOUSE = "SAFEHOUSE";
    public static final String FUNCTION_SHOP = "SHOP";
    public static final String FUNCTION_INDUSTRIAL = "INDUSTRIAL";
    private static final Set<String> VALID_FUNCTIONS = Set.of(
            FUNCTION_RESIDENTIAL, FUNCTION_DUNGEON_PORTAL, FUNCTION_SAFEHOUSE, FUNCTION_SHOP, FUNCTION_INDUSTRIAL);
    // 副本权限（dungeon_template_id）可配置的区域类型：住宅（原有）+ 农业/工业（地块福利系统新增专属副本模板）
    private static final Set<String> DUNGEON_LINKABLE_ZONE_TYPES = Set.of(
            ZONE_TYPE_RESIDENTIAL, ZONE_TYPE_AGRICULTURAL, ZONE_TYPE_INDUSTRIAL);
    // 副本内房产默认费率（原 dungeon.yml property.* 迁入）
    public static final double DEFAULT_DEVELOPMENT_FEE = 5000.0;
    public static final double DEFAULT_DEED_TAX_RATE = 0.15;

    private final KsEco eco;

    // 售楼沙盘单栋预渲染缓存。世界快照按区块派发到所属 region，隐藏面裁剪与 JSON 组装异步完成。
    private static final long VOXEL_CACHE_TTL_MILLIS = 5 * 60 * 1000L;
    private static final int MAX_HOUSE_SCAN_VOLUME = 250000;
    private final Map<String, CachedVoxelModel> voxelModelCache = new ConcurrentHashMap<>();
    private final Set<org.kseco.scheduler.EcoScheduler.TaskHandle> voxelSnapshotTasks = ConcurrentHashMap.newKeySet();
    private final Map<String, Map<String, FederatedPropertySnapshotFactory.HouseSnapshot>> federatedPropertySnapshots =
            new ConcurrentHashMap<>();
    private final Map<String, AtomicLong> federatedPropertyRevisions = new ConcurrentHashMap<>();

    private record CachedVoxelModel(String signature, long queuedAt,
                                    CompletableFuture<Map<String, Object>> future) {}

    // 地块边界缓存（仅主世界地块，instance_id IS NULL）：方块事件高频，不能每次查 SQLite
    private volatile boolean plotCacheHealthy = false;
    private volatile boolean houseCacheHealthy = false;
    private volatile boolean trustCacheHealthy = false;
    private volatile Map<String, List<Map<String, Object>>> plotCache = new HashMap<>();
    // Immutable flattened view for periodic systems. Avoid copying every plot map on every tick.
    private volatile List<Map<String, Object>> plotCacheView = List.of();
    // 房屋边界缓存（仅主世界地块上登记的房屋）：同上，方块事件高频不能每次查 SQLite
    private volatile Map<String, List<Map<String, Object>>> houseCache = new HashMap<>();
    // PLAYER 地块/房屋信任权限快照。保护事件只读内存，写入成功后原子替换对应条目。
    private volatile Map<String, Map<UUID, TrustPermissions>> plotTrustCache = Map.of();
    private volatile Map<String, Map<UUID, TrustPermissions>> houseTrustCache = Map.of();
    // 玩家测量棒选区临时状态（不落库，服务器重启即丢失）：world+两角点
    private final Map<UUID, Selection> houseSelections = new HashMap<>();

    public record PlotSummary(
            String id,
            String world,
            int x1,
            int z1,
            int x2,
            int z2,
            String ownerType,
            String ownerId,
            String zoneType,
            long purchasedAt
    ) {}

    public record TrustEntry(
            String plotId,
            UUID trustedUuid,
            String trustedName,
            boolean canBuild,
            boolean canContainer,
            boolean canInteract,
            long grantedAt
    ) {}

    private record TrustPermissions(boolean build, boolean container, boolean interact) {
        private boolean allows(String kind) {
            return switch (kind) {
                case "BUILD" -> build;
                case "CONTAINER" -> container;
                case "INTERACT" -> interact;
                default -> false;
            };
        }
    }

    /** 测量棒选区：左键=pos1，右键=pos2，两点都设好才能 /house register。 */
    public static final class Selection {
        public String world;
        public int[] pos1; // {x,y,z}
        public int[] pos2;
    }

    public RealEstateManager(KsEco eco) {
        this.eco = eco;
    }

    public void init() {
        ensureTables();
        refreshPlotCache();
        refreshHouseCache();
        refreshTrustCaches();
    }

    /** 模块停用时取消尚未完成的售楼沙盘快照，防止重载后旧任务继续读取世界。 */
    public void shutdownVoxelCache() {
        for (org.kseco.scheduler.EcoScheduler.TaskHandle task : voxelSnapshotTasks) task.cancel();
        voxelSnapshotTasks.clear();
        for (CachedVoxelModel entry : voxelModelCache.values()) {
            entry.future().cancel(false);
        }
        voxelModelCache.clear();
        federatedPropertySnapshots.clear();
        federatedPropertyRevisions.clear();
    }

    /** 全量重新加载主世界地块边界缓存。地块增/删后调用。 */
    public void refreshPlotCache() {
        refreshPlotCache(true);
    }

    private void refreshPlotCache(boolean publish) {
        Map<String, List<Map<String, Object>>> next = new HashMap<>();
        try (var conn = eco.ksCore().dataStore().getConnection()) {
            if (conn == null) {
                plotCacheHealthy = false;
                eco.getLogger().severe("[房地产] 地块缓存刷新失败：数据库连接不可用，继续使用旧缓存并 fail-closed");
                return;
            }
            try (var s = conn.createStatement();
                 var rs = s.executeQuery(
                         "SELECT p.*, z.type AS joined_zone_type FROM ks_re_plots p " +
                         "LEFT JOIN ks_re_zones z ON z.id = p.zone_id WHERE p.instance_id IS NULL")) {
                while (rs.next()) {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("id", rs.getString("id"));
                    m.put("zoneId", rs.getString("zone_id"));
                    m.put("world", rs.getString("world"));
                    m.put("x1", rs.getInt("x1")); m.put("z1", rs.getInt("z1"));
                    m.put("x2", rs.getInt("x2")); m.put("z2", rs.getInt("z2"));
                    m.put("ownerType", rs.getString("owner_type"));
                    m.put("ownerId", rs.getString("owner_id"));
                    m.put("zoneType", rs.getString("joined_zone_type"));
                    next.computeIfAbsent((String) m.get("world"), k -> new ArrayList<>()).add(m);
                }
            }
        } catch (SQLException e) {
            plotCacheHealthy = false;
            eco.getLogger().severe("[房地产] 刷新地块缓存失败，继续使用旧缓存并 fail-closed: " + e.getMessage());
            return;
        }
        List<Map<String, Object>> flattened = new ArrayList<>();
        for (List<Map<String, Object>> plots : next.values()) {
            for (Map<String, Object> plot : plots) {
                flattened.add(Map.copyOf(plot));
            }
        }
        Map<String, List<Map<String, Object>>> immutable = new HashMap<>();
        for (Map.Entry<String, List<Map<String, Object>>> entry : next.entrySet()) {
            immutable.put(entry.getKey(), List.copyOf(entry.getValue()));
        }
        plotCache = Map.copyOf(immutable);
        plotCacheView = List.copyOf(flattened);
        plotCacheHealthy = true;
        if (publish) eco.publishCrossServerInvalidation("real-estate", "all");
    }

    /** 找出 (world,x,z) 落在哪块主世界地块内；找不到返回 null。坐标用方块整数坐标。 */
    public Map<String, Object> findPlotAt(String world, int x, int z) {
        if (world == null) return null;
        List<Map<String, Object>> list = plotCache.get(world);
        if (list == null) return null;
        for (Map<String, Object> p : list) {
            int x1 = (Integer) p.get("x1"), x2 = (Integer) p.get("x2");
            int z1 = (Integer) p.get("z1"), z2 = (Integer) p.get("z2");
            if (x >= x1 && x <= x2 && z >= z1 && z <= z2) return p;
        }
        return null;
    }

    public List<Map<String, Object>> cachedPlots() {
        List<Map<String, Object>> out = new ArrayList<>();
        for (List<Map<String, Object>> list : plotCache.values()) {
            for (Map<String, Object> plot : list) {
                out.add(new LinkedHashMap<>(plot));
            }
        }
        return out;
    }

    /** Read-only cache view for server-thread periodic work. */
    public List<Map<String, Object>> cachedPlotsView() {
        return plotCacheView;
    }

    /** 全量重新加载主世界房屋边界缓存（仅含挂在主世界地块上的房屋）。房屋增删/转移所有权后调用。 */
    public void refreshHouseCache() {
        refreshHouseCache(true);
    }

    private void refreshHouseCache(boolean publish) {
        Map<String, List<Map<String, Object>>> next = new HashMap<>();
        try (var conn = eco.ksCore().dataStore().getConnection()) {
            if (conn == null) {
                houseCacheHealthy = false;
                eco.getLogger().severe("[房地产] 房屋缓存刷新失败：数据库连接不可用，继续使用旧缓存并 fail-closed");
                return;
            }
            try (var s = conn.createStatement();
                 var rs = s.executeQuery(
                         "SELECT h.* FROM ks_re_houses h JOIN ks_re_plots p ON p.id = h.plot_id " +
                         "WHERE p.instance_id IS NULL")) {
                while (rs.next()) {
                    Map<String, Object> m = houseRowToMap(rs);
                    next.computeIfAbsent((String) m.get("world"), k -> new ArrayList<>()).add(m);
                }
            }
        } catch (SQLException e) {
            houseCacheHealthy = false;
            eco.getLogger().severe("[房地产] 刷新房屋缓存失败，继续使用旧缓存并 fail-closed: " + e.getMessage());
            return;
        }
        Map<String, List<Map<String, Object>>> immutable = new HashMap<>();
        for (Map.Entry<String, List<Map<String, Object>>> entry : next.entrySet()) {
            immutable.put(entry.getKey(), List.copyOf(entry.getValue()));
        }
        houseCache = Map.copyOf(immutable);
        houseCacheHealthy = true;
        if (publish) eco.publishCrossServerInvalidation("real-estate", "all");
    }

    /** 找出 (world,x,y,z) 落在哪套主世界房屋内；找不到返回 null。 */
    public Map<String, Object> findHouseAt(String world, int x, int y, int z) {
        if (world == null) return null;
        List<Map<String, Object>> list = houseCache.get(world);
        if (list == null) return null;
        for (Map<String, Object> h : list) {
            int x1 = (Integer) h.get("x1"), x2 = (Integer) h.get("x2");
            int y1 = (Integer) h.get("y1"), y2 = (Integer) h.get("y2");
            int z1 = (Integer) h.get("z1"), z2 = (Integer) h.get("z2");
            if (x >= x1 && x <= x2 && y >= y1 && y <= y2 && z >= z1 && z <= z2) return h;
        }
        return null;
    }

    /**
     * 判断 actor 在该房屋是否有 kind(BUILD/CONTAINER/INTERACT) 权限。逻辑同 {@link #canAccess}，
     * 只是信任名单换成 ks_re_house_trust。
     */
    public boolean canAccessHouse(Map<String, Object> house, UUID actor, String kind) {
        if (house == null || actor == null) return false;
        String ownerType = (String) house.get("ownerType");
        String ownerId = (String) house.get("ownerId");
        if (OWNER_PLAYER.equals(ownerType)) {
            if (actor.toString().equals(ownerId)) return true;
            return hasHouseTrust((String) house.get("id"), actor, kind);
        }
        if (OWNER_ENTERPRISE.equals(ownerType)) {
            return isEnterpriseMember(ownerId, actor);
        }
        return false;
    }

    private boolean hasHouseTrust(String houseId, UUID actor, String kind) {
        Map<UUID, TrustPermissions> trusted = houseTrustCache.get(houseId);
        TrustPermissions permissions = trusted == null ? null : trusted.get(actor);
        return permissions != null && permissions.allows(kind);
    }

    /** 判断 actor 是否有权管理该地块（登记房屋等所有者专属操作）：PLAYER 地块要求本人，ENTERPRISE 地块要求企业成员。 */
    public boolean canManagePlot(Map<String, Object> plot, UUID actor) {
        if (plot == null || actor == null) return false;
        String ownerType = (String) plot.get("ownerType");
        String ownerId = (String) plot.get("ownerId");
        if (OWNER_PLAYER.equals(ownerType)) return actor.toString().equals(ownerId);
        if (OWNER_ENTERPRISE.equals(ownerType)) return isEnterpriseMember(ownerId, actor);
        return false;
    }

    /**
     * 判断 actor 在该地块是否有 kind(BUILD/CONTAINER/INTERACT) 权限。
     * 所有者（PLAYER 本人 / ENTERPRISE 任意成员）一律放行；否则查信任名单。
     */
    public boolean canAccess(Map<String, Object> plot, UUID actor, String kind) {
        if (plot == null || actor == null) return false;
        String ownerType = (String) plot.get("ownerType");
        String ownerId = (String) plot.get("ownerId");
        if (OWNER_PLAYER.equals(ownerType)) {
            if (actor.toString().equals(ownerId)) return true;
            return hasTrust((String) plot.get("id"), actor, kind);
        }
        if (OWNER_ENTERPRISE.equals(ownerType)) {
            return isEnterpriseMember(ownerId, actor);
        }
        return false;
    }

    public boolean isEnterpriseMember(String enterpriseId, UUID actor) {
        if (enterpriseId == null) return false;
        try (var conn = eco.ksCore().dataStore().getConnection()) {
            if (conn == null) return false;
            try (var ps = conn.prepareStatement(
                    "SELECT 1 FROM ks_ent_members WHERE enterprise_id=? AND player_uuid=?")) {
                ps.setString(1, enterpriseId);
                ps.setString(2, actor.toString());
                try (var rs = ps.executeQuery()) { return rs.next(); }
            }
        } catch (SQLException e) {
            return false; // 企业模块未装/表不存在：安全兜底为无权限
        }
    }

    private boolean hasTrust(String plotId, UUID actor, String kind) {
        Map<UUID, TrustPermissions> trusted = plotTrustCache.get(plotId);
        TrustPermissions permissions = trusted == null ? null : trusted.get(actor);
        return permissions != null && permissions.allows(kind);
    }

    /** 地块所有者授予信任权限（PLAYER 地块专用）。 @return null 成功，否则中文错误信息。 */
    public synchronized String grantTrust(String plotId, UUID owner, UUID targetUuid, String targetName,
                                          boolean build, boolean container, boolean interact) {
        if (plotId == null || plotId.isBlank() || owner == null || targetUuid == null) return "参数无效";
        if (targetUuid.equals(owner)) return "不能信任自己";
        long now = System.currentTimeMillis() / 1000;
        String safeTargetName = normalizeTrustName(targetName);
        try (var conn = eco.ksCore().dataStore().getConnection()) {
            if (conn == null) return "数据库不可用";
            PortableSqlMutation.upsert(conn,
                    "UPDATE ks_re_plot_trust SET trusted_name=?,can_build=?,can_container=?,can_interact=? " +
                            "WHERE plot_id=? AND trusted_uuid=? AND EXISTS (SELECT 1 FROM ks_re_plots WHERE id=? AND owner_type=? AND owner_id=?)",
                    ps -> bindTrustUpdate(ps, safeTargetName, build, container, interact, plotId, targetUuid, owner),
                    "INSERT INTO ks_re_plot_trust (plot_id,trusted_uuid,trusted_name,can_build,can_container,can_interact,granted_at) " +
                            "SELECT ?,?,?,?,?,?,? WHERE EXISTS (SELECT 1 FROM ks_re_plots WHERE id=? AND owner_type=? AND owner_id=?)",
                    ps -> bindTrustInsert(ps, plotId, targetUuid, safeTargetName, build, container, interact, now, owner));
        } catch (SQLException e) {
            eco.getLogger().warning("[房地产] 授予信任失败: " + e.getMessage());
            return "操作失败";
        }
        putPlotTrustCache(plotId, targetUuid, new TrustPermissions(build, container, interact));
        eco.publishCrossServerInvalidation("real-estate", "all");
        return null;
    }

    /** 地块所有者撤销信任。@return null 成功，否则中文错误信息。 */
    public synchronized String revokeTrust(String plotId, UUID owner, UUID targetUuid) {
        if (plotId == null || plotId.isBlank() || owner == null || targetUuid == null) return "参数无效";
        try (var conn = eco.ksCore().dataStore().getConnection()) {
            if (conn == null) return "数据库不可用";
            try (var ps = conn.prepareStatement(
                    "DELETE FROM ks_re_plot_trust WHERE plot_id=? AND trusted_uuid=? AND EXISTS (" +
                    "SELECT 1 FROM ks_re_plots WHERE id=? AND owner_type=? AND owner_id=?)")) {
                ps.setString(1, plotId);
                ps.setString(2, targetUuid.toString());
                ps.setString(3, plotId);
                ps.setString(4, OWNER_PLAYER);
                ps.setString(5, owner.toString());
                int deleted = ps.executeUpdate();
                if (deleted == 0 && !ownsPlayerPlot(conn, plotId, owner)) {
                    return "地块不存在或所有权已变更";
                }
            }
        } catch (SQLException e) {
            eco.getLogger().warning("[房地产] 撤销信任失败: " + e.getMessage());
            return "操作失败";
        }
        removePlotTrustCache(plotId, targetUuid);
        eco.publishCrossServerInvalidation("real-estate", "all");
        return null;
    }

    /** 列出玩家能管理/进入的主世界地块：自己直接持有的 + 自己作为成员的企业持有的。用于 /land GUI。 */
    public List<Map<String, Object>> listAccessiblePlots(UUID uuid) {
        try {
            List<Map<String, Object>> out = new ArrayList<>();
            for (PlotSummary plot : loadAccessiblePlotSummaries(uuid)) {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("id", plot.id());
                row.put("world", plot.world());
                row.put("x1", plot.x1());
                row.put("z1", plot.z1());
                row.put("x2", plot.x2());
                row.put("z2", plot.z2());
                row.put("ownerType", plot.ownerType());
                row.put("ownerId", plot.ownerId());
                row.put("zoneType", plot.zoneType());
                row.put("purchasedAt", plot.purchasedAt());
                out.add(row);
            }
            return out;
        } catch (SQLException e) {
            eco.getLogger().warning("[房地产] 列可管理地块失败: " + e.getMessage());
            return List.of();
        }
    }

    /** 列出地块的信任名单。 */
    public List<Map<String, Object>> listTrust(String plotId) {
        try {
            List<Map<String, Object>> out = new ArrayList<>();
            for (TrustEntry trust : loadTrustEntries(plotId)) {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("plotId", trust.plotId());
                row.put("trustedUuid", trust.trustedUuid().toString());
                row.put("trustedName", trust.trustedName());
                row.put("canBuild", trust.canBuild());
                row.put("canContainer", trust.canContainer());
                row.put("canInteract", trust.canInteract());
                row.put("grantedAt", trust.grantedAt());
                out.add(row);
            }
            return out;
        } catch (SQLException e) {
            eco.getLogger().warning("[房地产] 列信任名单失败: " + e.getMessage());
            return List.of();
        }
    }

    public List<PlotSummary> loadAccessiblePlotSummaries(UUID playerUuid) throws SQLException {
        Objects.requireNonNull(playerUuid, "playerUuid");
        try (var conn = eco.ksCore().dataStore().getConnection()) {
            if (conn == null) throw new SQLException("database unavailable");
            List<PlotSummary> out = new ArrayList<>();
            String columns = "SELECT p.id,p.world,p.x1,p.z1,p.x2,p.z2,p.owner_type,p.owner_id," +
                    "z.type AS joined_zone_type,p.purchased_at FROM ks_re_plots p " +
                    "LEFT JOIN ks_re_zones z ON z.id=p.zone_id ";
            try (var ps = conn.prepareStatement(columns +
                    "WHERE p.instance_id IS NULL AND p.owner_type=? AND p.owner_id=? " +
                    "ORDER BY p.purchased_at DESC LIMIT 200")) {
                ps.setString(1, OWNER_PLAYER);
                ps.setString(2, playerUuid.toString());
                readPlotSummaries(ps, out);
            }
            if (tableExists(conn, "ks_ent_members")) {
                try (var ps = conn.prepareStatement(columns +
                        "WHERE p.instance_id IS NULL AND p.owner_type=? AND EXISTS (" +
                        "SELECT 1 FROM ks_ent_members m WHERE m.enterprise_id=p.owner_id AND m.player_uuid=?) " +
                        "ORDER BY p.purchased_at DESC LIMIT 200")) {
                    ps.setString(1, OWNER_ENTERPRISE);
                    ps.setString(2, playerUuid.toString());
                    readPlotSummaries(ps, out);
                }
            }
            out.sort(Comparator.comparingLong(PlotSummary::purchasedAt).reversed());
            if (out.size() > 200) out.subList(200, out.size()).clear();
            return List.copyOf(out);
        }
    }

    public List<TrustEntry> loadTrustEntries(String plotId) throws SQLException {
        if (plotId == null || plotId.isBlank() || plotId.length() > 64) return List.of();
        try (var conn = eco.ksCore().dataStore().getConnection()) {
            if (conn == null) throw new SQLException("database unavailable");
            try (var ps = conn.prepareStatement(
                    "SELECT plot_id,trusted_uuid,trusted_name,can_build,can_container,can_interact,granted_at " +
                    "FROM ks_re_plot_trust WHERE plot_id=? ORDER BY granted_at ASC LIMIT 200")) {
                ps.setString(1, plotId);
                return readTrustEntries(ps, false);
            }
        }
    }

    public List<TrustEntry> loadOwnedTrustEntries(String plotId, UUID owner) throws SQLException {
        if (plotId == null || plotId.isBlank() || plotId.length() > 64 || owner == null) {
            throw new SQLException("invalid plot owner request");
        }
        try (var conn = eco.ksCore().dataStore().getConnection()) {
            if (conn == null) throw new SQLException("database unavailable");
            try (var ps = conn.prepareStatement(
                    "SELECT p.id AS plot_id,t.trusted_uuid,t.trusted_name,t.can_build,t.can_container," +
                    "t.can_interact,t.granted_at FROM ks_re_plots p LEFT JOIN ks_re_plot_trust t ON t.plot_id=p.id " +
                    "WHERE p.id=? AND p.owner_type=? AND p.owner_id=? ORDER BY t.granted_at ASC LIMIT 200")) {
                ps.setString(1, plotId);
                ps.setString(2, OWNER_PLAYER);
                ps.setString(3, owner.toString());
                return readTrustEntries(ps, true);
            }
        }
    }

    private List<TrustEntry> readTrustEntries(PreparedStatement statement, boolean requireOwnerRow) throws SQLException {
        List<TrustEntry> out = new ArrayList<>();
        boolean sawRow = false;
        try (var rs = statement.executeQuery()) {
            while (rs.next()) {
                sawRow = true;
                String trustedUuid = rs.getString("trusted_uuid");
                if (trustedUuid == null) continue;
                try {
                    out.add(new TrustEntry(
                            rs.getString("plot_id"),
                            UUID.fromString(trustedUuid),
                            rs.getString("trusted_name"),
                            rs.getInt("can_build") != 0,
                            rs.getInt("can_container") != 0,
                            rs.getInt("can_interact") != 0,
                            rs.getLong("granted_at")));
                } catch (IllegalArgumentException invalidUuid) {
                    eco.getLogger().warning("[房地产] 忽略无效信任 UUID: " + trustedUuid);
                }
            }
        }
        if (requireOwnerRow && !sawRow) throw new SQLException("plot ownership changed");
        return List.copyOf(out);
    }

    private void readPlotSummaries(PreparedStatement statement, List<PlotSummary> out) throws SQLException {
        try (var rs = statement.executeQuery()) {
            while (rs.next()) {
                out.add(new PlotSummary(
                        rs.getString("id"),
                        rs.getString("world"),
                        rs.getInt("x1"),
                        rs.getInt("z1"),
                        rs.getInt("x2"),
                        rs.getInt("z2"),
                        rs.getString("owner_type"),
                        rs.getString("owner_id"),
                        rs.getString("joined_zone_type"),
                        rs.getLong("purchased_at")));
            }
        }
    }

    private boolean tableExists(Connection conn, String tableName) throws SQLException {
        var metadata = conn.getMetaData();
        for (String candidate : List.of(tableName, tableName.toUpperCase(Locale.ROOT),
                tableName.toLowerCase(Locale.ROOT))) {
            try (var rows = metadata.getTables(conn.getCatalog(), null, candidate, new String[]{"TABLE"})) {
                if (rows.next()) return true;
            }
        }
        return false;
    }

    public synchronized void refreshTrustCaches() {
        refreshTrustCaches(true);
    }

    private synchronized void refreshTrustCaches(boolean publish) {
        Map<String, Map<UUID, TrustPermissions>> nextPlot = new HashMap<>();
        Map<String, Map<UUID, TrustPermissions>> nextHouse = new HashMap<>();
        try (var conn = eco.ksCore().dataStore().getConnection()) {
            if (conn == null) {
                trustCacheHealthy = false;
                eco.getLogger().severe("[房地产] 信任缓存刷新失败：数据库连接不可用，继续使用旧缓存并 fail-closed");
                return;
            }
            readTrustCache(conn, "ks_re_plot_trust", "plot_id", nextPlot);
            readTrustCache(conn, "ks_re_house_trust", "house_id", nextHouse);
        } catch (SQLException e) {
            trustCacheHealthy = false;
            eco.getLogger().severe("[房地产] 刷新信任缓存失败，继续使用旧缓存并 fail-closed: " + e.getMessage());
            return;
        }
        plotTrustCache = freezeTrustCache(nextPlot);
        houseTrustCache = freezeTrustCache(nextHouse);
        trustCacheHealthy = true;
        if (publish) eco.publishCrossServerInvalidation("real-estate", "all");
    }

    public boolean refreshAllCachesFromRemote() {
        refreshPlotCache(false);
        refreshHouseCache(false);
        refreshTrustCaches(false);
        return protectionCachesHealthy();
    }

    public boolean protectionCachesHealthy() {
        return plotCacheHealthy && houseCacheHealthy && trustCacheHealthy;
    }

    private void readTrustCache(Connection conn, String table, String idColumn,
                                Map<String, Map<UUID, TrustPermissions>> target) throws SQLException {
        String sql = "SELECT " + idColumn + ",trusted_uuid,can_build,can_container,can_interact FROM " + table;
        try (var statement = conn.createStatement(); var rs = statement.executeQuery(sql)) {
            while (rs.next()) {
                try {
                    UUID trustedUuid = UUID.fromString(rs.getString("trusted_uuid"));
                    TrustPermissions permissions = new TrustPermissions(
                            rs.getInt("can_build") != 0,
                            rs.getInt("can_container") != 0,
                            rs.getInt("can_interact") != 0);
                    target.computeIfAbsent(rs.getString(idColumn), ignored -> new HashMap<>())
                            .put(trustedUuid, permissions);
                } catch (IllegalArgumentException invalidUuid) {
                    eco.getLogger().warning("[房地产] 忽略无效信任 UUID: " + rs.getString("trusted_uuid"));
                }
            }
        }
    }

    private Map<String, Map<UUID, TrustPermissions>> freezeTrustCache(
            Map<String, Map<UUID, TrustPermissions>> source) {
        Map<String, Map<UUID, TrustPermissions>> frozen = new HashMap<>();
        for (var entry : source.entrySet()) {
            frozen.put(entry.getKey(), Map.copyOf(entry.getValue()));
        }
        return Map.copyOf(frozen);
    }

    private synchronized void putPlotTrustCache(String plotId, UUID trustedUuid, TrustPermissions permissions) {
        plotTrustCache = putTrustCacheEntry(plotTrustCache, plotId, trustedUuid, permissions);
    }

    private synchronized void removePlotTrustCache(String plotId, UUID trustedUuid) {
        plotTrustCache = removeTrustCacheEntry(plotTrustCache, plotId, trustedUuid);
    }

    private synchronized void putHouseTrustCache(String houseId, UUID trustedUuid, TrustPermissions permissions) {
        houseTrustCache = putTrustCacheEntry(houseTrustCache, houseId, trustedUuid, permissions);
    }

    private synchronized void removeHouseTrustCache(String houseId) {
        if (!houseTrustCache.containsKey(houseId)) return;
        Map<String, Map<UUID, TrustPermissions>> next = new HashMap<>(houseTrustCache);
        next.remove(houseId);
        houseTrustCache = Map.copyOf(next);
    }

    private Map<String, Map<UUID, TrustPermissions>> putTrustCacheEntry(
            Map<String, Map<UUID, TrustPermissions>> current,
            String targetId,
            UUID trustedUuid,
            TrustPermissions permissions) {
        Map<String, Map<UUID, TrustPermissions>> next = new HashMap<>(current);
        Map<UUID, TrustPermissions> entries = new HashMap<>(current.getOrDefault(targetId, Map.of()));
        entries.put(trustedUuid, permissions);
        next.put(targetId, Map.copyOf(entries));
        return Map.copyOf(next);
    }

    private Map<String, Map<UUID, TrustPermissions>> removeTrustCacheEntry(
            Map<String, Map<UUID, TrustPermissions>> current,
            String targetId,
            UUID trustedUuid) {
        Map<UUID, TrustPermissions> currentEntries = current.get(targetId);
        if (currentEntries == null || !currentEntries.containsKey(trustedUuid)) return current;
        Map<String, Map<UUID, TrustPermissions>> next = new HashMap<>(current);
        Map<UUID, TrustPermissions> entries = new HashMap<>(currentEntries);
        entries.remove(trustedUuid);
        if (entries.isEmpty()) next.remove(targetId);
        else next.put(targetId, Map.copyOf(entries));
        return Map.copyOf(next);
    }

    private boolean ownsPlayerPlot(Connection conn, String plotId, UUID owner) throws SQLException {
        try (var ps = conn.prepareStatement(
                "SELECT 1 FROM ks_re_plots WHERE id=? AND owner_type=? AND owner_id=? LIMIT 1")) {
            ps.setString(1, plotId);
            ps.setString(2, OWNER_PLAYER);
            ps.setString(3, owner.toString());
            try (var rs = ps.executeQuery()) {
                return rs.next();
            }
        }
    }

    private String normalizeTrustName(String targetName) {
        if (targetName == null) return "";
        String normalized = targetName.trim();
        return normalized.length() <= 32 ? normalized : normalized.substring(0, 32);
    }

    private static void bindTrustUpdate(PreparedStatement ps, String name, boolean build,
                                        boolean container, boolean interact, String assetId,
                                        UUID targetUuid, UUID ownerUuid) throws SQLException {
        ps.setString(1, name);
        ps.setInt(2, build ? 1 : 0);
        ps.setInt(3, container ? 1 : 0);
        ps.setInt(4, interact ? 1 : 0);
        ps.setString(5, assetId);
        ps.setString(6, targetUuid.toString());
        ps.setString(7, assetId);
        ps.setString(8, OWNER_PLAYER);
        ps.setString(9, ownerUuid.toString());
    }

    private static void bindTrustInsert(PreparedStatement ps, String assetId, UUID targetUuid,
                                        String name, boolean build, boolean container, boolean interact,
                                        long now, UUID ownerUuid) throws SQLException {
        ps.setString(1, assetId);
        ps.setString(2, targetUuid.toString());
        ps.setString(3, name);
        ps.setInt(4, build ? 1 : 0);
        ps.setInt(5, container ? 1 : 0);
        ps.setInt(6, interact ? 1 : 0);
        ps.setLong(7, now);
        ps.setString(8, assetId);
        ps.setString(9, OWNER_PLAYER);
        ps.setString(10, ownerUuid.toString());
    }

    private void ensureTables() {
        try (var conn = eco.ksCore().dataStore().getConnection()) {
            if (conn == null) return;
            RealEstateSchema.initialize(conn);
            try (var s = conn.createStatement()) {
                s.execute("""
                    CREATE TABLE IF NOT EXISTS ks_re_zones (
                        id VARCHAR(64) PRIMARY KEY,
                        name TEXT NOT NULL,
                        world TEXT NOT NULL,
                        x1 INTEGER NOT NULL, z1 INTEGER NOT NULL,
                        x2 INTEGER NOT NULL, z2 INTEGER NOT NULL,
                        type TEXT NOT NULL DEFAULT 'RESIDENTIAL',
                        base_price DOUBLE NOT NULL DEFAULT 1000,
                        tax_rate DOUBLE NOT NULL DEFAULT 0.05,
                        status TEXT NOT NULL DEFAULT 'STATE_OWNED',
                        created_at INTEGER NOT NULL
                    )
                """);
                s.execute("""
                    CREATE TABLE IF NOT EXISTS ks_re_plots (
                        id VARCHAR(64) PRIMARY KEY,
                        zone_id TEXT NOT NULL,
                        world TEXT NOT NULL,
                        x1 INTEGER NOT NULL, z1 INTEGER NOT NULL,
                        x2 INTEGER NOT NULL, z2 INTEGER NOT NULL,
                        owner_type TEXT NOT NULL,
                        owner_id TEXT NOT NULL,
                        price DOUBLE NOT NULL,
                        tax_rate DOUBLE NOT NULL,
                        status TEXT NOT NULL DEFAULT 'PURCHASED',
                        purchased_at INTEGER NOT NULL
                    )
                """);
                // 副本内房产共用 ks_re_plots：补 instance_id + property_function 两列（原 RealEstateDungeon 迁入，本插件现为唯一 owner）
                try { s.execute("ALTER TABLE ks_re_plots ADD COLUMN instance_id VARCHAR(128) DEFAULT NULL"); } catch (SQLException ignore) {}
                try { s.execute("ALTER TABLE ks_re_plots ADD COLUMN property_function VARCHAR(32) NOT NULL DEFAULT 'RESIDENTIAL'"); } catch (SQLException ignore) {}
                // 资产联动：住宅地块可绑定一个副本模板 ID，作为该副本的"资产钥匙"（与副本插件解耦，不做 FK 校验）
                try { s.execute("ALTER TABLE ks_re_plots ADD COLUMN dungeon_template_id VARCHAR(128) DEFAULT NULL"); } catch (SQLException ignore) {}
                // 容积率：区域最大合法登记地块数（0=不限）
                try { s.execute("ALTER TABLE ks_re_zones ADD COLUMN max_plots INTEGER NOT NULL DEFAULT 0"); } catch (SQLException ignore) {}
                // 资产联动：管理员划分住宅区域时直接配好副本权限（模板 ID），地块购买时自动继承到 ks_re_plots.dungeon_template_id
                try { s.execute("ALTER TABLE ks_re_zones ADD COLUMN dungeon_template_id VARCHAR(128) DEFAULT NULL"); } catch (SQLException ignore) {}
                // 领地保护：PLAYER 地块的信任名单（ENTERPRISE 地块按企业成员表直查，不进这张表）
                s.execute("""
                    CREATE TABLE IF NOT EXISTS ks_re_plot_trust (
                        plot_id VARCHAR(64) NOT NULL,
                        trusted_uuid VARCHAR(64) NOT NULL,
                        trusted_name TEXT DEFAULT '',
                        can_build INTEGER NOT NULL DEFAULT 1,
                        can_container INTEGER NOT NULL DEFAULT 1,
                        can_interact INTEGER NOT NULL DEFAULT 1,
                        granted_at INTEGER NOT NULL,
                        PRIMARY KEY (plot_id, trusted_uuid)
                    )
                """);
                // 房屋：在已购地块范围内圈定登记的实际建筑（登记才消耗容积率，买地本身不消耗）
                s.execute("""
                    CREATE TABLE IF NOT EXISTS ks_re_houses (
                        id VARCHAR(64) PRIMARY KEY,
                        plot_id TEXT NOT NULL,
                        zone_id TEXT NOT NULL,
                        world TEXT NOT NULL,
                        x1 INTEGER NOT NULL, y1 INTEGER NOT NULL, z1 INTEGER NOT NULL,
                        x2 INTEGER NOT NULL, y2 INTEGER NOT NULL, z2 INTEGER NOT NULL,
                        owner_type TEXT NOT NULL,
                        owner_id TEXT NOT NULL,
                        dungeon_template_id TEXT DEFAULT NULL,
                        tax_rate DOUBLE NOT NULL DEFAULT 0.05,
                        name TEXT DEFAULT NULL,
                        showcase_price DOUBLE NOT NULL DEFAULT 0,
                        showcase_marker VARCHAR(32) NOT NULL DEFAULT 'CYAN',
                        registered_at INTEGER NOT NULL
                    )
                """);
                // 兼容旧表：早期某次部署用的是缺 tax_rate 列的旧版 CREATE TABLE（已被 CREATE TABLE IF NOT EXISTS 跳过未升级），
                // 之后所有 registerHouse() 的 INSERT 都会因列不存在而失败——这里补 ALTER 兜底修复历史遗留表结构。
                try { s.execute("ALTER TABLE ks_re_houses ADD COLUMN tax_rate DOUBLE NOT NULL DEFAULT 0.05"); } catch (SQLException ignore) {}
                try { s.execute("ALTER TABLE ks_re_houses ADD COLUMN showcase_price DOUBLE NOT NULL DEFAULT 0"); } catch (SQLException ignore) {}
                try { s.execute("ALTER TABLE ks_re_houses ADD COLUMN showcase_marker VARCHAR(32) NOT NULL DEFAULT 'CYAN'"); } catch (SQLException ignore) {}
                // 领地保护：PLAYER 房屋的信任名单（结构同 ks_re_plot_trust，键换成 house_id）
                s.execute("""
                    CREATE TABLE IF NOT EXISTS ks_re_house_trust (
                        house_id VARCHAR(64) NOT NULL,
                        trusted_uuid VARCHAR(64) NOT NULL,
                        trusted_name TEXT DEFAULT '',
                        can_build INTEGER NOT NULL DEFAULT 1,
                        can_container INTEGER NOT NULL DEFAULT 1,
                        can_interact INTEGER NOT NULL DEFAULT 1,
                        granted_at INTEGER NOT NULL,
                        PRIMARY KEY (house_id, trusted_uuid)
                    )
                """);
            }
            RealEstatePricingSchema.migrate(conn);
        } catch (SQLException e) {
            eco.getLogger().warning("[房地产] 建表失败: " + e.getMessage());
        }
    }

    // ================================================================
    // 区域 CRUD
    // ================================================================

    public String createZone(String name, String world, int x1, int z1, int x2, int z2,
                             String type, double basePrice, double taxRate, String status, int maxPlots,
                             String dungeonTemplateId) {
        return createZone(name, world, x1, z1, x2, z2, type, basePrice, taxRate, status, maxPlots,
                dungeonTemplateId, PlotPricingPolicy.MODE_FLAT, 0.0d, 0.0d, 0L,
                0L, 0L, 0L, 0L);
    }

    /**
     * Extended zone creation contract. Zero area limits mean disabled; existing callers retain FLAT pricing.
     */
    public String createZone(String name, String world, int x1, int z1, int x2, int z2,
                             String type, double basePrice, double taxRate, String status, int maxPlots,
                             String dungeonTemplateId, String pricingMode, double pricePerBlock,
                             double minimumPlotPrice, long maxPlotArea,
                             long playerSoftArea, long playerHardArea,
                             long enterpriseSoftArea, long enterpriseHardArea) {
        PlotPricingPolicy.ZonePolicy pricing;
        try {
            pricing = PlotPricingPolicy.policy(pricingMode, Math.max(0, basePrice), pricePerBlock,
                    minimumPlotPrice, maxPlotArea, playerSoftArea, playerHardArea,
                    enterpriseSoftArea, enterpriseHardArea);
        } catch (IllegalArgumentException exception) {
            return null;
        }
        String id = "Z" + UUID.randomUUID().toString().substring(0, 8);
        String resolvedType = type != null ? type : ZONE_TYPE_RESIDENTIAL;
        // 副本权限对住宅/农业/工业用地开放；其余区域类型填了也忽略
        String dtid = dungeonTemplateId == null ? null : dungeonTemplateId.trim();
        if (dtid != null && dtid.isEmpty()) dtid = null;
        if (dtid != null && !DUNGEON_LINKABLE_ZONE_TYPES.contains(resolvedType)) dtid = null;
        long now = System.currentTimeMillis() / 1000;
        try (var conn = eco.ksCore().dataStore().getConnection()) {
            if (conn == null) return null;
            try (var ps = conn.prepareStatement(
                    "INSERT INTO ks_re_zones (id, name, world, x1, z1, x2, z2, type, base_price, tax_rate, status, created_at, max_plots, dungeon_template_id, " +
                    "pricing_mode, price_per_block, min_plot_price, max_plot_area, player_soft_area, player_hard_area, enterprise_soft_area, enterprise_hard_area) " +
                    "VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)")) {
                ps.setString(1, id);
                ps.setString(2, name);
                ps.setString(3, world);
                ps.setInt(4, x1);
                ps.setInt(5, z1);
                ps.setInt(6, x2);
                ps.setInt(7, z2);
                ps.setString(8, resolvedType);
                ps.setDouble(9, Math.max(0, basePrice));
                ps.setDouble(10, Math.max(0, Math.min(1, taxRate)));
                ps.setString(11, status != null ? status : ZONE_STATUS_STATE_OWNED);
                ps.setLong(12, now);
                ps.setInt(13, Math.max(0, maxPlots));
                ps.setString(14, dtid);
                ps.setString(15, pricing.pricingMode());
                ps.setDouble(16, pricing.pricePerBlock());
                ps.setDouble(17, pricing.minimumPrice());
                ps.setLong(18, pricing.maxPlotArea());
                ps.setLong(19, pricing.playerSoftArea());
                ps.setLong(20, pricing.playerHardArea());
                ps.setLong(21, pricing.enterpriseSoftArea());
                ps.setLong(22, pricing.enterpriseHardArea());
                ps.executeUpdate();
                return id;
            }
        } catch (SQLException e) {
            eco.getLogger().warning("创建区域失败: " + e.getMessage());
        }
        return null;
    }

    /** 更改区域规划类型（RESIDENTIAL/COMMERCIAL/INDUSTRIAL/AGRICULTURAL）。 */
    public boolean setZoneType(String zoneId, String type) {
        if (zoneId == null || type == null) return false;
        try (var conn = eco.ksCore().dataStore().getConnection()) {
            if (conn == null) return false;
            try (var ps = conn.prepareStatement("UPDATE ks_re_zones SET type=? WHERE id=?")) {
                ps.setString(1, type);
                ps.setString(2, zoneId);
                boolean ok = ps.executeUpdate() > 0;
                // plotCache 缓存了 zoneType（供高频方块事件判断地块类型用），改规划类型后必须刷新，
                // 否则已购地块的缓存条目会一直停留在旧类型上，导致地块福利判断结果与实际规划不符。
                if (ok) refreshPlotCache();
                return ok;
            }
        } catch (SQLException e) {
            eco.getLogger().warning("更改区域规划失败: " + e.getMessage());
        }
        return false;
    }

    /** 设置容积率（最大合法登记地块数；0=不限）。 */
    public boolean setZoneMaxPlots(String zoneId, int maxPlots) {
        if (zoneId == null) return false;
        try (var conn = eco.ksCore().dataStore().getConnection()) {
            if (conn == null) return false;
            try (var ps = conn.prepareStatement("UPDATE ks_re_zones SET max_plots=? WHERE id=?")) {
                ps.setInt(1, Math.max(0, maxPlots));
                ps.setString(2, zoneId);
                return ps.executeUpdate() > 0;
            }
        } catch (SQLException e) {
            eco.getLogger().warning("设置容积率失败: " + e.getMessage());
        }
        return false;
    }

    /**
     * 设置区域的副本权限（绑定的副本模板 ID；空/null=取消）。对住宅/农业/工业用地生效。
     * 同时回填该区域内**已经存在**的地块和已登记房屋（不只是"之后购买的"），
     * 否则在区域配权限之前就已购地/登记房的玩家会一直拿不到钥匙（曾发生：玩家买了商品房却进不去副本，
     * 根因是地块在区域配置副本权限之前就已购买，没有回填）。
     * @return null 成功，否则中文错误信息。
     */
    public String setZoneDungeonLink(String zoneId, String templateId) {
        Map<String, Object> zone = getZone(zoneId);
        if (zone == null) return "区域不存在";
        if (!DUNGEON_LINKABLE_ZONE_TYPES.contains(zone.get("type"))) return "只有住宅/农业/工业用地才能配置副本权限";
        String dtid = templateId == null ? null : templateId.trim();
        if (dtid != null && dtid.isEmpty()) dtid = null;
        try (var conn = eco.ksCore().dataStore().getConnection()) {
            if (conn == null) return "数据库不可用";
            try (var ps = conn.prepareStatement("UPDATE ks_re_zones SET dungeon_template_id=? WHERE id=?")) {
                ps.setString(1, dtid);
                ps.setString(2, zoneId);
                ps.executeUpdate();
            }
            // 回填：区域内已购买、不在副本中的地块
            try (var ps = conn.prepareStatement(
                    "UPDATE ks_re_plots SET dungeon_template_id=? WHERE zone_id=? AND instance_id IS NULL")) {
                ps.setString(1, dtid);
                ps.setString(2, zoneId);
                ps.executeUpdate();
            }
            // 回填：这些地块上已登记的房屋（商品房）
            try (var ps = conn.prepareStatement(
                    "UPDATE ks_re_houses SET dungeon_template_id=? WHERE plot_id IN " +
                    "(SELECT id FROM ks_re_plots WHERE zone_id=? AND instance_id IS NULL)")) {
                ps.setString(1, dtid);
                ps.setString(2, zoneId);
                ps.executeUpdate();
            }
        } catch (SQLException e) {
            eco.getLogger().warning("设置区域副本权限失败: " + e.getMessage());
            return "设置失败";
        }
        refreshHouseCache();
        return null;
    }

    /** 删除区域（连带删除其名下地块；副本内地块 instance_id 不属任何区域，不受影响）。 */
    public boolean deleteZone(String zoneId) {
        if (zoneId == null) return false;
        try (var conn = eco.ksCore().dataStore().getConnection()) {
            if (conn == null) return false;
            try (var ps = conn.prepareStatement("DELETE FROM ks_re_plots WHERE zone_id=?")) {
                ps.setString(1, zoneId);
                ps.executeUpdate();
            }
            try (var ps = conn.prepareStatement("DELETE FROM ks_re_zones WHERE id=?")) {
                ps.setString(1, zoneId);
                boolean ok = ps.executeUpdate() > 0;
                refreshPlotCache();
                return ok;
            }
        } catch (SQLException e) {
            eco.getLogger().warning("删除区域失败: " + e.getMessage());
        }
        return false;
    }

    /** 统计某区域已登记地块数（用于容积率校验/展示）。 */
    public int countPlotsInZone(String zoneId) {
        try (var conn = eco.ksCore().dataStore().getConnection()) {
            if (conn == null) return 0;
            try (var ps = conn.prepareStatement("SELECT COUNT(*) FROM ks_re_plots WHERE zone_id=?")) {
                ps.setString(1, zoneId);
                try (var rs = ps.executeQuery()) { if (rs.next()) return rs.getInt(1); }
            }
        } catch (SQLException ignored) {}
        return 0;
    }

    public boolean setZonePrice(String zoneId, double basePrice) {
        if (!Double.isFinite(basePrice)) return false;
        try (var conn = eco.ksCore().dataStore().getConnection()) {
            if (conn == null) return false;
            try (var ps = conn.prepareStatement(
                    "UPDATE ks_re_zones SET base_price=?, " +
                    "price_per_block=CASE WHEN pricing_mode='PER_BLOCK' THEN ? ELSE price_per_block END WHERE id=?")) {
                ps.setDouble(1, Math.max(0, basePrice));
                ps.setDouble(2, Math.max(0, basePrice));
                ps.setString(3, zoneId);
                return ps.executeUpdate() > 0;
            }
        } catch (SQLException e) {
            eco.getLogger().warning("设置区域价格失败: " + e.getMessage());
        }
        return false;
    }

    /**
     * Atomically replaces a zone's pricing and anti-hoarding limits. Zero limits disable that limit.
     * Existing rows remain FLAT until this method explicitly changes them.
     *
     * @return null on success, otherwise a stable error message.
     */
    public String setZonePricingPolicy(String zoneId, String pricingMode, double pricePerBlock,
                                       double minimumPlotPrice, long maxPlotArea,
                                       long playerSoftArea, long playerHardArea,
                                       long enterpriseSoftArea, long enterpriseHardArea) {
        if (zoneId == null || zoneId.isBlank()) return "区域不存在";
        Map<String, Object> zone = getZone(zoneId);
        if (zone == null) return "区域不存在";

        PlotPricingPolicy.ZonePolicy pricing;
        try {
            pricing = PlotPricingPolicy.policy(pricingMode,
                    ((Number) zone.get("basePrice")).doubleValue(), pricePerBlock, minimumPlotPrice,
                    maxPlotArea, playerSoftArea, playerHardArea, enterpriseSoftArea, enterpriseHardArea);
        } catch (IllegalArgumentException exception) {
            return "定价或面积限制无效";
        }

        try (var conn = eco.ksCore().dataStore().getConnection()) {
            if (conn == null) return "数据库不可用";
            try (var ps = conn.prepareStatement(
                    "UPDATE ks_re_zones SET pricing_mode=?, price_per_block=?, min_plot_price=?, " +
                    "max_plot_area=?, player_soft_area=?, player_hard_area=?, " +
                    "enterprise_soft_area=?, enterprise_hard_area=? WHERE id=?")) {
                ps.setString(1, pricing.pricingMode());
                ps.setDouble(2, pricing.pricePerBlock());
                ps.setDouble(3, pricing.minimumPrice());
                ps.setLong(4, pricing.maxPlotArea());
                ps.setLong(5, pricing.playerSoftArea());
                ps.setLong(6, pricing.playerHardArea());
                ps.setLong(7, pricing.enterpriseSoftArea());
                ps.setLong(8, pricing.enterpriseHardArea());
                ps.setString(9, zoneId);
                return ps.executeUpdate() == 1 ? null : "区域不存在";
            }
        } catch (SQLException exception) {
            eco.getLogger().warning("设置区域定价策略失败: " + exception.getMessage());
            return "设置失败";
        }
    }

    public boolean setZoneStatus(String zoneId, String status) {
        try (var conn = eco.ksCore().dataStore().getConnection()) {
            if (conn == null) return false;
            try (var ps = conn.prepareStatement("UPDATE ks_re_zones SET status=? WHERE id=?")) {
                ps.setString(1, status);
                ps.setString(2, zoneId);
                return ps.executeUpdate() > 0;
            }
        } catch (SQLException e) {
            eco.getLogger().warning("设置区域状态失败: " + e.getMessage());
        }
        return false;
    }

    public List<Map<String, Object>> listZones() {
        List<Map<String, Object>> out = new ArrayList<>();
        try (var conn = eco.ksCore().dataStore().getConnection()) {
            if (conn == null) return out;
            try (var ps = conn.prepareStatement(
                    "SELECT z.*, " +
                    "COALESCE((SELECT COUNT(*) FROM ks_re_plots p WHERE p.zone_id=z.id), 0) AS plot_count, " +
                    "COALESCE((SELECT COUNT(*) FROM ks_re_houses h WHERE h.zone_id=z.id), 0) AS house_count " +
                    "FROM ks_re_zones z ORDER BY z.created_at DESC")) {
                try (var rs = ps.executeQuery()) {
                    while (rs.next()) {
                        Map<String, Object> m = new LinkedHashMap<>();
                        m.put("id", rs.getString("id"));
                        m.put("name", rs.getString("name"));
                        m.put("world", rs.getString("world"));
                        m.put("x1", rs.getInt("x1"));
                        m.put("z1", rs.getInt("z1"));
                        m.put("x2", rs.getInt("x2"));
                        m.put("z2", rs.getInt("z2"));
                        m.put("type", rs.getString("type"));
                        m.put("basePrice", rs.getDouble("base_price"));
                        m.put("taxRate", rs.getDouble("tax_rate"));
                        m.put("status", rs.getString("status"));
                        m.put("createdAt", rs.getLong("created_at"));
                        m.put("plotCount", rs.getInt("plot_count"));
                        m.put("houseCount", rs.getInt("house_count"));
                        try { m.put("maxPlots", rs.getInt("max_plots")); } catch (SQLException ignored) { m.put("maxPlots", 0); }
                        try { m.put("dungeonTemplateId", rs.getString("dungeon_template_id")); } catch (SQLException ignored) {}
                        putZonePricing(m, rs);
                        out.add(m);
                    }
                }
            }
        } catch (SQLException e) {
            eco.getLogger().warning("列区域失败: " + e.getMessage());
        }
        return out;
    }

    // ================================================================
    // 地块 CRUD
    // ================================================================

    /**
     * 购买地块。ownerType=PLAYER 时扣 Vault 个人；ENTERPRISE 时扣 CORP-BANK 公户。
     * @return 地块 ID，失败 null
     */
    public String purchasePlot(String zoneId, int x1, int z1, int x2, int z2,
                               String ownerType, String ownerId) {
        if (zoneId == null || zoneId.isBlank() || ownerType == null || ownerId == null || ownerId.isBlank()) return null;
        int minX = Math.min(x1, x2), maxX = Math.max(x1, x2);
        int minZ = Math.min(z1, z2), maxZ = Math.max(z1, z2);
        String normalizedOwnerType;
        org.bukkit.OfflinePlayer playerOwner = null;
        if (OWNER_PLAYER.equalsIgnoreCase(ownerType)) {
            normalizedOwnerType = OWNER_PLAYER;
            if (!eco.vaultHook().isAvailable()) return null;
            try {
                playerOwner = org.bukkit.Bukkit.getOfflinePlayer(UUID.fromString(ownerId));
            } catch (IllegalArgumentException e) {
                return null;
            }
        } else if (OWNER_ENTERPRISE.equalsIgnoreCase(ownerType)) {
            normalizedOwnerType = OWNER_ENTERPRISE;
        } else {
            return null;
        }

        // 个人 Vault 可能正是 ks-Eco 内置的同库 SQLite 经济实现，因此个人扣款必须发生在
        // 地产写事务之外。事务开始后会重新读取并核对区域价格，失败时统一退款。
        double quotedPlayerPrice = 0.0;
        if (OWNER_PLAYER.equals(normalizedOwnerType)) {
            Map<String, Object> quotedZone = getZone(zoneId);
            if (quotedZone == null || !ZONE_STATUS_FOR_SALE.equals(quotedZone.get("status"))) return null;
            int zoneMinX = Math.min(((Number) quotedZone.get("x1")).intValue(), ((Number) quotedZone.get("x2")).intValue());
            int zoneMaxX = Math.max(((Number) quotedZone.get("x1")).intValue(), ((Number) quotedZone.get("x2")).intValue());
            int zoneMinZ = Math.min(((Number) quotedZone.get("z1")).intValue(), ((Number) quotedZone.get("z2")).intValue());
            int zoneMaxZ = Math.max(((Number) quotedZone.get("z1")).intValue(), ((Number) quotedZone.get("z2")).intValue());
            if (minX < zoneMinX || maxX > zoneMaxX || minZ < zoneMinZ || maxZ > zoneMaxZ) return null;
            try (Connection quoteConnection = eco.ksCore().dataStore().getConnection()) {
                if (quoteConnection == null) return null;
                long heldArea = loadOwnerHeldArea(quoteConnection, normalizedOwnerType, ownerId);
                quotedPlayerPrice = PlotPricingPolicy.quote(pricingPolicy(quotedZone), normalizedOwnerType,
                        heldArea, minX, minZ, maxX, maxZ).finalPrice();
            } catch (SQLException | IllegalArgumentException exception) {
                return null;
            }
            if (!Double.isFinite(quotedPlayerPrice) || quotedPlayerPrice < 0
                    || !eco.vaultHook().has(playerOwner, quotedPlayerPrice)
                    || !eco.vaultHook().withdraw(playerOwner, quotedPlayerPrice)) return null;
        }

        // 区域复核、重叠检查、企业扣款与地块写入必须共用一个事务。先对区域做一次无副作用
        // UPDATE 以获取 SQLite 写锁，避免两个并发购买都在重叠检查后成功插入。
        String plotId = "P" + UUID.randomUUID().toString().substring(0, 8);
        long now = System.currentTimeMillis() / 1000;
        Connection conn = null;
        boolean originalAutoCommit = true;
        boolean playerCharged = OWNER_PLAYER.equals(normalizedOwnerType);
        boolean committed = false;
        double price = 0.0;
        try {
            conn = eco.ksCore().dataStore().getConnection();
            if (conn == null) return null;
            originalAutoCommit = conn.getAutoCommit();
            conn.setAutoCommit(false);

            try (var lock = conn.prepareStatement("UPDATE ks_re_zones SET status=status WHERE id=?")) {
                lock.setString(1, zoneId);
                if (lock.executeUpdate() != 1) {
                    conn.rollback();
                    return null;
                }
            }

            String world;
            double taxRate;
            String dungeonTemplateId;
            PlotPricingPolicy.ZonePolicy pricingPolicy;
            try (var zoneQuery = conn.prepareStatement(
                    "SELECT world,x1,z1,x2,z2,base_price,tax_rate,status,dungeon_template_id,pricing_mode," +
                    "price_per_block,min_plot_price,max_plot_area,player_soft_area,player_hard_area," +
                    "enterprise_soft_area,enterprise_hard_area FROM ks_re_zones WHERE id=?")) {
                zoneQuery.setString(1, zoneId);
                try (var rs = zoneQuery.executeQuery()) {
                    if (!rs.next() || !ZONE_STATUS_FOR_SALE.equals(rs.getString("status"))) {
                        conn.rollback();
                        return null;
                    }
                    int zoneMinX = Math.min(rs.getInt("x1"), rs.getInt("x2"));
                    int zoneMaxX = Math.max(rs.getInt("x1"), rs.getInt("x2"));
                    int zoneMinZ = Math.min(rs.getInt("z1"), rs.getInt("z2"));
                    int zoneMaxZ = Math.max(rs.getInt("z1"), rs.getInt("z2"));
                    if (minX < zoneMinX || maxX > zoneMaxX || minZ < zoneMinZ || maxZ > zoneMaxZ) {
                        conn.rollback();
                        return null;
                    }
                    world = rs.getString("world");
                    taxRate = rs.getDouble("tax_rate");
                    dungeonTemplateId = rs.getString("dungeon_template_id");
                    pricingPolicy = pricingPolicy(rs);
                }
            }
            if (!Double.isFinite(taxRate) || taxRate < 0) {
                conn.rollback();
                return null;
            }

            try {
                long heldArea = loadOwnerHeldArea(conn, normalizedOwnerType, ownerId);
                price = PlotPricingPolicy.quote(pricingPolicy, normalizedOwnerType, heldArea,
                        minX, minZ, maxX, maxZ).finalPrice();
            } catch (IllegalArgumentException exception) {
                conn.rollback();
                return null;
            }
            if (playerCharged && Double.compare(price, quotedPlayerPrice) != 0) {
                conn.rollback();
                return null;
            }

            try (var overlap = conn.prepareStatement(
                    "SELECT id FROM ks_re_plots WHERE instance_id IS NULL AND world=? " +
                            "AND NOT (x2 < ? OR x1 > ? OR z2 < ? OR z1 > ?) LIMIT 1")) {
                overlap.setString(1, world);
                overlap.setInt(2, minX);
                overlap.setInt(3, maxX);
                overlap.setInt(4, minZ);
                overlap.setInt(5, maxZ);
                try (var rs = overlap.executeQuery()) {
                    if (rs.next()) {
                        conn.rollback();
                        return null;
                    }
                }
            }

            if (OWNER_ENTERPRISE.equals(normalizedOwnerType)) {
                try (var debit = conn.prepareStatement(
                        "UPDATE ks_ent_corporate_accounts SET balance=balance-?, updated_at=? " +
                                "WHERE enterprise_id=? AND balance>=?")) {
                    debit.setDouble(1, price);
                    debit.setLong(2, now);
                    debit.setString(3, ownerId);
                    debit.setDouble(4, price);
                    if (debit.executeUpdate() != 1) {
                        conn.rollback();
                        return null;
                    }
                }
            }

            try (var ps = conn.prepareStatement(
                    "INSERT INTO ks_re_plots (id, zone_id, world, x1, z1, x2, z2, owner_type, owner_id, price, tax_rate, status, purchased_at, dungeon_template_id) " +
                    "VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?)")) {
                ps.setString(1, plotId);
                ps.setString(2, zoneId);
                ps.setString(3, world);
                ps.setInt(4, minX);
                ps.setInt(5, minZ);
                ps.setInt(6, maxX);
                ps.setInt(7, maxZ);
                ps.setString(8, normalizedOwnerType);
                ps.setString(9, ownerId);
                ps.setDouble(10, price);
                ps.setDouble(11, taxRate);
                ps.setString(12, "PURCHASED");
                ps.setLong(13, now);
                ps.setString(14, dungeonTemplateId);
                ps.executeUpdate();
            }
            conn.commit();
            committed = true;
        } catch (Exception e) {
            eco.getLogger().warning("购买地块失败: " + e.getMessage());
            try { if (conn != null) conn.rollback(); } catch (SQLException ignored) {}
            return null;
        } finally {
            if (conn != null) {
                try { conn.setAutoCommit(originalAutoCommit); } catch (SQLException ignored) {}
                try { conn.close(); } catch (SQLException ignored) {}
            }
            if (playerCharged && !committed) eco.vaultHook().deposit(playerOwner, quotedPlayerPrice);
        }
        refreshPlotCache();
        return plotId;
    }

    private PlotPricingPolicy.ZonePolicy pricingPolicy(Map<String, Object> zone) {
        return PlotPricingPolicy.policy(
                String.valueOf(zone.getOrDefault("pricingMode", PlotPricingPolicy.MODE_FLAT)),
                ((Number) zone.getOrDefault("basePrice", 0.0d)).doubleValue(),
                ((Number) zone.getOrDefault("pricePerBlock", 0.0d)).doubleValue(),
                ((Number) zone.getOrDefault("minimumPlotPrice", 0.0d)).doubleValue(),
                ((Number) zone.getOrDefault("maxPlotArea", 0L)).longValue(),
                ((Number) zone.getOrDefault("playerSoftArea", 0L)).longValue(),
                ((Number) zone.getOrDefault("playerHardArea", 0L)).longValue(),
                ((Number) zone.getOrDefault("enterpriseSoftArea", 0L)).longValue(),
                ((Number) zone.getOrDefault("enterpriseHardArea", 0L)).longValue());
    }

    private PlotPricingPolicy.ZonePolicy pricingPolicy(ResultSet resultSet) throws SQLException {
        return PlotPricingPolicy.policy(
                resultSet.getString("pricing_mode"),
                resultSet.getDouble("base_price"),
                resultSet.getDouble("price_per_block"),
                resultSet.getDouble("min_plot_price"),
                resultSet.getLong("max_plot_area"),
                resultSet.getLong("player_soft_area"),
                resultSet.getLong("player_hard_area"),
                resultSet.getLong("enterprise_soft_area"),
                resultSet.getLong("enterprise_hard_area"));
    }

    private long loadOwnerHeldArea(Connection connection, String ownerType, String ownerId) throws SQLException {
        long total = 0L;
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT x1,z1,x2,z2 FROM ks_re_plots " +
                "WHERE instance_id IS NULL AND owner_type=? AND owner_id=?")) {
            statement.setString(1, ownerType);
            statement.setString(2, ownerId);
            try (ResultSet resultSet = statement.executeQuery()) {
                while (resultSet.next()) {
                    long area;
                    try {
                        area = PlotPricingPolicy.area(resultSet.getInt("x1"), resultSet.getInt("z1"),
                                resultSet.getInt("x2"), resultSet.getInt("z2"));
                        total = Math.addExact(total, area);
                    } catch (ArithmeticException | IllegalArgumentException exception) {
                        throw new SQLException("invalid stored plot area", exception);
                    }
                }
            }
        }
        return total;
    }

    public List<Map<String, Object>> listPlots(String zoneId, String ownerType, String ownerId) {
        List<Map<String, Object>> out = new ArrayList<>();
        try (var conn = eco.ksCore().dataStore().getConnection()) {
            if (conn == null) return out;
            StringBuilder sql = new StringBuilder("SELECT p.*, z.type AS joined_zone_type FROM ks_re_plots p LEFT JOIN ks_re_zones z ON z.id=p.zone_id WHERE 1=1");
            List<Object> params = new ArrayList<>();
            if (zoneId != null) { sql.append(" AND p.zone_id=?"); params.add(zoneId); }
            if (ownerType != null) { sql.append(" AND p.owner_type=?"); params.add(ownerType); }
            if (ownerId != null) { sql.append(" AND p.owner_id=?"); params.add(ownerId); }
            sql.append(" ORDER BY p.purchased_at DESC LIMIT 200");
            try (var ps = conn.prepareStatement(sql.toString())) {
                for (int i = 0; i < params.size(); i++) ps.setObject(i + 1, params.get(i));
                try (var rs = ps.executeQuery()) {
                    while (rs.next()) {
                        Map<String, Object> m = new LinkedHashMap<>();
                        m.put("id", rs.getString("id"));
                        m.put("zoneId", rs.getString("zone_id"));
                        m.put("world", rs.getString("world"));
                        m.put("x1", rs.getInt("x1"));
                        m.put("z1", rs.getInt("z1"));
                        m.put("x2", rs.getInt("x2"));
                        m.put("z2", rs.getInt("z2"));
                        m.put("ownerType", rs.getString("owner_type"));
                        m.put("ownerId", rs.getString("owner_id"));
                        m.put("price", rs.getDouble("price"));
                        m.put("taxRate", rs.getDouble("tax_rate"));
                        m.put("status", rs.getString("status"));
                        m.put("purchasedAt", rs.getLong("purchased_at"));
                        try { m.put("zoneType", rs.getString("joined_zone_type")); } catch (SQLException ignored) {}
                        try { m.put("dungeonTemplateId", rs.getString("dungeon_template_id")); } catch (SQLException ignored) {}
                        out.add(m);
                    }
                }
            }
        } catch (SQLException e) {
            eco.getLogger().warning("列地块失败: " + e.getMessage());
        }
        return out;
    }

    public Map<String, Object> getZone(String zoneId) {
        try (var conn = eco.ksCore().dataStore().getConnection()) {
            if (conn == null) return null;
            try (var ps = conn.prepareStatement("SELECT * FROM ks_re_zones WHERE id=?")) {
                ps.setString(1, zoneId);
                try (var rs = ps.executeQuery()) {
                    if (rs.next()) {
                        Map<String, Object> m = new LinkedHashMap<>();
                        m.put("id", rs.getString("id"));
                        m.put("name", rs.getString("name"));
                        m.put("world", rs.getString("world"));
                        m.put("x1", rs.getInt("x1"));
                        m.put("z1", rs.getInt("z1"));
                        m.put("x2", rs.getInt("x2"));
                        m.put("z2", rs.getInt("z2"));
                        m.put("type", rs.getString("type"));
                        m.put("basePrice", rs.getDouble("base_price"));
                        m.put("taxRate", rs.getDouble("tax_rate"));
                        m.put("status", rs.getString("status"));
                        m.put("createdAt", rs.getLong("created_at"));
                        try { m.put("maxPlots", rs.getInt("max_plots")); } catch (SQLException ignored) { m.put("maxPlots", 0); }
                        try { m.put("dungeonTemplateId", rs.getString("dungeon_template_id")); } catch (SQLException ignored) {}
                        putZonePricing(m, rs);
                        return m;
                    }
                }
            }
        } catch (SQLException e) {
            eco.getLogger().warning("查区域失败: " + e.getMessage());
        }
        return null;
    }

    private void putZonePricing(Map<String, Object> target, ResultSet resultSet) throws SQLException {
        target.put("pricingMode", resultSet.getString("pricing_mode"));
        target.put("pricePerBlock", resultSet.getDouble("price_per_block"));
        target.put("minimumPlotPrice", resultSet.getDouble("min_plot_price"));
        target.put("maxPlotArea", resultSet.getLong("max_plot_area"));
        target.put("playerSoftArea", resultSet.getLong("player_soft_area"));
        target.put("playerHardArea", resultSet.getLong("player_hard_area"));
        target.put("enterpriseSoftArea", resultSet.getLong("enterprise_soft_area"));
        target.put("enterpriseHardArea", resultSet.getLong("enterprise_hard_area"));
    }

    // ================================================================
    // 房屋登记（在已购地块范围内圈定建筑、登记产权 —— 容积率在这一步校验，买地本身不消耗容积率）
    // ================================================================

    /** registerHouse 的结果：houseId 非空=成功；否则 error 是中文失败原因。 */
    public static final class HouseResult {
        public final String houseId;
        public final String error;
        private HouseResult(String houseId, String error) { this.houseId = houseId; this.error = error; }
        static HouseResult ok(String id) { return new HouseResult(id, null); }
        static HouseResult fail(String err) { return new HouseResult(null, err); }
    }

    /** 设置/记录玩家测量棒选区的一个角点。kind=1 记 pos1（左键），kind=2 记 pos2（右键）。 */
    public void setHouseSelectionPoint(UUID player, String world, int x, int y, int z, int kind) {
        Selection sel = houseSelections.computeIfAbsent(player, k -> new Selection());
        sel.world = world;
        if (kind == 1) sel.pos1 = new int[]{x, y, z};
        else sel.pos2 = new int[]{x, y, z};
    }

    public Selection getHouseSelection(UUID player) {
        return houseSelections.get(player);
    }

    public void clearHouseSelection(UUID player) {
        houseSelections.remove(player);
    }

    /**
     * 在 plotId 范围内登记一套房屋。actor 须是地块所有者（PLAYER 本人或 ENTERPRISE 成员）。
     * 校验顺序：地块存在 → 世界一致 → 有权管理 → 选区落在地块 x/z 范围内 → 与本地块其它房屋不重叠 → 区域容积率未满。
     */
    public HouseResult registerHouse(String plotId, UUID actor, String world,
            int x1, int y1, int z1, int x2, int y2, int z2, String name) {
        Map<String, Object> plot = getPlot(plotId);
        if (plot == null) return HouseResult.fail("地块不存在");
        if (!plot.get("world").equals(world)) return HouseResult.fail("选区不在该地块所在世界");
        if (!canManagePlot(plot, actor)) return HouseResult.fail("你不是该地块的所有者");
        // 归一化坐标（兼容玩家两点点反的情况）
        int nx1 = Math.min(x1, x2), nx2 = Math.max(x1, x2);
        int ny1 = Math.min(y1, y2), ny2 = Math.max(y1, y2);
        int nz1 = Math.min(z1, z2), nz2 = Math.max(z1, z2);
        int px1 = ((Number) plot.get("x1")).intValue(), px2 = ((Number) plot.get("x2")).intValue();
        int pz1 = ((Number) plot.get("z1")).intValue(), pz2 = ((Number) plot.get("z2")).intValue();
        if (nx1 < px1 || nx2 > px2 || nz1 < pz1 || nz2 > pz2) return HouseResult.fail("选区超出地块范围");
        String zoneId = (String) plot.get("zoneId");
        Map<String, Object> zone = getZone(zoneId);
        int maxPlots = (zone != null && zone.get("maxPlots") instanceof Number n) ? n.intValue() : 0;
        boolean capacityLimited = zone != null && ZONE_TYPE_RESIDENTIAL.equals(zone.get("type")) && maxPlots > 0;
        // 校验(重叠+容积率)和插入必须在同一事务/同一连接里原子完成，否则两次查询之间的窗口可能被并发请求
        // 撞上，导致"提示失败但已被别的并发请求占用配额"这类不一致现象。
        String houseId = "H" + UUID.randomUUID().toString().substring(0, 8);
        long now = System.currentTimeMillis() / 1000;
        Connection conn = null;
        boolean originalAutoCommit = true;
        try {
            conn = eco.ksCore().dataStore().getConnection();
            if (conn == null) return HouseResult.fail("数据库不可用");
            originalAutoCommit = conn.getAutoCommit();
            conn.setAutoCommit(false);

            for (Map<String, Object> h : listHousesForPlotConn(conn, plotId)) {
                if (housesOverlap(nx1, ny1, nz1, nx2, ny2, nz2, h)) {
                    conn.rollback();
                    return HouseResult.fail("与本地块上已登记的房屋范围重叠（房屋 " + h.get("id") + "）");
                }
            }
            if (capacityLimited && countHousesInZoneConn(conn, zoneId) >= maxPlots) {
                conn.rollback();
                return HouseResult.fail("该区域容积率已满，无法再登记新房屋");
            }

            try (var ps = conn.prepareStatement(
                    "INSERT INTO ks_re_houses (id, plot_id, zone_id, world, x1, y1, z1, x2, y2, z2, owner_type, owner_id, dungeon_template_id, tax_rate, name, registered_at) " +
                    "VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)")) {
                ps.setString(1, houseId);
                ps.setString(2, plotId);
                ps.setString(3, zoneId);
                ps.setString(4, world);
                ps.setInt(5, nx1); ps.setInt(6, ny1); ps.setInt(7, nz1);
                ps.setInt(8, nx2); ps.setInt(9, ny2); ps.setInt(10, nz2);
                ps.setString(11, (String) plot.get("ownerType"));
                ps.setString(12, (String) plot.get("ownerId"));
                ps.setString(13, (String) plot.get("dungeonTemplateId"));
                ps.setDouble(14, ((Number) plot.get("taxRate")).doubleValue());
                ps.setString(15, name);
                ps.setLong(16, now);
                ps.executeUpdate();
            }
            conn.commit();
        } catch (Exception e) {
            eco.getLogger().warning("[房地产] 登记房屋失败: " + e.getMessage());
            try { if (conn != null) conn.rollback(); } catch (SQLException ignored) {}
            return HouseResult.fail("登记失败（数据库异常，未消耗容积率）");
        } finally {
            if (conn != null) {
                try { conn.setAutoCommit(originalAutoCommit); } catch (SQLException ignored) {}
                try { conn.close(); } catch (SQLException ignored) {}
            }
        }
        refreshHouseCache();
        return HouseResult.ok(houseId);
    }

    /** Web 管理端登记入口使用稳定的 Map 合同，避免主插件反射依赖 Extra 内部结果类型。 */
    public Map<String, Object> registerHouseForWeb(String plotId, UUID actor, String world,
            int x1, int y1, int z1, int x2, int y2, int z2, String name) {
        HouseResult result = registerHouse(plotId, actor, world, x1, y1, z1, x2, y2, z2, name);
        if (result.houseId != null) return Map.of("houseId", result.houseId, "message", "楼栋已登记并进入沙盘预热队列");
        return Map.of("error", result.error == null ? "登记失败" : result.error);
    }

    /** 设置售楼沙盘展示信息；展示价不等同于正式市场挂单，不参与资金结算。 */
    public boolean setHouseShowcase(String houseId, double showcasePrice, String marker) {
        if (houseId == null || houseId.isBlank() || !Double.isFinite(showcasePrice) || showcasePrice < 0) return false;
        String normalizedMarker = normalizeShowcaseMarker(marker);
        try (var conn = eco.ksCore().dataStore().getConnection()) {
            if (conn == null) return false;
            try (var ps = conn.prepareStatement(
                    "UPDATE ks_re_houses SET showcase_price=?,showcase_marker=? WHERE id=?")) {
                ps.setDouble(1, showcasePrice);
                ps.setString(2, normalizedMarker);
                ps.setString(3, houseId);
                if (ps.executeUpdate() != 1) return false;
            }
        } catch (SQLException error) {
            eco.getLogger().warning("[房地产] 设置沙盘展示信息失败: " + error.getMessage());
            return false;
        }
        refreshHouseCache();
        return true;
    }

    private String normalizeShowcaseMarker(String marker) {
        String value = marker == null ? "CYAN" : marker.trim().toUpperCase(Locale.ROOT);
        return Set.of("CYAN", "MAGENTA", "AMBER", "GREEN", "BLUE", "RED").contains(value) ? value : "CYAN";
    }

    /** unregisterHouse 的结果：success=true 表示已删除；否则 error 是中文失败原因。 */
    public static final class UnregisterResult {
        public final boolean success;
        public final String error;
        private UnregisterResult(boolean success, String error) { this.success = success; this.error = error; }
        static UnregisterResult ok() { return new UnregisterResult(true, null); }
        static UnregisterResult fail(String err) { return new UnregisterResult(false, err); }
    }

    /**
     * 退registration：撤销一套已登记房屋，释放其在区域容积率里占用的名额。
     * 正在市场挂牌出售中的房屋不能直接退登记，必须先 /house sell 撤回挂单。
     */
    public UnregisterResult unregisterHouse(String houseId, UUID actor) {
        Map<String, Object> house = getHouse(houseId);
        if (house == null) return UnregisterResult.fail("房屋不存在");
        if (isMortgagedCollateral("HOUSE", houseId)) return UnregisterResult.fail("该房屋已作为企业融资抵押物，结清贷款前不能注销或转让");
        String ownerType = (String) house.get("ownerType");
        String ownerId = (String) house.get("ownerId");
        boolean owns = OWNER_PLAYER.equals(ownerType) ? actor.toString().equals(ownerId)
                : OWNER_ENTERPRISE.equals(ownerType) && isEnterpriseMember(ownerId, actor);
        if (!owns) return UnregisterResult.fail("你不是该房屋的所有者");
        if (eco.listingManager().hasActivePropertyListing(houseId)) {
            return UnregisterResult.fail("该房屋正在市场挂牌出售中，请先撤回挂单（/market 我的挂单）再退登记");
        }
        try (var conn = eco.ksCore().dataStore().getConnection()) {
            if (conn == null) return UnregisterResult.fail("数据库不可用");
            try (var ps = conn.prepareStatement("DELETE FROM ks_re_house_trust WHERE house_id=?")) {
                ps.setString(1, houseId);
                ps.executeUpdate();
            }
            try (var ps = conn.prepareStatement("DELETE FROM ks_re_houses WHERE id=?")) {
                ps.setString(1, houseId);
                ps.executeUpdate();
            }
        } catch (SQLException e) {
            eco.getLogger().warning("[房地产] 退登记房屋失败: " + e.getMessage());
            return UnregisterResult.fail("退登记失败");
        }
        removeHouseTrustCache(houseId);
        refreshHouseCache();
        return UnregisterResult.ok();
    }

    /**
     * 给定一个坐标范围（两点，未必落在单一地块/区域内），查询该范围所涉及的地块/区域容积率状况，
     * 供玩家在 /house register 前先确认该区域是否还有名额，避免"圈完才发现已满"。
     */
    public AreaCheckResult checkArea(String world, int x1, int z1, int x2, int z2) {
        int nx1 = Math.min(x1, x2), nx2 = Math.max(x1, x2);
        int nz1 = Math.min(z1, z2), nz2 = Math.max(z1, z2);
        List<Map<String, Object>> list = plotCache.get(world);
        AreaCheckResult result = new AreaCheckResult();
        if (list == null) return result;
        for (Map<String, Object> plot : list) {
            int px1 = ((Number) plot.get("x1")).intValue(), px2 = ((Number) plot.get("x2")).intValue();
            int pz1 = ((Number) plot.get("z1")).intValue(), pz2 = ((Number) plot.get("z2")).intValue();
            boolean overlapsXZ = nx1 <= px2 && nx2 >= px1 && nz1 <= pz2 && nz2 >= pz1;
            if (!overlapsXZ) continue;
            String plotId = (String) plot.get("id");
            String zoneId = (String) plot.get("zoneId");
            Map<String, Object> zone = getZone(zoneId);
            int maxPlots = (zone != null && zone.get("maxPlots") instanceof Number n) ? n.intValue() : 0;
            int registered = countHousesInZone(zoneId);
            int housesInThisPlot = listHousesForPlot(plotId).size();
            result.plots.add(Map.of(
                    "plotId", plotId,
                    "zoneId", zoneId,
                    "zoneName", zone != null ? String.valueOf(zone.get("name")) : "?",
                    "zoneMaxPlots", maxPlots,
                    "zoneRegisteredHouses", registered,
                    "housesInThisPlot", housesInThisPlot,
                    "full", maxPlots > 0 && registered >= maxPlots
            ));
        }
        return result;
    }

    public static final class AreaCheckResult {
        public final List<Map<String, Object>> plots = new ArrayList<>();
    }

    private boolean housesOverlap(int x1, int y1, int z1, int x2, int y2, int z2, Map<String, Object> h) {
        int hx1 = (Integer) h.get("x1"), hx2 = (Integer) h.get("x2");
        int hy1 = (Integer) h.get("y1"), hy2 = (Integer) h.get("y2");
        int hz1 = (Integer) h.get("z1"), hz2 = (Integer) h.get("z2");
        return x1 <= hx2 && x2 >= hx1 && y1 <= hy2 && y2 >= hy1 && z1 <= hz2 && z2 >= hz1;
    }

    /** 统计某区域已登记房屋数（容积率校验用，替代原来按地块数计的 countPlotsInZone）。 */
    public int countHousesInZone(String zoneId) {
        try (var conn = eco.ksCore().dataStore().getConnection()) {
            if (conn == null) return 0;
            return countHousesInZoneConn(conn, zoneId);
        } catch (SQLException ignored) {}
        return 0;
    }

    /** 同 {@link #countHousesInZone}，但复用调用方传入的连接（用于事务内一致性读取，不单独开/关连接）。 */
    private int countHousesInZoneConn(Connection conn, String zoneId) throws SQLException {
        try (var ps = conn.prepareStatement("SELECT COUNT(*) FROM ks_re_houses WHERE zone_id=?")) {
            ps.setString(1, zoneId);
            try (var rs = ps.executeQuery()) { if (rs.next()) return rs.getInt(1); }
        }
        return 0;
    }

    public Map<String, Object> getHouse(String houseId) {
        if (houseId == null) return null;
        try (var conn = eco.ksCore().dataStore().getConnection()) {
            if (conn == null) return null;
            try (var ps = conn.prepareStatement("SELECT * FROM ks_re_houses WHERE id=?")) {
                ps.setString(1, houseId);
                try (var rs = ps.executeQuery()) { if (rs.next()) return houseRowToMap(rs); }
            }
        } catch (SQLException ignored) {}
        return null;
    }

    private static final int MAX_VOXEL_BLOCKS = 60000;
    public static final int MAX_REGION_FOOTPRINT = 128;
    private static final int MAX_REGION_SCAN_VOLUME = 600000;
    private static final int MAX_REGION_HEIGHT = 128;
    private static final int REGION_SURFACE_BELOW = 64;
    private static final int REGION_SURFACE_ABOVE = 16;

    /**
     * 导出一套房屋的售楼沙盘模型。首次请求只排队，不阻塞 HTTP 线程；网页轮询到 READY 后取得缓存模型。
     * 世界读取被切成每 tick 固定预算，完成后再异步裁掉完全被遮挡的内部方块。
     */
    public Map<String, Object> exportHouseVoxels(String houseId) {
        Map<String, Object> house = getHouse(houseId);
        if (house == null) return null;
        String world = (String) house.get("world");
        int x1 = (int) house.get("x1"), y1 = (int) house.get("y1"), z1 = (int) house.get("z1");
        int x2 = (int) house.get("x2"), y2 = (int) house.get("y2"), z2 = (int) house.get("z2");
        long volume = (long) (x2 - x1 + 1) * (y2 - y1 + 1) * (z2 - z1 + 1);
        if (volume > MAX_HOUSE_SCAN_VOLUME) {
            return voxelEnvelope(house, "FAILED", true, "volume_limit", List.of());
        }

        String signature = world + ':' + x1 + ':' + y1 + ':' + z1 + ':' + x2 + ':' + y2 + ':' + z2;
        long now = System.currentTimeMillis();
        CachedVoxelModel entry = voxelModelCache.compute(houseId, (id, current) -> {
            boolean expired = current != null && current.future().isDone()
                    && now - current.queuedAt() > VOXEL_CACHE_TTL_MILLIS;
            if (current == null || !current.signature().equals(signature) || expired || current.future().isCancelled()) {
                return queueHouseVoxelSnapshot(house, signature, now);
            }
            return current;
        });
        if (!entry.future().isDone()) {
            Map<String, Object> pending = voxelEnvelope(house, "PREPARING", false, null, List.of());
            pending.put("retryAfterMs", 250);
            pending.put("queuedAt", entry.queuedAt());
            return pending;
        }
        try {
            return entry.future().join();
        } catch (RuntimeException error) {
            voxelModelCache.remove(houseId, entry);
            eco.getLogger().warning("[房地产] 售楼沙盘快照失败: " + error.getMessage());
            return voxelEnvelope(house, "FAILED", true, "read_failed", List.of());
        }
    }

    /** 仅触发预热；城区清单接口调用后网页可以立即绘制地块骨架。 */
    public boolean prewarmHouseVoxels(String houseId) {
        return exportHouseVoxels(houseId) != null;
    }

    /** 城区由已登记单栋组成；调用方可据此先画道路/地块，再逐栋请求缓存模型。 */
    public List<Map<String, Object>> listHousesInZone(String zoneId) {
        if (zoneId == null || zoneId.isBlank()) return List.of();
        List<Map<String, Object>> out = new ArrayList<>();
        for (List<Map<String, Object>> houses : houseCache.values()) {
            for (Map<String, Object> house : houses) {
                if (zoneId.equals(house.get("zoneId"))) out.add(new LinkedHashMap<>(house));
            }
        }
        out.sort(Comparator.comparingLong(h -> ((Number) h.getOrDefault("registeredAt", 0L)).longValue()));
        return out;
    }

    private CachedVoxelModel queueHouseVoxelSnapshot(Map<String, Object> house, String signature, long queuedAt) {
        CompletableFuture<Map<String, Object>> future = new CompletableFuture<>();
        Map<String, Object> immutableHouse = Collections.unmodifiableMap(new LinkedHashMap<>(house));
        String worldName = String.valueOf(immutableHouse.get("world"));
        int x1 = ((Number) immutableHouse.get("x1")).intValue();
        int y1 = ((Number) immutableHouse.get("y1")).intValue();
        int z1 = ((Number) immutableHouse.get("z1")).intValue();
        int x2 = ((Number) immutableHouse.get("x2")).intValue();
        int y2 = ((Number) immutableHouse.get("y2")).intValue();
        int z2 = ((Number) immutableHouse.get("z2")).intValue();
        org.bukkit.World world = org.bukkit.Bukkit.getWorld(worldName);
        if (world == null) {
            future.complete(voxelEnvelope(immutableHouse, "FAILED", true, "world_unavailable", List.of()));
            return new CachedVoxelModel(signature, queuedAt, future);
        }
        String dimensionId = dimensionId(world);
        CompletableFuture<VoxelScan> snapshotFuture = snapshotVoxelsByRegion(world, x1, y1, z1, x2, y2, z2,
                MAX_VOXEL_BLOCKS, voxelSnapshotTasks);
        snapshotFuture.whenComplete((scan, failure) -> {
            if (failure != null) {
                future.completeExceptionally(failure);
                return;
            }
            if (scan.truncated) {
                future.complete(voxelEnvelope(immutableHouse, "FAILED", true, "block_limit", List.of()));
                return;
            }
            eco.scheduler().runAsync(() -> {
                try {
                    List<Object[]> snapshot = List.copyOf(scan.blocks);
                    List<Object[]> visible = cullHiddenVoxels(snapshot);
                    List<Map<String, Object>> rows = voxelRows(visible);
                    Map<String, Object> ready = voxelEnvelope(immutableHouse, "READY", false, null, rows);
                    ready.put("sourceBlocks", snapshot.size());
                    ready.put("generatedAt", System.currentTimeMillis());
                    ready.put("cacheTtlSeconds", VOXEL_CACHE_TTL_MILLIS / 1000);
                    publishPropertySnapshot(immutableHouse, ready, dimensionId);
                    future.complete(ready);
                } catch (RuntimeException error) {
                    future.completeExceptionally(error);
                }
            });
        });
        return new CachedVoxelModel(signature, queuedAt, future);
    }

    private void publishPropertySnapshot(Map<String, Object> house, Map<String, Object> ready, String dimensionId) {
        if (!eco.getConfig().getBoolean("realestate.federated-property-snapshots-enabled", false)
                || !eco.federatedAssetSettings().enabled()) return;
        try {
            String worldId = String.valueOf(house.get("world"));
            AssetSource source = new AssetSource(eco.ecoDatabase().serverId(), worldId, dimensionId);
            if (!eco.federatedAssetSettings().policy()
                    .decide(FederatedCapability.PROPERTY_TRADE, source).allowed()) return;
            String sourceKey = source.stableKey();
            Map<String, FederatedPropertySnapshotFactory.HouseSnapshot> bucket = federatedPropertySnapshots
                    .computeIfAbsent(sourceKey, ignored -> new ConcurrentHashMap<>());
            String houseId = String.valueOf(house.get("id"));
            bucket.put(houseId, new FederatedPropertySnapshotFactory.HouseSnapshot(house, ready));
            List<FederatedPropertySnapshotFactory.HouseSnapshot> snapshot = bucket.entrySet().stream()
                    .sorted(Map.Entry.comparingByKey()).map(Map.Entry::getValue).toList();
            long now = System.currentTimeMillis();
            long revision = federatedPropertyRevisions.computeIfAbsent(sourceKey, ignored -> new AtomicLong())
                    .updateAndGet(previous -> Math.max(previous + 1, now));
            var prepared = FederatedPropertySnapshotFactory.create(eco.ecoDatabase().serverId(), dimensionId,
                    snapshot, revision, now);
            eco.publishFederatedSnapshot(prepared).whenComplete((result, failure) -> {
                if (failure != null) {
                    eco.getLogger().warning("[房地产] 跨服房产快照发布失败: " + failure.getMessage());
                }
            });
        } catch (RuntimeException failure) {
            eco.getLogger().warning("[房地产] 跨服房产快照准备失败: " + failure.getMessage());
        }
    }

    private static String dimensionId(org.bukkit.World world) {
        return switch (world.getEnvironment()) {
            case NORMAL -> "minecraft:overworld";
            case NETHER -> "minecraft:the_nether";
            case THE_END -> "minecraft:the_end";
            default -> world.getKey().toString();
        };
    }

    private static List<Map<String, Object>> voxelRows(List<Object[]> source) {
        List<Map<String, Object>> rows = new ArrayList<>(source.size());
        for (Object[] block : source) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("x", block[0]); row.put("y", block[1]); row.put("z", block[2]);
            row.put("color", block[3]); row.put("mat", block[4]);
            if (block[5] != null) row.put("data", block[5]);
            rows.add(row);
        }
        return rows;
    }

    private Map<String, Object> voxelEnvelope(Map<String, Object> house, String status,
                                               boolean truncated, String reason,
                                               List<Map<String, Object>> blocks) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("houseId", house.get("id"));
        result.put("name", house.get("name"));
        result.put("plotId", house.get("plotId"));
        result.put("zoneId", house.get("zoneId"));
        result.put("world", house.get("world"));
        result.put("x1", house.get("x1")); result.put("y1", house.get("y1")); result.put("z1", house.get("z1"));
        result.put("x2", house.get("x2")); result.put("y2", house.get("y2")); result.put("z2", house.get("z2"));
        result.put("status", status);
        result.put("truncated", truncated);
        if (reason != null) result.put("reason", reason);
        result.put("blocks", blocks);
        return result;
    }

    private List<Object[]> cullHiddenVoxels(List<Object[]> blocks) {
        Map<Long, String> occupancy = new HashMap<>(blocks.size() * 2);
        for (Object[] block : blocks) {
            occupancy.put(voxelKey((Integer) block[0], (Integer) block[1], (Integer) block[2]), (String) block[4]);
        }
        List<Object[]> visible = new ArrayList<>(blocks.size());
        int[][] directions = {{1,0,0},{-1,0,0},{0,1,0},{0,-1,0},{0,0,1},{0,0,-1}};
        for (Object[] block : blocks) {
            int x = (Integer) block[0], y = (Integer) block[1], z = (Integer) block[2];
            String material = (String) block[4];
            if (isVoxelTransparent(material)) { visible.add(block); continue; }
            boolean hidden = true;
            for (int[] d : directions) {
                String neighbor = occupancy.get(voxelKey(x + d[0], y + d[1], z + d[2]));
                if (neighbor == null || isVoxelTransparent(neighbor)) { hidden = false; break; }
            }
            if (!hidden) visible.add(block);
        }
        return visible;
    }

    private static long voxelKey(int x, int y, int z) {
        return (((long) x & 0x1fffffL) << 42) | (((long) y & 0x1fffffL) << 21) | ((long) z & 0x1fffffL);
    }

    private static boolean isVoxelTransparent(String material) {
        return material == null || material.contains("GLASS") || material.endsWith("_PANE")
                || material.endsWith("_LEAVES") || material.equals("WATER") || material.equals("LAVA")
                || material.endsWith("_FENCE") || material.endsWith("_WALL") || material.endsWith("_DOOR")
                || material.endsWith("_TRAPDOOR") || material.endsWith("_STAIRS") || material.endsWith("_SLAB");
    }

    /** 形状依赖朝向/状态数据（楼梯/台阶/活板门/门/栅栏门）才能正确渲染的方块，需要额外导出 BlockData 字符串。 */
    /** Exports a map-selected region around the surface with strict scan and block limits. */
    public Map<String, Object> exportMapRegionVoxels(String world, int x1, int z1, int x2, int z2) {
        int minX = Math.min(x1, x2), maxX = Math.max(x1, x2);
        int minZ = Math.min(z1, z2), maxZ = Math.max(z1, z2);
        int width = maxX - minX + 1, depth = maxZ - minZ + 1;
        if (width > MAX_REGION_FOOTPRINT || depth > MAX_REGION_FOOTPRINT) return rejectedRegion(world, minX, minZ, maxX, maxZ, "footprint_limit");
        try {
            org.bukkit.World bukkitWorld = org.bukkit.Bukkit.getWorld(world);
            if (bukkitWorld == null) return rejectedRegion(world, minX, minZ, maxX, maxZ, "world_unavailable");
            int[] heights = snapshotHeightsByRegion(bukkitWorld, minX, minZ, maxX, maxZ)
                    .get(5, java.util.concurrent.TimeUnit.SECONDS);
            int minY = Math.max(bukkitWorld.getMinHeight(), heights[0] - REGION_SURFACE_BELOW);
            int maxY = Math.min(bukkitWorld.getMaxHeight() - 1, heights[1] + REGION_SURFACE_ABOVE);
            if (maxY - minY + 1 > MAX_REGION_HEIGHT) return rejectedRegion(world, minX, minZ, maxX, maxZ, "height_limit");
            return exportRegionVoxels(world, minX, minY, minZ, maxX, maxY, maxZ);
        } catch (Exception e) {
            eco.getLogger().warning("[RealEstate] Region voxel export failed: " + e.getMessage());
            return rejectedRegion(world, minX, minZ, maxX, maxZ, "read_failed");
        }
    }

    /** Generic bounded AABB exporter for map-region consumers. */
    public Map<String, Object> exportRegionVoxels(String world, int x1, int y1, int z1, int x2, int y2, int z2) {
        int minX = Math.min(x1, x2), maxX = Math.max(x1, x2);
        int minY = Math.min(y1, y2), maxY = Math.max(y1, y2);
        int minZ = Math.min(z1, z2), maxZ = Math.max(z1, z2);
        long volume = (long) (maxX - minX + 1) * (maxY - minY + 1) * (maxZ - minZ + 1);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("world", world); result.put("x1", minX); result.put("y1", minY); result.put("z1", minZ);
        result.put("x2", maxX); result.put("y2", maxY); result.put("z2", maxZ);
        if (volume > MAX_REGION_SCAN_VOLUME) {
            result.put("truncated", true); result.put("reason", "volume_limit"); result.put("blocks", List.of());
            return result;
        }
        try {
            org.bukkit.World bukkitWorld = org.bukkit.Bukkit.getWorld(world);
            if (bukkitWorld == null) throw new IllegalStateException("world_unavailable");
            VoxelScan scan = snapshotVoxelsByRegion(bukkitWorld, minX, minY, minZ, maxX, maxY, maxZ,
                    MAX_VOXEL_BLOCKS, null).get(8, java.util.concurrent.TimeUnit.SECONDS);
            if (scan.truncated) {
                result.put("truncated", true); result.put("reason", "block_limit"); result.put("blocks", List.of());
                return result;
            }
            List<Map<String, Object>> blocks = voxelRows(scan.blocks);
            result.put("truncated", false); result.put("blocks", blocks);
        } catch (Exception e) {
            eco.getLogger().warning("[RealEstate] Region voxel export failed: " + e.getMessage());
            result.put("truncated", true); result.put("reason", "read_failed"); result.put("blocks", List.of());
        }
        return result;
    }

    private Map<String, Object> rejectedRegion(String world, int x1, int z1, int x2, int z2, String reason) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("world", world); result.put("x1", x1); result.put("z1", z1); result.put("x2", x2); result.put("z2", z2);
        result.put("truncated", true); result.put("reason", reason); result.put("blocks", List.of());
        return result;
    }

    private static final class VoxelScan {
        private final List<Object[]> blocks;
        private final boolean truncated;
        private VoxelScan(List<Object[]> blocks, boolean truncated) { this.blocks = blocks; this.truncated = truncated; }
    }

    private CompletableFuture<int[]> snapshotHeightsByRegion(org.bukkit.World world,
                                                               int x1, int z1, int x2, int z2) {
        CompletableFuture<int[]> future = new CompletableFuture<>();
        int chunkX1 = Math.floorDiv(x1, 16), chunkX2 = Math.floorDiv(x2, 16);
        int chunkZ1 = Math.floorDiv(z1, 16), chunkZ2 = Math.floorDiv(z2, 16);
        int pieces = (chunkX2 - chunkX1 + 1) * (chunkZ2 - chunkZ1 + 1);
        java.util.concurrent.atomic.AtomicInteger remaining = new java.util.concurrent.atomic.AtomicInteger(pieces);
        java.util.concurrent.atomic.AtomicInteger lowest = new java.util.concurrent.atomic.AtomicInteger(Integer.MAX_VALUE);
        java.util.concurrent.atomic.AtomicInteger highest = new java.util.concurrent.atomic.AtomicInteger(Integer.MIN_VALUE);
        for (int cx = chunkX1; cx <= chunkX2; cx++) for (int cz = chunkZ1; cz <= chunkZ2; cz++) {
            int minX = Math.max(x1, cx << 4), maxX = Math.min(x2, (cx << 4) + 15);
            int minZ = Math.max(z1, cz << 4), maxZ = Math.min(z2, (cz << 4) + 15);
            org.bukkit.Location owner = new org.bukkit.Location(world, minX, 0, minZ);
            eco.scheduler().runRegion(owner, () -> {
                try {
                    for (int x = minX; x <= maxX; x++) for (int z = minZ; z <= maxZ; z++) {
                        int y = world.getHighestBlockYAt(x, z);
                        lowest.accumulateAndGet(y, Math::min);
                        highest.accumulateAndGet(y, Math::max);
                    }
                    if (remaining.decrementAndGet() == 0) future.complete(new int[]{lowest.get(), highest.get()});
                } catch (Throwable failure) {
                    future.completeExceptionally(failure);
                }
            });
        }
        return future;
    }

    private CompletableFuture<VoxelScan> snapshotVoxelsByRegion(org.bukkit.World world,
                                                                 int x1, int y1, int z1,
                                                                 int x2, int y2, int z2, int limit,
                                                                 Set<org.kseco.scheduler.EcoScheduler.TaskHandle> tracked) {
        CompletableFuture<VoxelScan> future = new CompletableFuture<>();
        java.util.concurrent.ConcurrentLinkedQueue<Object[]> blocks = new java.util.concurrent.ConcurrentLinkedQueue<>();
        java.util.concurrent.atomic.AtomicInteger blockCount = new java.util.concurrent.atomic.AtomicInteger();
        java.util.concurrent.atomic.AtomicBoolean truncated = new java.util.concurrent.atomic.AtomicBoolean();
        int chunkX1 = Math.floorDiv(x1, 16), chunkX2 = Math.floorDiv(x2, 16);
        int chunkZ1 = Math.floorDiv(z1, 16), chunkZ2 = Math.floorDiv(z2, 16);
        int pieces = (chunkX2 - chunkX1 + 1) * (chunkZ2 - chunkZ1 + 1);
        java.util.concurrent.atomic.AtomicInteger remaining = new java.util.concurrent.atomic.AtomicInteger(pieces);
        List<org.kseco.scheduler.EcoScheduler.TaskHandle> localTasks = new java.util.concurrent.CopyOnWriteArrayList<>();
        Runnable finish = () -> {
            if (remaining.decrementAndGet() != 0) return;
            if (tracked != null) tracked.removeAll(localTasks);
            future.complete(new VoxelScan(new ArrayList<>(blocks), truncated.get()));
        };
        for (int cx = chunkX1; cx <= chunkX2; cx++) for (int cz = chunkZ1; cz <= chunkZ2; cz++) {
            int minX = Math.max(x1, cx << 4), maxX = Math.min(x2, (cx << 4) + 15);
            int minZ = Math.max(z1, cz << 4), maxZ = Math.min(z2, (cz << 4) + 15);
            org.bukkit.Location owner = new org.bukkit.Location(world, minX, y1, minZ);
            org.kseco.scheduler.EcoScheduler.TaskHandle handle = eco.scheduler().runRegion(owner, () -> {
                try {
                    if (!future.isCancelled() && eco.isEnabled() && !truncated.get()) {
                        for (int x = minX; x <= maxX && !truncated.get(); x++) {
                            for (int y = y1; y <= y2 && !truncated.get(); y++) {
                                for (int z = minZ; z <= maxZ; z++) {
                                    org.bukkit.block.Block block = world.getBlockAt(x, y, z);
                                    org.bukkit.Material material = block.getType();
                                    if (material.isAir()) continue;
                                    if (blockCount.incrementAndGet() > limit) {
                                        truncated.set(true);
                                        break;
                                    }
                                    String materialName = material.name();
                                    String data = needsBlockData(materialName) ? block.getBlockData().getAsString() : null;
                                    blocks.add(new Object[]{x - x1, y - y1, z - z1,
                                            VoxelColors.colorOf(material), materialName, data});
                                }
                            }
                        }
                    }
                    finish.run();
                } catch (Throwable failure) {
                    if (tracked != null) tracked.removeAll(localTasks);
                    future.completeExceptionally(failure);
                }
            });
            localTasks.add(handle);
            if (tracked != null) tracked.add(handle);
        }
        future.whenComplete((ignored, failure) -> {
            if (future.isCancelled()) for (var task : localTasks) task.cancel();
            if (tracked != null && future.isDone()) tracked.removeAll(localTasks);
        });
        return future;
    }

    private static boolean needsBlockData(String matName) {
        return matName.endsWith("_STAIRS") || matName.endsWith("_SLAB")
                || matName.endsWith("_TRAPDOOR") || matName.endsWith("_DOOR") || matName.endsWith("_FENCE_GATE")
                || matName.endsWith("_PANE") || matName.endsWith("_FENCE") || matName.endsWith("_WALL");
    }

    public List<Map<String, Object>> listHousesForPlot(String plotId) {
        try (var conn = eco.ksCore().dataStore().getConnection()) {
            if (conn == null) return new ArrayList<>();
            return listHousesForPlotConn(conn, plotId);
        } catch (SQLException e) {
            eco.getLogger().warning("[房地产] 列地块房屋失败: " + e.getMessage());
        }
        return new ArrayList<>();
    }

    /** 同 {@link #listHousesForPlot}，但复用调用方传入的连接（用于事务内一致性读取，不单独开/关连接）。 */
    private List<Map<String, Object>> listHousesForPlotConn(Connection conn, String plotId) throws SQLException {
        List<Map<String, Object>> out = new ArrayList<>();
        try (var ps = conn.prepareStatement("SELECT * FROM ks_re_houses WHERE plot_id=? ORDER BY registered_at ASC")) {
            ps.setString(1, plotId);
            try (var rs = ps.executeQuery()) { while (rs.next()) out.add(houseRowToMap(rs)); }
        }
        return out;
    }

    /** 列出某所有者（PLAYER uuid 或 ENTERPRISE id）名下的房屋。 */
    public List<Map<String, Object>> listHousesOwnedBy(String ownerType, String ownerId) {
        List<Map<String, Object>> out = new ArrayList<>();
        try (var conn = eco.ksCore().dataStore().getConnection()) {
            if (conn == null) return out;
            try (var ps = conn.prepareStatement(
                    "SELECT * FROM ks_re_houses WHERE owner_type=? AND owner_id=? ORDER BY registered_at DESC LIMIT 200")) {
                ps.setString(1, ownerType);
                ps.setString(2, ownerId);
                try (var rs = ps.executeQuery()) { while (rs.next()) out.add(houseRowToMap(rs)); }
            }
        } catch (SQLException e) {
            eco.getLogger().warning("[房地产] 列所有者房屋失败: " + e.getMessage());
        }
        return out;
    }

    /** 列出玩家能管理的房屋：自己直接持有的 + 自己作为成员的企业持有的。 */
    public List<Map<String, Object>> listAccessibleHouses(UUID uuid) {
        List<Map<String, Object>> out = new ArrayList<>(listHousesOwnedBy(OWNER_PLAYER, uuid.toString()));
        try (var conn = eco.ksCore().dataStore().getConnection()) {
            if (conn != null) {
                try (var ps = conn.prepareStatement("SELECT * FROM ks_re_houses WHERE owner_type=? ORDER BY registered_at DESC LIMIT 500")) {
                    ps.setString(1, OWNER_ENTERPRISE);
                    try (var rs = ps.executeQuery()) {
                        while (rs.next()) {
                            Map<String, Object> h = houseRowToMap(rs);
                            if (isEnterpriseMember((String) h.get("ownerId"), uuid)) out.add(h);
                        }
                    }
                }
            }
        } catch (SQLException e) {
            eco.getLogger().warning("[房地产] 列可管理房屋失败: " + e.getMessage());
        }
        return out;
    }

    /**
     * 转移房屋所有权（市场卖出商品房时调用）：只换 owner，地块所有权不动；旧信任名单清空（换房主作废）。
     * @return null 成功，否则中文错误信息
     */
    public String transferHouseOwnership(String houseId, String newOwnerType, String newOwnerId) {
        Map<String, Object> house = getHouse(houseId);
        if (house == null) return "房屋不存在";
        return transferHouseOwnership(houseId, String.valueOf(house.get("ownerType")),
                String.valueOf(house.get("ownerId")), newOwnerType, newOwnerId);
    }

    public String transferHouseOwnership(String houseId, String expectedOwnerType, String expectedOwnerId,
                                         String newOwnerType, String newOwnerId) {
        try (var conn = eco.ksCore().dataStore().getConnection()) {
            if (conn == null) return "数据库不可用";
            conn.setAutoCommit(false);
            if (isMortgagedCollateral(conn, "HOUSE", houseId)) {
                conn.rollback();
                return "该房屋已作为企业融资抵押物，结清贷款前不能出售或转让";
            }
            try (var ps = conn.prepareStatement(
                    "UPDATE ks_re_houses SET owner_type=?, owner_id=? " +
                            "WHERE id=? AND owner_type=? AND owner_id=?")) {
                ps.setString(1, newOwnerType);
                ps.setString(2, newOwnerId);
                ps.setString(3, houseId);
                ps.setString(4, expectedOwnerType);
                ps.setString(5, expectedOwnerId);
                if (ps.executeUpdate() != 1) {
                    conn.rollback();
                    return "房屋所有权已变化，请刷新后重试";
                }
            }
            try (var ps = conn.prepareStatement("DELETE FROM ks_re_house_trust WHERE house_id=?")) {
                ps.setString(1, houseId);
                ps.executeUpdate();
            }
            conn.commit();
        } catch (SQLException e) {
            eco.getLogger().warning("[房地产] 转移房屋所有权失败: " + e.getMessage());
            return "转移失败";
        }
        removeHouseTrustCache(houseId);
        refreshHouseCache();
        return null;
    }

    private boolean isMortgagedCollateral(Connection conn, String assetType, String assetRef) throws SQLException {
        if (!tableExists(conn, "ks_bank_collateral")) return false;
        try (var ps = conn.prepareStatement(
                "SELECT 1 FROM ks_bank_collateral WHERE asset_type=? AND asset_ref=? " +
                        "AND status IN ('LOCKED','SEIZED')")) {
            ps.setString(1, assetType);
            ps.setString(2, assetRef);
            try (var rs = ps.executeQuery()) {
                return rs.next();
            }
        }
    }

    /** The finance extra owns collateral records; real-estate only enforces its lock. */
    private boolean isMortgagedCollateral(String assetType, String assetRef) {
        try (var conn = eco.ksCore().dataStore().getConnection()) {
            if (conn == null) return false;
            if (!tableExists(conn, "ks_bank_collateral")) return false;
            try (var ps = conn.prepareStatement(
                    "SELECT 1 FROM ks_bank_collateral WHERE asset_type=? AND asset_ref=? AND status IN ('LOCKED','SEIZED')")) {
                ps.setString(1, assetType);
                ps.setString(2, assetRef);
                try (var rs = ps.executeQuery()) { return rs.next(); }
            }
        } catch (SQLException ignored) {
            // Bank extra may not be installed yet; in that case no collateral can exist.
            return false;
        }
    }

    /** 房屋所有者授予信任权限（PLAYER 房屋专用）。@return null 成功，否则中文错误信息。 */
    public synchronized String grantHouseTrust(String houseId, UUID owner, UUID targetUuid, String targetName,
                                               boolean build, boolean container, boolean interact) {
        if (houseId == null || houseId.isBlank() || owner == null || targetUuid == null) return "参数无效";
        if (targetUuid.equals(owner)) return "不能信任自己";
        long now = System.currentTimeMillis() / 1000;
        String safeTargetName = normalizeTrustName(targetName);
        try (var conn = eco.ksCore().dataStore().getConnection()) {
            if (conn == null) return "数据库不可用";
            PortableSqlMutation.upsert(conn,
                    "UPDATE ks_re_house_trust SET trusted_name=?,can_build=?,can_container=?,can_interact=? " +
                            "WHERE house_id=? AND trusted_uuid=? AND EXISTS (SELECT 1 FROM ks_re_houses WHERE id=? AND owner_type=? AND owner_id=?)",
                    ps -> bindTrustUpdate(ps, safeTargetName, build, container, interact, houseId, targetUuid, owner),
                    "INSERT INTO ks_re_house_trust (house_id,trusted_uuid,trusted_name,can_build,can_container,can_interact,granted_at) " +
                            "SELECT ?,?,?,?,?,?,? WHERE EXISTS (SELECT 1 FROM ks_re_houses WHERE id=? AND owner_type=? AND owner_id=?)",
                    ps -> bindTrustInsert(ps, houseId, targetUuid, safeTargetName, build, container, interact, now, owner));
        } catch (SQLException e) {
            eco.getLogger().warning("[房地产] 授予房屋信任失败: " + e.getMessage());
            return "操作失败";
        }
        putHouseTrustCache(houseId, targetUuid, new TrustPermissions(build, container, interact));
        eco.publishCrossServerInvalidation("real-estate", "all");
        return null;
    }

    private Map<String, Object> houseRowToMap(ResultSet rs) throws SQLException {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", rs.getString("id"));
        m.put("plotId", rs.getString("plot_id"));
        m.put("zoneId", rs.getString("zone_id"));
        m.put("world", rs.getString("world"));
        m.put("x1", rs.getInt("x1")); m.put("y1", rs.getInt("y1")); m.put("z1", rs.getInt("z1"));
        m.put("x2", rs.getInt("x2")); m.put("y2", rs.getInt("y2")); m.put("z2", rs.getInt("z2"));
        m.put("ownerType", rs.getString("owner_type"));
        m.put("ownerId", rs.getString("owner_id"));
        m.put("dungeonTemplateId", rs.getString("dungeon_template_id"));
        m.put("taxRate", rs.getDouble("tax_rate"));
        m.put("name", rs.getString("name"));
        m.put("showcasePrice", rs.getDouble("showcase_price"));
        m.put("showcaseMarker", rs.getString("showcase_marker"));
        m.put("registeredAt", rs.getLong("registered_at"));
        return m;
    }

    // ================================================================
    // 公户扣款
    // ================================================================

    private boolean chargeCorporate(String enterpriseId, double amount) {
        if (amount <= 0) return true;
        try (var conn = eco.ksCore().dataStore().getConnection()) {
            if (conn == null) return false;
            conn.setAutoCommit(false);
            try (var ps = conn.prepareStatement(
                    "SELECT balance FROM ks_ent_corporate_accounts WHERE enterprise_id=?")) {
                ps.setString(1, enterpriseId);
                try (var rs = ps.executeQuery()) {
                    if (!rs.next() || rs.getDouble(1) < amount) {
                        conn.rollback();
                        return false;
                    }
                }
            }
            try (var ps = conn.prepareStatement(
                    "UPDATE ks_ent_corporate_accounts SET balance=balance-?, updated_at=? WHERE enterprise_id=?")) {
                ps.setDouble(1, amount);
                ps.setLong(2, System.currentTimeMillis() / 1000);
                ps.setString(3, enterpriseId);
                ps.executeUpdate();
            }
            conn.commit();
            return true;
        } catch (SQLException e) {
            eco.getLogger().warning("企业公户扣款失败: " + e.getMessage());
            return false;
        }
    }

    private void refundAfterFail(String ownerType, String ownerId, double amount) {
        if (OWNER_PLAYER.equalsIgnoreCase(ownerType)) {
            var p = org.bukkit.Bukkit.getOfflinePlayer(java.util.UUID.fromString(ownerId));
            eco.vaultHook().deposit(p, amount);
        } else if (OWNER_ENTERPRISE.equalsIgnoreCase(ownerType)) {
            try (var conn = eco.ksCore().dataStore().getConnection()) {
                if (conn == null) return;
                try (var ps = conn.prepareStatement(
                        "UPDATE ks_ent_corporate_accounts SET balance=balance+?, updated_at=? WHERE enterprise_id=?")) {
                    ps.setDouble(1, amount);
                    ps.setLong(2, System.currentTimeMillis() / 1000);
                    ps.setString(3, ownerId);
                    ps.executeUpdate();
                }
            } catch (SQLException ignored) {}
        }
    }

    // ================================================================
    // 副本内房产（原 ks-Eco-RealEstateDungeon.PropertyManager 迁入）
    // 与主世界房产共用 ks_re_plots，副本房产 instance_id != NULL。
    // 副本世界名只读解析自 ks_dungeon_grids（共享库），房地产插件不依赖副本插件运行时。
    // ================================================================

    /** 在副本内购买房产。instanceId 必填，扣玩家个人 Vault。 */
    public String purchaseInInstance(String instanceId, String ownerUuid, String ownerName,
                                     int x1, int z1, int x2, int z2, String propertyFunction) {
        if (instanceId == null || ownerUuid == null) return null;
        if (propertyFunction == null || !VALID_FUNCTIONS.contains(propertyFunction)) propertyFunction = FUNCTION_RESIDENTIAL;
        int minX = Math.min(x1, x2), maxX = Math.max(x1, x2);
        int minZ = Math.min(z1, z2), maxZ = Math.max(z1, z2);
        long area = ((long) maxX - minX + 1L) * ((long) maxZ - minZ + 1L);
        int maxRadius = Math.max(16, eco.getConfig().getInt("realestate.instance-plot-max-radius", 256));
        int maxArea = Math.max(256, eco.getConfig().getInt("realestate.instance-plot-max-area", 65536));
        double unitPrice = eco.getConfig().getDouble("realestate.instance-plot-price-per-block", 100.0);
        double minimumPrice = eco.getConfig().getDouble("realestate.instance-plot-min-price", 1000.0);
        if (area <= 0 || area > maxArea || !Double.isFinite(unitPrice) || unitPrice <= 0
                || !Double.isFinite(minimumPrice) || minimumPrice < 0) return null;
        UUID uuid;
        try { uuid = UUID.fromString(ownerUuid); } catch (Exception e) { return null; }
        String world;
        int centerX;
        int centerZ;
        try (var conn = eco.ksCore().dataStore().getConnection()) {
            if (conn == null) return null;
            try (var instance = conn.prepareStatement(
                    "SELECT g.world,g.grid_x,g.grid_z FROM ks_dungeon_instances i JOIN ks_dungeon_grids g ON g.id=i.grid_id " +
                            "WHERE i.id=? AND i.status IN ('WAITING','ACTIVE')")) {
                instance.setString(1, instanceId);
                try (var rs = instance.executeQuery()) {
                    if (!rs.next()) return null;
                    world = rs.getString(1); centerX = rs.getInt(2); centerZ = rs.getInt(3);
                }
            }
            if (minX < centerX - maxRadius || maxX > centerX + maxRadius
                    || minZ < centerZ - maxRadius || maxZ > centerZ + maxRadius) return null;
            try (var overlap = conn.prepareStatement(
                    "SELECT 1 FROM ks_re_plots WHERE instance_id=? AND NOT (x2<? OR x1>? OR z2<? OR z1>?) LIMIT 1")) {
                overlap.setString(1, instanceId);
                overlap.setInt(2, minX); overlap.setInt(3, maxX);
                overlap.setInt(4, minZ); overlap.setInt(5, maxZ);
                if (overlap.executeQuery().next()) return null;
            }
        } catch (SQLException e) {
            return null;
        }
        double price = Math.max(minimumPrice, area * unitPrice);
        if (!Double.isFinite(price) || price <= 0) return null;
        if (!eco.vaultHook().isAvailable()) return null;
        var p = org.bukkit.Bukkit.getOfflinePlayer(uuid);
        if (!eco.vaultHook().has(p, price)) return null;
        if (!eco.vaultHook().withdraw(p, price)) return null;
        String plotId = "DP" + UUID.randomUUID().toString().substring(0, 8);
        long now = System.currentTimeMillis() / 1000;
        try (var conn = eco.ksCore().dataStore().getConnection()) {
            if (conn == null) { eco.vaultHook().deposit(p, price); return null; }
            try (var ps = conn.prepareStatement(
                    "INSERT INTO ks_re_plots (id, zone_id, world, x1, z1, x2, z2, owner_type, owner_id, price, tax_rate, status, purchased_at, instance_id, property_function) " +
                    "VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)")) {
                ps.setString(1, plotId);
                ps.setString(2, "DUNGEON_ZONE_" + instanceId);
                ps.setString(3, world);
                ps.setInt(4, minX); ps.setInt(5, minZ); ps.setInt(6, maxX); ps.setInt(7, maxZ);
                ps.setString(8, OWNER_PLAYER);
                ps.setString(9, ownerUuid);
                ps.setDouble(10, price);
                ps.setDouble(11, DEFAULT_DEED_TAX_RATE);
                ps.setString(12, "PURCHASED");
                ps.setLong(13, now);
                ps.setString(14, instanceId);
                ps.setString(15, propertyFunction);
                ps.executeUpdate();
                return plotId;
            }
        } catch (SQLException e) {
            eco.getLogger().warning("[房地产] 购买副本房产失败: " + e.getMessage());
            eco.vaultHook().deposit(p, price);
            return null;
        }
    }

    /** 缴纳副本房产开发配套费。返回 null 成功，否则错误信息。 */
    public String payInstanceDevelopmentFee(String plotId, String ownerUuid) {
        Map<String, Object> plot = getPlot(plotId);
        if (plot == null) return "房产不存在";
        if (ownerUuid == null || !ownerUuid.equals(plot.get("ownerId"))) return "非房产所有者";
        double fee = DEFAULT_DEVELOPMENT_FEE;
        if (!eco.vaultHook().isAvailable()) return "Vault 不可用";
        var p = org.bukkit.Bukkit.getOfflinePlayer(UUID.fromString(ownerUuid));
        if (!eco.vaultHook().has(p, fee)) return "余额不足";
        if (!eco.vaultHook().withdraw(p, fee)) return "扣款失败";
        try (var conn = eco.ksCore().dataStore().getConnection()) {
            if (conn != null) try (var ps = conn.prepareStatement("UPDATE ks_re_plots SET status='DEVELOPED' WHERE id=?")) {
                ps.setString(1, plotId);
                ps.executeUpdate();
            }
        } catch (SQLException ignored) {}
        return null;
    }

    /** 副本销毁时清理该副本所有房产（按 instance_id）。 */
    public int cleanupByInstance(String instanceId) {
        if (instanceId == null) return 0;
        try (var conn = eco.ksCore().dataStore().getConnection()) {
            if (conn == null) return 0;
            try (var ps = conn.prepareStatement("DELETE FROM ks_re_plots WHERE instance_id=?")) {
                ps.setString(1, instanceId);
                return ps.executeUpdate();
            }
        } catch (SQLException e) {
            eco.getLogger().warning("[房地产] 清理副本房产失败: " + e.getMessage());
            return 0;
        }
    }

    /** 列玩家的副本内房产。instanceId 为空/__any_dungeon__ 则列出所有副本内房产。 */
    public List<Map<String, Object>> listInstancePlots(String ownerUuid, String instanceId) {
        List<Map<String, Object>> out = new ArrayList<>();
        if (ownerUuid == null) return out;
        try (var conn = eco.ksCore().dataStore().getConnection()) {
            if (conn == null) return out;
            StringBuilder sql = new StringBuilder("SELECT * FROM ks_re_plots WHERE owner_type='PLAYER' AND owner_id=?");
            List<Object> params = new ArrayList<>();
            params.add(ownerUuid);
            if (instanceId != null && !instanceId.isEmpty() && !"__any_dungeon__".equals(instanceId)) {
                sql.append(" AND instance_id=?");
                params.add(instanceId);
            } else {
                sql.append(" AND instance_id IS NOT NULL");
            }
            sql.append(" ORDER BY purchased_at DESC LIMIT 100");
            try (var ps = conn.prepareStatement(sql.toString())) {
                for (int i = 0; i < params.size(); i++) ps.setObject(i + 1, params.get(i));
                try (var rs = ps.executeQuery()) {
                    while (rs.next()) out.add(plotRowToMapFull(rs));
                }
            }
        } catch (SQLException e) {
            eco.getLogger().warning("[房地产] 列副本房产失败: " + e.getMessage());
        }
        return out;
    }

    public Map<String, Object> getPlot(String plotId) {
        if (plotId == null) return null;
        try (var conn = eco.ksCore().dataStore().getConnection()) {
            if (conn == null) return null;
            try (var ps = conn.prepareStatement("SELECT * FROM ks_re_plots WHERE id=?")) {
                ps.setString(1, plotId);
                try (var rs = ps.executeQuery()) {
                    if (rs.next()) return plotRowToMapFull(rs);
                }
            }
        } catch (SQLException ignored) {}
        return null;
    }

    private String resolveInstanceWorld(String instanceId) {
        try (var conn = eco.ksCore().dataStore().getConnection()) {
            if (conn == null) return "ks-dungeon-world";
            try (var ps = conn.prepareStatement(
                    "SELECT g.world FROM ks_dungeon_grids g JOIN ks_dungeon_instances i ON i.grid_id=g.id WHERE i.id=?")) {
                ps.setString(1, instanceId);
                try (var rs = ps.executeQuery()) {
                    if (rs.next()) return rs.getString(1);
                }
            }
        } catch (SQLException ignored) { /* 副本插件未装时回退默认 */ }
        return "ks-dungeon-world";
    }

    private Map<String, Object> plotRowToMapFull(ResultSet rs) throws SQLException {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", rs.getString("id"));
        m.put("zoneId", rs.getString("zone_id"));
        m.put("world", rs.getString("world"));
        m.put("x1", rs.getInt("x1")); m.put("z1", rs.getInt("z1"));
        m.put("x2", rs.getInt("x2")); m.put("z2", rs.getInt("z2"));
        m.put("ownerType", rs.getString("owner_type"));
        m.put("ownerId", rs.getString("owner_id"));
        m.put("price", rs.getDouble("price"));
        m.put("taxRate", rs.getDouble("tax_rate"));
        m.put("status", rs.getString("status"));
        m.put("purchasedAt", rs.getLong("purchased_at"));
        try { m.put("instanceId", rs.getString("instance_id")); } catch (SQLException ignored) {}
        try { m.put("propertyFunction", rs.getString("property_function")); } catch (SQLException ignored) {}
        try { m.put("dungeonTemplateId", rs.getString("dungeon_template_id")); } catch (SQLException ignored) {}
        return m;
    }

}
