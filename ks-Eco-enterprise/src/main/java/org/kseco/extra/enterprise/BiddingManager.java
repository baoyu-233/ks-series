package org.kseco.extra.enterprise;

import org.kseco.KsEco;
import org.kseco.ProjectBiddingDeadlineStore;

import java.sql.*;
import java.util.*;
import java.util.function.Consumer;

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
    private final Consumer<String> warningSink;

    public BiddingManager(KsEco eco, EnterpriseManager enterpriseManager) {
        this(eco, enterpriseManager,
                message -> eco.getLogger().warning("[招投标] " + message));
    }

    BiddingManager(KsEco eco, EnterpriseManager enterpriseManager, Consumer<String> warningSink) {
        this.eco = eco;
        this.enterpriseManager = enterpriseManager;
        this.warningSink = warningSink;
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
        long now = System.currentTimeMillis() / 1000;
        if (project == null || !"OPEN".equals(project.status())
                || !ProjectBiddingDeadlineStore.acceptsBid(project.deadline(), now)) return null;

        if (ent.registeredCapital() < project.budget() * 0.75) {
            return null; // 资质不足
        }

        String id = UUID.randomUUID().toString().substring(0, 8);
        try (Connection conn = eco.ksCore().dataStore().getConnection()) {
            if (conn == null) return null;
            if (ProjectBiddingDeadlineStore.insertBidIfOpen(conn, id, projectId,
                    enterpriseId, null, "ENTERPRISE", bidAmount, isConsortium,
                    String.join(",", consortiumMembers != null ? consortiumMembers : List.of()), now) != 1) return null;
            return new Bid(id, projectId, enterpriseId, bidAmount, isConsortium, "PENDING", now);
        } catch (SQLException e) {
            eco.getLogger().warning("[招投标] 投标失败: " + e.getMessage());
            return null;
        }
    }

    /**
     * Legacy compatibility entry. Project awards require funded escrow, conditional settlement,
     * and recovery state owned by the current Web award workflow. This old method cannot prove
     * those invariants, so it must never mutate bid/project rows or pay owners through Vault.
     */
    public Bid awardProject(String projectId) {
        warningSink.accept("已拒绝旧评标入口"
                + (projectId == null || projectId.isBlank() ? "" : "，项目=" + projectId)
                + "；该入口没有托管与可恢复结算，必须改走现有 Web 托管评标流程。");
        return null;
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
