package org.kseco.database;

import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DriverManager;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CoreBusinessSchemaTest {
    @Test
    void initializesSqliteIdempotently() throws Exception {
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite::memory:")) {
            exercise(connection, DatabaseDialect.SQLITE);
        }
    }

    @Test
    void migratesLegacyReservedConfigurationColumns() throws Exception {
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite::memory:")) {
            connection.createStatement().executeUpdate(
                    "CREATE TABLE ks_eco_settings (key TEXT PRIMARY KEY,value TEXT NOT NULL,updated_at INTEGER NOT NULL)");
            connection.createStatement().executeUpdate(
                    "INSERT INTO ks_eco_settings VALUES ('enterprise_max_members','50',1)");
            connection.createStatement().executeUpdate(
                    "CREATE TABLE ks_bank_cb_config (key TEXT PRIMARY KEY,value TEXT NOT NULL)");
            connection.createStatement().executeUpdate(
                    "INSERT INTO ks_bank_cb_config VALUES ('rate_min','0.01')");

            CoreBusinessSchema.initialize(connection, DatabaseDialect.SQLITE);

            try (var rows = connection.createStatement().executeQuery(
                    "SELECT config_value FROM ks_eco_settings WHERE config_key='enterprise_max_members'")) {
                assertTrue(rows.next());
                assertEquals("50", rows.getString(1));
            }
            try (var rows = connection.createStatement().executeQuery(
                    "SELECT config_value FROM ks_bank_cb_config WHERE config_key='rate_min'")) {
                assertTrue(rows.next());
                assertEquals("0.01", rows.getString(1));
            }
        }
    }

    @Test
    void initializesMysqlCompatibleSchemaIdempotently() throws Exception {
        try (Connection connection = DriverManager.getConnection(
                "jdbc:h2:mem:core_mysql;MODE=MySQL;DATABASE_TO_LOWER=TRUE")) {
            exercise(connection, DatabaseDialect.MYSQL);
        }
    }

    @Test
    void initializesPostgresqlCompatibleSchemaIdempotently() throws Exception {
        try (Connection connection = DriverManager.getConnection(
                "jdbc:h2:mem:core_postgres;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE")) {
            exercise(connection, DatabaseDialect.POSTGRESQL);
        }
    }

    private static void exercise(Connection connection, DatabaseDialect dialect) throws Exception {
        CoreBusinessSchema.initialize(connection, dialect);
        CoreBusinessSchema.initialize(connection, dialect);

        try (var insert = connection.prepareStatement(
                "INSERT INTO ks_eco_transport_log "
                        + "(sender_uuid,target_uuid,item_material,quantity,source_world,target_world,distance,cross_world,fee,created_at) "
                        + "VALUES (?,?,?,?,?,?,?,?,?,?)")) {
            insert.setString(1, "sender");
            insert.setString(2, "target");
            insert.setString(3, "STONE");
            insert.setInt(4, 1);
            insert.setString(5, "world");
            insert.setString(6, "world_nether");
            insert.setDouble(7, 2.0);
            insert.setInt(8, 1);
            insert.setDouble(9, 3.0);
            insert.setLong(10, 4L);
            assertEquals(1, insert.executeUpdate());
        }

        connection.createStatement().executeUpdate(
                "INSERT INTO ks_bank_banks (id,name,type,owner_uuids,total_assets,created_at) "
                        + "VALUES ('bank','Bank','COMMERCIAL','owner',100,1)");
        connection.createStatement().executeUpdate(
                "INSERT INTO ks_bank_accounts (id,bank_id,player_uuid,balance,opened_at) "
                        + "VALUES ('account','bank','player',10,1)");
        connection.createStatement().executeUpdate(
                "INSERT INTO ks_ent_projects (id,title,publisher_uuid,budget,deadline,created_at) "
                        + "VALUES ('project','Project','publisher',100,10,1)");
        connection.createStatement().executeUpdate(
                "INSERT INTO ks_ent_bids (id,project_id,enterprise_id,bid_amount,submitted_at) "
                        + "VALUES ('bid','project','enterprise',90,2)");

        try (var rows = connection.createStatement().executeQuery(
                "SELECT COUNT(*) FROM ks_eco_transport_log WHERE id>0")) {
            assertTrue(rows.next());
            assertEquals(1, rows.getInt(1));
        }
        try (var rows = connection.createStatement().executeQuery(
                "SELECT dividend_rate,description FROM ks_ent_enterprises WHERE 1=0")) {
            assertEquals(2, rows.getMetaData().getColumnCount());
        }
    }
}
