package org.kseco.extra.politic;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;

import static org.junit.jupiter.api.Assertions.assertEquals;

final class ProposalTaxRateStoreTest {

    private Connection connection;

    @BeforeEach
    void setUp() throws Exception {
        connection = DriverManager.getConnection("jdbc:sqlite::memory:");
        try (Statement statement = connection.createStatement()) {
            statement.execute("""
                    CREATE TABLE ks_tax_rates (
                        category VARCHAR(128) PRIMARY KEY,
                        rate DOUBLE PRECISION NOT NULL,
                        updated_at BIGINT NOT NULL
                    )
                    """);
            statement.execute("""
                    CREATE TABLE ks_tax_industry_rates (
                        category VARCHAR(128) NOT NULL,
                        industry VARCHAR(128) NOT NULL,
                        rate DOUBLE PRECISION NOT NULL,
                        updated_at BIGINT NOT NULL,
                        PRIMARY KEY(category, industry)
                    )
                    """);
        }
    }

    @AfterEach
    void tearDown() throws Exception {
        if (connection != null) connection.close();
    }

    @Test
    void generalRateUsesCurrentGeneralSchemaAndNormalizedKey() throws Exception {
        String category = ProposalTaxRateStore.normalizeKey(" market_trade ");
        ProposalTaxRateStore.upsertGeneral(connection, category, 0.08d, 10);
        ProposalTaxRateStore.upsertGeneral(connection, category, 0.09d, 11);

        try (Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery(
                     "SELECT category,rate,updated_at FROM ks_tax_rates")) {
            result.next();
            assertEquals("MARKET_TRADE", result.getString("category"));
            assertEquals(0.09d, result.getDouble("rate"), 0.000001d);
            assertEquals(11, result.getLong("updated_at"));
        }
    }

    @Test
    void industryRateUsesDedicatedIndustryTable() throws Exception {
        String category = ProposalTaxRateStore.normalizeKey("enterprise_tax");
        String industry = ProposalTaxRateStore.normalizeKey(" agriculture ");
        ProposalTaxRateStore.upsertIndustry(connection, category, industry, 0.12d, 20);
        ProposalTaxRateStore.upsertIndustry(connection, category, industry, 0.13d, 21);

        try (Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery(
                     "SELECT category,industry,rate,updated_at FROM ks_tax_industry_rates")) {
            result.next();
            assertEquals("ENTERPRISE_TAX", result.getString("category"));
            assertEquals("AGRICULTURE", result.getString("industry"));
            assertEquals(0.13d, result.getDouble("rate"), 0.000001d);
            assertEquals(21, result.getLong("updated_at"));
        }
    }
}
