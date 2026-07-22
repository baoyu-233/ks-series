package org.kseco.extra.bank;

import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.Statement;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BankInterestSettlementStoreTest {

    @Test
    void lateDepositOnlyEarnsForItsActualSlice() throws Exception {
        try (Connection connection = database()) {
            apply(connection, 10_000, 1_000, true);
            apply(connection, 90_000, 1_099, false);
            BankInterestSettlementStore.SettlementResult settlement =
                    apply(connection, 0, 1_100, false);

            assertEquals(1_090, settlement.interestMinor());
            assertEquals(101_090, settlement.finalBalanceMinor());
            assertEquals(1, count(connection, "ks_bank_interest_postings"));
        }
    }

    @Test
    void rolledBackSettlementRetriesOnce() throws Exception {
        try (Connection connection = database()) {
            apply(connection, 10_000, 2_000, true);

            connection.setAutoCommit(false);
            BankInterestSettlementStore.SettlementResult first = storeApply(connection, 0, 2_100, false);
            assertEquals(1_000, first.interestMinor());
            connection.rollback();
            connection.setAutoCommit(true);

            assertEquals(0, count(connection, "ks_bank_interest_postings"));
            BankInterestSettlementStore.SettlementResult retry = apply(connection, 0, 2_100, false);
            BankInterestSettlementStore.SettlementResult duplicate = apply(connection, 0, 2_100, false);

            assertEquals(1_000, retry.interestMinor());
            assertEquals(0, duplicate.interestMinor());
            assertEquals(1, count(connection, "ks_bank_interest_postings"));
            assertEquals(110.0, balance(connection), 0.000_001);
        }
    }

    @Test
    void accountAndLiquidityRollbackTogether() throws Exception {
        try (Connection connection = database()) {
            apply(connection, 10_000, 3_000, true);
            try (Statement statement = connection.createStatement()) {
                statement.execute("CREATE TABLE ks_bank_banks (id TEXT PRIMARY KEY,total_assets REAL,status TEXT)");
                statement.execute("INSERT INTO ks_bank_banks VALUES ('BANK-1',50,'ACTIVE')");
            }

            connection.setAutoCommit(false);
            BankInterestSettlementStore.SettlementResult withdrawal =
                    storeApply(connection, -8_000, 3_000, false);
            assertTrue(withdrawal.applied());
            try (PreparedStatement statement = connection.prepareStatement(
                    "UPDATE ks_bank_banks SET total_assets=total_assets-80 "
                            + "WHERE id='BANK-1' AND total_assets>=80")) {
                assertEquals(0, statement.executeUpdate());
            }
            connection.rollback();
            connection.setAutoCommit(true);

            assertEquals(100.0, balance(connection), 0.000_001);
            assertEquals(10_000, stateBalance(connection));
            assertFalse(connection.isClosed());
        }
    }

    private static Connection database() throws Exception {
        Class.forName("org.sqlite.JDBC");
        Connection connection = DriverManager.getConnection("jdbc:sqlite::memory:");
        try (Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE ks_bank_accounts (id TEXT PRIMARY KEY,bank_id TEXT NOT NULL,"
                    + "player_uuid TEXT NOT NULL,balance REAL DEFAULT 0,interest_earned REAL DEFAULT 0,"
                    + "opened_at INTEGER NOT NULL,last_interest_at INTEGER)");
            BankInterestSettlementStore.createTables(statement);
        }
        return connection;
    }

    private static BankInterestSettlementStore.SettlementResult apply(
            Connection connection, long mutationMinor, long now, boolean allowCreate) throws Exception {
        connection.setAutoCommit(false);
        try {
            BankInterestSettlementStore.SettlementResult result =
                    storeApply(connection, mutationMinor, now, allowCreate);
            connection.commit();
            return result;
        } finally {
            connection.setAutoCommit(true);
        }
    }

    private static BankInterestSettlementStore.SettlementResult storeApply(
            Connection connection, long mutationMinor, long now, boolean allowCreate) throws Exception {
        return BankInterestSettlementStore.apply(connection, "BANK-1", "ACCOUNT-1", "player-1",
                0.10, 100, now, mutationMinor, allowCreate);
    }

    private static int count(Connection connection, String table) throws Exception {
        try (var result = connection.createStatement().executeQuery("SELECT COUNT(*) FROM " + table)) {
            return result.next() ? result.getInt(1) : 0;
        }
    }

    private static double balance(Connection connection) throws Exception {
        try (var result = connection.createStatement()
                .executeQuery("SELECT balance FROM ks_bank_accounts WHERE id='ACCOUNT-1'")) {
            return result.next() ? result.getDouble(1) : 0;
        }
    }

    private static long stateBalance(Connection connection) throws Exception {
        try (var result = connection.createStatement()
                .executeQuery("SELECT balance_minor FROM ks_bank_interest_state WHERE account_id='ACCOUNT-1'")) {
            return result.next() ? result.getLong(1) : 0;
        }
    }
}
