package org.kseco.database;

import org.junit.jupiter.api.Test;

import java.sql.DriverManager;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PriceEngineSchemaTest {
    @Test
    void initializesSqliteAndMigratesReservedColumns() throws Exception {
        try (var connection = DriverManager.getConnection("jdbc:sqlite::memory:")) {
            connection.createStatement().executeUpdate(
                    "CREATE TABLE ks_eco_price_settings (key TEXT PRIMARY KEY,value TEXT NOT NULL)");
            connection.createStatement().executeUpdate(
                    "INSERT INTO ks_eco_price_settings VALUES ('max_fluctuation','0.3')");
            exercise(connection);
            try (var rows = connection.createStatement().executeQuery(
                    "SELECT config_value FROM ks_eco_price_settings WHERE config_key='max_fluctuation'")) {
                assertTrue(rows.next());
                assertEquals("0.4", rows.getString(1));
            }
        }
    }

    @Test
    void initializesMysqlCompatibleSchema() throws Exception {
        try (var connection = DriverManager.getConnection(
                "jdbc:h2:mem:price_mysql;MODE=MySQL;DATABASE_TO_LOWER=TRUE")) {
            exercise(connection);
        }
    }

    @Test
    void initializesPostgresqlCompatibleSchema() throws Exception {
        try (var connection = DriverManager.getConnection(
                "jdbc:h2:mem:price_postgres;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE")) {
            exercise(connection);
        }
    }

    private static void exercise(java.sql.Connection connection) throws Exception {
        PriceEngineSchema.initialize(connection);
        PriceEngineSchema.initialize(connection);
        PortableSqlMutation.upsert(connection,
                "UPDATE ks_eco_price_settings SET config_value=? WHERE config_key=?",
                statement -> { statement.setString(1, "0.4"); statement.setString(2, "max_fluctuation"); },
                "INSERT INTO ks_eco_price_settings (config_key,config_value) VALUES (?,?)",
                statement -> { statement.setString(1, "max_fluctuation"); statement.setString(2, "0.4"); });
        connection.createStatement().executeUpdate(
                "INSERT INTO ks_eco_trades "
                        + "(id,item_material,item_signature,quantity,unit_price,timestamp,trade_type,is_test) "
                        + "VALUES ('trade','STONE','stone',1,2,3,'OFFICIAL_BUY',0)");
        try (var rows = connection.createStatement().executeQuery(
                "SELECT COUNT(*) FROM ks_eco_trades WHERE item_material='STONE'")) {
            assertTrue(rows.next());
            assertEquals(1, rows.getInt(1));
        }
    }
}
