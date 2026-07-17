package org.kseco.extra.enterprise;

import org.kseco.KsEco;

import java.sql.*;
import java.util.*;

/**
 * 企业管理器。
 * 现代企业注册制度：区域企业/合伙办企、注册资本、管理与注销。
 * 支持官方注资企业/国有企业（注册资本隐匿）。
 */
public final class EnterpriseManager {

    private final KsEco eco;

    public EnterpriseManager(KsEco eco) {
        this.eco = eco;
    }

    public void init() {
        createTables();
    }

    private void createTables() {
        try (Connection conn = eco.ksCore().dataStore().getConnection()) {
            if (conn == null) return;
            try (Statement stmt = conn.createStatement()) {
                // 企业表
                stmt.execute("""
                    CREATE TABLE IF NOT EXISTS ks_ent_enterprises (
                        id TEXT PRIMARY KEY,
                        name TEXT NOT NULL,
                        type TEXT NOT NULL DEFAULT 'PRIVATE',
                        owner_uuids TEXT NOT NULL,
                        registered_capital REAL NOT NULL,
                        current_assets REAL DEFAULT 0.0,
                        employee_count INTEGER DEFAULT 0,
                        region TEXT,
                        status TEXT DEFAULT 'ACTIVE',
                        created_at INTEGER NOT NULL
                    )
                """);
                // 企业成员表
                stmt.execute("""
                    CREATE TABLE IF NOT EXISTS ks_ent_members (
                        enterprise_id TEXT NOT NULL,
                        player_uuid TEXT NOT NULL,
                        role TEXT DEFAULT 'EMPLOYEE',
                        salary REAL DEFAULT 0.0,
                        joined_at INTEGER NOT NULL,
                        PRIMARY KEY (enterprise_id, player_uuid)
                    )
                """);
                stmt.execute("""
                    CREATE TABLE IF NOT EXISTS ks_ent_join_requests (
                        id TEXT PRIMARY KEY,
                        enterprise_id TEXT NOT NULL,
                        applicant_uuid TEXT NOT NULL,
                        applicant_name TEXT DEFAULT '',
                        status TEXT NOT NULL DEFAULT 'PENDING',
                        created_at INTEGER NOT NULL,
                        reviewed_by TEXT,
                        reviewed_at INTEGER DEFAULT 0,
                        UNIQUE (enterprise_id, applicant_uuid)
                    )
                """);
                stmt.execute("CREATE INDEX IF NOT EXISTS idx_ent_join_requests_status ON ks_ent_join_requests (enterprise_id, status, created_at)");
                // 招投标项目表
                stmt.execute("""
                    CREATE TABLE IF NOT EXISTS ks_ent_projects (
                        id TEXT PRIMARY KEY,
                        title TEXT NOT NULL,
                        publisher_uuid TEXT NOT NULL,
                        publisher_type TEXT NOT NULL DEFAULT 'OFFICIAL',
                        budget REAL NOT NULL,
                        prepayment_ratio REAL DEFAULT 0.3,
                        penalty_ratio REAL DEFAULT 0.1,
                        deadline INTEGER NOT NULL,
                        location TEXT,
                        allow_subcontract INTEGER DEFAULT 1,
                        allow_consortium INTEGER DEFAULT 1,
                        status TEXT DEFAULT 'OPEN',
                        created_at INTEGER NOT NULL
                    )
                """);
                // 投标记录表
                stmt.execute("""
                    CREATE TABLE IF NOT EXISTS ks_ent_bids (
                        id TEXT PRIMARY KEY,
                        project_id TEXT NOT NULL,
                        enterprise_id TEXT NOT NULL,
                        bid_amount REAL NOT NULL,
                        is_consortium INTEGER DEFAULT 0,
                        consortium_members TEXT,
                        status TEXT DEFAULT 'PENDING',
                        submitted_at INTEGER NOT NULL,
                        FOREIGN KEY (project_id) REFERENCES ks_ent_projects(id)
                    )
                """);
                stmt.execute("""
                    CREATE TABLE IF NOT EXISTS ks_ent_capital_injections (
                        id TEXT PRIMARY KEY,
                        enterprise_id TEXT NOT NULL,
                        contributor_uuid TEXT NOT NULL,
                        amount REAL NOT NULL,
                        injected_at INTEGER NOT NULL
                    )
                """);
                stmt.execute("""
                    CREATE TABLE IF NOT EXISTS ks_ent_dividend_shares (
                        enterprise_id TEXT NOT NULL,
                        owner_uuid TEXT NOT NULL,
                        share_percent REAL NOT NULL,
                        updated_at INTEGER NOT NULL,
                        PRIMARY KEY (enterprise_id, owner_uuid)
                    )
                """);
                stmt.execute("""
                    CREATE TABLE IF NOT EXISTS ks_ent_dividend_payouts (
                        id TEXT PRIMARY KEY,
                        dividend_id TEXT NOT NULL,
                        enterprise_id TEXT NOT NULL,
                        recipient_uuid TEXT NOT NULL,
                        share_percent REAL NOT NULL,
                        gross_amount REAL NOT NULL,
                        tax_amount REAL NOT NULL,
                        net_amount REAL NOT NULL,
                        paid_at INTEGER NOT NULL
                    )
                """);
                // Older installations already have the enterprise table, so this migration must be idempotent.
                try {
                    stmt.execute("ALTER TABLE ks_ent_enterprises ADD COLUMN dividend_rate REAL NOT NULL DEFAULT 0");
                } catch (SQLException ignored) {
                    // The column already exists.
                }
            }
        } catch (SQLException e) {
            eco.getLogger().warning("[企业] 创建表失败: " + e.getMessage());
        }
    }

    /**
     * 注册企业。
     * @param name 企业名称
     * @param type PRIVATE（私有）/ STATE（国有）
     * @param ownerUuids 所有者 UUID 列表
     * @param registeredCapital 注册资本
     * @param region 注册区域
     */
    public Enterprise register(String name, String type, List<UUID> ownerUuids,
                                double registeredCapital, String region) {
        if (name == null || name.isBlank() || ownerUuids == null || ownerUuids.isEmpty()
                || !isValidAmount(registeredCapital)) return null;
        try {
        String id = UUID.randomUUID().toString().substring(0, 8);
        long now = System.currentTimeMillis() / 1000;

        // 扣除注册资本：全员足额才放行。旧逻辑"付不起就跳过扣款但照样全额记账"
        // 意味着零成本注册后 current_assets 凭空有钱，配合 dissolve 派发 = 无限印钞。
        // 国企（STATE/STATE_OWNED）为官方注资，不从玩家扣款。
        boolean deductFromOwners = !"STATE_OWNED".equalsIgnoreCase(type) && !"STATE".equalsIgnoreCase(type);
        if (deductFromOwners) {
            if (!eco.vaultHook().isAvailable()) {
                eco.getLogger().warning("[企业] Vault 不可用，拒绝注册（注册资本无法扣除）");
                return null;
            }
            double share = registeredCapital / ownerUuids.size();
            for (UUID uuid : ownerUuids) {
                if (!eco.vaultHook().has(org.bukkit.Bukkit.getOfflinePlayer(uuid), share)) {
                    eco.getLogger().info("[企业] 注册被拒：所有者 " + uuid + " 余额不足（需 " + share + "）");
                    return null;
                }
            }
            for (UUID uuid : ownerUuids) {
                if (!eco.vaultHook().withdraw(org.bukkit.Bukkit.getOfflinePlayer(uuid), share)) {
                    for (UUID charged : ownerUuids) {
                        if (charged.equals(uuid)) break;
                        eco.vaultHook().deposit(org.bukkit.Bukkit.getOfflinePlayer(charged), share);
                    }
                    return null;
                }
            }
        }

        try (Connection conn = eco.ksCore().dataStore().getConnection()) {
            if (conn == null) {
                if (deductFromOwners) {
                    double share = registeredCapital / ownerUuids.size();
                    for (UUID uuid : ownerUuids) eco.vaultHook().deposit(org.bukkit.Bukkit.getOfflinePlayer(uuid), share);
                }
                return null;
            }
            try (PreparedStatement ps = conn.prepareStatement(
                    "INSERT INTO ks_ent_enterprises (id, name, type, owner_uuids, registered_capital, " +
                    "current_assets, region, created_at) VALUES (?,?,?,?,?,?,?,?)")) {
                ps.setString(1, id);
                ps.setString(2, name);
                ps.setString(3, type);
                ps.setString(4, String.join(",", ownerUuids.stream().map(UUID::toString).toList()));
                ps.setDouble(5, registeredCapital);
                ps.setDouble(6, registeredCapital);
                ps.setString(7, region);
                ps.setLong(8, now);
                ps.executeUpdate();
            }
            // 注册资本注入企业公户（与 web 端 handleEnterpriseRegister 保持一致的记账：
            // 公户余额 ↔ current_assets ↔ CORP-BANK total_assets 镜像同步）。
            // 不建公户的话，企业后续一旦发生公户操作会以 0 余额建户，注销按公户派发时注册资本就丢了。
            try (PreparedStatement ps = conn.prepareStatement(
                    "INSERT OR IGNORE INTO ks_ent_corporate_accounts (enterprise_id, bank_id, balance, updated_at) " +
                    "VALUES (?, 'CORP-BANK', ?, ?)")) {
                ps.setString(1, id);
                ps.setDouble(2, registeredCapital);
                ps.setLong(3, now);
                ps.executeUpdate();
            } catch (SQLException corpEx) {
                eco.getLogger().warning("[企业] 公户初始化失败（表未就绪？）: " + corpEx.getMessage());
            }
            try (PreparedStatement ps = conn.prepareStatement(
                    "UPDATE ks_bank_banks SET total_assets=total_assets+? WHERE id='CORP-BANK'")) {
                ps.setDouble(1, registeredCapital);
                ps.executeUpdate();
            } catch (SQLException ignored) {}
            return new Enterprise(id, name, type, ownerUuids, registeredCapital, registeredCapital, region, now);
        } catch (SQLException e) {
            eco.getLogger().warning("[企业] 注册失败: " + e.getMessage());
            // 回滚注册资本（只有真扣过款才退）
            if (deductFromOwners) {
                for (UUID uuid : ownerUuids) {
                    var player = org.bukkit.Bukkit.getOfflinePlayer(uuid);
                    eco.vaultHook().deposit(player, registeredCapital / ownerUuids.size());
                }
            }
            return null;
        }
        } catch (Exception e) {
            eco.getLogger().warning("[企业] 注册异常: " + e.getClass().getSimpleName() + " - " + e.getMessage());
            e.printStackTrace();
            return null;
        }
    }

    /**
     * 获取企业信息。
     */
    public Enterprise getEnterprise(String id) {
        try (Connection conn = eco.ksCore().dataStore().getConnection()) {
            if (conn == null) return null;
            try (PreparedStatement ps = conn.prepareStatement(
                    "SELECT * FROM ks_ent_enterprises WHERE id=?")) {
                ps.setString(1, id);
                ResultSet rs = ps.executeQuery();
                if (rs.next()) {
                    List<UUID> owners = Arrays.stream(rs.getString("owner_uuids").split(","))
                            .map(UUID::fromString).toList();
                    return new Enterprise(
                            rs.getString("id"), rs.getString("name"), rs.getString("type"),
                            owners, rs.getDouble("registered_capital"), rs.getDouble("current_assets"),
                            rs.getString("region"), rs.getLong("created_at"));
                }
            }
        } catch (SQLException e) {
            eco.getLogger().warning("[企业] 查询失败: " + e.getMessage());
        }
        return null;
    }

    /** Submit a join request. Membership is only created after an authorized manager approves it. */
    public Map<String, Object> requestJoin(String enterpriseId, UUID applicantUuid, String applicantName) {
        if (enterpriseId == null || enterpriseId.isBlank() || applicantUuid == null) {
            return result(false, "企业或申请人无效。", null);
        }
        long now = System.currentTimeMillis() / 1000;
        try (Connection conn = eco.ksCore().dataStore().getConnection()) {
            if (conn == null) return result(false, "数据库未连接。", null);
            try (PreparedStatement enterprise = conn.prepareStatement(
                    "SELECT owner_uuids,status FROM ks_ent_enterprises WHERE id=?")) {
                enterprise.setString(1, enterpriseId);
                try (ResultSet rs = enterprise.executeQuery()) {
                    if (!rs.next() || !"ACTIVE".equalsIgnoreCase(rs.getString("status"))) {
                        return result(false, "该企业不存在或未处于经营状态。", null);
                    }
                    if (containsOwner(rs.getString("owner_uuids"), applicantUuid)) {
                        return result(false, "你已经是该企业所有者。", null);
                    }
                }
            }
            try (PreparedStatement member = conn.prepareStatement(
                    "SELECT 1 FROM ks_ent_members WHERE enterprise_id=? AND player_uuid=?")) {
                member.setString(1, enterpriseId);
                member.setString(2, applicantUuid.toString());
                if (member.executeQuery().next()) return result(false, "你已经是该企业成员。", null);
            }
            String requestId = UUID.randomUUID().toString();
            try (PreparedStatement request = conn.prepareStatement("""
                    INSERT INTO ks_ent_join_requests
                    (id,enterprise_id,applicant_uuid,applicant_name,status,created_at,reviewed_by,reviewed_at)
                    VALUES (?,?,?,?,'PENDING',?,NULL,0)
                    ON CONFLICT(enterprise_id,applicant_uuid) DO UPDATE SET
                    id=excluded.id, applicant_name=excluded.applicant_name, status='PENDING',
                    created_at=excluded.created_at, reviewed_by=NULL, reviewed_at=0
                    WHERE ks_ent_join_requests.status IN ('REJECTED','CANCELLED')
                    """)) {
                request.setString(1, requestId);
                request.setString(2, enterpriseId);
                request.setString(3, applicantUuid.toString());
                request.setString(4, applicantName == null ? applicantUuid.toString() : applicantName);
                request.setLong(5, now);
                if (request.executeUpdate() == 0) return result(false, "你已有一条待审批申请。", null);
            }
            return result(true, "加入申请已提交，等待企业管理者审批。", Map.of("requestId", requestId));
        } catch (SQLException e) {
            eco.getLogger().warning("[企业] 提交加入申请失败: " + e.getMessage());
            return result(false, "提交加入申请失败。", null);
        }
    }

    /** Approve or reject a pending request. The request state and member row change atomically. */
    public Map<String, Object> reviewJoinRequest(String enterpriseId, UUID reviewerUuid,
                                                 String requestId, boolean approve) {
        if (enterpriseId == null || reviewerUuid == null || requestId == null || requestId.isBlank()) {
            return result(false, "审批参数无效。", null);
        }
        try (Connection conn = eco.ksCore().dataStore().getConnection()) {
            if (conn == null) return result(false, "数据库未连接。", null);
            if (!hasEnterprisePermission(enterpriseId, reviewerUuid, "MANAGE_MEMBERS")) {
                return result(false, "你没有该企业的成员管理权限。", null);
            }
            conn.setAutoCommit(false);
            try {
                String applicantUuid;
                String applicantName;
                try (PreparedStatement request = conn.prepareStatement(
                        "SELECT applicant_uuid,applicant_name FROM ks_ent_join_requests WHERE id=? AND enterprise_id=? AND status='PENDING'")) {
                    request.setString(1, requestId);
                    request.setString(2, enterpriseId);
                    try (ResultSet rs = request.executeQuery()) {
                        if (!rs.next()) throw new IllegalArgumentException("申请不存在或已处理。");
                        applicantUuid = rs.getString("applicant_uuid");
                        applicantName = rs.getString("applicant_name");
                    }
                }
                if (approve) {
                    int maxMembers = 50;
                    try (PreparedStatement setting = conn.prepareStatement(
                            "SELECT value FROM ks_eco_settings WHERE key='enterprise_max_members'")) {
                        try (ResultSet rs = setting.executeQuery()) {
                            if (rs.next()) maxMembers = Math.max(1, Integer.parseInt(rs.getString(1)));
                        } catch (NumberFormatException ignored) {}
                    }
                    try (PreparedStatement count = conn.prepareStatement(
                            "SELECT COUNT(*) FROM ks_ent_members WHERE enterprise_id=?")) {
                        count.setString(1, enterpriseId);
                        try (ResultSet rs = count.executeQuery()) {
                            if (rs.next() && rs.getInt(1) >= maxMembers) throw new IllegalStateException("企业成员已达到上限。");
                        }
                    }
                    try (PreparedStatement member = conn.prepareStatement(
                            "INSERT INTO ks_ent_members (enterprise_id,player_uuid,player_name,role,joined_at) VALUES (?,?,?,'EMPLOYEE',?) ON CONFLICT(enterprise_id,player_uuid) DO NOTHING")) {
                        member.setString(1, enterpriseId);
                        member.setString(2, applicantUuid);
                        member.setString(3, applicantName);
                        member.setLong(4, System.currentTimeMillis() / 1000);
                        if (member.executeUpdate() != 1) throw new IllegalStateException("申请人已经是企业成员。");
                    }
                }
                try (PreparedStatement request = conn.prepareStatement(
                        "UPDATE ks_ent_join_requests SET status=?,reviewed_by=?,reviewed_at=? WHERE id=? AND enterprise_id=? AND status='PENDING'")) {
                    request.setString(1, approve ? "APPROVED" : "REJECTED");
                    request.setString(2, reviewerUuid.toString());
                    request.setLong(3, System.currentTimeMillis() / 1000);
                    request.setString(4, requestId);
                    request.setString(5, enterpriseId);
                    if (request.executeUpdate() != 1) throw new IllegalStateException("申请状态已经变更。");
                }
                updateMemberCount(conn, enterpriseId);
                conn.commit();
                return result(true, approve ? "加入申请已批准。" : "加入申请已拒绝。", null);
            } catch (Exception e) {
                conn.rollback();
                return result(false, messageOf(e, "审批失败。"), null);
            } finally {
                try { conn.setAutoCommit(true); } catch (SQLException ignored) {}
            }
        } catch (SQLException e) {
            return result(false, "审批失败。", null);
        }
    }

    /** Ordinary members may leave voluntarily; owners must transfer ownership or dissolve instead. */
    public Map<String, Object> leave(String enterpriseId, UUID memberUuid) {
        if (enterpriseId == null || memberUuid == null) return result(false, "退出参数无效。", null);
        try (Connection conn = eco.ksCore().dataStore().getConnection()) {
            if (conn == null) return result(false, "数据库未连接。", null);
            try (PreparedStatement enterprise = conn.prepareStatement(
                    "SELECT owner_uuids,status FROM ks_ent_enterprises WHERE id=?")) {
                enterprise.setString(1, enterpriseId);
                try (ResultSet rs = enterprise.executeQuery()) {
                    if (!rs.next() || !"ACTIVE".equalsIgnoreCase(rs.getString("status"))) return result(false, "企业不存在或未处于经营状态。", null);
                    if (containsOwner(rs.getString("owner_uuids"), memberUuid)) return result(false, "企业所有者不能直接退出，请先由管理员调整所有权或解散企业。", null);
                }
            }
            conn.setAutoCommit(false);
            try (PreparedStatement member = conn.prepareStatement(
                    "DELETE FROM ks_ent_members WHERE enterprise_id=? AND player_uuid=?")) {
                member.setString(1, enterpriseId);
                member.setString(2, memberUuid.toString());
                if (member.executeUpdate() != 1) throw new IllegalArgumentException("你不是该企业成员。");
                try (PreparedStatement permissions = conn.prepareStatement(
                        "DELETE FROM ks_ent_permissions WHERE enterprise_id=? AND player_uuid=?")) {
                    permissions.setString(1, enterpriseId);
                    permissions.setString(2, memberUuid.toString());
                    permissions.executeUpdate();
                }
                try (PreparedStatement request = conn.prepareStatement(
                        "UPDATE ks_ent_join_requests SET status='CANCELLED',reviewed_by=?,reviewed_at=? "
                                + "WHERE enterprise_id=? AND applicant_uuid=? AND status='APPROVED'")) {
                    request.setString(1, memberUuid.toString());
                    request.setLong(2, System.currentTimeMillis() / 1000);
                    request.setString(3, enterpriseId);
                    request.setString(4, memberUuid.toString());
                    request.executeUpdate();
                }
                updateMemberCount(conn, enterpriseId);
                conn.commit();
                return result(true, "你已退出企业。", null);
            } catch (Exception e) {
                conn.rollback();
                return result(false, messageOf(e, "退出企业失败。"), null);
            } finally {
                try { conn.setAutoCommit(true); } catch (SQLException ignored) {}
            }
        } catch (SQLException e) {
            return result(false, "退出企业失败。", null);
        }
    }

    /**
     * 注销企业。
     */
    public boolean dissolve(String enterpriseId, UUID requesterUuid) {
        Enterprise ent = getEnterprise(enterpriseId);
        if (ent == null || !ent.ownerUuids().contains(requesterUuid)) return false;
        // A single owner may not unilaterally dissolve a joint-owned enterprise.
        if (ent.ownerUuids().size() != 1) return false;

        // 先用条件更新把状态从 ACTIVE 原子翻到 DISSOLVED，成功才派发资产。
        // 旧顺序（先派发后更新、且不检查状态）可以对同一企业反复注销反复派发 = 无限印钞。
        double payout = ent.currentAssets();
        try (Connection conn = eco.ksCore().dataStore().getConnection()) {
            if (conn == null) return false;
            if (hasBlockingDissolutionRelations(conn, enterpriseId)) return false;
            conn.setAutoCommit(false);
            try {
            try (PreparedStatement ps = conn.prepareStatement(
                    "UPDATE ks_ent_enterprises SET status='DISSOLVED' WHERE id=? AND status='ACTIVE'")) {
                ps.setString(1, enterpriseId);
                if (ps.executeUpdate() == 0) return false; // 已注销/冻结，拒绝重复派发
            }
            // 记账模型：公户余额 ↔ current_assets ↔ CORP-BANK total_assets 三者镜像同步。
            // 派发额以公户实际余额为准（无公户行的老企业回退 current_assets），
            // 并清零公户 + 扣减 CORP-BANK + 清零 current_assets——否则注销派发之后
            // 同一笔钱还留在公户里可以再次提现/采购（双重支付）。
            try (PreparedStatement ps = conn.prepareStatement(
                    "SELECT balance FROM ks_ent_corporate_accounts WHERE enterprise_id=?")) {
                ps.setString(1, enterpriseId);
                ResultSet rs = ps.executeQuery();
                if (rs.next()) payout = rs.getDouble("balance");
            }
            try (PreparedStatement ps = conn.prepareStatement(
                    "UPDATE ks_ent_corporate_accounts SET balance=0, updated_at=? WHERE enterprise_id=?")) {
                ps.setLong(1, System.currentTimeMillis() / 1000);
                ps.setString(2, enterpriseId);
                ps.executeUpdate();
            }
            if (payout > 0) {
                adjustCorporateBankAssets(conn, enterpriseId, -payout);
            }
            try (PreparedStatement ps = conn.prepareStatement(
                    "UPDATE ks_ent_enterprises SET current_assets=0 WHERE id=?")) {
                ps.setString(1, enterpriseId);
                ps.executeUpdate();
            }
            try (PreparedStatement ps = conn.prepareStatement(
                    "UPDATE ks_ent_join_requests SET status='CANCELLED',reviewed_by=?,reviewed_at=? WHERE enterprise_id=? AND status='PENDING'")) {
                ps.setString(1, requesterUuid.toString());
                ps.setLong(2, System.currentTimeMillis() / 1000);
                ps.setString(3, enterpriseId);
                ps.executeUpdate();
            }
            conn.commit();
            } catch (Exception e) {
                conn.rollback();
                throw new SQLException("企业解散事务失败", e);
            } finally {
                try { conn.setAutoCommit(true); } catch (SQLException ignored) {}
            }
        } catch (SQLException e) {
            eco.getLogger().warning("[企业] 注销失败: " + e.getMessage());
            return false;
        }

        // 退还剩余资产给所有者
        if (payout > 0) {
            double share = payout / ent.ownerUuids().size();
            for (UUID uuid : ent.ownerUuids()) {
                var player = org.bukkit.Bukkit.getOfflinePlayer(uuid);
                eco.vaultHook().deposit(player, share);
            }
        }
        return true;
    }

    /**
     * 企业所有者向企业追加注册资本。资金进入企业公户，并同步企业资产及企业银行资产。
     */
    public Map<String, Object> injectCapital(String enterpriseId, UUID contributorUuid, double amount) {
        if (!isValidAmount(amount)) return result(false, "注资金额必须大于 0。", null);
        if (!eco.vaultHook().isAvailable()) return result(false, "Vault 经济未连接。", null);

        var contributor = org.bukkit.Bukkit.getOfflinePlayer(contributorUuid);
        if (!eco.vaultHook().has(contributor, amount)) return result(false, "余额不足。", null);
        if (!eco.vaultHook().withdraw(contributor, amount)) return result(false, "扣款失败。", null);

        try (Connection conn = eco.ksCore().dataStore().getConnection()) {
            if (conn == null) {
                eco.vaultHook().deposit(contributor, amount);
                return result(false, "数据库未连接，已退还资金。", null);
            }
            conn.setAutoCommit(false);
            try {
                String ownerUuids;
                try (PreparedStatement ps = conn.prepareStatement(
                        "SELECT owner_uuids FROM ks_ent_enterprises WHERE id=? AND status='ACTIVE'")) {
                    ps.setString(1, enterpriseId);
                    ResultSet rs = ps.executeQuery();
                    if (!rs.next()) throw new IllegalArgumentException("企业不存在或未处于经营状态。");
                    ownerUuids = rs.getString(1);
                }
                if (!hasEnterprisePermission(enterpriseId, contributorUuid, "MANAGE_FUNDS")) {
                    throw new IllegalArgumentException("只有企业所有者可以注资。");
                }

                long now = System.currentTimeMillis() / 1000;
                try (PreparedStatement ps = conn.prepareStatement(
                        "INSERT OR IGNORE INTO ks_ent_corporate_accounts (enterprise_id, bank_id, balance, updated_at) VALUES (?, 'CORP-BANK', 0, ?)")) {
                    ps.setString(1, enterpriseId);
                    ps.setLong(2, now);
                    ps.executeUpdate();
                }
                try (PreparedStatement ps = conn.prepareStatement(
                        "UPDATE ks_ent_enterprises SET registered_capital=registered_capital+?, current_assets=current_assets+? WHERE id=? AND status='ACTIVE'")) {
                    ps.setDouble(1, amount);
                    ps.setDouble(2, amount);
                    ps.setString(3, enterpriseId);
                    if (ps.executeUpdate() != 1) throw new SQLException("企业状态已变更。");
                }
                try (PreparedStatement ps = conn.prepareStatement(
                        "UPDATE ks_ent_corporate_accounts SET balance=balance+?, updated_at=? WHERE enterprise_id=?")) {
                    ps.setDouble(1, amount);
                    ps.setLong(2, now);
                    ps.setString(3, enterpriseId);
                    ps.executeUpdate();
                }
                adjustCorporateBankAssets(conn, enterpriseId, amount);
                try (PreparedStatement ps = conn.prepareStatement(
                        "INSERT INTO ks_ent_capital_injections (id, enterprise_id, contributor_uuid, amount, injected_at) VALUES (?,?,?,?,?)")) {
                    ps.setString(1, UUID.randomUUID().toString().substring(0, 8));
                    ps.setString(2, enterpriseId);
                    ps.setString(3, contributorUuid.toString());
                    ps.setDouble(4, amount);
                    ps.setLong(5, now);
                    ps.executeUpdate();
                }
                conn.commit();
                return result(true, "已向企业公户注资 " + formatAmount(amount) + "。", Map.of("amount", amount));
            } catch (Exception e) {
                conn.rollback();
                eco.vaultHook().deposit(contributor, amount);
                return result(false, messageOf(e, "注资失败，已退还资金。"), null);
            } finally {
                try { conn.setAutoCommit(true); } catch (SQLException ignored) {}
            }
        } catch (SQLException e) {
            eco.vaultHook().deposit(contributor, amount);
            return result(false, "注资失败，已退还资金。", null);
        }
    }

    /** Pays one configured employee salary into that employee's account at the enterprise's current bank. */
    public Map<String, Object> paySalary(String enterpriseId, UUID ownerUuid, UUID employeeUuid) {
        try (Connection conn = eco.ksCore().dataStore().getConnection()) {
            if (conn == null) return result(false, "数据库不可用。", null);
            conn.setAutoCommit(false);
            try {
                String owners, bankId;
                double salary, balance;
                try (PreparedStatement ps = conn.prepareStatement("""
                        SELECT e.owner_uuids,m.salary,ca.bank_id,ca.balance FROM ks_ent_enterprises e
                        JOIN ks_ent_members m ON m.enterprise_id=e.id
                        JOIN ks_ent_corporate_accounts ca ON ca.enterprise_id=e.id
                        WHERE e.id=? AND m.player_uuid=? AND e.status='ACTIVE'
                    """)) {
                    ps.setString(1, enterpriseId); ps.setString(2, employeeUuid.toString());
                    try (ResultSet rs = ps.executeQuery()) {
                        if (!rs.next()) throw new IllegalArgumentException("员工或企业不存在");
                        owners = rs.getString(1); salary = rs.getDouble(2); bankId = rs.getString(3); balance = rs.getDouble(4);
                    }
                }
                if (!hasEnterprisePermission(enterpriseId, ownerUuid, "MANAGE_FUNDS")) throw new IllegalArgumentException("需要企业资金管理权限才能发薪");
                if (salary <= 0 || balance < salary) throw new IllegalArgumentException("工资未设置或企业公户余额不足");
                long now = System.currentTimeMillis() / 1000;
                try (PreparedStatement debit = conn.prepareStatement("UPDATE ks_ent_corporate_accounts SET balance=balance-?,updated_at=? WHERE enterprise_id=? AND balance>=?")) {
                    debit.setDouble(1, salary); debit.setLong(2, now); debit.setString(3, enterpriseId); debit.setDouble(4, salary);
                    if (debit.executeUpdate() != 1) throw new SQLException("公户余额已变化");
                }
                try (PreparedStatement credit = conn.prepareStatement("INSERT OR IGNORE INTO ks_bank_accounts (id,bank_id,player_uuid,balance,interest_earned,opened_at) VALUES (?,?,?,?,0,?)")) {
                    credit.setString(1, bankId + ":" + employeeUuid); credit.setString(2, bankId); credit.setString(3, employeeUuid.toString()); credit.setDouble(4, 0); credit.setLong(5, now); credit.executeUpdate();
                }
                try (PreparedStatement credit = conn.prepareStatement("UPDATE ks_bank_accounts SET balance=balance+? WHERE id=? AND bank_id=?")) {
                    credit.setDouble(1, salary); credit.setString(2, bankId + ":" + employeeUuid); credit.setString(3, bankId);
                    if (credit.executeUpdate() != 1) throw new SQLException("员工银行账户入账失败");
                }
                try (PreparedStatement assets = conn.prepareStatement("UPDATE ks_ent_enterprises SET current_assets=MAX(current_assets-?,0) WHERE id=?")) {
                    assets.setDouble(1, salary); assets.setString(2, enterpriseId); assets.executeUpdate();
                }
                conn.commit();
                return result(true, "工资已转入员工银行账户。", Map.of("amount", salary, "bankId", bankId));
            } catch (Exception e) { conn.rollback(); return result(false, messageOf(e, "发薪失败。"), null); }
            finally { try { conn.setAutoCommit(true); } catch (SQLException ignored) {} }
        } catch (SQLException e) { return result(false, "发薪失败。", null); }
    }

    /** 企业所有者设置每次分红占当前公户余额的比例，范围为 0 到 100%。 */
    public Map<String, Object> setDividendRate(String enterpriseId, UUID ownerUuid, double ratePercent) {
        if (!Double.isFinite(ratePercent) || ratePercent < 0 || ratePercent > 100) {
            return result(false, "分红比例必须在 0 到 100 之间。", null);
        }
        try (Connection conn = eco.ksCore().dataStore().getConnection()) {
            if (conn == null) return result(false, "数据库未连接。", null);
            try (PreparedStatement check = conn.prepareStatement(
                    "SELECT owner_uuids FROM ks_ent_enterprises WHERE id=? AND status='ACTIVE'")) {
                check.setString(1, enterpriseId);
                ResultSet rs = check.executeQuery();
                if (!rs.next()) return result(false, "企业不存在或未处于经营状态。", null);
                if (!hasEnterprisePermission(enterpriseId, ownerUuid, "DECLARE_DIVIDEND")) return result(false, "需要企业分红声明权限。", null);
            }
            try (PreparedStatement ps = conn.prepareStatement(
                    "UPDATE ks_ent_enterprises SET dividend_rate=? WHERE id=?")) {
                ps.setDouble(1, ratePercent);
                ps.setString(2, enterpriseId);
                ps.executeUpdate();
            }
            return result(true, "分红比例已设置为 " + formatAmount(ratePercent) + "% 。", Map.of("rate", ratePercent));
        } catch (SQLException e) {
            return result(false, "保存分红比例失败。", null);
        }
    }

    /**
     * 设置税后分红在各所有者间的分配比例。传入的 UUID 必须恰好覆盖全部所有者，合计为 100%。
     */
    public Map<String, Object> setDividendShares(String enterpriseId, UUID requesterUuid, Map<String, Object> requestedShares) {
        if (requestedShares == null || requestedShares.isEmpty()) return result(false, "请提供所有者分红比例。", null);
        try (Connection conn = eco.ksCore().dataStore().getConnection()) {
            if (conn == null) return result(false, "数据库未连接。", null);
            conn.setAutoCommit(false);
            try {
                String ownerUuids;
                try (PreparedStatement ps = conn.prepareStatement(
                        "SELECT owner_uuids FROM ks_ent_enterprises WHERE id=? AND status='ACTIVE'")) {
                    ps.setString(1, enterpriseId);
                    ResultSet rs = ps.executeQuery();
                    if (!rs.next()) throw new IllegalArgumentException("企业不存在或未处于经营状态。");
                    ownerUuids = rs.getString(1);
                }
                if (!hasEnterprisePermission(enterpriseId, requesterUuid, "DECLARE_DIVIDEND")) throw new IllegalArgumentException("需要企业分红声明权限。 ");
                List<UUID> owners = parseOwners(ownerUuids);
                Map<UUID, Double> shares = parseShares(requestedShares);
                if (!shares.keySet().equals(new LinkedHashSet<>(owners))) {
                    throw new IllegalArgumentException("分红比例必须包含且只包含全部企业所有者。 ");
                }
                double total = shares.values().stream().mapToDouble(Double::doubleValue).sum();
                if (shares.values().stream().anyMatch(value -> !Double.isFinite(value) || value < 0) || Math.abs(total - 100.0) > 0.0001) {
                    throw new IllegalArgumentException("所有者分红比例必须为非负数且合计 100%。");
                }
                long now = System.currentTimeMillis() / 1000;
                try (PreparedStatement delete = conn.prepareStatement("DELETE FROM ks_ent_dividend_shares WHERE enterprise_id=?")) {
                    delete.setString(1, enterpriseId);
                    delete.executeUpdate();
                }
                try (PreparedStatement insert = conn.prepareStatement(
                        "INSERT INTO ks_ent_dividend_shares (enterprise_id, owner_uuid, share_percent, updated_at) VALUES (?,?,?,?)")) {
                    for (UUID owner : owners) {
                        insert.setString(1, enterpriseId);
                        insert.setString(2, owner.toString());
                        insert.setDouble(3, shares.get(owner));
                        insert.setLong(4, now);
                        insert.addBatch();
                    }
                    insert.executeBatch();
                }
                conn.commit();
                Map<String, Object> values = new LinkedHashMap<>();
                shares.forEach((owner, share) -> values.put(owner.toString(), share));
                return result(true, "所有者分红比例已保存。", Map.of("shares", values));
            } catch (Exception e) {
                conn.rollback();
                return result(false, messageOf(e, "保存所有者分红比例失败。"), null);
            } finally {
                try { conn.setAutoCommit(true); } catch (SQLException ignored) {}
            }
        } catch (SQLException e) {
            return result(false, "保存所有者分红比例失败。", null);
        }
    }

    /** 按企业设定的发放比例及所有者分配比例从企业公户发放一次分红。 */
    public Map<String, Object> distributeDividend(String enterpriseId, UUID ownerUuid) {
        if (!eco.vaultHook().isAvailable()) return result(false, "Vault 经济未连接。", null);
        List<UUID> owners;
        Map<UUID, Double> ownerShares;
        double grossAmount;
        double taxRate = 0.10;
        double taxPaid;
        double netAmount;
        Map<UUID, double[]> payoutSnapshot = new LinkedHashMap<>();
        try (Connection conn = eco.ksCore().dataStore().getConnection()) {
            if (conn == null) return result(false, "数据库未连接。", null);
            conn.setAutoCommit(false);
            try {
                String ownerUuids;
                double rate;
                double balance;
                try (PreparedStatement ps = conn.prepareStatement(
                        "SELECT e.owner_uuids, e.dividend_rate, COALESCE(ca.balance,0) AS balance FROM ks_ent_enterprises e LEFT JOIN ks_ent_corporate_accounts ca ON ca.enterprise_id=e.id WHERE e.id=? AND e.status='ACTIVE'")) {
                    ps.setString(1, enterpriseId);
                    ResultSet rs = ps.executeQuery();
                    if (!rs.next()) throw new IllegalArgumentException("企业不存在或未处于经营状态。");
                    ownerUuids = rs.getString("owner_uuids");
                    rate = rs.getDouble("dividend_rate");
                    balance = rs.getDouble("balance");
                }
                if (!hasEnterprisePermission(enterpriseId, ownerUuid, "DECLARE_DIVIDEND")) throw new IllegalArgumentException("需要企业分红声明权限。");
                owners = parseOwners(ownerUuids);
                if (owners.isEmpty()) throw new IllegalArgumentException("企业没有有效的所有者记录。");
                ownerShares = loadDividendShares(conn, enterpriseId, owners);
                grossAmount = Math.floor(balance * rate) / 100.0;
                if (grossAmount <= 0) throw new IllegalArgumentException("当前分红比例或公户余额不足以发放分红。");

                try (PreparedStatement ps = conn.prepareStatement(
                        "UPDATE ks_ent_corporate_accounts SET balance=balance-?, updated_at=? WHERE enterprise_id=? AND balance>=?")) {
                    ps.setDouble(1, grossAmount);
                    ps.setLong(2, System.currentTimeMillis() / 1000);
                    ps.setString(3, enterpriseId);
                    ps.setDouble(4, grossAmount);
                    if (ps.executeUpdate() != 1) throw new IllegalStateException("企业公户余额已变更，请重试。");
                }
                try (PreparedStatement ps = conn.prepareStatement(
                        "UPDATE ks_ent_enterprises SET current_assets=MAX(current_assets-?,0) WHERE id=?")) {
                    ps.setDouble(1, grossAmount);
                    ps.setString(2, enterpriseId);
                    ps.executeUpdate();
                }
                adjustCorporateBankAssets(conn, enterpriseId, -grossAmount);
                taxRate = readDividendTaxRate(conn);
                taxPaid = grossAmount * taxRate;
                netAmount = grossAmount - taxPaid;
                long now = System.currentTimeMillis() / 1000;
                String dividendId = UUID.randomUUID().toString().substring(0, 8);
                try (PreparedStatement ps = conn.prepareStatement(
                        "INSERT INTO ks_ent_dividends (id, enterprise_id, amount, declared_at, tax_rate, tax_paid, status) VALUES (?,?,?,?,?,?, 'PAID')")) {
                    ps.setString(1, dividendId);
                    ps.setString(2, enterpriseId);
                    ps.setDouble(3, grossAmount);
                    ps.setLong(4, now);
                    ps.setDouble(5, taxRate);
                    ps.setDouble(6, taxPaid);
                    ps.executeUpdate();
                }
                try (PreparedStatement ps = conn.prepareStatement(
                        "INSERT INTO ks_ent_dividend_payouts (id,dividend_id,enterprise_id,recipient_uuid,share_percent,gross_amount,tax_amount,net_amount,paid_at) VALUES (?,?,?,?,?,?,?,?,?)")) {
                    double remainingGross = grossAmount;
                    double remainingNet = netAmount;
                    for (int i = 0; i < owners.size(); i++) {
                        UUID owner = owners.get(i);
                        double share = ownerShares.get(owner);
                        double gross = i == owners.size() - 1 ? remainingGross : grossAmount * share / 100.0;
                        double net = i == owners.size() - 1 ? remainingNet : netAmount * share / 100.0;
                        remainingGross -= gross; remainingNet -= net;
                        payoutSnapshot.put(owner, new double[]{gross, gross - net, net, share});
                        ps.setString(1, UUID.randomUUID().toString()); ps.setString(2, dividendId); ps.setString(3, enterpriseId);
                        ps.setString(4, owner.toString()); ps.setDouble(5, share); ps.setDouble(6, gross);
                        ps.setDouble(7, gross - net); ps.setDouble(8, net); ps.setLong(9, now); ps.addBatch();
                    }
                    ps.executeBatch();
                }
                insertDividendTaxRecord(conn, enterpriseId, grossAmount, taxRate, taxPaid, now);
                conn.commit();
            } catch (Exception e) {
                conn.rollback();
                return result(false, messageOf(e, "分红失败。"), null);
            } finally {
                try { conn.setAutoCommit(true); } catch (SQLException ignored) {}
            }
        } catch (SQLException e) {
            return result(false, "分红失败。", null);
        }

        Map<String, Object> paidShares = new LinkedHashMap<>();
        for (UUID owner : owners) {
            double payout = payoutSnapshot.get(owner)[2];
            eco.vaultHook().deposit(org.bukkit.Bukkit.getOfflinePlayer(owner), payout);
            paidShares.put(owner.toString(), payout);
        }
        return result(true, "已按设定比例发放分红。", Map.of(
                "amount", grossAmount, "taxPaid", taxPaid, "netAmount", netAmount, "payouts", paidShares));
    }

    /**
     * 一次性精确分红：企业所有者指定税前总额、参与分红的企业成员及其税后分配比例。
     */
    public Map<String, Object> distributeCustomDividend(String enterpriseId, UUID ownerUuid, double grossAmount,
                                                         Map<String, Object> requestedShares) {
        if (!isValidAmount(grossAmount)) return result(false, "分红总额必须大于 0。", null);
        if (!eco.vaultHook().isAvailable()) return result(false, "Vault 经济未连接。", null);
        List<UUID> recipients;
        Map<UUID, Double> shares;
        double taxRate = 0.10;
        double taxPaid;
        double netAmount;
        Map<UUID, double[]> payoutSnapshot = new LinkedHashMap<>();

        try (Connection conn = eco.ksCore().dataStore().getConnection()) {
            if (conn == null) return result(false, "数据库未连接。", null);
            conn.setAutoCommit(false);
            try {
                String ownerUuids;
                double balance;
                try (PreparedStatement ps = conn.prepareStatement(
                        "SELECT e.owner_uuids, COALESCE(ca.balance,0) AS balance FROM ks_ent_enterprises e LEFT JOIN ks_ent_corporate_accounts ca ON ca.enterprise_id=e.id WHERE e.id=? AND e.status='ACTIVE'")) {
                    ps.setString(1, enterpriseId);
                    ResultSet rs = ps.executeQuery();
                    if (!rs.next()) throw new IllegalArgumentException("企业不存在或未处于经营状态。");
                    ownerUuids = rs.getString("owner_uuids");
                    balance = rs.getDouble("balance");
                }
                if (!hasEnterprisePermission(enterpriseId, ownerUuid, "DECLARE_DIVIDEND")) throw new IllegalArgumentException("需要企业分红声明权限。");
                if (grossAmount > balance) throw new IllegalArgumentException("企业公户余额不足。");

                Set<UUID> eligible = new LinkedHashSet<>(parseOwners(ownerUuids));
                try (PreparedStatement ps = conn.prepareStatement("SELECT player_uuid FROM ks_ent_members WHERE enterprise_id=?")) {
                    ps.setString(1, enterpriseId);
                    ResultSet rs = ps.executeQuery();
                    while (rs.next()) {
                        try { eligible.add(UUID.fromString(rs.getString(1))); } catch (IllegalArgumentException ignored) {}
                    }
                }
                shares = parseShares(requestedShares);
                if (shares.isEmpty() || !eligible.containsAll(shares.keySet())) {
                    throw new IllegalArgumentException("分红成员必须是该企业的所有者或成员。 ");
                }
                double totalPercent = shares.values().stream().mapToDouble(Double::doubleValue).sum();
                if (shares.values().stream().anyMatch(value -> !Double.isFinite(value) || value < 0) || Math.abs(totalPercent - 100.0) > 0.0001) {
                    throw new IllegalArgumentException("分红成员比例必须为非负数且合计 100%。");
                }
                recipients = new ArrayList<>(shares.keySet());

                long now = System.currentTimeMillis() / 1000;
                try (PreparedStatement ps = conn.prepareStatement(
                        "UPDATE ks_ent_corporate_accounts SET balance=balance-?, updated_at=? WHERE enterprise_id=? AND balance>=?")) {
                    ps.setDouble(1, grossAmount); ps.setLong(2, now); ps.setString(3, enterpriseId); ps.setDouble(4, grossAmount);
                    if (ps.executeUpdate() != 1) throw new IllegalStateException("企业公户余额已变更，请重试。");
                }
                try (PreparedStatement ps = conn.prepareStatement("UPDATE ks_ent_enterprises SET current_assets=MAX(current_assets-?,0) WHERE id=?")) {
                    ps.setDouble(1, grossAmount); ps.setString(2, enterpriseId); ps.executeUpdate();
                }
                adjustCorporateBankAssets(conn, enterpriseId, -grossAmount);
                taxRate = readDividendTaxRate(conn);
                taxPaid = grossAmount * taxRate;
                netAmount = grossAmount - taxPaid;
                String dividendId = UUID.randomUUID().toString().substring(0, 8);
                try (PreparedStatement ps = conn.prepareStatement(
                        "INSERT INTO ks_ent_dividends (id, enterprise_id, amount, declared_at, tax_rate, tax_paid, status) VALUES (?,?,?,?,?,?, 'PAID')")) {
                    ps.setString(1, dividendId); ps.setString(2, enterpriseId);
                    ps.setDouble(3, grossAmount); ps.setLong(4, now); ps.setDouble(5, taxRate); ps.setDouble(6, taxPaid); ps.executeUpdate();
                }
                try (PreparedStatement ps = conn.prepareStatement(
                        "INSERT INTO ks_ent_dividend_payouts (id,dividend_id,enterprise_id,recipient_uuid,share_percent,gross_amount,tax_amount,net_amount,paid_at) VALUES (?,?,?,?,?,?,?,?,?)")) {
                    double remainingGross = grossAmount;
                    double remainingNet = netAmount;
                    for (int i = 0; i < recipients.size(); i++) {
                        UUID recipient = recipients.get(i);
                        double share = shares.get(recipient);
                        double gross = i == recipients.size() - 1 ? remainingGross : grossAmount * share / 100.0;
                        double net = i == recipients.size() - 1 ? remainingNet : netAmount * share / 100.0;
                        remainingGross -= gross; remainingNet -= net;
                        payoutSnapshot.put(recipient, new double[]{gross, gross - net, net, share});
                        ps.setString(1, UUID.randomUUID().toString()); ps.setString(2, dividendId); ps.setString(3, enterpriseId);
                        ps.setString(4, recipient.toString()); ps.setDouble(5, share); ps.setDouble(6, gross);
                        ps.setDouble(7, gross - net); ps.setDouble(8, net); ps.setLong(9, now); ps.addBatch();
                    }
                    ps.executeBatch();
                }
                insertDividendTaxRecord(conn, enterpriseId, grossAmount, taxRate, taxPaid, now);
                conn.commit();
            } catch (Exception e) {
                conn.rollback();
                return result(false, messageOf(e, "分红失败。"), null);
            } finally {
                try { conn.setAutoCommit(true); } catch (SQLException ignored) {}
            }
        } catch (SQLException e) {
            return result(false, "分红失败。", null);
        }

        Map<String, Object> payouts = new LinkedHashMap<>();
        for (UUID recipient : recipients) {
            double payout = payoutSnapshot.get(recipient)[2];
            eco.vaultHook().deposit(org.bukkit.Bukkit.getOfflinePlayer(recipient), payout);
            payouts.put(recipient.toString(), payout);
        }
        return result(true, "精确分红已发放。", Map.of("amount", grossAmount, "taxPaid", taxPaid, "netAmount", netAmount, "payouts", payouts));
    }

    /** Mirrors enterprise cash movement into the bank currently hosting the corporate account. */
    private void adjustCorporateBankAssets(Connection conn, String enterpriseId, double delta) throws SQLException {
        String bankId = "CORP-BANK";
        try (PreparedStatement account = conn.prepareStatement(
                "SELECT bank_id FROM ks_ent_corporate_accounts WHERE enterprise_id=?")) {
            account.setString(1, enterpriseId);
            try (ResultSet rs = account.executeQuery()) {
                if (rs.next() && rs.getString(1) != null && !rs.getString(1).isBlank()) bankId = rs.getString(1);
            }
        }
        try (PreparedStatement bank = conn.prepareStatement(
                "UPDATE ks_bank_banks SET total_assets=MAX(total_assets+?,0) WHERE id=?")) {
            bank.setDouble(1, delta);
            bank.setString(2, bankId);
            if (bank.executeUpdate() != 1) throw new SQLException("企业开户行不可用: " + bankId);
        }
    }

    private double readDividendTaxRate(Connection conn) {
        double rate = eco.getCategoryTaxRate("DIVIDEND_TAX", 0.10);
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT rate FROM ks_tax_rates WHERE category='DIVIDEND_TAX' AND (industry IS NULL OR industry='') ORDER BY updated_at DESC LIMIT 1")) {
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) rate = rs.getDouble(1);
            }
        } catch (SQLException ignored) {
            try (PreparedStatement ps = conn.prepareStatement(
                    "SELECT rate FROM ks_tax_rates WHERE category='DIVIDEND_TAX' LIMIT 1")) {
                try (ResultSet rs = ps.executeQuery()) { if (rs.next()) rate = rs.getDouble(1); }
            } catch (SQLException ignoredLegacySchema) {}
        }
        if (!Double.isFinite(rate)) return 0.10;
        if (rate > 1.0 && rate <= 100.0) rate /= 100.0;
        return Math.max(0.0, Math.min(1.0, rate));
    }

    private void insertDividendTaxRecord(Connection conn, String enterpriseId, double grossAmount,
                                         double taxRate, double taxPaid, long now) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "INSERT INTO ks_tax_records (payer_uuid,payer_name,category,base_amount,tax_rate,tax_amount,description,collected_at) VALUES (?,?,'DIVIDEND_TAX',?,?,?,?,?)")) {
            ps.setString(1, enterpriseId);
            ps.setString(2, "企业分红");
            ps.setDouble(3, grossAmount);
            ps.setDouble(4, taxRate);
            ps.setDouble(5, taxPaid);
            ps.setString(6, "企业 " + enterpriseId + " 分红纳税");
            ps.setLong(7, now);
            ps.executeUpdate();
        }
    }

    private static void updateMemberCount(Connection conn, String enterpriseId) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "UPDATE ks_ent_enterprises SET employee_count=(SELECT COUNT(*) FROM ks_ent_members WHERE enterprise_id=?) WHERE id=?")) {
            ps.setString(1, enterpriseId);
            ps.setString(2, enterpriseId);
            ps.executeUpdate();
        }
    }

    private static boolean hasBlockingDissolutionRelations(Connection conn, String enterpriseId) throws SQLException {
        if (tableExists(conn, "ks_bank_enterprise_loans")) {
            try (PreparedStatement ps = conn.prepareStatement(
                    "SELECT 1 FROM ks_bank_enterprise_loans WHERE enterprise_id=? AND status IN ('ACTIVE','OVERDUE') LIMIT 1")) {
                ps.setString(1, enterpriseId);
                if (ps.executeQuery().next()) return true;
            }
        }
        if (tableExists(conn, "ks_bank_enterprise_loan_requests")) {
            try (PreparedStatement ps = conn.prepareStatement(
                    "SELECT 1 FROM ks_bank_enterprise_loan_requests WHERE enterprise_id=? AND status IN ('PENDING','PROCESSING') LIMIT 1")) {
                ps.setString(1, enterpriseId);
                if (ps.executeQuery().next()) return true;
            }
        }
        return false;
    }

    private static boolean tableExists(Connection conn, String table) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT 1 FROM sqlite_master WHERE type='table' AND name=?")) {
            ps.setString(1, table);
            return ps.executeQuery().next();
        }
    }

    private static boolean isValidAmount(double amount) {
        return Double.isFinite(amount) && amount > 0 && amount <= 1_000_000_000_000d;
    }

    private boolean hasEnterprisePermission(String enterpriseId, UUID playerUuid, String permission) {
        var provider = eco.enterpriseAccessProvider();
        if (provider != null) return provider.hasPermission(enterpriseId, playerUuid, permission);
        Enterprise enterprise = getEnterprise(enterpriseId);
        return enterprise != null && enterprise.ownerUuids().contains(playerUuid);
    }

    private static boolean containsOwner(String ownerUuids, UUID uuid) {
        return parseOwners(ownerUuids).contains(uuid);
    }

    private static List<UUID> parseOwners(String ownerUuids) {
        if (ownerUuids == null || ownerUuids.isBlank()) return List.of();
        List<UUID> owners = new ArrayList<>();
        for (String value : ownerUuids.split(",")) {
            try { owners.add(UUID.fromString(value.trim())); } catch (IllegalArgumentException ignored) {}
        }
        return owners;
    }

    private static Map<UUID, Double> parseShares(Map<String, Object> requestedShares) {
        Map<UUID, Double> shares = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : requestedShares.entrySet()) {
            UUID owner = UUID.fromString(entry.getKey().trim());
            if (!(entry.getValue() instanceof Number number)) throw new IllegalArgumentException("分红比例必须是数字。");
            shares.put(owner, number.doubleValue());
        }
        return shares;
    }

    private static Map<UUID, Double> loadDividendShares(Connection conn, String enterpriseId, List<UUID> owners) throws SQLException {
        Map<UUID, Double> shares = new LinkedHashMap<>();
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT owner_uuid, share_percent FROM ks_ent_dividend_shares WHERE enterprise_id=?")) {
            ps.setString(1, enterpriseId);
            ResultSet rs = ps.executeQuery();
            while (rs.next()) {
                try { shares.put(UUID.fromString(rs.getString(1)), rs.getDouble(2)); } catch (IllegalArgumentException ignored) {}
            }
        }
        if (!shares.keySet().equals(new LinkedHashSet<>(owners))
                || Math.abs(shares.values().stream().mapToDouble(Double::doubleValue).sum() - 100.0) > 0.0001) {
            shares.clear();
            double equalShare = 100.0 / owners.size();
            for (UUID owner : owners) shares.put(owner, equalShare);
        }
        return shares;
    }

    private static Map<String, Object> result(boolean success, String message, Map<String, Object> extras) {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("success", success);
        value.put("message", message);
        if (extras != null) value.putAll(extras);
        return value;
    }

    private static String messageOf(Exception exception, String fallback) {
        return exception.getMessage() == null || exception.getMessage().isBlank() ? fallback : exception.getMessage();
    }

    private static String formatAmount(double amount) {
        return String.format(Locale.ROOT, "%.2f", amount);
    }

    // ---- 数据类 ----

    public record Enterprise(
            String id, String name, String type, List<UUID> ownerUuids,
            double registeredCapital, double currentAssets, String region, long createdAt
    ) {}
}
