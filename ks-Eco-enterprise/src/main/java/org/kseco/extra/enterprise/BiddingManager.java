package org.kseco.extra.enterprise;

import org.kseco.KsEco;

import java.sql.*;
import java.util.*;
import org.kseco.extra.enterprise.EnterpriseManager.Enterprise;

/**
 * 招投标管理器。
 *
 * 流程：
 * 1. 官方/企业发布项目（设置预算、预付款比例、违约金比例、截止日期）
 * 2. 企业投标（必须满足资质：注册资本 ≥ 标的 75%）
 * 3. 截止后评标（当前简化：最低价中标）
 * 4. 中标方获得预付款
 * 5. 支持分包（大项目拆分）和拼包（多小企业联合投标）
 */
public final class BiddingManager {

    private final KsEco eco;
    private final EnterpriseManager enterpriseManager;

    public BiddingManager(KsEco eco, EnterpriseManager enterpriseManager) {
        this.eco = eco;
        this.enterpriseManager = enterpriseManager;
    }

    public void init() {}

    /**
     * 发布招标项目。
     */
    public Project publishProject(String title, UUID publisherUuid, String publisherType,
                                   double budget, double prepaymentRatio, double penaltyRatio,
                                   long deadline, String location, boolean allowSubcontract,
                                   boolean allowConsortium) {
        String id = UUID.randomUUID().toString().substring(0, 8);
        long now = System.currentTimeMillis() / 1000;

        try (Connection conn = eco.ksCore().dataStore().getConnection()) {
            if (conn == null) return null;
            try (PreparedStatement ps = conn.prepareStatement(
                    "INSERT INTO ks_ent_projects (id, title, publisher_uuid, publisher_type, " +
                    "budget, prepayment_ratio, penalty_ratio, deadline, location, " +
                    "allow_subcontract, allow_consortium, created_at) " +
                    "VALUES (?,?,?,?,?,?,?,?,?,?,?,?)")) {
                ps.setString(1, id);
                ps.setString(2, title);
                ps.setString(3, publisherUuid.toString());
                ps.setString(4, publisherType);
                ps.setDouble(5, budget);
                ps.setDouble(6, prepaymentRatio);
                ps.setDouble(7, penaltyRatio);
                ps.setLong(8, deadline);
                ps.setString(9, location);
                ps.setInt(10, allowSubcontract ? 1 : 0);
                ps.setInt(11, allowConsortium ? 1 : 0);
                ps.setLong(12, now);
                ps.executeUpdate();
            }
            return new Project(id, title, publisherUuid, publisherType, budget,
                    prepaymentRatio, penaltyRatio, deadline, location,
                    allowSubcontract, allowConsortium, "OPEN", now);
        } catch (SQLException e) {
            eco.getLogger().warning("[招投标] 发布项目失败: " + e.getMessage());
            return null;
        }
    }

    /**
     * 企业投标。
     */
    public Bid submitBid(String projectId, String enterpriseId, double bidAmount,
                          boolean isConsortium, List<String> consortiumMembers) {
        // 资质校验：注册资本 ≥ 标的 75%
        var ent = enterpriseManager.getEnterprise(enterpriseId);
        if (ent == null) return null;

        // 获取项目预算
        Project project = getProject(projectId);
        if (project == null || !"OPEN".equals(project.status())) return null;

        if (ent.registeredCapital() < project.budget() * 0.75) {
            return null; // 资质不足
        }

        String id = UUID.randomUUID().toString().substring(0, 8);
        long now = System.currentTimeMillis() / 1000;

        try (Connection conn = eco.ksCore().dataStore().getConnection()) {
            if (conn == null) return null;
            try (PreparedStatement ps = conn.prepareStatement(
                    "INSERT INTO ks_ent_bids (id, project_id, enterprise_id, bid_amount, " +
                    "is_consortium, consortium_members, submitted_at) VALUES (?,?,?,?,?,?,?)")) {
                ps.setString(1, id);
                ps.setString(2, projectId);
                ps.setString(3, enterpriseId);
                ps.setDouble(4, bidAmount);
                ps.setInt(5, isConsortium ? 1 : 0);
                ps.setString(6, String.join(",", consortiumMembers != null ? consortiumMembers : List.of()));
                ps.setLong(7, now);
                ps.executeUpdate();
            }
            return new Bid(id, projectId, enterpriseId, bidAmount, isConsortium, "PENDING", now);
        } catch (SQLException e) {
            eco.getLogger().warning("[招投标] 投标失败: " + e.getMessage());
            return null;
        }
    }

    /**
     * 评标/中标：基础规则仍是价低者中标，但拥有工业地块的企业享受"招投标信誉加成"——
     * 报价按 (1 - industry_bidding_reputation_bonus_pct) 打折后参与排名（只影响排名，不影响实际中标价/预付款）。
     * 招投标本身是纯数据库/GUI 操作，没有物理坐标，因此这里是地块福利系统里唯一的"纯所有权判定"场景。
     */
    public Bid awardProject(String projectId) {
        try (Connection conn = eco.ksCore().dataStore().getConnection()) {
            if (conn == null) return null;

            // 取出全部待评标投标，逐条计入工业地块信誉加成后按 effectiveAmount 最低者中标
            List<Bid> pending = new ArrayList<>();
            try (PreparedStatement ps = conn.prepareStatement(
                    "SELECT * FROM ks_ent_bids WHERE project_id=? AND status='PENDING'")) {
                ps.setString(1, projectId);
                ResultSet rs = ps.executeQuery();
                while (rs.next()) {
                    pending.add(new Bid(
                            rs.getString("id"), rs.getString("project_id"),
                            rs.getString("enterprise_id"), rs.getDouble("bid_amount"),
                            rs.getInt("is_consortium") == 1, "PENDING",
                            rs.getLong("submitted_at")));
                }
            }
            if (pending.isEmpty()) return null;

            double reputationBonusPct = industryReputationBonusPct();
            Bid bestBid = null;
            double bestEffectiveAmount = Double.MAX_VALUE;
            for (Bid b : pending) {
                double effectiveAmount = b.bidAmount();
                if (reputationBonusPct > 0 && enterpriseOwnsIndustrialPlot(b.enterpriseId())) {
                    effectiveAmount *= (1 - reputationBonusPct);
                }
                if (effectiveAmount < bestEffectiveAmount) {
                    bestEffectiveAmount = effectiveAmount;
                    bestBid = b;
                }
            }
            if (bestBid == null) return null;

            // 先条件更新项目状态（仅 OPEN 可评标）：不加这个检查的话，已评标项目可以
            // 反复调用本方法，每次给剩余 PENDING 投标发一份预付款。
            try (PreparedStatement ps = conn.prepareStatement(
                    "UPDATE ks_ent_projects SET status='AWARDED' WHERE id=? AND status='OPEN'")) {
                ps.setString(1, projectId);
                if (ps.executeUpdate() == 0) return null; // 已评标/已关闭
            }
            // 更新投标状态
            try (PreparedStatement ps = conn.prepareStatement(
                    "UPDATE ks_ent_bids SET status='AWARDED' WHERE id=?")) {
                ps.setString(1, bestBid.id());
                ps.executeUpdate();
            }

            // 发放预付款给中标企业
            Project project = getProject(projectId);
            if (project != null) {
                double prepayment = project.budget() * project.prepaymentRatio();
                Enterprise ent = enterpriseManager.getEnterprise(bestBid.enterpriseId());
                if (ent != null) {
                    for (UUID owner : ent.ownerUuids()) {
                        var player = org.bukkit.Bukkit.getOfflinePlayer(owner);
                        eco.vaultHook().deposit(player, prepayment / ent.ownerUuids().size());
                    }
                }
            }

            return bestBid;
        } catch (SQLException e) {
            eco.getLogger().warning("[招投标] 评标失败: " + e.getMessage());
            return null;
        }
    }

    /** 招投标信誉加成比例（industry_bidding_reputation_bonus_pct，地块福利系统管理员可调，缺省 5%）。 */
    private double industryReputationBonusPct() {
        Object pct = callLandPerk("getPerkValue", new Class<?>[]{String.class, double.class},
                "industry_bidding_reputation_bonus_pct", 0.05);
        return pct instanceof Number n ? n.doubleValue() : 0.05;
    }

    private boolean enterpriseOwnsIndustrialPlot(String enterpriseId) {
        Object owns = callLandPerk("enterpriseOwnsZoneType", new Class<?>[]{String.class, String.class},
                enterpriseId, "INDUSTRIAL");
        return Boolean.TRUE.equals(owns);
    }

    /** 反射调用 ks-eco-realestate 的 LandPerkManager（ks-Eco-enterprise 对该模块没有编译期依赖）。 */
    private Object callLandPerk(String method, Class<?>[] argTypes, Object... args) {
        if (eco.extraModuleLoader() == null) return null;
        Object module = eco.extraModuleLoader().getModule("ks-eco-realestate");
        if (module == null) return null;
        try {
            Object manager = module.getClass().getMethod("landPerkManager").invoke(module);
            if (manager == null) return null;
            return manager.getClass().getMethod(method, argTypes).invoke(manager, args);
        } catch (Exception e) {
            eco.getLogger().warning("[招投标] 反射调用地块福利模块失败(" + method + "): " + e.getMessage());
            return null;
        }
    }

    private Project getProject(String id) {
        try (Connection conn = eco.ksCore().dataStore().getConnection()) {
            if (conn == null) return null;
            try (PreparedStatement ps = conn.prepareStatement(
                    "SELECT * FROM ks_ent_projects WHERE id=?")) {
                ps.setString(1, id);
                ResultSet rs = ps.executeQuery();
                if (rs.next()) {
                    return new Project(
                            rs.getString("id"), rs.getString("title"),
                            UUID.fromString(rs.getString("publisher_uuid")),
                            rs.getString("publisher_type"), rs.getDouble("budget"),
                            rs.getDouble("prepayment_ratio"), rs.getDouble("penalty_ratio"),
                            rs.getLong("deadline"), rs.getString("location"),
                            rs.getInt("allow_subcontract") == 1,
                            rs.getInt("allow_consortium") == 1,
                            rs.getString("status"), rs.getLong("created_at"));
                }
            }
        } catch (SQLException e) {
            eco.getLogger().warning("[招投标] 查询项目失败: " + e.getMessage());
        }
        return null;
    }

    // ---- 数据类 ----

    public record Project(String id, String title, UUID publisherUuid, String publisherType,
                          double budget, double prepaymentRatio, double penaltyRatio,
                          long deadline, String location, boolean allowSubcontract,
                          boolean allowConsortium, String status, long createdAt) {}

    public record Bid(String id, String projectId, String enterpriseId,
                      double bidAmount, boolean isConsortium, String status, long submittedAt) {}
}
