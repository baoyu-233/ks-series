package org.kseco.extra.bank;

import org.junit.jupiter.api.Test;

import java.sql.DriverManager;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BankEquitySchemaTest {

    @Test
    void bootstrapsExistingOwnersExactlyOnce() throws Exception {
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        try (var connection = DriverManager.getConnection("jdbc:h2:mem:equity_" + UUID.randomUUID()
                + ";MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1")) {
            connection.createStatement().execute("CREATE TABLE ks_bank_banks(id VARCHAR(128) PRIMARY KEY,"
                    + "type VARCHAR(32),owner_uuids VARCHAR(1024),total_assets DOUBLE)");
            try (var insert = connection.prepareStatement("INSERT INTO ks_bank_banks VALUES('bank','COMMERCIAL',?,100000)")) {
                insert.setString(1, first + "," + second); insert.executeUpdate();
            }
            BankEquityManager.initialize(connection);
            BankEquityManager.ensureInitialized(connection, "bank");
            BankEquityManager.ensureInitialized(connection, "bank");

            assertEquals(1_000_000, scalarLong(connection,
                    "SELECT total_issued FROM ks_bank_equity_state WHERE bank_id='bank'"));
            assertEquals(1_000_000, scalarLong(connection,
                    "SELECT SUM(shares) FROM ks_bank_share_ledger WHERE bank_id='bank'"));
            assertEquals(2, scalarLong(connection,
                    "SELECT COUNT(*) FROM ks_bank_share_ledger WHERE bank_id='bank'"));
            assertEquals(500_000, scalarLong(connection,
                    "SELECT shares FROM ks_bank_share_ledger WHERE shareholder_uuid='" + first + "'"));
        }
    }

    @Test
    void approvalSelectContainsExecutableFromClause() throws Exception {
        try (var connection = DriverManager.getConnection("jdbc:h2:mem:approval_" + UUID.randomUUID())) {
            connection.createStatement().execute("CREATE TABLE ks_bank_loan_requests(id VARCHAR(128),bank_id VARCHAR(128),"
                    + "borrower_uuid VARCHAR(36),principal DOUBLE,term_days INTEGER,quoted_rate DOUBLE,"
                    + "product_type VARCHAR(32),repayment_type VARCHAR(32),purpose VARCHAR(256),status VARCHAR(32))");
            try (var statement = connection.prepareStatement(BankManager.APPROVAL_REQUEST_SELECT)) {
                statement.setString(1, "missing");
                try (var rows = statement.executeQuery()) { assertTrue(!rows.next()); }
            }
        }
    }

    private static long scalarLong(java.sql.Connection connection, String sql) throws Exception {
        try (var rows = connection.createStatement().executeQuery(sql)) {
            assertTrue(rows.next()); return rows.getLong(1);
        }
    }
}
