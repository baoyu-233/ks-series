package org.kseco.database;

import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DriverManager;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EconomicFeatureSchemaTest {
    @Test
    void initializesAndExtendsLegacySqliteTables() throws Exception {
        try (var connection = DriverManager.getConnection("jdbc:sqlite::memory:")) {
            connection.createStatement().executeUpdate(
                    "CREATE TABLE ks_eco_exchange_rules (id TEXT PRIMARY KEY,input_material TEXT NOT NULL,"
                            + "input_quantity INTEGER NOT NULL DEFAULT 1,input_item_data BLOB,output_material TEXT NOT NULL,"
                            + "output_quantity INTEGER NOT NULL DEFAULT 1,output_item_data BLOB,created_by TEXT,"
                            + "created_at INTEGER NOT NULL,enabled INTEGER DEFAULT 1)");
            connection.createStatement().executeUpdate(
                    "CREATE TABLE ks_major_orders (id TEXT PRIMARY KEY,title TEXT NOT NULL,description TEXT DEFAULT '',"
                            + "metric_type TEXT NOT NULL DEFAULT 'MANUAL',target_value REAL NOT NULL DEFAULT 1,"
                            + "manual_value REAL NOT NULL DEFAULT 0,status TEXT NOT NULL DEFAULT 'ACTIVE',"
                            + "starts_at INTEGER NOT NULL DEFAULT 0,ends_at INTEGER NOT NULL DEFAULT 0,"
                            + "created_at INTEGER NOT NULL,updated_at INTEGER NOT NULL)");
            exercise(connection);
        }
    }

    @Test
    void initializesMysqlCompatibleSchema() throws Exception {
        try (var connection = DriverManager.getConnection(
                "jdbc:h2:mem:feature_mysql;MODE=MySQL;DATABASE_TO_LOWER=TRUE")) {
            exercise(connection);
        }
    }

    @Test
    void initializesPostgresqlCompatibleSchema() throws Exception {
        try (var connection = DriverManager.getConnection(
                "jdbc:h2:mem:feature_postgres;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE")) {
            exercise(connection);
        }
    }

    private static void exercise(Connection connection) throws Exception {
        EconomicFeatureSchema.initialize(connection);
        EconomicFeatureSchema.initialize(connection);

        PortableSqlMutation.upsert(connection,
                "UPDATE ks_eco_exchange_rules SET name=?,inputs_json=?,outputs_json=? WHERE id=?",
                statement -> {
                    statement.setString(1, "矿物兑换");
                    statement.setString(2, "[]");
                    statement.setString(3, "[]");
                    statement.setString(4, "exchange");
                },
                "INSERT INTO ks_eco_exchange_rules "
                        + "(id,name,inputs_json,outputs_json,input_material,input_quantity,output_material,"
                        + "output_quantity,created_at,enabled) VALUES (?,?,?,?,?,?,?,?,?,?)",
                statement -> {
                    statement.setString(1, "exchange");
                    statement.setString(2, "矿物兑换");
                    statement.setString(3, "[]");
                    statement.setString(4, "[]");
                    statement.setString(5, "STONE");
                    statement.setInt(6, 1);
                    statement.setString(7, "DIAMOND");
                    statement.setInt(8, 1);
                    statement.setLong(9, 1L);
                    statement.setInt(10, 1);
                });
        connection.createStatement().executeUpdate(
                "INSERT INTO ks_major_orders "
                        + "(id,title,metric_type,target_value,created_at,updated_at,policy_industry,policy_purpose) "
                        + "VALUES ('order','测试订单','MANUAL',10,1,1,'ALL','ALL')");

        try (var rows = connection.createStatement().executeQuery(
                "SELECT COUNT(*) FROM ks_eco_exchange_rules WHERE name='矿物兑换'")) {
            assertTrue(rows.next());
            assertEquals(1, rows.getInt(1));
        }
        try (var rows = connection.createStatement().executeQuery(
                "SELECT policy_loan_rate_multiplier,policy_reserve_delta FROM ks_major_orders WHERE id='order'")) {
            assertTrue(rows.next());
            assertEquals(1.0D, rows.getDouble(1));
            assertEquals(0.0D, rows.getDouble(2));
        }
    }
}
