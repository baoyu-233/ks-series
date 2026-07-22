package org.kseco.extra.politic;

import org.kseco.database.BusinessSchemaDialect;
import org.kseco.database.PortableSqlMutation;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Locale;

final class ProposalEnactmentJournal {

    static final String NONE = "NONE";
    static final String PENDING = "PENDING";
    static final String APPLYING = "APPLYING";
    static final String APPLIED = "APPLIED";
    static final String FAILED = "FAILED";
    static final String REVIEW_REQUIRED = "REVIEW_REQUIRED";

    private static final int MAX_ERROR_LENGTH = 1000;

    private ProposalEnactmentJournal() {}

    static void ensureSchema(Connection conn) throws SQLException {
        try (Statement statement = conn.createStatement()) {
            statement.execute("""
                    CREATE TABLE IF NOT EXISTS ks_politic_enactment_journal (
                        proposal_id VARCHAR(64) PRIMARY KEY,
                        operation_id VARCHAR(128) NOT NULL UNIQUE,
                        proposal_type VARCHAR(64) NOT NULL,
                        status VARCHAR(32) NOT NULL,
                        claim_token VARCHAR(64) NOT NULL DEFAULT '',
                        attempt_count INTEGER NOT NULL DEFAULT 0,
                        error_text VARCHAR(1024) NOT NULL DEFAULT '',
                        created_at INTEGER NOT NULL,
                        updated_at INTEGER NOT NULL,
                        applied_at INTEGER NOT NULL DEFAULT 0
                    )
                    """);
        }
        BusinessSchemaDialect.createIndexIfMissing(conn, "idx_politic_enactment_status",
                "ks_politic_enactment_journal", "status", "updated_at");
        addColumnIfMissing(conn, "ks_politic_proposals", "enactment_operation_id",
                "VARCHAR(128) NOT NULL DEFAULT ''");
        addColumnIfMissing(conn, "ks_politic_proposals", "enactment_status",
                "VARCHAR(32) NOT NULL DEFAULT 'NONE'");
        addColumnIfMissing(conn, "ks_politic_proposals", "enactment_error",
                "VARCHAR(1024) NOT NULL DEFAULT ''");
    }

    static void recoverInterrupted(Connection conn, long now) throws SQLException {
        try (PreparedStatement applied = conn.prepareStatement("""
                UPDATE ks_politic_enactment_journal
                SET status='APPLIED', claim_token='', error_text='', updated_at=?, applied_at=?
                WHERE status='APPLYING' AND proposal_id IN (
                    SELECT id FROM ks_politic_proposals WHERE status='ENACTED'
                )
                """)) {
            applied.setLong(1, now);
            applied.setLong(2, now);
            applied.executeUpdate();
        }
        try (PreparedStatement failed = conn.prepareStatement("""
                UPDATE ks_politic_enactment_journal
                SET status='FAILED', claim_token='', error_text=?, updated_at=?
                WHERE status='APPLYING'
                """)) {
            failed.setString(1, "Recovered an interrupted enactment claim; no effects were committed");
            failed.setLong(2, now);
            failed.executeUpdate();
        }
        try (PreparedStatement legacy = conn.prepareStatement("""
                UPDATE ks_politic_proposals
                SET enactment_status='REVIEW_REQUIRED',
                    enactment_error='Pre-journal proposal requires review before enactment retry'
                WHERE status IN ('APPROVED','OVERRIDDEN')
                  AND COALESCE(enactment_operation_id,'')=''
                """)) {
            legacy.executeUpdate();
        }
        try (PreparedStatement syncApplied = conn.prepareStatement("""
                UPDATE ks_politic_proposals
                SET enactment_status='APPLIED', enactment_error=''
                WHERE status='ENACTED'
                  AND id IN (SELECT proposal_id FROM ks_politic_enactment_journal WHERE status='APPLIED')
                """)) {
            syncApplied.executeUpdate();
        }
    }

    static String operationId(String proposalId) {
        return "POLITIC-ENACT:" + proposalId;
    }

    static void prepare(Connection conn, String proposalId, String operationId,
                        String proposalType, long now) throws SQLException {
        PortableSqlMutation.insertIfAbsent(conn,
                "SELECT 1 FROM ks_politic_enactment_journal WHERE proposal_id=?",
                ps -> ps.setString(1, proposalId),
                "INSERT INTO ks_politic_enactment_journal (proposal_id,operation_id,proposal_type,status,claim_token,attempt_count,error_text,created_at,updated_at,applied_at) VALUES (?,?,?,'PENDING','',0,'',?,?,0)",
                ps -> { ps.setString(1, proposalId); ps.setString(2, operationId); ps.setString(3, proposalType); ps.setLong(4, now); ps.setLong(5, now); });
        JournalRow row = read(conn, proposalId);
        if (row == null || !operationId.equals(row.operationId()) || !proposalType.equals(row.proposalType())) {
            throw new SQLException("Enactment journal identity mismatch for proposal " + proposalId);
        }
    }

    static ClaimResult claim(Connection conn, String proposalId, String operationId,
                             String proposalType, String claimToken, long now) throws SQLException {
        prepare(conn, proposalId, operationId, proposalType, now);
        try (PreparedStatement update = conn.prepareStatement("""
                UPDATE ks_politic_enactment_journal
                SET status='APPLYING', claim_token=?, attempt_count=attempt_count+1,
                    error_text='', updated_at=?
                WHERE proposal_id=? AND operation_id=? AND status IN ('PENDING','FAILED')
                """)) {
            update.setString(1, claimToken);
            update.setLong(2, now);
            update.setString(3, proposalId);
            update.setString(4, operationId);
            if (update.executeUpdate() == 1) return new ClaimResult(ClaimOutcome.CLAIMED, "");
        }

        JournalRow row = read(conn, proposalId);
        if (row == null) throw new SQLException("Enactment journal disappeared for proposal " + proposalId);
        if (!operationId.equals(row.operationId())) {
            throw new SQLException("Enactment operation ID changed for proposal " + proposalId);
        }
        return switch (row.status()) {
            case APPLIED -> new ClaimResult(ClaimOutcome.APPLIED, "");
            case APPLYING -> new ClaimResult(ClaimOutcome.BUSY, "Another enactment claim is active");
            case REVIEW_REQUIRED -> new ClaimResult(ClaimOutcome.REVIEW_REQUIRED, row.errorText());
            default -> throw new SQLException("Unexpected enactment status " + row.status());
        };
    }

    static void markApplied(Connection conn, String proposalId, String operationId,
                            String claimToken, long now) throws SQLException {
        try (PreparedStatement update = conn.prepareStatement("""
                UPDATE ks_politic_enactment_journal
                SET status='APPLIED', claim_token='', error_text='', updated_at=?, applied_at=?
                WHERE proposal_id=? AND operation_id=? AND status='APPLYING' AND claim_token=?
                """)) {
            update.setLong(1, now);
            update.setLong(2, now);
            update.setString(3, proposalId);
            update.setString(4, operationId);
            update.setString(5, claimToken);
            if (update.executeUpdate() != 1) {
                throw new SQLException("Enactment claim changed before completion for proposal " + proposalId);
            }
        }
    }

    static void recordFailure(Connection conn, String proposalId, String operationId,
                              String proposalType, String error, long now) throws SQLException {
        String normalizedError = trimError(error);
        try (PreparedStatement update = conn.prepareStatement("""
                UPDATE ks_politic_enactment_journal
                SET status='FAILED', claim_token='', error_text=?, updated_at=?
                WHERE proposal_id=? AND operation_id=? AND status<>'APPLIED'
                """)) {
            update.setString(1, normalizedError);
            update.setLong(2, now);
            update.setString(3, proposalId);
            update.setString(4, operationId);
            if (update.executeUpdate() == 1) return;
        }

        JournalRow existing = read(conn, proposalId);
        if (existing != null) {
            if (!operationId.equals(existing.operationId())) {
                throw new SQLException("Cannot record failure for a different enactment operation");
            }
            return;
        }
        try (PreparedStatement insert = conn.prepareStatement("""
                INSERT INTO ks_politic_enactment_journal
                    (proposal_id,operation_id,proposal_type,status,claim_token,attempt_count,
                     error_text,created_at,updated_at,applied_at)
                VALUES (?,?,?,'FAILED','',1,?,?,?,0)
                """)) {
            insert.setString(1, proposalId);
            insert.setString(2, operationId);
            insert.setString(3, proposalType);
            insert.setString(4, normalizedError);
            insert.setLong(5, now);
            insert.setLong(6, now);
            insert.executeUpdate();
        } catch (SQLException racedInsert) {
            JournalRow raced = read(conn, proposalId);
            if (raced == null || !operationId.equals(raced.operationId())) throw racedInsert;
        }
    }

    static JournalRow read(Connection conn, String proposalId) throws SQLException {
        try (PreparedStatement select = conn.prepareStatement("""
                SELECT operation_id,proposal_type,status,claim_token,attempt_count,error_text,
                       created_at,updated_at,applied_at
                FROM ks_politic_enactment_journal WHERE proposal_id=?
                """)) {
            select.setString(1, proposalId);
            try (ResultSet rs = select.executeQuery()) {
                if (!rs.next()) return null;
                return new JournalRow(proposalId, rs.getString("operation_id"),
                        rs.getString("proposal_type"), rs.getString("status"),
                        rs.getString("claim_token"), rs.getInt("attempt_count"),
                        rs.getString("error_text"), rs.getLong("created_at"),
                        rs.getLong("updated_at"), rs.getLong("applied_at"));
            }
        }
    }

    private static void addColumnIfMissing(Connection conn, String table, String column,
                                           String declaration) throws SQLException {
        if (hasColumn(conn, table, column)) return;
        try (Statement statement = conn.createStatement()) {
            statement.execute("ALTER TABLE " + table + " ADD COLUMN " + column + " " + declaration);
        }
    }

    private static boolean hasColumn(Connection conn, String table, String column) throws SQLException {
        DatabaseMetaData metadata = conn.getMetaData();
        for (String tableName : new String[]{table, table.toUpperCase(Locale.ROOT), table.toLowerCase(Locale.ROOT)}) {
            try (ResultSet columns = metadata.getColumns(null, null, tableName, null)) {
                while (columns.next()) {
                    if (column.equalsIgnoreCase(columns.getString("COLUMN_NAME"))) return true;
                }
            }
        }
        return false;
    }

    private static String trimError(String error) {
        String value = error == null || error.isBlank() ? "Unknown enactment failure" : error;
        return value.length() <= MAX_ERROR_LENGTH ? value : value.substring(0, MAX_ERROR_LENGTH);
    }

    enum ClaimOutcome { CLAIMED, APPLIED, BUSY, REVIEW_REQUIRED }

    record ClaimResult(ClaimOutcome outcome, String detail) {}

    record JournalRow(String proposalId, String operationId, String proposalType, String status,
                      String claimToken, int attemptCount, String errorText,
                      long createdAt, long updatedAt, long appliedAt) {}
}
