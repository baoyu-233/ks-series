package org.kseco.extra.politic;

import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.kseco.KsEco;
import org.kseco.database.PortableSqlMutation;

import java.sql.*;
import java.util.*;

/**
 * 政治职务管理器。
 *
 * 管理四个政治身份（元老/执政官/骑士/保民官）的增删改查，
 * 以及职务间的互斥约束检查与级联补位。
 */
public final class PoliticManager {

    private final KsEco eco;

    // 内存缓存 — 职务在内存中维护，DB 做持久化。
    // 用 CopyOnWriteArrayList：选举定时器（异步线程）会增删 offices，
    // 而 Web/命令线程同时迭代它 → 普通 ArrayList 会抛 ConcurrentModificationException。
    private volatile List<Office> offices = new java.util.concurrent.CopyOnWriteArrayList<>();
    private volatile Map<String, String> config = new java.util.concurrent.ConcurrentHashMap<>();

    // 默认配置
    private static final int DEF_SENATE_SEATS = 8;
    private static final int DEF_EQUESTRIAN_SEATS = 3;
    private static final int DEF_TRIBUNE_SEATS = 2;
    private static final int DEF_TERM_HOURS = 168;
    private static final boolean DEF_LEGISLATIVE_MODE = false;

    public PoliticManager(KsEco eco) {
        this.eco = eco;
    }

    // ================================================================
    // 初始化
    // ================================================================

    public void init() {
        ensureTables();
        loadConfig();
        loadOffices();
    }

    private void ensureTables() {
        try (Connection conn = eco.ksCore().dataStore().getConnection()) {
            if (conn == null) {
                eco.getLogger().warning("[政治系统] 数据库连接未就绪");
                return;
            }
            int removed = PoliticSchema.initialize(conn);
            if (removed > 0) {
                eco.getLogger().warning("[政治系统] 已清理 " + removed + " 条重复投票记录");
            }
        } catch (SQLException e) {
            eco.getLogger().warning("[政治系统] 建表失败: " + e.getMessage());
        }
    }

    private void loadConfig() {
        Map<String, String> loaded = new java.util.concurrent.ConcurrentHashMap<>();
        loaded.put("senate_seats", String.valueOf(DEF_SENATE_SEATS));
        loaded.put("equestrian_seats", String.valueOf(DEF_EQUESTRIAN_SEATS));
        loaded.put("tribune_seats", String.valueOf(DEF_TRIBUNE_SEATS));
        loaded.put("term_duration_hours", String.valueOf(DEF_TERM_HOURS));
        loaded.put("legislative_mode", String.valueOf(DEF_LEGISLATIVE_MODE));
        loaded.put("election_check_interval_min", "30");
        loaded.put("tribune_election_interval_hours", "24");
        loaded.put("proposal_vote_duration_minutes", "1440");
        loaded.put("proposal_override_duration_minutes", "720");
        loaded.put("proposal_progress_interval_minutes", "15");
        loaded.put("proposal_deadline_warning_minutes", "10");
        loaded.put("appeal_player_cooldown_seconds", "900");
        loaded.put("appeal_proposal_cooldown_seconds", "180");
        loaded.put("appeal_global_cooldown_seconds", "60");
        loaded.put("appeal_bulletin_duration_seconds", "900");

        try (Connection conn = eco.ksCore().dataStore().getConnection()) {
            if (conn == null) {
                config = loaded;
                return;
            }
            try (Statement stmt = conn.createStatement();
                 ResultSet rs = stmt.executeQuery("SELECT config_key, config_value FROM ks_politic_config")) {
                while (rs.next()) {
                    loaded.put(rs.getString("config_key"), rs.getString("config_value"));
                }
            }
            config = loaded;
        } catch (SQLException e) {
            eco.getLogger().warning("[政治系统] 加载配置失败: " + e.getMessage());
        }
    }

    private void loadOffices() {
        List<Office> loaded = new ArrayList<>();
        try (Connection conn = eco.ksCore().dataStore().getConnection()) {
            if (conn == null) return;
            try (Statement stmt = conn.createStatement();
                 ResultSet rs = stmt.executeQuery(
                     "SELECT * FROM ks_politic_offices WHERE is_active=1 ORDER BY office_type, seat_index")) {
                while (rs.next()) {
                    loaded.add(new Office(rs));
                }
            }
            offices = new java.util.concurrent.CopyOnWriteArrayList<>(loaded);
        } catch (SQLException e) {
            eco.getLogger().warning("[政治系统] 加载职务失败: " + e.getMessage());
        }
    }

    // ================================================================
    // 配置存取
    // ================================================================

    public String getConfig(String key) {
        return config.get(key);
    }

    public int getConfigInt(String key, int def) {
        try { return Integer.parseInt(config.getOrDefault(key, String.valueOf(def))); }
        catch (NumberFormatException e) { return def; }
    }

    public boolean getConfigBool(String key, boolean def) {
        String v = config.get(key);
        if (v == null) return def;
        return "true".equalsIgnoreCase(v) || "1".equals(v);
    }

    public void setConfig(String key, String value) {
        try (Connection conn = eco.ksCore().dataStore().getConnection()) {
            if (conn == null) return;
            try (PreparedStatement ps = conn.prepareStatement(
                "UPDATE ks_politic_config SET config_value=? WHERE config_key=?")) {
                ps.setString(1, value);
                ps.setString(2, key);
                if (ps.executeUpdate() == 0) {
                    try (PreparedStatement insert = conn.prepareStatement(
                            "INSERT INTO ks_politic_config (config_key,config_value) VALUES (?,?)")) {
                        insert.setString(1, key);
                        insert.setString(2, value);
                        insert.executeUpdate();
                    }
                }
            }
        } catch (SQLException e) {
            eco.getLogger().warning("[政治系统] 保存配置失败: " + e.getMessage());
            return;
        }
        config.put(key, value);
        eco.publishCrossServerInvalidation("politic", "state");
    }

    public Map<String, String> getAllConfig() { return new LinkedHashMap<>(config); }

    public boolean isLegislativeMode() { return getConfigBool("legislative_mode", DEF_LEGISLATIVE_MODE); }

    // ================================================================
    // 职务查询
    // ================================================================

    public List<Office> getActiveOffices(String officeType) {
        List<Office> out = new ArrayList<>();
        for (Office o : offices) {
            if (officeType == null || o.officeType.equals(officeType)) out.add(o);
        }
        return out;
    }

    public List<Office> getSenators() { return getActiveOffices("SENATOR"); }

    public Office getConsul() {
        for (Office o : offices) if (o.officeType.equals("CONSUL")) return o;
        return null;
    }

    public List<Office> getTribunes() { return getActiveOffices("TRIBUNE"); }

    public List<Office> getEquestrians() { return getActiveOffices("EQUESTRIAN"); }

    public String getPlayerOffice(UUID playerUuid) {
        String uuid = playerUuid.toString();
        // 执政官同时是元老，须优先返回最高职务（否则刚选举的执政官会被显示为元老）
        String best = null;
        for (Office o : offices) {
            if (!o.playerUuid.equals(uuid)) continue;
            if ("CONSUL".equals(o.officeType)) return "CONSUL";
            if (best == null || "SENATOR".equals(o.officeType)) best = o.officeType;
        }
        return best;
    }

    public boolean isSenator(UUID playerUuid) {
        String uuid = playerUuid.toString();
        for (Office o : offices) {
            if (o.playerUuid.equals(uuid) && "SENATOR".equals(o.officeType)) return true;
        }
        return false;
    }

    public boolean isConsul(UUID playerUuid) {
        Office c = getConsul();
        return c != null && c.playerUuid.equals(playerUuid.toString());
    }

    public boolean isTribune(UUID playerUuid) {
        String uuid = playerUuid.toString();
        for (Office o : offices) {
            if (o.playerUuid.equals(uuid) && "TRIBUNE".equals(o.officeType)) return true;
        }
        return false;
    }

    public boolean isEquestrian(UUID playerUuid) {
        String uuid = playerUuid.toString();
        for (Office o : offices) {
            if (o.playerUuid.equals(uuid) && "EQUESTRIAN".equals(o.officeType)) return true;
        }
        return false;
    }

    public boolean isActiveOfficial(UUID playerUuid) {
        return getPlayerOffice(playerUuid) != null;
    }

    public boolean canPropose(UUID playerUuid) {
        return isConsul(playerUuid) || isEquestrian(playerUuid);
    }

    public boolean canVeto(UUID playerUuid) { return isTribune(playerUuid); }

    public boolean canVoteInSenate(UUID playerUuid) {
        return isSenator(playerUuid) || isConsul(playerUuid);
    }

    // ================================================================
    // 职务任命
    // ================================================================

    public Office insertOffice(UUID playerUuid, String playerName, String officeType,
                                String enterpriseId, long termEnds, String electedBy) {
        // 唯一性守卫：同一玩家同一职务只能有一条在任记录，
        // 否则 fillTribuneSeats / triggerElection 反复运行会插入重复行（曾导致 2 名玩家各 5 条 TRIBUNE）。
        String puid = playerUuid.toString();
        for (Office o : offices) {
            if (o.playerUuid.equals(puid) && o.officeType.equals(officeType)) return o;
        }
        String id = UUID.randomUUID().toString().substring(0, 8);
        long now = System.currentTimeMillis() / 1000;
        try (Connection conn = eco.ksCore().dataStore().getConnection()) {
            if (conn == null) return null;
            try (PreparedStatement ps = conn.prepareStatement(
                "INSERT INTO ks_politic_offices (id, player_uuid, player_name, office_type, enterprise_id, elected_at, term_ends_at, elected_by, is_active) " +
                "VALUES (?,?,?,?,?,?,?,?,1)")) {
                ps.setString(1, id);
                ps.setString(2, playerUuid.toString());
                ps.setString(3, playerName);
                ps.setString(4, officeType);
                ps.setString(5, enterpriseId != null ? enterpriseId : "");
                ps.setLong(6, now);
                ps.setLong(7, termEnds);
                ps.setString(8, electedBy);
                ps.executeUpdate();
            }
            Office o = new Office(id, playerUuid.toString(), playerName, officeType, enterpriseId != null ? enterpriseId : "", now, termEnds, electedBy, true);
            offices.add(o);
            eco.publishCrossServerInvalidation("politic", "state");
            return o;
        } catch (SQLException e) {
            eco.getLogger().warning("[政治系统] 任命失败: " + e.getMessage());
            return null;
        }
    }

    public boolean vacateOffice(UUID playerUuid, String officeType) {
        String uuid = playerUuid.toString();
        try (Connection conn = eco.ksCore().dataStore().getConnection()) {
            if (conn == null) return false;
            try (PreparedStatement ps = conn.prepareStatement(
                "UPDATE ks_politic_offices SET is_active=0 WHERE player_uuid=? AND office_type=? AND is_active=1")) {
                ps.setString(1, uuid);
                ps.setString(2, officeType);
                ps.executeUpdate();
            }
            offices.removeIf(o -> o.playerUuid.equals(uuid) && o.officeType.equals(officeType));
            eco.publishCrossServerInvalidation("politic", "state");
            return true;
        } catch (SQLException e) {
            eco.getLogger().warning("[政治系统] 免职失败: " + e.getMessage());
            return false;
        }
    }

    public boolean vacateOfficeById(String officeId) {
        try (Connection conn = eco.ksCore().dataStore().getConnection()) {
            if (conn == null) return false;
            try (PreparedStatement ps = conn.prepareStatement(
                "UPDATE ks_politic_offices SET is_active=0 WHERE id=? AND is_active=1")) {
                ps.setString(1, officeId);
                ps.executeUpdate();
            }
            offices.removeIf(o -> o.id.equals(officeId));
            eco.publishCrossServerInvalidation("politic", "state");
            return true;
        } catch (SQLException e) {
            eco.getLogger().warning("[政治系统] 免职失败: " + e.getMessage());
            return false;
        }
    }

    public void refreshSharedStateFromRemote() {
        loadConfig();
        loadOffices();
    }

    // ================================================================
    // 元老任命/移除
    // ================================================================

    public AssignResult assignSenator(UUID playerUuid, String playerName) {
        int max = getConfigInt("senate_seats", DEF_SENATE_SEATS);
        int current = getSenators().size() + (getConsul() != null ? 1 : 0);
        if (current >= max) return AssignResult.fail("元老院已满 (" + max + " 席)");
        if (isSenator(playerUuid) || isConsul(playerUuid)) return AssignResult.fail("已是元老");

        int seatIndex = 0;
        Set<Integer> used = new HashSet<>();
        for (Office o : offices) {
            if ("SENATOR".equals(o.officeType)) used.add(o.seatIndex);
        }
        while (used.contains(seatIndex)) seatIndex++;

        Office o = insertOffice(playerUuid, playerName, "SENATOR", null, 0, "ADMIN");
        if (o == null) return AssignResult.fail("数据库写入失败");
        o.seatIndex = seatIndex;

        // checkMutualExclusion 在调用方触发（不做重复检查）
        return AssignResult.success(o, "已任命为元老");
    }

    public boolean removeSenator(UUID playerUuid) {
        if (!isSenator(playerUuid)) return false;
        // 如果是执政官，先撤销执政官
        if (isConsul(playerUuid)) {
            vacateOffice(playerUuid, "CONSUL");
        }
        return vacateOffice(playerUuid, "SENATOR");
    }

    // ================================================================
    // 执政官选举
    // ================================================================

    public AssignResult electConsul(UUID playerUuid, String playerName) {
        if (!isSenator(playerUuid)) return AssignResult.fail("执政官必须是元老院成员");
        if (isConsul(playerUuid)) return AssignResult.fail("已是执政官");

        // 撤销前一任执政官
        Office old = getConsul();
        if (old != null) vacateOffice(UUID.fromString(old.playerUuid), "CONSUL");

        int termHours = getConfigInt("term_duration_hours", DEF_TERM_HOURS);
        long termEnds = (System.currentTimeMillis() / 1000) + (termHours * 3600L);

        Office o = insertOffice(playerUuid, playerName, "CONSUL", null, termEnds, "SENATE_VOTE");
        if (o == null) return AssignResult.fail("数据库写入失败");
        return AssignResult.success(o, "已选举为执政官");
    }

    // ================================================================
    // 保民官选举
    // ================================================================

    public AssignResult electTribune(UUID playerUuid, String playerName) {
        int max = getConfigInt("tribune_seats", DEF_TRIBUNE_SEATS);
        int current = getTribunes().size();
        if (current >= max) return AssignResult.fail("保民官已满 (" + max + " 席)");

        // 互斥前置检查：必须是平民
        if (isSenator(playerUuid) || isConsul(playerUuid))
            return AssignResult.fail("元老院成员不能担任保民官");
        if (isEquestrian(playerUuid))
            return AssignResult.fail("骑士阶级不能担任保民官（先离职骑士身份）");

        // 检查是否是 admin
        OfflinePlayer op = Bukkit.getOfflinePlayer(playerUuid);
        if (op.getPlayer() != null && op.getPlayer().hasPermission("kseco.admin"))
            return AssignResult.fail("管理员不能担任保民官");

        int termHours = getConfigInt("term_duration_hours", DEF_TERM_HOURS);
        long termEnds = (System.currentTimeMillis() / 1000) + (termHours * 3600L);

        Office o = insertOffice(playerUuid, playerName, "TRIBUNE", null, termEnds, "PUBLIC_VOTE");
        if (o == null) return AssignResult.fail("数据库写入失败");

        // 互斥检查：如果已是骑士 → 吊销骑士身份，级联补位
        CheckResult cr = checkMutualExclusion(playerUuid);
        return AssignResult.success(o, "已选举为保民官" + (cr.conflict ? "（已处理互斥冲突）" : ""));
    }

    // ================================================================
    // 保民官全民选举（投票制）
    // ================================================================

    public String getTribuneElectionId() { return config.get("tribune_election_id"); }

    public long getTribuneElectionStartedAt() {
        try { return Long.parseLong(config.getOrDefault("tribune_election_started_at", "0")); }
        catch (NumberFormatException e) { return 0; }
    }

    public long getTribuneElectionEndsAt() {
        long started = getTribuneElectionStartedAt();
        if (started <= 0) return 0;
        int hours = getConfigInt("tribune_election_interval_hours", 24);
        return started + (hours * 3600L);
    }

    /** 开启新一轮保民官选举周期（清空上一轮的有效投票窗口，旧票仍按 election_id 隔离保留作历史记录）。 */
    public void startNewTribuneElection() {
        long now = System.currentTimeMillis() / 1000;
        String id = "te-" + now;
        setConfig("tribune_election_id", id);
        setConfig("tribune_election_started_at", String.valueOf(now));
    }

    public boolean isTribuneCandidateEligible(UUID playerUuid) {
        if (isSenator(playerUuid) || isConsul(playerUuid) || isEquestrian(playerUuid)) return false;
        OfflinePlayer op = Bukkit.getOfflinePlayer(playerUuid);
        if (op.getPlayer() != null && op.getPlayer().hasPermission("kseco.admin")) return false;
        return true;
    }

    public AssignResult castTribuneElectionVote(String electionId, UUID voterUuid, String voterName,
                                                 UUID candidateUuid, String candidateName) {
        if (electionId == null || electionId.isEmpty()) return AssignResult.fail("当前没有进行中的保民官选举");
        if (!isTribuneCandidateEligible(candidateUuid)) return AssignResult.fail("该玩家不具备保民官候选资格（元老/执政官/骑士/管理员不可被选）");
        try (Connection conn = eco.ksCore().dataStore().getConnection()) {
            if (conn == null) return AssignResult.fail("数据库连接失败");
            long now = System.currentTimeMillis() / 1000;
            PortableSqlMutation.upsert(conn,
                    "UPDATE ks_politic_election_votes SET candidate_uuid=?,candidate_name=?,cast_at=? WHERE election_id=? AND voter_uuid=?",
                    ps -> { ps.setString(1, candidateUuid.toString()); ps.setString(2, candidateName);
                        ps.setLong(3, now); ps.setString(4, electionId); ps.setString(5, voterUuid.toString()); },
                    "INSERT INTO ks_politic_election_votes (id,election_id,voter_uuid,candidate_uuid,candidate_name,cast_at) VALUES (?,?,?,?,?,?)",
                    ps -> { ps.setString(1, electionId + ":" + voterUuid); ps.setString(2, electionId);
                        ps.setString(3, voterUuid.toString()); ps.setString(4, candidateUuid.toString());
                        ps.setString(5, candidateName); ps.setLong(6, now); });
            return AssignResult.success(null, "投票成功，已投给 " + candidateName);
        } catch (SQLException e) {
            return AssignResult.fail("投票失败: " + e.getMessage());
        }
    }

    public List<Map<String, Object>> getTribuneElectionTally(String electionId) {
        List<Map<String, Object>> out = new ArrayList<>();
        if (electionId == null || electionId.isEmpty()) return out;
        try (Connection conn = eco.ksCore().dataStore().getConnection()) {
            if (conn == null) return out;
            try (PreparedStatement ps = conn.prepareStatement(
                "SELECT candidate_uuid, candidate_name, COUNT(*) AS votes FROM ks_politic_election_votes " +
                "WHERE election_id=? GROUP BY candidate_uuid ORDER BY votes DESC, MIN(cast_at) ASC")) {
                ps.setString(1, electionId);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        Map<String, Object> m = new LinkedHashMap<>();
                        m.put("candidateUuid", rs.getString("candidate_uuid"));
                        m.put("candidateName", rs.getString("candidate_name"));
                        m.put("votes", rs.getInt("votes"));
                        out.add(m);
                    }
                }
            }
        } catch (SQLException e) {
            eco.getLogger().warning("[政治系统] 查询保民官选票失败: " + e.getMessage());
        }
        return out;
    }

    public String getMyTribuneVote(String electionId, UUID voterUuid) {
        if (electionId == null || electionId.isEmpty()) return null;
        try (Connection conn = eco.ksCore().dataStore().getConnection()) {
            if (conn == null) return null;
            try (PreparedStatement ps = conn.prepareStatement(
                "SELECT candidate_name FROM ks_politic_election_votes WHERE election_id=? AND voter_uuid=?")) {
                ps.setString(1, electionId);
                ps.setString(2, voterUuid.toString());
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) return rs.getString("candidate_name");
                }
            }
        } catch (SQLException e) { /* ignore */ }
        return null;
    }

    /** 按票数从高到低取前 N 名（N=tribune_seats）就任保民官，取代现任保民官。 */
    public List<ChangeRecord> tallyAndAssignTribunes(String electionId) {
        return tallyAndAssignTribunes(electionId, null);
    }

    /** Database-only tally using a server-thread snapshot of administrator UUIDs. */
    public List<ChangeRecord> tallyAndAssignTribunes(String electionId, Set<UUID> adminUuids) {
        List<ChangeRecord> changes = new ArrayList<>();
        if (electionId == null || electionId.isEmpty()) return changes;
        int max = getConfigInt("tribune_seats", DEF_TRIBUNE_SEATS);
        List<Map<String, Object>> tally = getTribuneElectionTally(electionId);

        // 卸任现任保民官
        for (Office o : getTribunes()) {
            vacateOfficeById(o.id);
            changes.add(new ChangeRecord(o.playerUuid, "TRIBUNE", "TERM_END", null));
        }

        int termHours = getConfigInt("term_duration_hours", DEF_TERM_HOURS);
        long termEnds = (System.currentTimeMillis() / 1000) + (termHours * 3600L);
        int filled = 0;
        for (Map<String, Object> row : tally) {
            if (filled >= max) break;
            String uid = (String) row.get("candidateUuid");
            String name = (String) row.get("candidateName");
            UUID puuid = UUID.fromString(uid);
            if (!isTribuneCandidateEligible(puuid)) continue; // 投票期间身份变化，跳过失格候选人
            Office o = insertOffice(puuid, name, "TRIBUNE", null, termEnds, "PUBLIC_VOTE");
            if (o != null) {
                changes.add(new ChangeRecord(uid, "TRIBUNE", "ELECTED", "votes=" + row.get("votes")));
                checkMutualExclusion(puuid);
                filled++;
            }
        }
        return changes;
    }

    // ================================================================
    // 骑士排行刷新
    // ================================================================

    /**
     * 按企业注册资本降序，重新计算骑士阶级。
     * 跳过已是 Tribune 的玩家（互斥），跳过已是 Senator/Consul 的 admin。
     */
    public List<ChangeRecord> recomputeEquestrians() {
        List<ChangeRecord> changes = new ArrayList<>();
        int max = getConfigInt("equestrian_seats", DEF_EQUESTRIAN_SEATS);

        // 当前骑士 UUID 集合
        Set<String> currentEquestrians = new LinkedHashSet<>();
        for (Office o : offices) {
            if ("EQUESTRIAN".equals(o.officeType) && o.isActive) {
                currentEquestrians.add(o.playerUuid);
            }
        }

        // 获取企业排行
        List<String[]> ranking = new ArrayList<>(); // [ownerUuid, enterpriseId, enterpriseName]
        try (Connection conn = eco.ksCore().dataStore().getConnection()) {
            if (conn != null) {
                try (Statement stmt = conn.createStatement();
                     ResultSet rs = stmt.executeQuery(
                         "SELECT owner_uuids, id, name FROM ks_ent_enterprises WHERE status='ACTIVE' ORDER BY registered_capital DESC")) {
                    while (rs.next()) {
                        String owners = rs.getString("owner_uuids");
                        String entId = rs.getString("id");
                        String entName = rs.getString("name");
                        if (owners != null && !owners.isEmpty()) {
                            for (String owner : owners.split(",")) {
                                String uid = owner.trim();
                                if (!uid.isEmpty()) {
                                    ranking.add(new String[]{uid, entId, entName});
                                }
                            }
                        }
                    }
                }
            }
        } catch (SQLException e) {
            eco.getLogger().warning("[政治系统] 查询企业排行失败: " + e.getMessage());
        }

        // 填充骑士
        Set<String> newEquestrians = new LinkedHashSet<>();
        int filled = 0;
        for (String[] entry : ranking) {
            if (filled >= max) break;
            String uid = entry[0];
            String entId = entry[1];
            if (newEquestrians.contains(uid)) continue;

            // 互斥：跳过保民官
            if (isTribune(UUID.fromString(uid))) continue;

            newEquestrians.add(uid);

            if (!currentEquestrians.contains(uid)) {
                // 新骑士
                Office o = insertOffice(UUID.fromString(uid), safePlayerName(uid),
                    "EQUESTRIAN", entId, 0, "AUTO_RANKING");
                if (o != null) changes.add(new ChangeRecord(uid, "EQUESTRIAN", "PROMOTED", entId));
            }
            filled++;
        }

        // 贬出排行外的旧骑士
        for (String existingUid : currentEquestrians) {
            if (!newEquestrians.contains(existingUid)) {
                vacateOffice(UUID.fromString(existingUid), "EQUESTRIAN");
                changes.add(new ChangeRecord(existingUid, "EQUESTRIAN", "DEMOTED", null));
            }
        }

        return changes;
    }

    // ================================================================
    // 互斥检查（核心算法）
    // ================================================================

    /**
     * 检查一个玩家是否同时持有互斥职务。
     *
     * 规则:
     * - Consul 必须是 Senator（否则自动开除 Consul）
     * - Equestrian 和 Tribune 绝对互斥（先到先得，后来的被免职+级联补位）
     */
    public CheckResult checkMutualExclusion(UUID playerUuid) {
        String uuid = playerUuid.toString();
        List<Office> myOffices = new ArrayList<>();
        for (Office o : offices) {
            if (o.playerUuid.equals(uuid)) myOffices.add(o);
        }

        if (myOffices.size() <= 1)
            return new CheckResult(false, false, null, null, List.of());

        boolean hasConsul = false;
        boolean hasSenator = false;
        Office equestrianOffice = null;
        Office tribuneOffice = null;

        for (Office o : myOffices) {
            switch (o.officeType) {
                case "CONSUL": hasConsul = true; break;
                case "SENATOR": hasSenator = true; break;
                case "EQUESTRIAN": equestrianOffice = o; break;
                case "TRIBUNE": tribuneOffice = o; break;
            }
        }

        List<ChangeRecord> cascaded = new ArrayList<>();

        // 规则 A: Consul 必须是 Senator
        if (hasConsul && !hasSenator) {
            vacateOffice(playerUuid, "CONSUL");
            return new CheckResult(true, true, "CONSUL", null, List.of(
                new ChangeRecord(uuid, "CONSUL", "REVOKED", "不是元老院成员")));
        }

        // 规则 B: Equestrian ↔ Tribune 互斥
        if (equestrianOffice != null && tribuneOffice != null) {
            // 先到先得
            if (equestrianOffice.electedAt < tribuneOffice.electedAt) {
                // 骑士先就任 → 保留骑士，剥夺保民官
                vacateOffice(playerUuid, "TRIBUNE");
                cascaded.addAll(promoteNextTribune());
                return new CheckResult(true, true, "TRIBUNE", "EQUESTRIAN", cascaded);
            } else {
                // 保民官先就任 → 保留保民官，剥夺骑士
                vacateOffice(playerUuid, "EQUESTRIAN");
                cascaded.addAll(promoteNextEquestrian());
                return new CheckResult(true, true, "EQUESTRIAN", "TRIBUNE", cascaded);
            }
        }

        return new CheckResult(false, false, null, null, List.of());
    }

    /**
     * 全面互斥巡检（定时器调用，扫描所有在职者）
     */
    public List<CheckResult> fullMutualExclusionAudit() {
        List<CheckResult> results = new ArrayList<>();
        Set<String> checked = new LinkedHashSet<>();
        for (Office o : new ArrayList<>(offices)) {
            if (!checked.contains(o.playerUuid)) {
                CheckResult cr = checkMutualExclusion(UUID.fromString(o.playerUuid));
                if (cr.conflict) results.add(cr);
                checked.add(o.playerUuid);
            }
        }
        return results;
    }

    // ================================================================
    // 级联补位
    // ================================================================

    private List<ChangeRecord> promoteNextEquestrian() {
        return recomputeEquestrians(); // 重算整个排行即自动补位
    }

    private List<ChangeRecord> promoteNextTribune() {
        int max = getConfigInt("tribune_seats", DEF_TRIBUNE_SEATS);
        int current = getTribunes().size();
        if (current >= max) return List.of();

        // 找出所有不被禁止的玩家
        Set<String> excluded = new LinkedHashSet<>();
        for (Office o : offices) {
            if ("SENATOR".equals(o.officeType) || "CONSUL".equals(o.officeType) || "EQUESTRIAN".equals(o.officeType))
                excluded.add(o.playerUuid);
        }

        int termHours = getConfigInt("term_duration_hours", DEF_TERM_HOURS);
        long termEnds = (System.currentTimeMillis() / 1000) + (termHours * 3600L);
        List<ChangeRecord> results = new ArrayList<>();
        int seatsToFill = max - current;

        for (OfflinePlayer op : Bukkit.getOfflinePlayers()) {
            String uid = op.getUniqueId().toString();
            if (excluded.contains(uid)) continue;
            // 排除 admin
            if (op.getPlayer() != null && op.getPlayer().hasPermission("kseco.admin")) continue;

            Office o = insertOffice(op.getUniqueId(), safePlayerName(uid), "TRIBUNE", null, termEnds, "AUTO_PROMOTE");
            if (o != null) {
                results.add(new ChangeRecord(uid, "TRIBUNE", "PROMOTED", null));
                seatsToFill--;
                if (seatsToFill <= 0) break;
            }
        }
        return results;
    }

    // ================================================================
    // 工具
    // ================================================================

    private String safePlayerName(String uuid) {
        try {
            OfflinePlayer op = Bukkit.getOfflinePlayer(UUID.fromString(uuid));
            if (op.getName() != null) return op.getName();
        } catch (Exception ignored) {}
        return uuid;
    }

    void cleanupExpired() {
        long now = System.currentTimeMillis() / 1000;
        List<Office> toExpire = new ArrayList<>();
        for (Office o : offices) {
            if (o.termEndsAt > 0 && o.termEndsAt <= now) toExpire.add(o);
        }
        for (Office o : toExpire) {
            vacateOfficeById(o.id);
            eco.getLogger().info("[政治系统] 职务到期: " + o.playerName + " " + o.officeType);
        }
    }

    // ================================================================
    // 数据类
    // ================================================================

    public static class Office {
        public final String id;
        public final String playerUuid;
        public final String playerName;
        public final String officeType;
        public final String enterpriseId;
        public final long electedAt;
        public final long termEndsAt;
        public final String electedBy;
        public final boolean isActive;
        public int seatIndex;

        Office(ResultSet rs) throws SQLException {
            this.id = rs.getString("id");
            this.playerUuid = rs.getString("player_uuid");
            this.playerName = rs.getString("player_name");
            this.officeType = rs.getString("office_type");
            this.enterpriseId = rs.getString("enterprise_id");
            this.electedAt = rs.getLong("elected_at");
            this.termEndsAt = rs.getLong("term_ends_at");
            this.electedBy = rs.getString("elected_by");
            this.isActive = rs.getInt("is_active") != 0;
            this.seatIndex = 0;
        }

        Office(String id, String playerUuid, String playerName, String officeType, String enterpriseId,
                long electedAt, long termEndsAt, String electedBy, boolean isActive) {
            this.id = id;
            this.playerUuid = playerUuid;
            this.playerName = playerName;
            this.officeType = officeType;
            this.enterpriseId = enterpriseId;
            this.electedAt = electedAt;
            this.termEndsAt = termEndsAt;
            this.electedBy = electedBy;
            this.isActive = isActive;
        }

        public Map<String, Object> toMap() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", id);
            m.put("playerUuid", playerUuid);
            m.put("playerName", playerName);
            m.put("officeType", officeType);
            m.put("enterpriseId", enterpriseId);
            m.put("electedAt", electedAt);
            m.put("termEndsAt", termEndsAt);
            m.put("electedBy", electedBy);
            m.put("seatIndex", seatIndex);
            return m;
        }
    }

    public record AssignResult(boolean success, Office office, String message) {
        public static AssignResult success(Office o, String msg) { return new AssignResult(true, o, msg); }
        public static AssignResult fail(String msg) { return new AssignResult(false, null, msg); }
    }

    public record CheckResult(boolean conflict, boolean resolved, String previousOffice,
                               String newOffice, List<ChangeRecord> cascadedChanges) {}

    public record ChangeRecord(String playerUuid, String officeType, String action, String detail) {}
}
