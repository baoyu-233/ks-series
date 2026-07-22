package org.kseco.extra.bank;

import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LoanRepaymentSettlementStoreTest {

    @Test
    void chargedRepaymentCreditsBankAndReducesLoanExactlyOnce() throws Exception {
        try (Connection connection = database()) {
            UUID borrower = UUID.randomUUID();
            insertLoan(connection, "LOAN-1", borrower, 100);
            LoanRepaymentSettlementStore.Settlement settlement =
                    LoanRepaymentSettlementStore.prepare(connection, "LOAN-1", borrower, 40, 10);

            assertNotNull(settlement);
            assertTrue(LoanRepaymentSettlementStore.claimCharge(connection, settlement.id(), 11));
            assertTrue(LoanRepaymentSettlementStore.markCharged(connection, settlement.id(), 12));
            assertTrue(LoanRepaymentSettlementStore.finalizeCharged(connection, settlement, 13));
            assertTrue(LoanRepaymentSettlementStore.finalizeCharged(connection, settlement, 14));

            assertEquals(60, loanRemaining(connection, "LOAN-1"), 0.000_001);
            assertEquals(140, bankAssets(connection), 0.000_001);
            assertNull(loanMarker(connection, "LOAN-1"));
            assertEquals(LoanRepaymentSettlementStore.FINALIZED,
                    LoanRepaymentSettlementStore.find(connection, settlement.id()).status());
        }
    }

    @Test
    void changedLoanSnapshotQueuesRefundWithoutCreditingBank() throws Exception {
        try (Connection connection = database()) {
            UUID borrower = UUID.randomUUID();
            insertLoan(connection, "LOAN-1", borrower, 100);
            LoanRepaymentSettlementStore.Settlement settlement =
                    LoanRepaymentSettlementStore.prepare(connection, "LOAN-1", borrower, 40, 10);
            assertTrue(LoanRepaymentSettlementStore.claimCharge(connection, settlement.id(), 11));
            assertTrue(LoanRepaymentSettlementStore.markCharged(connection, settlement.id(), 12));
            connection.createStatement().executeUpdate(
                    "UPDATE ks_bank_loans SET remaining=90 WHERE id='LOAN-1'");

            assertFalse(LoanRepaymentSettlementStore.finalizeCharged(connection, settlement, 13));
            assertTrue(LoanRepaymentSettlementStore.queueRefund(
                    connection, settlement.id(), "snapshot changed", 14));
            assertTrue(LoanRepaymentSettlementStore.claimRefund(connection, settlement.id(), 15));
            assertTrue(LoanRepaymentSettlementStore.finishRefund(
                    connection, settlement.id(), true, "", 16));

            assertEquals(90, loanRemaining(connection, "LOAN-1"), 0.000_001);
            assertEquals(100, bankAssets(connection), 0.000_001);
            assertNull(loanMarker(connection, "LOAN-1"));
        }
    }

    @Test
    void activeSettlementBlocksConcurrentRepaymentPreparation() throws Exception {
        try (Connection connection = database()) {
            UUID borrower = UUID.randomUUID();
            insertLoan(connection, "LOAN-1", borrower, 100);

            assertNotNull(LoanRepaymentSettlementStore.prepare(
                    connection, "LOAN-1", borrower, 40, 10));
            assertNull(LoanRepaymentSettlementStore.prepare(
                    connection, "LOAN-1", borrower, 20, 11));
        }
    }

    @Test
    void startupRecoveryOnlyMarksUnknownExternalCallsForReview() throws Exception {
        try (Connection connection = database()) {
            UUID borrower = UUID.randomUUID();
            insertLoan(connection, "LOAN-1", borrower, 100);
            insertLoan(connection, "LOAN-2", borrower, 100);
            LoanRepaymentSettlementStore.Settlement charge =
                    LoanRepaymentSettlementStore.prepare(connection, "LOAN-1", borrower, 10, 10);
            LoanRepaymentSettlementStore.Settlement refund =
                    LoanRepaymentSettlementStore.prepare(connection, "LOAN-2", borrower, 10, 10);
            assertTrue(LoanRepaymentSettlementStore.claimCharge(connection, charge.id(), 11));
            assertTrue(LoanRepaymentSettlementStore.claimCharge(connection, refund.id(), 11));
            assertTrue(LoanRepaymentSettlementStore.markCharged(connection, refund.id(), 12));
            assertTrue(LoanRepaymentSettlementStore.queueRefund(connection, refund.id(), "changed", 13));
            assertTrue(LoanRepaymentSettlementStore.claimRefund(connection, refund.id(), 14));

            assertEquals(2, LoanRepaymentSettlementStore.recoverUnknownExternalCalls(connection, 20));
            assertEquals(LoanRepaymentSettlementStore.CHARGE_CLAIMED,
                    LoanRepaymentSettlementStore.find(connection, charge.id()).reviewStage());
            assertEquals(LoanRepaymentSettlementStore.REFUND_CLAIMED,
                    LoanRepaymentSettlementStore.find(connection, refund.id()).reviewStage());
        }
    }

    @Test
    void untouchedReadySettlementCanBeCancelledSafely() throws Exception {
        try (Connection connection = database()) {
            UUID borrower = UUID.randomUUID();
            insertLoan(connection, "LOAN-1", borrower, 100);
            LoanRepaymentSettlementStore.Settlement settlement =
                    LoanRepaymentSettlementStore.prepare(connection, "LOAN-1", borrower, 10, 10);

            assertTrue(LoanRepaymentSettlementStore.cancelReady(
                    connection, settlement, "no wallet call", 11));
            assertNull(loanMarker(connection, "LOAN-1"));
            assertEquals(LoanRepaymentSettlementStore.COMPENSATED,
                    LoanRepaymentSettlementStore.find(connection, settlement.id()).status());
        }
    }

    @Test
    void reviewResolutionRequiresExactStage() throws Exception {
        try (Connection connection = database()) {
            UUID borrower = UUID.randomUUID();
            insertLoan(connection, "LOAN-1", borrower, 100);
            LoanRepaymentSettlementStore.Settlement settlement =
                    LoanRepaymentSettlementStore.prepare(connection, "LOAN-1", borrower, 10, 10);
            assertTrue(LoanRepaymentSettlementStore.claimCharge(connection, settlement.id(), 11));
            assertTrue(LoanRepaymentSettlementStore.markReview(connection, settlement.id(),
                    LoanRepaymentSettlementStore.CHARGE_CLAIMED,
                    LoanRepaymentSettlementStore.CHARGE_CLAIMED, "unknown", 12));

            assertFalse(LoanRepaymentSettlementStore.resolveReview(connection, settlement.id(),
                    LoanRepaymentSettlementStore.REFUND_CLAIMED,
                    LoanRepaymentSettlementStore.CHARGED, "wrong stage", 13));
            assertTrue(LoanRepaymentSettlementStore.resolveReview(connection, settlement.id(),
                    LoanRepaymentSettlementStore.CHARGE_CLAIMED,
                    LoanRepaymentSettlementStore.CHARGED, "confirmed", 14));
            assertEquals(LoanRepaymentSettlementStore.CHARGED,
                    LoanRepaymentSettlementStore.find(connection, settlement.id()).status());
        }
    }

    @Test
    void confirmedFailedChargeCompensatesAndReleasesLoanMarkerAtomically() throws Exception {
        try (Connection connection = database()) {
            UUID borrower = UUID.randomUUID();
            insertLoan(connection, "LOAN-1", borrower, 100);
            LoanRepaymentSettlementStore.Settlement settlement =
                    LoanRepaymentSettlementStore.prepare(connection, "LOAN-1", borrower, 10, 10);
            assertTrue(LoanRepaymentSettlementStore.claimCharge(connection, settlement.id(), 11));
            assertTrue(LoanRepaymentSettlementStore.markReview(connection, settlement.id(),
                    LoanRepaymentSettlementStore.CHARGE_CLAIMED,
                    LoanRepaymentSettlementStore.CHARGE_CLAIMED, "unknown", 12));

            assertFalse(LoanRepaymentSettlementStore.confirmCompensatedReview(connection, settlement,
                    LoanRepaymentSettlementStore.REFUND_CLAIMED, "wrong stage", 13));
            assertNotNull(loanMarker(connection, "LOAN-1"));
            assertTrue(LoanRepaymentSettlementStore.confirmCompensatedReview(connection, settlement,
                    LoanRepaymentSettlementStore.CHARGE_CLAIMED, "not charged", 14));
            assertNull(loanMarker(connection, "LOAN-1"));
            assertEquals(LoanRepaymentSettlementStore.COMPENSATED,
                    LoanRepaymentSettlementStore.find(connection, settlement.id()).status());
        }
    }

    private static Connection database() throws Exception {
        Class.forName("org.sqlite.JDBC");
        Connection connection = DriverManager.getConnection("jdbc:sqlite::memory:");
        try (Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE ks_bank_banks (id TEXT PRIMARY KEY,total_assets REAL NOT NULL,status TEXT)");
            statement.execute("INSERT INTO ks_bank_banks VALUES ('BANK-1',100,'ACTIVE')");
            statement.execute("CREATE TABLE ks_bank_loans (id TEXT PRIMARY KEY,bank_id TEXT NOT NULL,"
                    + "borrower_uuid TEXT NOT NULL,remaining REAL NOT NULL,status TEXT NOT NULL,paid_at INTEGER,"
                    + "repayment_settlement_id TEXT)");
        }
        LoanRepaymentSettlementStore.initialize(connection);
        return connection;
    }

    private static void insertLoan(Connection connection, String id, UUID borrower, double remaining)
            throws Exception {
        try (var statement = connection.prepareStatement(
                "INSERT INTO ks_bank_loans (id,bank_id,borrower_uuid,remaining,status) "
                        + "VALUES (?,'BANK-1',?,?,'ACTIVE')")) {
            statement.setString(1, id);
            statement.setString(2, borrower.toString());
            statement.setDouble(3, remaining);
            statement.executeUpdate();
        }
    }

    private static double loanRemaining(Connection connection, String id) throws Exception {
        try (var result = connection.createStatement().executeQuery(
                "SELECT remaining FROM ks_bank_loans WHERE id='" + id + "'")) {
            return result.next() ? result.getDouble(1) : -1;
        }
    }

    private static String loanMarker(Connection connection, String id) throws Exception {
        try (var result = connection.createStatement().executeQuery(
                "SELECT repayment_settlement_id FROM ks_bank_loans WHERE id='" + id + "'")) {
            return result.next() ? result.getString(1) : "missing";
        }
    }

    private static double bankAssets(Connection connection) throws Exception {
        try (var result = connection.createStatement().executeQuery(
                "SELECT total_assets FROM ks_bank_banks WHERE id='BANK-1'")) {
            return result.next() ? result.getDouble(1) : -1;
        }
    }
}
