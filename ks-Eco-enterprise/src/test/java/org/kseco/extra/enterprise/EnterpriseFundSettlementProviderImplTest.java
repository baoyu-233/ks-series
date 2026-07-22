package org.kseco.extra.enterprise;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import org.junit.jupiter.api.Test;

class EnterpriseFundSettlementProviderImplTest {
    @Test
    void creditsCorporateAccountAndHostingBankWithoutOwningTransaction() throws Exception {
        try (Connection connection = database()) {
            var provider = new EnterpriseFundSettlementProviderImpl();
            connection.setAutoCommit(false);
            provider.creditPropertySale(connection, "enterprise-1", 125.0d, 10L);
            connection.rollback();
            assertEquals(0.0d, scalar(connection, "SELECT balance FROM ks_ent_corporate_accounts"));
            assertEquals(1000.0d, scalar(connection, "SELECT balance FROM ks_banks"));

            provider.creditPropertySale(connection, "enterprise-1", 125.0d, 11L);
            connection.commit();
            assertEquals(125.0d, scalar(connection, "SELECT balance FROM ks_ent_corporate_accounts"));
            assertEquals(1125.0d, scalar(connection, "SELECT balance FROM ks_banks"));
        }
    }

    @Test
    void failsClosedWhenEnterpriseOrBankIsUnavailable() throws Exception {
        try (Connection connection = database()) {
            connection.createStatement().execute(
                    "UPDATE ks_ent_enterprises SET status='DISSOLVED' WHERE id='enterprise-1'");
            var provider = new EnterpriseFundSettlementProviderImpl();
            assertThrows(SQLException.class,
                    () -> provider.creditPropertySale(connection, "enterprise-1", 50.0d, 10L));
            assertEquals(0.0d, scalar(connection, "SELECT balance FROM ks_ent_corporate_accounts"));
            assertEquals(1000.0d, scalar(connection, "SELECT balance FROM ks_banks"));
        }
    }

    private static Connection database() throws Exception {
        Connection connection = DriverManager.getConnection("jdbc:h2:mem:" + System.nanoTime());
        connection.createStatement().execute(
                "CREATE TABLE ks_ent_enterprises (id VARCHAR(128) PRIMARY KEY,status VARCHAR(32) NOT NULL)");
        connection.createStatement().execute(
                "CREATE TABLE ks_ent_corporate_accounts (enterprise_id VARCHAR(128) PRIMARY KEY,bank_id VARCHAR(128) NOT NULL,balance DOUBLE PRECISION NOT NULL,updated_at BIGINT NOT NULL)");
        connection.createStatement().execute(
                "CREATE TABLE ks_banks (id VARCHAR(128) PRIMARY KEY,balance DOUBLE PRECISION NOT NULL)");
        connection.createStatement().execute("INSERT INTO ks_ent_enterprises VALUES ('enterprise-1','ACTIVE')");
        connection.createStatement().execute(
                "INSERT INTO ks_ent_corporate_accounts VALUES ('enterprise-1','bank-1',0,0)");
        connection.createStatement().execute("INSERT INTO ks_banks VALUES ('bank-1',1000)");
        return connection;
    }

    private static double scalar(Connection connection, String sql) throws Exception {
        try (var rows = connection.createStatement().executeQuery(sql)) {
            rows.next();
            return rows.getDouble(1);
        }
    }
}
