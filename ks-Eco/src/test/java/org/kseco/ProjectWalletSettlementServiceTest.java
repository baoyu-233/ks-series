package org.kseco;

import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DriverManager;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class ProjectWalletSettlementServiceTest {
    @Test
    void directPayoutFinalizesProjectAndEscrowTogether() throws Exception {
        try (Connection connection = database()) {
            connection.setAutoCommit(false);
            UUID player = UUID.randomUUID();
            ProjectWalletSettlementStore.Settlement settlement =
                    ProjectWalletSettlementService.prepareDirectAward(
                            connection, "project", "bid", player, 300.0d, 1L);
            assertTrue(ProjectWalletSettlementService.claimPrepayment(connection, settlement.id(),
                    ProjectWalletSettlementStore.PREPAYMENT_READY, 2L));
            ProjectWalletSettlementService.finalizePayout(connection, settlement, 3L);
            connection.commit();

            assertEquals("AWARDED", scalar(connection, "SELECT status FROM ks_ent_projects WHERE id='project'"));
            assertEquals("AWARDED", scalar(connection, "SELECT status FROM ks_ent_bids WHERE id='bid'"));
            assertEquals(700.0d, number(connection,
                    "SELECT remaining FROM ks_ent_project_escrow WHERE project_id='project'"));
        }
    }

    @Test
    void rejectedDepositChargeRestoresPendingDeposit() throws Exception {
        try (Connection connection = database()) {
            connection.createStatement().executeUpdate("UPDATE ks_ent_projects SET status='PENDING_DEPOSIT'");
            connection.createStatement().executeUpdate("UPDATE ks_ent_bids SET status='PENDING_DEPOSIT'");
            connection.setAutoCommit(false);
            ProjectWalletSettlementStore.Settlement settlement =
                    ProjectWalletSettlementService.prepareDepositAward(connection, "project", "bid",
                            UUID.randomUUID(), 100.0d, 300.0d, 1L);
            assertTrue(ProjectWalletSettlementService.claimDepositCharge(connection, settlement.id(), 2L));
            ProjectWalletSettlementService.rollbackRejectedExternalCall(connection, settlement,
                    ProjectWalletSettlementStore.DEPOSIT_CHARGE_CLAIMED, "rejected", 3L);
            connection.commit();

            assertEquals("PENDING_DEPOSIT", scalar(connection,
                    "SELECT status FROM ks_ent_projects WHERE id='project'"));
            assertEquals(ProjectWalletSettlementStore.COMPENSATED,
                    ProjectWalletSettlementStore.find(connection, settlement.id()).status());
        }
    }

    @Test
    void zeroPrepaymentDepositFinalizesWithoutConsumingEscrow() throws Exception {
        try (Connection connection = depositDatabase()) {
            ProjectWalletSettlementStore.Settlement settlement = prepareClaimedDeposit(
                    connection, 100.0d, 0.0d);
            ProjectWalletSettlementService.recordDepositHeld(connection, settlement, 3L);
            ProjectWalletSettlementService.finalizeDepositWithoutPayout(connection, settlement, 4L);
            connection.commit();

            assertEquals("AWARDED", scalar(connection, "SELECT status FROM ks_ent_projects WHERE id='project'"));
            assertEquals("AWARDED", scalar(connection, "SELECT status FROM ks_ent_bids WHERE id='bid'"));
            assertEquals("REJECTED", scalar(connection, "SELECT status FROM ks_ent_bids WHERE id='other'"));
            assertEquals(ProjectWalletSettlementStore.FINALIZED,
                    ProjectWalletSettlementStore.find(connection, settlement.id()).status());
            assertEquals(1000.0d, number(connection,
                    "SELECT remaining FROM ks_ent_project_escrow WHERE project_id='project'"));
            assertEquals(100.0d, number(connection,
                    "SELECT amount FROM ks_ent_bid_deposits WHERE bid_id='bid'"));
        }
    }

    @Test
    void failedPrepaymentAfterDepositKeepsHeldFundsAndBecomesRecoverable() throws Exception {
        try (Connection connection = depositDatabase()) {
            ProjectWalletSettlementStore.Settlement settlement = prepareClaimedDeposit(
                    connection, 100.0d, 300.0d);
            ProjectWalletSettlementService.recordDepositHeld(connection, settlement, 3L);
            assertTrue(ProjectWalletSettlementService.claimPrepayment(connection, settlement.id(),
                    ProjectWalletSettlementStore.DEPOSIT_HELD, 4L));

            assertTrue(ProjectWalletSettlementStore.transition(connection, settlement.id(),
                    ProjectWalletSettlementStore.PREPAYMENT_CLAIMED,
                    ProjectWalletSettlementStore.PREPAYMENT_READY,
                    "prepayment payout rejected", 5L));
            connection.commit();

            assertEquals("PREPAYMENT_SETTLING", scalar(connection,
                    "SELECT status FROM ks_ent_projects WHERE id='project'"));
            assertEquals("HELD", scalar(connection,
                    "SELECT status FROM ks_ent_bid_deposits WHERE bid_id='bid'"));
            assertEquals(1000.0d, number(connection,
                    "SELECT remaining FROM ks_ent_project_escrow WHERE project_id='project'"));
            assertEquals(ProjectWalletSettlementStore.PREPAYMENT_READY,
                    ProjectWalletSettlementStore.find(connection, settlement.id()).status());
            assertEquals(settlement.id(), ProjectWalletSettlementStore.recoverable(connection).get(0).id());
        }
    }

    @Test
    void adminConfirmChargeSucceededRecordsDepositAndContinuesSettlement() throws Exception {
        try (Connection connection = depositDatabase()) {
            ProjectWalletSettlementStore.Settlement settlement = prepareClaimedDeposit(
                    connection, 100.0d, 300.0d);
            moveToReview(connection, settlement, ProjectWalletSettlementStore.DEPOSIT_CHARGE_CLAIMED);

            assertTrue(ProjectWalletSettlementStore.resolveReview(connection, settlement.id(),
                    ProjectWalletSettlementStore.DEPOSIT_CHARGE_CLAIMED,
                    ProjectWalletSettlementStore.DEPOSIT_CHARGE_CLAIMED,
                    "administrator confirmed charge", 4L));
            ProjectWalletSettlementService.recordDepositHeld(connection, settlement, 5L);
            connection.commit();

            assertEquals(ProjectWalletSettlementStore.DEPOSIT_HELD,
                    ProjectWalletSettlementStore.find(connection, settlement.id()).status());
            assertEquals("PREPAYMENT_SETTLING", scalar(connection,
                    "SELECT status FROM ks_ent_projects WHERE id='project'"));
            assertEquals(100.0d, number(connection,
                    "SELECT amount FROM ks_ent_bid_deposits WHERE bid_id='bid'"));
        }
    }

    @Test
    void adminConfirmChargeFailedRestoresBidWithoutRecordingDeposit() throws Exception {
        try (Connection connection = depositDatabase()) {
            ProjectWalletSettlementStore.Settlement settlement = prepareClaimedDeposit(
                    connection, 100.0d, 300.0d);
            moveToReview(connection, settlement, ProjectWalletSettlementStore.DEPOSIT_CHARGE_CLAIMED);

            assertTrue(ProjectWalletSettlementStore.resolveReview(connection, settlement.id(),
                    ProjectWalletSettlementStore.DEPOSIT_CHARGE_CLAIMED,
                    ProjectWalletSettlementStore.DEPOSIT_CHARGE_CLAIMED,
                    "administrator rejected charge", 4L));
            ProjectWalletSettlementService.rollbackRejectedExternalCall(connection, settlement,
                    ProjectWalletSettlementStore.DEPOSIT_CHARGE_CLAIMED,
                    "administrator confirmed charge failed", 5L);
            connection.commit();

            assertEquals("PENDING_DEPOSIT", scalar(connection,
                    "SELECT status FROM ks_ent_projects WHERE id='project'"));
            assertEquals("PENDING_DEPOSIT", scalar(connection,
                    "SELECT status FROM ks_ent_bids WHERE id='bid'"));
            assertEquals(ProjectWalletSettlementStore.COMPENSATED,
                    ProjectWalletSettlementStore.find(connection, settlement.id()).status());
            assertEquals(0.0d, number(connection, "SELECT COUNT(*) FROM ks_ent_bid_deposits"));
        }
    }

    @Test
    void adminConfirmPayoutSucceededFinalizesAwardExactlyOnce() throws Exception {
        try (Connection connection = database()) {
            connection.setAutoCommit(false);
            ProjectWalletSettlementStore.Settlement settlement =
                    ProjectWalletSettlementService.prepareDirectAward(
                            connection, "project", "bid", UUID.randomUUID(), 300.0d, 1L);
            assertTrue(ProjectWalletSettlementService.claimPrepayment(connection, settlement.id(),
                    ProjectWalletSettlementStore.PREPAYMENT_READY, 2L));
            moveToReview(connection, settlement, ProjectWalletSettlementStore.PREPAYMENT_CLAIMED);

            assertTrue(ProjectWalletSettlementStore.resolveReview(connection, settlement.id(),
                    ProjectWalletSettlementStore.PREPAYMENT_CLAIMED,
                    ProjectWalletSettlementStore.PREPAYMENT_CLAIMED,
                    "administrator confirmed payout", 4L));
            ProjectWalletSettlementService.finalizePayout(connection, settlement, 5L);
            connection.commit();

            assertEquals(ProjectWalletSettlementStore.FINALIZED,
                    ProjectWalletSettlementStore.find(connection, settlement.id()).status());
            assertEquals("AWARDED", scalar(connection, "SELECT status FROM ks_ent_projects WHERE id='project'"));
            assertEquals(700.0d, number(connection,
                    "SELECT remaining FROM ks_ent_project_escrow WHERE project_id='project'"));
        }
    }

    @Test
    void adminConfirmDirectPayoutFailedCompensatesAward() throws Exception {
        try (Connection connection = database()) {
            connection.setAutoCommit(false);
            ProjectWalletSettlementStore.Settlement settlement =
                    ProjectWalletSettlementService.prepareDirectAward(
                            connection, "project", "bid", UUID.randomUUID(), 300.0d, 1L);
            assertTrue(ProjectWalletSettlementService.claimPrepayment(connection, settlement.id(),
                    ProjectWalletSettlementStore.PREPAYMENT_READY, 2L));
            moveToReview(connection, settlement, ProjectWalletSettlementStore.PREPAYMENT_CLAIMED);

            assertTrue(ProjectWalletSettlementStore.resolveReview(connection, settlement.id(),
                    ProjectWalletSettlementStore.PREPAYMENT_CLAIMED,
                    ProjectWalletSettlementStore.PREPAYMENT_CLAIMED,
                    "administrator rejected payout", 4L));
            ProjectWalletSettlementService.rollbackRejectedExternalCall(connection, settlement,
                    ProjectWalletSettlementStore.PREPAYMENT_CLAIMED,
                    "administrator confirmed payout failed", 5L);
            connection.commit();

            assertEquals("OPEN", scalar(connection, "SELECT status FROM ks_ent_projects WHERE id='project'"));
            assertEquals("PENDING", scalar(connection, "SELECT status FROM ks_ent_bids WHERE id='bid'"));
            assertEquals(ProjectWalletSettlementStore.COMPENSATED,
                    ProjectWalletSettlementStore.find(connection, settlement.id()).status());
            assertEquals(1000.0d, number(connection,
                    "SELECT remaining FROM ks_ent_project_escrow WHERE project_id='project'"));
        }
    }

    @Test
    void adminConfirmDepositPayoutFailedReturnsToHeldRetryState() throws Exception {
        try (Connection connection = depositDatabase()) {
            ProjectWalletSettlementStore.Settlement settlement = prepareClaimedDeposit(
                    connection, 100.0d, 300.0d);
            ProjectWalletSettlementService.recordDepositHeld(connection, settlement, 3L);
            assertTrue(ProjectWalletSettlementService.claimPrepayment(connection, settlement.id(),
                    ProjectWalletSettlementStore.DEPOSIT_HELD, 4L));
            moveToReview(connection, settlement, ProjectWalletSettlementStore.PREPAYMENT_CLAIMED);

            assertTrue(ProjectWalletSettlementStore.resolveReview(connection, settlement.id(),
                    ProjectWalletSettlementStore.PREPAYMENT_CLAIMED,
                    ProjectWalletSettlementStore.DEPOSIT_HELD,
                    "administrator confirmed payout failed; ready to retry", 6L));
            connection.commit();

            assertEquals(ProjectWalletSettlementStore.DEPOSIT_HELD,
                    ProjectWalletSettlementStore.find(connection, settlement.id()).status());
            assertEquals("PREPAYMENT_SETTLING", scalar(connection,
                    "SELECT status FROM ks_ent_projects WHERE id='project'"));
            assertEquals("HELD", scalar(connection,
                    "SELECT status FROM ks_ent_bid_deposits WHERE bid_id='bid'"));
            assertEquals(1000.0d, number(connection,
                    "SELECT remaining FROM ks_ent_project_escrow WHERE project_id='project'"));
        }
    }

    private static Connection depositDatabase() throws Exception {
        Connection connection = database();
        connection.createStatement().executeUpdate("UPDATE ks_ent_projects SET status='PENDING_DEPOSIT'");
        connection.createStatement().executeUpdate("UPDATE ks_ent_bids SET status='PENDING_DEPOSIT' WHERE id='bid'");
        connection.setAutoCommit(false);
        return connection;
    }

    private static ProjectWalletSettlementStore.Settlement prepareClaimedDeposit(
            Connection connection, double deposit, double prepayment) throws Exception {
        ProjectWalletSettlementStore.Settlement settlement =
                ProjectWalletSettlementService.prepareDepositAward(connection, "project", "bid",
                        UUID.randomUUID(), deposit, prepayment, 1L);
        assertTrue(ProjectWalletSettlementService.claimDepositCharge(connection, settlement.id(), 2L));
        return settlement;
    }

    private static void moveToReview(Connection connection,
                                     ProjectWalletSettlementStore.Settlement settlement,
                                     String claimedStage) throws Exception {
        assertTrue(ProjectWalletSettlementStore.transition(connection, settlement.id(), claimedStage,
                ProjectWalletSettlementStore.REVIEW_REQUIRED, "outcome unknown", 3L));
        ProjectWalletSettlementStore.Settlement review =
                ProjectWalletSettlementStore.find(connection, settlement.id());
        assertEquals(ProjectWalletSettlementStore.REVIEW_REQUIRED, review.status());
        assertEquals(claimedStage, review.reviewStage());
    }

    @Test
    void directAwardCannotClaimProjectBeforeBidDeadline() throws Exception {
        try (Connection connection = database()) {
            connection.createStatement().executeUpdate("UPDATE ks_ent_projects SET deadline=10");
            connection.setAutoCommit(false);

            assertThrows(java.sql.SQLException.class, () ->
                    ProjectWalletSettlementService.prepareDirectAward(
                            connection, "project", "bid", UUID.randomUUID(), 300.0d, 9L));
            connection.rollback();

            assertEquals("OPEN", scalar(connection,
                    "SELECT status FROM ks_ent_projects WHERE id='project'"));
        }
    }

    private static Connection database() throws Exception {
        Connection connection = DriverManager.getConnection("jdbc:sqlite::memory:");
        connection.createStatement().execute("CREATE TABLE ks_ent_projects(id TEXT PRIMARY KEY,status TEXT,deadline INTEGER)");
        connection.createStatement().execute("CREATE TABLE ks_ent_bids(id TEXT PRIMARY KEY,project_id TEXT,status TEXT)");
        connection.createStatement().execute("CREATE TABLE ks_ent_project_escrow(project_id TEXT PRIMARY KEY,remaining REAL)");
        connection.createStatement().execute("CREATE TABLE ks_ent_bid_deposits(id TEXT PRIMARY KEY,bid_id TEXT,project_id TEXT,payer_uuid TEXT,payer_enterprise_id TEXT,amount REAL,status TEXT,paid_at INTEGER)");
        connection.createStatement().execute("INSERT INTO ks_ent_projects VALUES('project','OPEN',0)");
        connection.createStatement().execute("INSERT INTO ks_ent_bids VALUES('bid','project','PENDING')");
        connection.createStatement().execute("INSERT INTO ks_ent_bids VALUES('other','project','PENDING')");
        connection.createStatement().execute("INSERT INTO ks_ent_project_escrow VALUES('project',1000)");
        ProjectWalletSettlementStore.initialize(connection);
        return connection;
    }

    private static String scalar(Connection connection, String sql) throws Exception {
        try (var rows = connection.createStatement().executeQuery(sql)) {
            assertTrue(rows.next());
            return rows.getString(1);
        }
    }

    private static double number(Connection connection, String sql) throws Exception {
        try (var rows = connection.createStatement().executeQuery(sql)) {
            assertTrue(rows.next());
            return rows.getDouble(1);
        }
    }
}
