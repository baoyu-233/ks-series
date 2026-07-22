package org.kseco.extra.tax;

import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TaxJdbcSupportTest {

    @Test
    void schemaAndWritesWorkInMysqlMode() throws Exception {
        exerciseDialect("jdbc:h2:mem:tax_mysql;MODE=MySQL;DB_CLOSE_DELAY=-1");
    }

    @Test
    void schemaAndWritesWorkInPostgresqlMode() throws Exception {
        exerciseDialect("jdbc:h2:mem:tax_postgresql;MODE=PostgreSQL;DB_CLOSE_DELAY=-1");
    }

    @Test
    void generalRateDoesNotOverwriteLegacyIndustryRows() throws Exception {
        try (Connection connection = DriverManager.getConnection(
                "jdbc:h2:mem:tax_legacy;MODE=MySQL;DB_CLOSE_DELAY=-1")) {
            try (Statement statement = connection.createStatement()) {
                statement.execute("""
                        CREATE TABLE ks_tax_rates (
                            category VARCHAR(128) NOT NULL,
                            industry VARCHAR(128) NOT NULL,
                            rate DOUBLE PRECISION NOT NULL,
                            updated_at BIGINT NOT NULL,
                            PRIMARY KEY(category, industry)
                        )
                        """);
                statement.execute("INSERT INTO ks_tax_rates VALUES "
                        + "('MARKET_TRADE', 'AGRICULTURE', 0.01, 1)");
            }

            TaxJdbcSupport.upsertGeneralRate(connection, "MARKET_TRADE", 0.03d, 2L);

            assertEquals(0.01d, scalarDouble(connection,
                    "SELECT rate FROM ks_tax_rates WHERE category='MARKET_TRADE' "
                            + "AND industry='AGRICULTURE'"));
            assertEquals(0.03d, scalarDouble(connection,
                    "SELECT rate FROM ks_tax_rates WHERE category='MARKET_TRADE' AND industry=''"));
        }
    }

    private static void exerciseDialect(String url) throws Exception {
        try (Connection connection = DriverManager.getConnection(url)) {
            TaxJdbcSupport.ensureTables(connection);
            assertTrue(TaxJdbcSupport.hasColumn(connection, "ks_tax_rates", "category"));

            TaxJdbcSupport.upsertGeneralRate(connection, "MARKET_TRADE", 0.02d, 1L);
            TaxJdbcSupport.upsertGeneralRate(connection, "MARKET_TRADE", 0.03d, 2L);
            assertEquals(0.03d, scalarDouble(connection,
                    "SELECT rate FROM ks_tax_rates WHERE category='MARKET_TRADE'"));

            TaxJdbcSupport.insertIndustryRateIfAbsent(
                    connection, "MARKET_TRADE", "AGRICULTURE", 0.01d, 1L);
            TaxJdbcSupport.insertIndustryRateIfAbsent(
                    connection, "MARKET_TRADE", "AGRICULTURE", 0.99d, 2L);
            assertEquals(0.01d, scalarDouble(connection,
                    "SELECT rate FROM ks_tax_industry_rates "
                            + "WHERE category='MARKET_TRADE' AND industry='AGRICULTURE'"));

            TaxJdbcSupport.upsertIndustryRate(
                    connection, "MARKET_TRADE", "AGRICULTURE", 0.04d, 3L);
            assertEquals(0.04d, scalarDouble(connection,
                    "SELECT rate FROM ks_tax_industry_rates "
                            + "WHERE category='MARKET_TRADE' AND industry='AGRICULTURE'"));

            TaxJdbcSupport.upsertBracket(
                    connection, "bracket-1", "AGRICULTURE", "ENTERPRISE_TAX",
                    0.0d, 1000.0d, 0.05d, 1L);
            TaxJdbcSupport.upsertBracket(
                    connection, "bracket-1", "INDUSTRY", "ENTERPRISE_TAX",
                    1000.0d, 5000.0d, 0.08d, 2L);
            assertEquals(0.08d, scalarDouble(connection,
                    "SELECT rate FROM ks_tax_brackets WHERE id='bracket-1'"));
            assertEquals(5, tableCount(connection));
        }
    }

    private static double scalarDouble(Connection connection, String sql) throws Exception {
        try (Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery(sql)) {
            assertTrue(result.next());
            return result.getDouble(1);
        }
    }

    private static int tableCount(Connection connection) throws Exception {
        int count = 0;
        for (String table : new String[]{
                "KS_TAX_RECORDS", "KS_TAX_PENALTIES", "KS_TAX_RATES",
                "KS_TAX_INDUSTRY_RATES", "KS_TAX_BRACKETS"}) {
            try (ResultSet tables = connection.getMetaData().getTables(
                    connection.getCatalog(), null, table, new String[]{"TABLE"})) {
                if (tables.next()) count++;
            }
        }
        return count;
    }
}
