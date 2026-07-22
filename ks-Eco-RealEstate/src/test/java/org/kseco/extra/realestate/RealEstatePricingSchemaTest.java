package org.kseco.extra.realestate;

import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;

import static org.junit.jupiter.api.Assertions.assertEquals;

class RealEstatePricingSchemaTest {

    @Test
    void legacyZoneRowsMigrateToFlatWithoutChangingExistingValues() throws Exception {
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite::memory:")) {
            try (Statement statement = connection.createStatement()) {
                statement.execute("""
                        CREATE TABLE ks_re_zones (
                            id TEXT PRIMARY KEY,
                            name TEXT NOT NULL,
                            base_price REAL NOT NULL,
                            tax_rate REAL NOT NULL
                        )
                        """);
                statement.executeUpdate("INSERT INTO ks_re_zones(id,name,base_price,tax_rate) "
                        + "VALUES ('legacy','Legacy',1234.5,0.07)");
            }

            RealEstatePricingSchema.migrate(connection);
            RealEstatePricingSchema.migrate(connection);

            try (Statement statement = connection.createStatement();
                 ResultSet resultSet = statement.executeQuery("SELECT * FROM ks_re_zones WHERE id='legacy'")) {
                resultSet.next();
                assertEquals("Legacy", resultSet.getString("name"));
                assertEquals(1234.5d, resultSet.getDouble("base_price"));
                assertEquals(0.07d, resultSet.getDouble("tax_rate"));
                assertEquals("FLAT", resultSet.getString("pricing_mode"));
                assertEquals(0.0d, resultSet.getDouble("price_per_block"));
                assertEquals(0.0d, resultSet.getDouble("min_plot_price"));
                assertEquals(0L, resultSet.getLong("max_plot_area"));
                assertEquals(0L, resultSet.getLong("player_soft_area"));
                assertEquals(0L, resultSet.getLong("player_hard_area"));
                assertEquals(0L, resultSet.getLong("enterprise_soft_area"));
                assertEquals(0L, resultSet.getLong("enterprise_hard_area"));
            }
        }
    }

    @Test
    void validPerBlockModeSurvivesRepeatedMigration() throws Exception {
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite::memory:")) {
            try (Statement statement = connection.createStatement()) {
                statement.execute("CREATE TABLE ks_re_zones (id TEXT PRIMARY KEY)");
                statement.executeUpdate("INSERT INTO ks_re_zones(id) VALUES ('new-zone')");
            }

            RealEstatePricingSchema.migrate(connection);
            try (Statement statement = connection.createStatement()) {
                statement.executeUpdate("UPDATE ks_re_zones SET pricing_mode='per_block', "
                        + "price_per_block=2.75, min_plot_price=500, max_plot_area=4096, "
                        + "player_soft_area=8192, player_hard_area=16384, "
                        + "enterprise_soft_area=32768, enterprise_hard_area=65536 WHERE id='new-zone'");
            }

            RealEstatePricingSchema.migrate(connection);

            try (Statement statement = connection.createStatement();
                 ResultSet resultSet = statement.executeQuery("SELECT * FROM ks_re_zones WHERE id='new-zone'")) {
                resultSet.next();
                assertEquals("PER_BLOCK", resultSet.getString("pricing_mode"));
                assertEquals(2.75d, resultSet.getDouble("price_per_block"));
                assertEquals(500.0d, resultSet.getDouble("min_plot_price"));
                assertEquals(4096L, resultSet.getLong("max_plot_area"));
                assertEquals(16384L, resultSet.getLong("player_hard_area"));
                assertEquals(65536L, resultSet.getLong("enterprise_hard_area"));
            }
        }
    }
}
