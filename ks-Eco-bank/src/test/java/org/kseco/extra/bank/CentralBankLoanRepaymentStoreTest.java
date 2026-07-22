package org.kseco.extra.bank;

import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CentralBankLoanRepaymentStoreTest {

    @Test
    void replayCannotDebitTheBankTwice() throws Exception {
        try (Connection connection = database(1_000)) {
            CentralBankLoanRepaymentStore.Result first = settle(connection);
            CentralBankLoanRepaymentStore.Result replay = settle(connection);

            assertTrue(first.paid());
            assertFalse(replay.paid());
            assertEquals(CentralBankLoanRepaymentStore.Outcome.NOT_PAYABLE, replay.outcome());
            assertEquals(890, bankAssets(connection), 0.000_001);
            assertEquals(CentralBankLoanRepaymentStore.REPAID, loanState(connection));
        }
    }

    @Test
    void insufficientAssetsRollBackTheClaim() throws Exception {
        try (Connection connection = database(50)) {
            CentralBankLoanRepaymentStore.Result result = settle(connection);

            assertEquals(CentralBankLoanRepaymentStore.Outcome.INSUFFICIENT_ASSETS, result.outcome());
            assertEquals(50, bankAssets(connection), 0.000_001);
            assertEquals(CentralBankLoanRepaymentStore.OPEN, loanState(connection));
        }
    }

    @Test
    void fullSubsidyRateRepaysWithoutDebitingAssets() throws Exception {
        try (Connection connection = database(50, -1)) {
            CentralBankLoanRepaymentStore.Result result = settle(connection);

            assertTrue(result.paid());
            assertEquals(0, result.amount(), 0.000_001);
            assertEquals(50, bankAssets(connection), 0.000_001);
            assertEquals(CentralBankLoanRepaymentStore.REPAID, loanState(connection));
        }
    }

    @Test
    void rateBelowMinusOneIsRejectedAndClaimRollsBack() throws Exception {
        try (Connection connection = database(50, -1.01)) {
            assertThrows(SQLException.class, () -> settle(connection));
            assertEquals(50, bankAssets(connection), 0.000_001);
            assertEquals(CentralBankLoanRepaymentStore.OPEN, loanState(connection));
        }
    }

    @Test
    void freshSchemaUsesPortableFixedLengthPrimaryKey() throws Exception {
        assertTrue(CentralBankLoanSchema.CREATE_TABLE_SQL.contains("id VARCHAR(191) PRIMARY KEY"));
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite::memory:");
             Statement statement = connection.createStatement()) {
            statement.execute(CentralBankLoanSchema.CREATE_TABLE_SQL);
        }
    }

    private static CentralBankLoanRepaymentStore.Result settle(Connection connection) throws Exception {
        connection.setAutoCommit(false);
        try {
            CentralBankLoanRepaymentStore.Result result =
                    CentralBankLoanRepaymentStore.apply(connection, "CBL-1");
            if (result.paid()) connection.commit();
            else connection.rollback();
            return result;
        } catch (Exception error) {
            connection.rollback();
            throw error;
        } finally {
            connection.setAutoCommit(true);
        }
    }

    private static Connection database(double assets) throws Exception {
        return database(assets, 0.10);
    }

    private static Connection database(double assets, double rate) throws Exception {
        Class.forName("org.sqlite.JDBC");
        Connection connection = DriverManager.getConnection("jdbc:sqlite::memory:");
        try (Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE ks_bank_banks (id TEXT PRIMARY KEY,total_assets REAL NOT NULL)");
            statement.execute("INSERT INTO ks_bank_banks VALUES ('BANK-1'," + assets + ")");
            statement.execute(CentralBankLoanSchema.CREATE_TABLE_SQL);
            statement.execute("INSERT INTO ks_bank_cb_loans "
                    + "(id,bank_id,principal,interest_rate,term_days,issued_at,due_at,repaid) VALUES "
                    + "('CBL-1','BANK-1',100," + rate + ",30,0,1,0)");
        }
        return connection;
    }

    private static double bankAssets(Connection connection) throws Exception {
        try (var result = connection.createStatement().executeQuery(
                "SELECT total_assets FROM ks_bank_banks WHERE id='BANK-1'")) {
            return result.next() ? result.getDouble(1) : -1;
        }
    }

    private static int loanState(Connection connection) throws Exception {
        try (var result = connection.createStatement().executeQuery(
                "SELECT repaid FROM ks_bank_cb_loans WHERE id='CBL-1'")) {
            return result.next() ? result.getInt(1) : -1;
        }
    }
}
