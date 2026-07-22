package org.kseco.extra.bank;

import org.kseco.database.BusinessSchemaDialect;
import org.kseco.database.DatabaseDialect;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** Durable SQL journal around the external wallet call used for player loan repayment. */
final class LoanRepaymentSettlementStore {

    static final String CHARGE_READY = "CHARGE_READY";
    static final String CHARGE_CLAIMED = "CHARGE_CLAIMED";
    static final String CHARGED = "CHARGED";
    static final String REFUND_READY = "REFUND_READY";
    static final String REFUND_CLAIMED = "REFUND_CLAIMED";
    static final String FINALIZED = "FINALIZED";
    static final String COMPENSATED = "COMPENSATED";
    static final String REVIEW_REQUIRED = "REVIEW_REQUIRED";

    record Settlement(String id, String loanId, UUID borrowerUuid, String bankId,
                      double amount, double expectedRemaining, String status,
                      String reviewStage, String lastError) { }

    private LoanRepaymentSettlementStore() { }

    static void initialize(Connection connection) throws SQLException {
        initialize(connection, BusinessSchemaDialect.detect(connection));
    }

    static void initialize(Connection connection, DatabaseDialect dialect) throws SQLException {
        String number = BusinessSchemaDialect.floatingPointType(dialect);
        try (var statement = connection.createStatement()) {
            statement.execute("""
                    CREATE TABLE IF NOT EXISTS ks_bank_loan_repayment_settlements (
                        id VARCHAR(128) PRIMARY KEY,
                        loan_id VARCHAR(128) NOT NULL,
                        borrower_uuid VARCHAR(36) NOT NULL,
                        bank_id VARCHAR(128) NOT NULL,
                        amount %s NOT NULL,
                        expected_remaining %s NOT NULL,
                        status VARCHAR(32) NOT NULL,
                        review_stage VARCHAR(32) NOT NULL DEFAULT '',
                        last_error VARCHAR(1024) NOT NULL DEFAULT '',
                        created_at BIGINT NOT NULL,
                        updated_at BIGINT NOT NULL
                    )
                    """.formatted(number, number));
        }
    }

    static Settlement prepare(Connection connection, String loanId, UUID borrowerUuid,
                              double requestedAmount, long now) throws SQLException {
        if (loanId == null || loanId.isBlank() || borrowerUuid == null
                || !Double.isFinite(requestedAmount) || requestedAmount <= 0) return null;
        requireAutoCommit(connection);
        connection.setAutoCommit(false);
        try {
            String bankId;
            double remaining;
            String loanStatus;
            String activeSettlement;
            try (var statement = connection.prepareStatement(
                    "SELECT bank_id,remaining,status,repayment_settlement_id FROM ks_bank_loans "
                            + "WHERE id=? AND borrower_uuid=? AND status IN ('ACTIVE','OVERDUE')")) {
                statement.setString(1, loanId);
                statement.setString(2, borrowerUuid.toString());
                try (ResultSet rows = statement.executeQuery()) {
                    if (!rows.next()) {
                        connection.rollback();
                        return null;
                    }
                    bankId = rows.getString(1);
                    remaining = rows.getDouble(2);
                    loanStatus = rows.getString(3);
                    activeSettlement = rows.getString(4);
                }
            }
            if (!Double.isFinite(remaining) || remaining <= 0
                    || (activeSettlement != null && !activeSettlement.isBlank())) {
                connection.rollback();
                return null;
            }
            double amount = Math.min(requestedAmount, remaining);
            String settlementId = "LR-" + UUID.randomUUID();
            try (var statement = connection.prepareStatement("""
                    INSERT INTO ks_bank_loan_repayment_settlements
                    (id,loan_id,borrower_uuid,bank_id,amount,expected_remaining,status,
                     review_stage,last_error,created_at,updated_at)
                    VALUES (?,?,?,?,?,?,?,?,?,?,?)
                    """)) {
                statement.setString(1, settlementId);
                statement.setString(2, loanId);
                statement.setString(3, borrowerUuid.toString());
                statement.setString(4, bankId);
                statement.setDouble(5, amount);
                statement.setDouble(6, remaining);
                statement.setString(7, CHARGE_READY);
                statement.setString(8, "");
                statement.setString(9, "");
                statement.setLong(10, now);
                statement.setLong(11, now);
                statement.executeUpdate();
            }
            try (var statement = connection.prepareStatement(
                    "UPDATE ks_bank_loans SET repayment_settlement_id=? "
                            + "WHERE id=? AND borrower_uuid=? AND remaining=? AND status=? "
                            + "AND (repayment_settlement_id IS NULL OR repayment_settlement_id='')")) {
                statement.setString(1, settlementId);
                statement.setString(2, loanId);
                statement.setString(3, borrowerUuid.toString());
                statement.setDouble(4, remaining);
                statement.setString(5, loanStatus);
                if (statement.executeUpdate() != 1) {
                    connection.rollback();
                    return null;
                }
            }
            connection.commit();
            return new Settlement(settlementId, loanId, borrowerUuid, bankId, amount,
                    remaining, CHARGE_READY, "", "");
        } catch (SQLException | RuntimeException failure) {
            rollback(connection, failure);
            throw failure;
        } finally {
            connection.setAutoCommit(true);
        }
    }

    static boolean claimCharge(Connection connection, String id, long now) throws SQLException {
        return transition(connection, id, CHARGE_READY, CHARGE_CLAIMED, "", "", now);
    }

    static boolean markCharged(Connection connection, String id, long now) throws SQLException {
        return transition(connection, id, CHARGE_CLAIMED, CHARGED, "", "", now);
    }

    static boolean markReview(Connection connection, String id, String expectedStatus,
                              String reviewStage, String error, long now) throws SQLException {
        return transition(connection, id, expectedStatus, REVIEW_REQUIRED, reviewStage, error, now);
    }

    static boolean resolveReview(Connection connection, String id, String expectedReviewStage,
                                 String nextStatus, String error, long now) throws SQLException {
        try (var statement = connection.prepareStatement(
                "UPDATE ks_bank_loan_repayment_settlements "
                        + "SET status=?,review_stage='',last_error=?,updated_at=? "
                        + "WHERE id=? AND status=? AND review_stage=?")) {
            statement.setString(1, nextStatus);
            statement.setString(2, error == null ? "" : error);
            statement.setLong(3, now);
            statement.setString(4, id);
            statement.setString(5, REVIEW_REQUIRED);
            statement.setString(6, expectedReviewStage);
            return statement.executeUpdate() == 1;
        }
    }

    static boolean confirmCompensatedReview(Connection connection, Settlement settlement,
                                             String expectedReviewStage, String error, long now)
            throws SQLException {
        requireAutoCommit(connection);
        connection.setAutoCommit(false);
        try {
            if (!resolveReview(connection, settlement.id(), expectedReviewStage,
                    COMPENSATED, error, now)) {
                connection.rollback();
                return false;
            }
            clearLoanMarker(connection, settlement);
            connection.commit();
            return true;
        } catch (SQLException | RuntimeException failure) {
            rollback(connection, failure);
            throw failure;
        } finally {
            connection.setAutoCommit(true);
        }
    }

    static boolean cancelReady(Connection connection, Settlement settlement, String error, long now)
            throws SQLException {
        requireAutoCommit(connection);
        connection.setAutoCommit(false);
        try {
            if (!transition(connection, settlement.id(), CHARGE_READY, COMPENSATED, "", error, now)) {
                connection.rollback();
                return false;
            }
            clearLoanMarker(connection, settlement);
            connection.commit();
            return true;
        } catch (SQLException | RuntimeException failure) {
            rollback(connection, failure);
            throw failure;
        } finally {
            connection.setAutoCommit(true);
        }
    }

    /** Applies the loan and bank ledger mutation exactly once. False means the loan snapshot changed. */
    static boolean finalizeCharged(Connection connection, Settlement settlement, long now) throws SQLException {
        requireAutoCommit(connection);
        connection.setAutoCommit(false);
        try {
            Settlement current = find(connection, settlement.id());
            if (current == null || !CHARGED.equals(current.status())) {
                connection.rollback();
                return current != null && FINALIZED.equals(current.status());
            }
            double remaining;
            String loanStatus;
            try (var statement = connection.prepareStatement(
                    "SELECT remaining,status FROM ks_bank_loans WHERE id=? AND borrower_uuid=? "
                            + "AND repayment_settlement_id=?")) {
                statement.setString(1, current.loanId());
                statement.setString(2, current.borrowerUuid().toString());
                statement.setString(3, current.id());
                try (ResultSet rows = statement.executeQuery()) {
                    if (!rows.next()) {
                        connection.rollback();
                        return false;
                    }
                    remaining = rows.getDouble(1);
                    loanStatus = rows.getString(2);
                }
            }
            if (Double.compare(remaining, current.expectedRemaining()) != 0
                    || !("ACTIVE".equals(loanStatus) || "OVERDUE".equals(loanStatus))) {
                connection.rollback();
                return false;
            }
            double nextRemaining = Math.max(0, remaining - current.amount());
            String nextStatus = nextRemaining <= 0.01 ? "PAID" : loanStatus;
            if ("PAID".equals(nextStatus)) nextRemaining = 0;
            try (var statement = connection.prepareStatement(
                    "UPDATE ks_bank_loans SET remaining=?,status=?,"
                            + "paid_at=CASE WHEN ?='PAID' THEN ? ELSE paid_at END,repayment_settlement_id=NULL "
                            + "WHERE id=? AND borrower_uuid=? AND repayment_settlement_id=? "
                            + "AND remaining=? AND status=?")) {
                statement.setDouble(1, nextRemaining);
                statement.setString(2, nextStatus);
                statement.setString(3, nextStatus);
                statement.setLong(4, now);
                statement.setString(5, current.loanId());
                statement.setString(6, current.borrowerUuid().toString());
                statement.setString(7, current.id());
                statement.setDouble(8, remaining);
                statement.setString(9, loanStatus);
                if (statement.executeUpdate() != 1) {
                    connection.rollback();
                    return false;
                }
            }
            try (var statement = connection.prepareStatement(
                    "UPDATE ks_bank_banks SET total_assets=total_assets+? WHERE id=?")) {
                statement.setDouble(1, current.amount());
                statement.setString(2, current.bankId());
                if (statement.executeUpdate() != 1) throw new SQLException("Repayment bank disappeared");
            }
            if ("PAID".equals(nextStatus)) {
                PlayerLoanCollateralStore.releaseLoan(connection, current.loanId(), now);
            }
            if (!transition(connection, current.id(), CHARGED, FINALIZED, "", "", now)) {
                throw new SQLException("Repayment settlement finalization changed");
            }
            connection.commit();
            return true;
        } catch (SQLException | RuntimeException failure) {
            rollback(connection, failure);
            throw failure;
        } finally {
            connection.setAutoCommit(true);
        }
    }

    static boolean queueRefund(Connection connection, String id, String error, long now) throws SQLException {
        return transition(connection, id, CHARGED, REFUND_READY, "", error, now);
    }

    static boolean claimRefund(Connection connection, String id, long now) throws SQLException {
        return transition(connection, id, REFUND_READY, REFUND_CLAIMED, "", "", now);
    }

    static boolean finishRefund(Connection connection, String id, boolean refunded,
                                String error, long now) throws SQLException {
        requireAutoCommit(connection);
        connection.setAutoCommit(false);
        try {
            Settlement settlement = find(connection, id);
            if (settlement == null || !REFUND_CLAIMED.equals(settlement.status())) {
                connection.rollback();
                return false;
            }
            String next = refunded ? COMPENSATED : REVIEW_REQUIRED;
            String reviewStage = refunded ? "" : REFUND_CLAIMED;
            if (!transition(connection, id, REFUND_CLAIMED, next, reviewStage, error, now)) {
                connection.rollback();
                return false;
            }
            if (refunded) clearLoanMarker(connection, settlement);
            connection.commit();
            return true;
        } catch (SQLException | RuntimeException failure) {
            rollback(connection, failure);
            throw failure;
        } finally {
            connection.setAutoCommit(true);
        }
    }

    static int recoverUnknownExternalCalls(Connection connection, long now) throws SQLException {
        try (var statement = connection.prepareStatement(
                "UPDATE ks_bank_loan_repayment_settlements "
                        + "SET status=?,review_stage=status,last_error=?,updated_at=? "
                        + "WHERE status IN (?,?)")) {
            statement.setString(1, REVIEW_REQUIRED);
            statement.setString(2, "external wallet outcome unknown after restart");
            statement.setLong(3, now);
            statement.setString(4, CHARGE_CLAIMED);
            statement.setString(5, REFUND_CLAIMED);
            return statement.executeUpdate();
        }
    }

    static List<Settlement> list(Connection connection, String status) throws SQLException {
        List<Settlement> settlements = new ArrayList<>();
        try (var statement = connection.prepareStatement(
                "SELECT id,loan_id,borrower_uuid,bank_id,amount,expected_remaining,status,review_stage,last_error "
                        + "FROM ks_bank_loan_repayment_settlements WHERE status=? ORDER BY created_at,id")) {
            statement.setString(1, status);
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) settlements.add(read(rows));
            }
        }
        return List.copyOf(settlements);
    }

    static Settlement find(Connection connection, String id) throws SQLException {
        try (var statement = connection.prepareStatement(
                "SELECT id,loan_id,borrower_uuid,bank_id,amount,expected_remaining,status,review_stage,last_error "
                        + "FROM ks_bank_loan_repayment_settlements WHERE id=?")) {
            statement.setString(1, id);
            try (ResultSet rows = statement.executeQuery()) {
                return rows.next() ? read(rows) : null;
            }
        }
    }

    private static Settlement read(ResultSet rows) throws SQLException {
        return new Settlement(rows.getString(1), rows.getString(2), UUID.fromString(rows.getString(3)),
                rows.getString(4), rows.getDouble(5), rows.getDouble(6), rows.getString(7),
                rows.getString(8), rows.getString(9));
    }

    private static void clearLoanMarker(Connection connection, Settlement settlement) throws SQLException {
        try (var statement = connection.prepareStatement(
                "UPDATE ks_bank_loans SET repayment_settlement_id=NULL "
                        + "WHERE id=? AND borrower_uuid=? AND repayment_settlement_id=?")) {
            statement.setString(1, settlement.loanId());
            statement.setString(2, settlement.borrowerUuid().toString());
            statement.setString(3, settlement.id());
            if (statement.executeUpdate() != 1) throw new SQLException("Repayment loan marker changed");
        }
    }

    private static boolean transition(Connection connection, String id, String expected, String next,
                                      String reviewStage, String error, long now) throws SQLException {
        try (var statement = connection.prepareStatement(
                "UPDATE ks_bank_loan_repayment_settlements "
                        + "SET status=?,review_stage=?,last_error=?,updated_at=? WHERE id=? AND status=?")) {
            statement.setString(1, next);
            statement.setString(2, reviewStage == null ? "" : reviewStage);
            statement.setString(3, error == null ? "" : error);
            statement.setLong(4, now);
            statement.setString(5, id);
            statement.setString(6, expected);
            return statement.executeUpdate() == 1;
        }
    }

    private static void requireAutoCommit(Connection connection) throws SQLException {
        if (!connection.getAutoCommit()) throw new SQLException("Repayment settlement requires auto-commit connection");
    }

    private static void rollback(Connection connection, Throwable failure) {
        try {
            connection.rollback();
        } catch (SQLException rollbackFailure) {
            failure.addSuppressed(rollbackFailure);
        }
    }
}
