package org.kseco.extra.bank;

import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.Statement;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EnterpriseLoanRepaymentCasTest {

    @Test
    void staleRemainingCannotOverwriteAConcurrentPayment() throws Exception {
        try (Connection connection = database()) {
            assertTrue(EnterpriseLoanRepaymentCas.update(
                    connection, "LOAN-1", "ENT-1", 100, "ACTIVE", 60, "ACTIVE"));
            assertFalse(EnterpriseLoanRepaymentCas.update(
                    connection, "LOAN-1", "ENT-1", 100, "ACTIVE", 60, "ACTIVE"));
            assertEquals(60, remaining(connection), 0.000_001);
        }
    }

    @Test
    void failedCasRollsBackTheCorporateDebit() throws Exception {
        try (Connection connection = database()) {
            connection.setAutoCommit(false);
            try (PreparedStatement debit = connection.prepareStatement(
                    "UPDATE ks_ent_corporate_accounts SET balance=balance-40 WHERE enterprise_id='ENT-1'")) {
                assertEquals(1, debit.executeUpdate());
            }
            assertFalse(EnterpriseLoanRepaymentCas.update(
                    connection, "LOAN-1", "ENT-1", 90, "ACTIVE", 50, "ACTIVE"));
            connection.rollback();
            connection.setAutoCommit(true);

            assertEquals(100, corporateBalance(connection), 0.000_001);
            assertEquals(100, remaining(connection), 0.000_001);
        }
    }

    @Test
    void defaultingLoanIsNotRepayable() throws Exception {
        try (Connection connection = database()) {
            try (PreparedStatement statement = connection.prepareStatement(
                    "UPDATE ks_bank_enterprise_loans SET status='OVERDUE' WHERE id='LOAN-1'")) {
                statement.executeUpdate();
            }
            assertTrue(EnterpriseLoanDefaultingCas.claim(connection, "LOAN-1", "ENT-1"));
            assertFalse(EnterpriseLoanRepaymentCas.update(
                    connection, "LOAN-1", "ENT-1", 100, "DEFAULTING", 0, "PAID"));
            assertEquals("DEFAULTING", status(connection));
            assertTrue(EnterpriseLoanDefaultingCas.finish(connection, "LOAN-1", "ENT-1", 1234));
            assertEquals("DEFAULTED", status(connection));
        }
    }

    @Test
    void paidLoanCannotBeClaimedForDefault() throws Exception {
        try (Connection connection = database()) {
            try (PreparedStatement statement = connection.prepareStatement(
                    "UPDATE ks_bank_enterprise_loans SET status='PAID' WHERE id='LOAN-1'")) {
                statement.executeUpdate();
            }
            assertFalse(EnterpriseLoanDefaultingCas.claim(connection, "LOAN-1", "ENT-1"));
            assertEquals("PAID", status(connection));
        }
    }

    private static Connection database() throws Exception {
        Class.forName("org.sqlite.JDBC");
        Connection connection = DriverManager.getConnection("jdbc:sqlite::memory:");
        try (Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE ks_bank_enterprise_loans (id TEXT PRIMARY KEY,"
                    + "enterprise_id TEXT NOT NULL,remaining REAL NOT NULL,default_at INTEGER DEFAULT 0,"
                    + "status TEXT NOT NULL)");
            statement.execute("INSERT INTO ks_bank_enterprise_loans "
                    + "(id,enterprise_id,remaining,status) VALUES ('LOAN-1','ENT-1',100,'ACTIVE')");
            statement.execute("CREATE TABLE ks_ent_corporate_accounts (enterprise_id TEXT PRIMARY KEY,balance REAL)");
            statement.execute("INSERT INTO ks_ent_corporate_accounts VALUES ('ENT-1',100)");
        }
        return connection;
    }

    private static double remaining(Connection connection) throws Exception {
        try (var result = connection.createStatement().executeQuery(
                "SELECT remaining FROM ks_bank_enterprise_loans WHERE id='LOAN-1'")) {
            return result.next() ? result.getDouble(1) : -1;
        }
    }

    private static double corporateBalance(Connection connection) throws Exception {
        try (var result = connection.createStatement().executeQuery(
                "SELECT balance FROM ks_ent_corporate_accounts WHERE enterprise_id='ENT-1'")) {
            return result.next() ? result.getDouble(1) : -1;
        }
    }

    private static String status(Connection connection) throws Exception {
        try (var result = connection.createStatement().executeQuery(
                "SELECT status FROM ks_bank_enterprise_loans WHERE id='LOAN-1'")) {
            return result.next() ? result.getString(1) : null;
        }
    }
}
