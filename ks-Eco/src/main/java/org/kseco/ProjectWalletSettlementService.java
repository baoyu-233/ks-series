package org.kseco;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.UUID;

/** Transactional SQL transitions around the external personal project wallet calls. */
final class ProjectWalletSettlementService {
    private ProjectWalletSettlementService() { }

    static ProjectWalletSettlementStore.Settlement prepareDirectAward(
            Connection connection, String projectId, String bidId, UUID playerUuid,
            double prepayment, long now) throws SQLException {
        String id = "PWS-" + UUID.randomUUID();
        if (ProjectBiddingDeadlineStore.claimProjectForAward(
                connection, projectId, "AWARD_SETTLING", now) != 1
                || updateState(connection, "ks_ent_bids", bidId, "PENDING", "AWARD_SETTLING") != 1) {
            throw new SQLException("project award changed before personal payout claim");
        }
        ProjectWalletSettlementStore.Settlement settlement = new ProjectWalletSettlementStore.Settlement(
                id, ProjectWalletSettlementStore.DIRECT_AWARD, projectId, bidId, playerUuid,
                0.0d, prepayment, ProjectWalletSettlementStore.PREPAYMENT_READY, "", "");
        ProjectWalletSettlementStore.insert(connection, settlement, now);
        return settlement;
    }

    static ProjectWalletSettlementStore.Settlement prepareDepositAward(
            Connection connection, String projectId, String bidId, UUID playerUuid,
            double deposit, double prepayment, long now) throws SQLException {
        String id = "PWS-" + UUID.randomUUID();
        if (updateState(connection, "ks_ent_projects", projectId, "PENDING_DEPOSIT", "DEPOSIT_SETTLING") != 1
                || updateState(connection, "ks_ent_bids", bidId, "PENDING_DEPOSIT", "DEPOSIT_SETTLING") != 1) {
            throw new SQLException("project deposit changed before personal charge claim");
        }
        ProjectWalletSettlementStore.Settlement settlement = new ProjectWalletSettlementStore.Settlement(
                id, ProjectWalletSettlementStore.DEPOSIT_AWARD, projectId, bidId, playerUuid,
                deposit, prepayment, ProjectWalletSettlementStore.DEPOSIT_CHARGE_READY, "", "");
        ProjectWalletSettlementStore.insert(connection, settlement, now);
        return settlement;
    }

    static boolean claimDepositCharge(Connection connection, String id, long now) throws SQLException {
        return ProjectWalletSettlementStore.transition(connection, id,
                ProjectWalletSettlementStore.DEPOSIT_CHARGE_READY,
                ProjectWalletSettlementStore.DEPOSIT_CHARGE_CLAIMED, "", now);
    }

    static boolean claimPrepayment(Connection connection, String id, String expected, long now) throws SQLException {
        if (!ProjectWalletSettlementStore.DEPOSIT_HELD.equals(expected)
                && !ProjectWalletSettlementStore.PREPAYMENT_READY.equals(expected)) return false;
        return ProjectWalletSettlementStore.transition(connection, id, expected,
                ProjectWalletSettlementStore.PREPAYMENT_CLAIMED, "", now);
    }

    static void recordDepositHeld(Connection connection,
                                  ProjectWalletSettlementStore.Settlement settlement, long now) throws SQLException {
        try (var deposit = connection.prepareStatement(
                "INSERT INTO ks_ent_bid_deposits "
                        + "(id,bid_id,project_id,payer_uuid,payer_enterprise_id,amount,status,paid_at) "
                        + "VALUES (?,?,?,?,NULL,?,'HELD',?)")) {
            deposit.setString(1, "PD-" + settlement.id());
            deposit.setString(2, settlement.bidId());
            deposit.setString(3, settlement.projectId());
            deposit.setString(4, settlement.playerUuid().toString());
            deposit.setDouble(5, settlement.depositAmount());
            deposit.setLong(6, now);
            deposit.executeUpdate();
        }
        if (updateState(connection, "ks_ent_projects", settlement.projectId(),
                "DEPOSIT_SETTLING", "PREPAYMENT_SETTLING") != 1
                || updateState(connection, "ks_ent_bids", settlement.bidId(),
                "DEPOSIT_SETTLING", "PREPAYMENT_SETTLING") != 1
                || !ProjectWalletSettlementStore.transition(connection, settlement.id(),
                ProjectWalletSettlementStore.DEPOSIT_CHARGE_CLAIMED,
                ProjectWalletSettlementStore.DEPOSIT_HELD, "", now)) {
            throw new SQLException("personal project deposit hold changed concurrently");
        }
    }

    static void finalizePayout(Connection connection,
                               ProjectWalletSettlementStore.Settlement settlement, long now) throws SQLException {
        if (settlement.prepaymentAmount() > 0.0d) {
            try (var escrow = connection.prepareStatement(
                    "UPDATE ks_ent_project_escrow SET remaining=remaining-? "
                            + "WHERE project_id=? AND remaining>=?")) {
                escrow.setDouble(1, settlement.prepaymentAmount());
                escrow.setString(2, settlement.projectId());
                escrow.setDouble(3, settlement.prepaymentAmount());
                if (escrow.executeUpdate() != 1) throw new SQLException("project escrow no longer funds prepayment");
            }
        }
        String projectExpected = ProjectWalletSettlementStore.DIRECT_AWARD.equals(settlement.kind())
                ? "AWARD_SETTLING" : "PREPAYMENT_SETTLING";
        String bidExpected = projectExpected;
        if (updateState(connection, "ks_ent_projects", settlement.projectId(), projectExpected, "AWARDED") != 1
                || updateState(connection, "ks_ent_bids", settlement.bidId(), bidExpected, "AWARDED") != 1) {
            throw new SQLException("project changed before personal payout finalization");
        }
        try (var reject = connection.prepareStatement(
                "UPDATE ks_ent_bids SET status='REJECTED' WHERE project_id=? AND id<>? AND status='PENDING'")) {
            reject.setString(1, settlement.projectId());
            reject.setString(2, settlement.bidId());
            reject.executeUpdate();
        }
        if (!ProjectWalletSettlementStore.transition(connection, settlement.id(),
                ProjectWalletSettlementStore.PREPAYMENT_CLAIMED,
                ProjectWalletSettlementStore.FINALIZED, "", now)) {
            throw new SQLException("personal payout journal changed before finalization");
        }
    }

    static void finalizeDepositWithoutPayout(Connection connection,
                                             ProjectWalletSettlementStore.Settlement settlement, long now)
            throws SQLException {
        if (settlement.prepaymentAmount() > 0.0d) throw new IllegalArgumentException("prepayment is not zero");
        if (updateState(connection, "ks_ent_projects", settlement.projectId(),
                "PREPAYMENT_SETTLING", "AWARDED") != 1
                || updateState(connection, "ks_ent_bids", settlement.bidId(),
                "PREPAYMENT_SETTLING", "AWARDED") != 1) {
            throw new SQLException("project changed before deposit-only finalization");
        }
        try (var reject = connection.prepareStatement(
                "UPDATE ks_ent_bids SET status='REJECTED' WHERE project_id=? AND id<>? AND status='PENDING'")) {
            reject.setString(1, settlement.projectId());
            reject.setString(2, settlement.bidId());
            reject.executeUpdate();
        }
        if (!ProjectWalletSettlementStore.transition(connection, settlement.id(),
                ProjectWalletSettlementStore.DEPOSIT_HELD,
                ProjectWalletSettlementStore.FINALIZED, "", now)) {
            throw new SQLException("deposit-only journal changed before finalization");
        }
    }

    static void rollbackRejectedExternalCall(Connection connection,
                                             ProjectWalletSettlementStore.Settlement settlement,
                                             String expectedJournalState, String error, long now) throws SQLException {
        String projectState = ProjectWalletSettlementStore.DIRECT_AWARD.equals(settlement.kind())
                ? "AWARD_SETTLING" : "DEPOSIT_SETTLING";
        String projectRestored = ProjectWalletSettlementStore.DIRECT_AWARD.equals(settlement.kind())
                ? "OPEN" : "PENDING_DEPOSIT";
        String bidRestored = ProjectWalletSettlementStore.DIRECT_AWARD.equals(settlement.kind())
                ? "PENDING" : "PENDING_DEPOSIT";
        if (updateState(connection, "ks_ent_projects", settlement.projectId(), projectState, projectRestored) != 1
                || updateState(connection, "ks_ent_bids", settlement.bidId(), projectState, bidRestored) != 1
                || !ProjectWalletSettlementStore.transition(connection, settlement.id(), expectedJournalState,
                ProjectWalletSettlementStore.COMPENSATED, error, now)) {
            throw new SQLException("personal project rollback changed concurrently");
        }
    }

    private static int updateState(Connection connection, String table, String id,
                                   String expected, String next) throws SQLException {
        try (var statement = connection.prepareStatement(
                "UPDATE " + table + " SET status=? WHERE id=? AND status=?")) {
            statement.setString(1, next);
            statement.setString(2, id);
            statement.setString(3, expected);
            return statement.executeUpdate();
        }
    }
}
